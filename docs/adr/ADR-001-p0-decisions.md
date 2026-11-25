# ADR-001 — P0 Foundation Decisions

**Status:** accepted · **Date:** 2026-11-25 · **Phase:** P0

Resolves the open decisions from `docs/ULTIBIZ_MASTER_PROMPT.md` §20 that affect P0.

## 1. jOOQ usage: plain SQL via DSLContext, codegen deferred to P1

- **Decision:** repositories use `DSLContext` with hand-written, fully
  parameterized SQL. jOOQ **code generation** is deferred to P1 (master data),
  where a checked-in DDL snapshot will drive `jooq-codegen` so builds stay
  offline-capable.
- **Why:** P0 has ~10 tables and simple queries; codegen adds a build-time DB
  dependency for near-zero benefit now. Plain SQL keeps the "SQL is visible"
  rule and avoids a premature build step on the 2 GB-RAM build host.
- **Consequence:** when codegen lands in P1, repositories migrate one aggregate
  at a time; SQL strings remain the source of truth in code review either way.

## 2. Session storage: MariaDB via Spring Session JDBC

- **Decision:** `spring-session-jdbc`, schema owned by the Flyway baseline
  (`SPRING_SESSION` tables), `spring.session.jdbc.initialize-schema=never`.
- **Why:** matches the spec (no Redis; horizontal scaling without sticky
  sessions on Tomcat parallel deployment).

## 3. Theme persistence: session + localStorage (P0), user profile (P1)

- **Decision:** `POST /api/me/theme` stores the choice in the HTTP session;
  the JS keeps a per-device copy in `localStorage`. Persisting to
  `app_user.theme` lands with user settings in P1.
- **Why:** P0 has no self-service profile UI yet; the column already exists
  in the baseline schema so the P1 change is additive.

## 4. Remember-me: not in P0

- **Decision:** no remember-me tokens. Spring Session gives 8 h persistent
  sessions, which covers the shift-work use case. Persistent remember-me
  tokens are a P9 hardening item (needs its own table + rotation policy).

## 5. Tomcat bind address: 10.10.70.12 (user directive)

- **Decision:** the HTTP connector binds `10.10.70.12:18080` (server1 eth0
  LAN), not loopback. The shutdown port stays on `127.0.0.1:18005`.
- **Why:** the operator fronts the app from a forwarder on another host
  (domain `ultibiz.ahadmart.com`). Binding to the LAN address keeps the app
  unreachable from the public internet while reachable by the forwarder.
- **Consequence:** the Apache-vhost alternative (loopback bind) remains
  documented in `docs/OPERATIONS.md`; switching is a one-line `server.xml`
  change.

## 6. Database: dedicated MariaDB 11.8 container

- **Decision:** UltiBiz uses its own MariaDB 11.8 container
  (`ultibiz-mariadb`, 127.0.0.1:3311, 384 MB memory cap, 128 MB InnoDB buffer
  pool), not the host MariaDB 10.3.
- **Why:** the host instance is 10.3 (below the spec minimum and shared with
  other apps); 11.8 is the first LTS with the `VECTOR` type the Knowledge
  Vault RAG needs in P7. Isolation keeps UltiBiz migrations and backup
  policies independent.
