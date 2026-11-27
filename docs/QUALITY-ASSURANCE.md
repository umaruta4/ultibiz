# The quality assurance of UltiBiz

Three layers, each with its own tool. Everything runs against a **quality
assurance instance** — a second Tomcat with a scratch database — so the
production instance and the production database are never touched.

| Layer | Tool | What it proves |
|---|---|---|
| 1 — static | `python3 tools/qa-static-audit.py` | no secret in the tree, no dangerous call, the vendored assets, the migrations, the i18n, the documentation |
| 2 — unit & integration | `bash tools/release-check.sh` | the whole suite with a real MariaDB (the tenant test creates a real second database) |
| 3 & 4 — contract & E2E | `python3 tools/qa-e2e.py` | the live behaviour: the authentication, the authorisation, the configuration, the rate limit, the exchange, the session |

## The quality assurance instance

```bash
bash tools/qa-instance-setup.sh      # on the deploy host, as root
```

It creates:

* the scratch database `ultibiz_qa` (grants for the application user),
* the environment file of the instance (mode 600, outside the repository): its
  own administrator password, its own sealing key, its own exchange secret —
  **the secrets of the production are never copied**,
* the second Tomcat (`ultibiz-qa.service`) on its own port, bound to the
  loopback only, with the connector of the production rewritten,
* the unit of the instance (`systemctl status ultibiz-qa`).

The commands of the run:

```bash
python3 tools/qa-e2e.py               # 27 checks against the instance
QA_RESTART=1 python3 tools/qa-e2e.py   # + the session across a restart
python3 tools/qa-static-audit.py       # the static layer
```

The harness reads the secrets from the environment file of the instance and
never prints them; it asserts a mutation by reading the state of the database,
not the output of a command.

## The rules of the run

* **No output that is not real.** Every claim carries the status code, the row
  or the value that was read back.
* **RED → GREEN.** A bug is proven with a failing test before it is fixed; the
  failing output stays in the report next to the green one.
* **No test on the production database.** The instance under test has its own
  scratch database.
* **No secret in the log, the report or the repository.**
* **The scope stays small**: only the acceptance criteria and the bugs that
  the run found.

## The defects the run found (2026-11-27)

| Defect | Symptom | Root cause | Fix | Regression |
|---|---|---|---|---|
| The partner webhook answered a redirect | `POST /api/exchange/inbound/x` → **302** to the login page | the CSRF protection covers every POST; a partner has no token | the machine endpoints are exempt from the CSRF (`SecurityConfig`) | `MachineEndpointsTest.thePartnerWebhookIsNotBlockedByTheCsrfProtection` |
| The partner webhook answered 500 | the same call → **500** (`No site access`) | the scope came from the user session; a partner has none | the organisation comes from the registry of the partners (`ExchangeService.scopeOfPartner`) | `MachineEndpointsTest.thePartnerWebhookAcceptsAPayloadWithTheSharedSecret`, `anUnknownPartnerIsRefusedWithNotFound` |
| An unknown portal token answered 500 | `GET /portal/<token>` → **500** | `HelpdeskException` was not mapped | the controller answers 404 | `MachineEndpointsTest.thePortalAnswersNotFoundForAnUnknownToken` |

Two expectations of the harness were wrong and were corrected (a session
cookie may exist after a failed login — the access is what matters; the
missing CSRF token of a browser form bounces back to the login form by
design).
