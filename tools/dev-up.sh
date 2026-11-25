#!/usr/bin/env bash
# dev-up.sh — build and start the full local stack (db + app) with docker compose.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT}/deploy/docker-compose.yml"
ENV_FILE="${ROOT}/deploy/.env"
HEALTH_URL="http://127.0.0.1:18080/actuator/health"
APP_URL="http://127.0.0.1:18080/"

if [[ ! -f "${ENV_FILE}" ]]; then
  cp "${ROOT}/deploy/.env.example" "${ENV_FILE}"
  echo "Created ${ENV_FILE} from .env.example — edit the passwords, then re-run this script." >&2
  exit 1
fi

docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" up -d --build

echo "Waiting for ${HEALTH_URL} to report UP (up to 120s)..."
deadline=$((SECONDS + 120))
while (( SECONDS < deadline )); do
  status="$(curl -fsS --max-time 3 "${HEALTH_URL}" 2>/dev/null || true)"
  if [[ "${status}" == *'"UP"'* ]]; then
    echo
    echo "UltiBiz is up at ${APP_URL}"
    echo "Sign in with the admin account seeded on first boot (see docs/ULTIBIZ_MASTER_PROMPT.md)."
    exit 0
  fi
  sleep 3
done

echo "Timed out waiting for the app to become healthy. Check: docker compose -f ${COMPOSE_FILE} logs app" >&2
exit 1
