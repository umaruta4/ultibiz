# Release notes — P12 (the single sign-on) and P13 (one database per organisation)

## P12 — SSO / OpenID Connect (Keycloak)

* The OAuth2 client of the Spring Security (the authorization code flow) with
  the profile `sso`; without the profile there is no OAuth2 client and the
  form login behaves exactly as before.
* `com.ultibiz.core.sso`:
  * `SsoUserPolicy` — the claims of the provider are flattened
    (`preferred_username`, `email`, `name`, `realm_access.roles`), the
    username and the mail are normalised and the eligibility of the automatic
    provisioning is decided;
  * `SsoRepository` — the lookup of the local user and the data of the
    provisioning;
  * `SsoProvisioningService` — creates the local user (a random password that
    cannot be used on the form login, the default organisation and role), the
    second login reuses the row.
* `SsoSuccessHandler` — the session continues with the **local** principal, so
  the permissions, the site scope and the audit behave like with the form
  login.
* `SecurityConfig` — the OIDC login and the OIDC logout are only configured
  when a client registration exists; the form login stays the fallback.
* The login page shows **Sign in with Keycloak** only when the SSO is active.
* `deploy/keycloak/realm-ultibiz.json` — the realm template (the realm
  `ultibiz`, the client `ultibiz`, the roles `ultibiz-admin`/`ultibiz-user`,
  the protocol mappers, the demo user) with a placeholder secret.
* `deploy/keycloak/start-keycloak.sh` — starts the provider on
  `localhost:8180` with a generated client secret and a generated password of
  the demo user; both go to the `.env` of the service.
* `tools/sso-smoke-test.py` — the end-to-end test of the flow (the
  authorization code, the login form of the provider, the callback, the local
  session).
* `docs/SSO.md` — the guide.

## P13 — the multi-tenant on the database level

* The migration `V202611250017__p13_tenants.sql` — the table
  `tenant_registry` (the organisation, the database, the state, the actor).
* `com.ultibiz.core.tenant`:
  * `TenantContext` — the tenant of the request (a thread local, removed at
    the end of the request);
  * `TenantRoutingDataSource` — the connection goes to the database of the
    tenant; with the mode `single` every connection goes to the control
    database;
  * `TenantDataSourceFactory` — the pool of a tenant database;
  * `TenantService` — the tenant of an organisation (the state `ACTIVE` and
    the mode `database` decide whether the routing is used);
  * `TenantProvisionService` — creates the database, runs the migrations,
    removes the demo data, copies the identity and the reference data, grants
    the application user and registers the tenant.
* `TenantRegistryRepository` — the registry, always read from the **control**
  database (the routing must be able to read it before the tenant of the
  request is known).
* `TenantDataSourceConfig` — the control pool, the routing data source (the
  primary one) and the template of the control database.
* `TenantContextFilter` — puts the tenant of the logged-in user on the
  thread.
* **Administration → Tenants** (`/admin/settings/tenants`, the administrators
  only) — the list of the tenants, the suspension and the provisioning.
* The mode stays `single` (`ULTIBIZ_TENANT_MODE=single`): the existing
  installation changes nothing until the mode is switched on.
* `docs/MULTI-TENANT.md` — the guide.

## The tests

* `TenantContextTest` — the tenant of the thread.
* `TenantDatabaseIntegrationTest` — a **real** second database: it is
  provisioned, the migrations run on it, the identity is copied, a row written
  in the tenant is **not** in the control database, a suspended tenant falls
  back to the control database.
* `SsoUserPolicyTest` — the claims and the policy.
* `SsoIntegrationTest` — the provisioning of a user of the provider against
  the database.
* `ApplicationContextTest` — the **whole** application context against the
  test database (a web slice does not load the domain layer: a broken
  data-source configuration only shows up there).

**208 core + 5 web tests green**, the i18n audit passes.


## The result of the deployment (2026-11-27)

* The release gate is green, the WAR is deployed, the login page and the
  administration of the tenants are reachable.
* The migration `V202611250017__p13_tenants.sql` is applied, the registry
  contains `ACME → ultibiz → ACTIVE`.
* The single sign-on was tested **live** (see `docs/SSO.md`), including the
  provisioning with the role `USER`.
* **The health endpoint is UP again.** The cause of the old DOWN was the
  indicator of the mail server: `spring.mail.host` was always defined (empty
  when no relay is configured), so a mail sender without a server was created
  and the indicator failed. The relay of the environment now lives in
  `ultibiz.mail.smtp.*` (the report mailer builds its own session — the
  application never used the mail sender of Spring Boot).
* The health endpoint shows the details (the name of a failed indicator) to
  the administrators (`show-details: when_authorized`); the anonymous client
  only sees the status.
