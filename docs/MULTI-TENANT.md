# Multi-tenant: one database per organisation

## The model

There are two kinds of databases:

* the **control database** (`ultibiz`) — the identity (the organisations, the
  users, the roles, the site scopes) and the **tenant registry**;
* one **database per tenant** — the business data of that organisation
  (the products, the orders, the journals, the projects, …).

Every request carries the organisation of the logged-in user. A filter puts
the database of that organisation on the thread, the routing data source gives
the connection of that database, and at the end of the request the tenant is
removed (a pooled thread must not keep a tenant).

The login itself always reads the control database — the identity lives there
and is copied into every tenant database, so the site scopes and the audit of
a user keep working after the switch.

## The modes

| Mode | The behaviour |
|------|---------------|
| `single` (the default) | everything in one database; the registry is read but the routing stays on the control database |
| `database` | the queries of a tenant go to its own database; an unknown or suspended tenant falls back to the control database |

Switch with `ULTIBIZ_TENANT_MODE=database` in the environment of the service.

## Provisioning

In the administration: **Admin → Tenants → Provision a tenant database**.
Give the code of the organisation (it must exist in the control database) and
the name of the database. The service

1. creates the database (`utf8mb4`),
2. runs the migrations (the same files as the control database),
3. removes the demo data of the seed,
4. copies the identity of that organisation and the reference data
   (the countries),
5. registers the tenant as `ACTIVE`.

The creation needs the administrator of the MariaDB server, because the
application user may not create a database:

| Variable | The meaning |
|----------|-------------|
| `ULTIBIZ_DB_ADMIN_USER` | the administrator (default `root`) |
| `ULTIBIZ_DB_ADMIN_PASSWORD` | its password (default `MARIADB_ROOT_PASSWORD`) |
| `ULTIBIZ_DB_HOST`, `ULTIBIZ_DB_PORT` | the server (default `localhost:3311`) |
| `ULTIBIZ_DB_NAME`, `ULTIBIZ_DB_USER`, `ULTIBIZ_DB_PASSWORD` | the control database and the application user |

## Suspension

**Suspend** in the tenant list marks the tenant `SUSPENDED`: the routing falls
back to the control database. The database itself is not dropped.

## The tests

`TenantDatabaseIntegrationTest` provisions a real second database, runs the
migrations on it, writes a row in the tenant and proves that the row is not in
the control database. It runs when the release check has the administrator
(`ULTIBIZ_IT_ADMIN_PASSWORD`, default `MARIADB_ROOT_PASSWORD`); the CI passes
the root password of the service container.

## The limits of this version

* The identity is copied at the provisioning. A user created later in the
  control database is not automatically copied into an existing tenant
  database — run the provisioning again or copy the identity rows.
* The database of a tenant lives on the same MariaDB server as the control
  database. Another server would need its own connection settings.
* With the mode `single` nothing changes for the existing installation.
