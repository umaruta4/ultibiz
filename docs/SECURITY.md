# The hardening notes

What protects the application, and where the limits are.

## The access

- **Spring Security** with a form login; every page needs an account
  (`anyRequest().authenticated()`), only the login, the static assets, the
  health endpoint, the inbound webhook and the customer portal are public.
- **The customer portal** (`/portal/{token}`) is public: the 32-character token
  in the link is the secret, the page is read-only plus the reply of the
  customer, and the token is not guessable (SecureRandom).
- **The inbound webhook** (`/api/exchange/inbound/**`) is public but carries
  its own shared secret (`ULTIBIZ_EXCHANGE_WEBHOOK_SECRET`), checked in the
  controller.
- **CSRF** is on for every form (the hidden `_csrf` field); the APIs use the
  same token.
- **The security headers**: the content security policy, the frame options and
  the content type options are set in `SecurityConfig`.

## The rate limit

The public endpoints are limited per IP address and per path:
`ultibiz.rate-limit.requests` (60) requests per `ultibiz.rate-limit.window-seconds`
(60) seconds; the answer is `429` with a `Retry-After` header.

## The data

- **The SQL is always parameterised** — no string concatenation of a value
  into a query (the only dynamic parts are the table/column names of the
  framework and the fixed IN-lists).
- **The multi-tenant filter**: every query filters on `organization_id` and,
  for the transactional data, on the site scope of the user.
- **The personal data** (HRM) is in the GDPR scope: the export and the erasure
  are implemented, the erasure anonymises and never touches the financial rows.
- **The audit trail** is append-only and written for every mutating action.
- **The encryption key** of the AI provider keys is read from the environment;
  without it no key is stored.

## The secrets

The secrets live in `.env` (mode 600, owner sufehmi) on the server; they are
never in the git repository. The AI provider keys are encrypted at rest with
`ULTIBIZ_AI_ENCRYPTION_KEY` (AES).

## The limits (honest)

- The rate limiter keeps its counters in the memory of the instance: with
  several replicas the limit is per instance.
- The customer portal token does not expire; a ticket that is closed keeps its
  link readable (the customer may still need the history).
- The report delivery uses a webhook (HTTP POST with JSON), not SMTP: there is
  no mail relay on the server, the report is written to disk and offered for
  download.
- The PDF is a print-ready HTML page (the browser saves it as PDF); no PDF
  renderer is bundled.
