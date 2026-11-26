# The single sign-on (Keycloak)

The application can delegate the login to an OpenID Connect provider. The local
form login stays available as a fallback; the session of an SSO user is an
ordinary UltiBiz session (the same permissions, the same site scope, the same
audit).

## How it works

1. The user clicks **Sign in with Keycloak** on the login page
   (`/oauth2/authorization/keycloak`).
2. Keycloak authenticates the user and redirects back to
   `/login/oauth2/code/keycloak`.
3. The application reads the claims (`preferred_username`, `email`, `name`,
   `realm_access.roles`), finds the local user and — when the automatic
   provisioning is on — creates it with the default organisation and role.
4. The session continues with the **local principal**: every permission, the
   site scope and the audit work exactly as with the form login.
5. The logout ends the Keycloak session too (the OIDC logout).

## Enabling it

The single sign-on lives in the Spring profile `sso` — without it the
application has **no OAuth2 client** and behaves exactly as before.

```bash
# in /home/sufehmi/projects/ultibiz/.env
SPRING_PROFILES_ACTIVE=sso
ULTIBIZ_SSO_ISSUER=https://keycloak.example.com/realms/ultibiz
ULTIBIZ_SSO_CLIENT_ID=ultibiz
ULTIBIZ_SSO_CLIENT_SECRET=<the client secret of Keycloak>
ULTIBIZ_SSO_AUTO_PROVISION=true          # a new user is created on the first login
ULTIBIZ_SSO_ORGANIZATION=ACME            # the organisation of a new user
ULTIBIZ_SSO_ROLE=VIEWER                  # the role of a new user
ULTIBIZ_SSO_ADMIN_ROLES=ultibiz-admin    # the provider roles that make an administrator
```

The profile is passed to Tomcat as an environment variable (the systemd unit
reads `.env`); restart the service afterwards.

## Keycloak on the deploy host

`deploy/keycloak/start-keycloak.sh` starts the provider as a container
(`ultibiz-keycloak`, image `quay.io/keycloak/keycloak:26.0.7`) on
**127.0.0.1:8180**. The script

1. generates the client secret and the password of the demo user once and keeps
   them in the `.env` of the service,
2. renders the realm template with those secrets into
   `/var/lib/ultibiz-keycloak/realm-ultibiz.json`,
3. imports the realm on the first start,
4. writes `ULTIBIZ_SSO_ISSUER`, `ULTIBIZ_SSO_CLIENT_ID`,
   `ULTIBIZ_SSO_CLIENT_SECRET` and `ULTIBIZ_SSO_REDIRECT_URI` into the `.env`.

```bash
bash deploy/keycloak/start-keycloak.sh          # start / update
bash deploy/keycloak/start-keycloak.sh status   # the container and the discovery document
bash deploy/keycloak/start-keycloak.sh stop     # stop and remove the container
```

**The public address of the provider.** The listener on 127.0.0.1 is enough
for a test on the deploy host itself. For a real user the issuer must be a URL
the browser of that user can reach (for example
`https://sso.ahadmart.com/realms/ultibiz`): the reverse proxy in front of
`ultibiz.ahadmart.com` has to forward that hostname to the provider, and
`ULTIBIZ_SSO_ISSUER` / the redirect URI of the client have to point at the
public address. That forwarding lives outside this repository.

## The end-to-end test

`tools/sso-smoke-test.py` drives the real flow with urllib: the application
redirects to the provider, the login form is filled in, the provider sends the
code back and the application continues with the local session.

```bash
python3 tools/sso-smoke-test.py http://10.10.70.12:18080 http://127.0.0.1:8180/realms/ultibiz
```

The password of the demo user is read from the `.env`
(`ULTIBIZ_SSO_TEST_PASSWORD`), nothing is printed.

## The limits (honest)

`deploy/keycloak/realm-ultibiz.json` is a realm export: the realm `ultibiz`,
the client `ultibiz` (a confidential client with the standard flow), the roles
`ultibiz-admin` and `ultibiz-user` and a demo user.

Import it in Keycloak (Realm settings → Import), then:

1. Replace `CHANGE-ME-GENERATE-A-SECRET` by a real client secret (Clients →
   ultibiz → Credentials) and put the same value into
   `ULTIBIZ_SSO_CLIENT_SECRET`.
2. Put the public URL of the application in the redirect URI of the client
   (`https://ultibiz.ahadmart.com/login/oauth2/code/keycloak`).
3. Give the users the role `ultibiz-admin` when they should be administrators.

Without Keycloak any OpenID Connect provider works: the issuer URI is the only
provider-specific setting, and the claims are the standard ones.

- The automatic provisioning gives a new user the **default** organisation and
  role — the role is not derived from the provider's groups (only the
  administrator roles are mapped). Change the role afterwards in the user
  administration.
- The SSO session and the local session have the same lifetime
  (`spring.session.timeout`, 8 h by default).
- A user that was created by the SSO has a **random password**: the form login
  does not work for that user until an administrator sets a password.
- The `sso` profile must be active **before** the first SSO user is expected:
  without the profile the login page has no SSO link and the OAuth2 endpoints
  do not exist.
