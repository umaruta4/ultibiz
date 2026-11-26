#!/usr/bin/env bash
# Starts the Keycloak of UltiBiz — the identity provider of the single sign-on.
#
# The realm template (deploy/keycloak/realm-ultibiz.json) is imported with
#
#   - a generated client secret  -> ULTIBIZ_SSO_CLIENT_SECRET
#   - a generated password of the demo user -> the OIDC test
#   - the redirect URI of the internal address (the public one stays in the
#     template)
#
# The secrets are written to the .env of the service (mode 600) — never into
# the repository. The provider listens on localhost:8180 only: the public URL
# of the provider is a question of the reverse proxy in front of
# erp.example.com.
#
# Usage: bash deploy/keycloak/start-keycloak.sh [start|stop|status]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEMPLATE="${ROOT}/deploy/keycloak/realm-ultibiz.json"
RUNTIME_DIR=/var/lib/ultibiz-keycloak
RUNTIME_REALM="${RUNTIME_DIR}/realm-ultibiz.json"
ENV_FILE=/home/janedoe/projects/ultibiz/.env
CONTAINER=ultibiz-keycloak
IMAGE=quay.io/keycloak/keycloak:26.0.7
PORT=8180
ISSUER="http://localhost:${PORT}/realms/ultibiz"

set_env() {
  # sets the variable in the .env of the service (replaces an existing line)
  local key="$1" value="$2"
  if [ -f "${ENV_FILE}" ] && grep -q "^${key}=" "${ENV_FILE}"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "${ENV_FILE}"
  else
    printf '%s=%s\n' "${key}" "${value}" >> "${ENV_FILE}"
  fi
  chmod 600 "${ENV_FILE}"
}

case "${1:-start}" in
  stop)
    docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
    echo "the Keycloak container is stopped"
    exit 0
    ;;
  status)
    docker ps --filter "name=${CONTAINER}" --format "{{.Names}} {{.Status}}"
    curl -s -o /dev/null -w "the discovery document: %{http_code}\n" \
      "${ISSUER}/.well-known/openid-configuration" || true
    exit 0
    ;;
esac

[ -f "${TEMPLATE}" ] || { echo "the realm template is missing: ${TEMPLATE}" >&2; exit 1; }
mkdir -p "${RUNTIME_DIR}"

# the secrets (generated once, then reused)
CLIENT_SECRET=$(grep '^ULTIBIZ_SSO_CLIENT_SECRET=' "${ENV_FILE}" 2>/dev/null | cut -d= -f2- || true)
[ -n "${CLIENT_SECRET}" ] || CLIENT_SECRET=$(openssl rand -hex 24)
ADMIN_PASSWORD=$(grep '^KEYCLOAK_ADMIN_PASSWORD=' "${ENV_FILE}" 2>/dev/null | cut -d= -f2- || true)
[ -n "${ADMIN_PASSWORD}" ] || ADMIN_PASSWORD=$(openssl rand -hex 16)
USER_PASSWORD=$(grep '^ULTIBIZ_SSO_TEST_PASSWORD=' "${ENV_FILE}" 2>/dev/null | cut -d= -f2- || true)
[ -n "${USER_PASSWORD}" ] || USER_PASSWORD=$(openssl rand -hex 12)

# the runtime realm: the template with the generated secrets
sed -e "s|CHANGE-ME-GENERATE-A-SECRET|${CLIENT_SECRET}|" \
    -e "s|\"value\": \"CHANGE-ME\"|\"value\": \"${USER_PASSWORD}\"|" \
    -e "s|\"sslRequired\": \"external\"|\"sslRequired\": \"none\"|" \
    -e "s|\"redirectUris\": \[\"https://erp.example.com/login/oauth2/code/keycloak\"\]|\"redirectUris\": [\"https://erp.example.com/login/oauth2/code/keycloak\", \"http://localhost:8080/login/oauth2/code/keycloak\", \"http://localhost:8080/login/oauth2/code/keycloak\"]|" \
    -e "s|\"webOrigins\": \[\"https://erp.example.com\"\]|\"webOrigins\": [\"https://erp.example.com\", \"http://localhost:8080\"]|" \
    "${TEMPLATE}" > "${RUNTIME_REALM}"
chmod 640 "${RUNTIME_REALM}"
# the container runs as uid 1000 and must be able to read the realm
chown 1000:0 "${RUNTIME_REALM}" 2>/dev/null || true
chmod 755 "${RUNTIME_DIR}"

docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
docker run -d --name "${CONTAINER}" \
  --restart unless-stopped \
  -p "localhost:${PORT}:8080" \
  -e "KEYCLOAK_ADMIN=admin" \
  -e "KEYCLOAK_ADMIN_PASSWORD=${ADMIN_PASSWORD}" \
  -e KC_HEALTH_ENABLED=true \
  -v "${RUNTIME_REALM}:/opt/keycloak/data/import/realm-ultibiz.json:ro" \
  "${IMAGE}" start-dev --import-realm >/dev/null

echo "the Keycloak container is starting (the import takes a moment)..."
ready=no
for i in $(seq 1 60); do
  if curl -s -o /dev/null -m 3 "${ISSUER}/.well-known/openid-configuration"; then
    ready=yes
    break
  fi
  sleep 3
done
if [ "${ready}" != "yes" ]; then
  echo "the provider did not come up; the last lines of the container:" >&2
  docker logs --tail 15 "${CONTAINER}" >&2 || true
  exit 1
fi

set_env ULTIBIZ_SSO_ISSUER "${ISSUER}"
set_env ULTIBIZ_SSO_CLIENT_ID ultibiz
set_env ULTIBIZ_SSO_CLIENT_SECRET "${CLIENT_SECRET}"
set_env ULTIBIZ_SSO_REDIRECT_URI "http://localhost:8080/login/oauth2/code/keycloak"
set_env ULTIBIZ_SSO_ROLE USER
set_env ULTIBIZ_SSO_ORGANIZATION ACME
set_env ULTIBIZ_SSO_TEST_PASSWORD "${USER_PASSWORD}"
set_env KEYCLOAK_ADMIN_PASSWORD "${ADMIN_PASSWORD}"

echo "the provider is ready: ${ISSUER}"
echo "the client secret and the demo password are in ${ENV_FILE} (mode 600)"
echo "the demo user of the realm: budi / (the password in the .env)"
echo "to use it in UltiBiz: SPRING_PROFILES_ACTIVE=sso in the .env, then restart the service"
