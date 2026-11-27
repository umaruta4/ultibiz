#!/usr/bin/env python3
"""The end-to-end quality assurance of UltiBiz.

The harness runs against a quality-assurance instance: a second Tomcat on
localhost:18081 with its own scratch database (ultibiz_qa). The production
instance and the production database are never touched.

The evidence of a mutation is the state of the database, not the output of a
command; the secrets are never printed (only their length).

Usage (on the deploy host, as root):
    python3 tools/qa-e2e.py
Environment:
    QA_BASE_URL   the instance under test (default http://localhost:18081)
    QA_ENV_FILE   the environment file of the instance (the secrets stay here)
    QA_DB         the scratch database (default ultibiz_qa)
    QA_RESTART    1 = also test that the session survives a restart
"""
import http.cookiejar
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("QA_BASE_URL", "http://localhost:18081").rstrip("/")
ENV_FILE = os.environ.get("QA_ENV_FILE", "/home/sufehmi/ultibiz-qa.env")
DB = os.environ.get("QA_DB", "ultibiz_qa")
CONTAINER = os.environ.get("QA_MARIADB", "ultibiz-mariadb")
RESTART = os.environ.get("QA_RESTART", "0") == "1"

RESULTS = []


def read_env():
    values = {}
    try:
        for line in open(ENV_FILE, encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key] = value
    except OSError:
        pass
    return values


ENV = read_env()


def sql(query):
    """A query in the scratch database; returns the rows as lists of columns."""
    result = subprocess.run(
        ["docker", "exec", "-e", "MYSQL_PWD=" + ENV.get("MARIADB_ROOT_PASSWORD", ""),
         CONTAINER, "mariadb", "-uroot", "-N", "-B", "-e", query],
        capture_output=True, text=True)
    if result.returncode != 0:
        return []
    return [line.split("\t") for line in result.stdout.strip().splitlines() if line]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """The redirects are not followed: the status code itself is the evidence."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Session:
    """One browser: a cookie jar per persona, a fresh CSRF token per POST."""

    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            NoRedirect, urllib.request.HTTPCookieProcessor(self.jar))

    def call(self, method, path, data=None, headers=None):
        url = BASE + path
        if isinstance(data, (bytes, bytearray)):
            body = bytes(data)
        else:
            body = urllib.parse.urlencode(data).encode() if data is not None else None
        request = urllib.request.Request(url, data=body, method=method)
        request.add_header("User-Agent", "ultibiz-qa/1.0")
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        try:
            with self.opener.open(request, timeout=30) as response:
                return (response.status, response.headers,
                        response.read().decode("utf-8", "replace"))
        except urllib.error.HTTPError as error:
            return (error.code, error.headers, error.read().decode("utf-8", "replace"))

    def get(self, path):
        return self.call("GET", path)

    def post(self, path, data):
        return self.call("POST", path, data=data)

    def csrf(self, path="/login"):
        """The CSRF token of the form, extracted with a regular expression."""
        _, _, body = self.get(path)
        found = re.search(r'name="_csrf"\s+[^>]*value="([^"]+)"', body)
        if not found:
            found = re.search(r'value="([^"]+)"\s+name="_csrf"', body)
        return found.group(1) if found else ""

    def cookie(self, name):
        for cookie in self.jar:
            if cookie.name == name:
                return cookie.value
        return ""

    def login(self, username, password):
        token = self.csrf("/login")
        if not token:
            return 0, ""
        code, headers, _ = self.post("/login", {"_csrf": token, "username": username,
                                                "password": password})
        return code, headers.get("Location", "")

    def save_setting(self, key, value):
        """The form of the configuration: value_<key> saves, clear_<key> removes."""
        token = self.csrf("/admin/settings/system")
        if not token:
            return 0
        return self.post("/admin/settings/system",
                         {"_csrf": token, "value_" + key: value})[0]

    def clear_setting(self, key):
        token = self.csrf("/admin/settings/system")
        if not token:
            return 0
        return self.post("/admin/settings/system",
                         {"_csrf": token, "clear_" + key: "true"})[0]


def check(name, ok, evidence=""):
    RESULTS.append(("PASS" if ok else "FAIL", name, evidence))
    print("%-4s %-62s %s" % ("PASS" if ok else "FAIL", name, evidence))
    return ok


def info(name, evidence=""):
    RESULTS.append(("INFO", name, evidence))
    print("%-4s %-62s %s" % ("INFO", name, evidence))


def main():
    print("== the quality assurance of UltiBiz ==")
    print("the instance: %s (the scratch database %s)" % (BASE, DB))

    # ---- the public surface -------------------------------------------------
    anonymous = Session()
    code, _, body = anonymous.get("/login")
    check("the login page is public", code == 200, "the status %s" % code)
    token = anonymous.csrf("/login")
    check("the login form carries a CSRF token", len(token) > 20,
          "the token of %d characters" % len(token))

    code, _, body = anonymous.get("/actuator/health")
    check("the health endpoint reports UP", code == 200 and '"UP"' in body,
          "the status %s" % code)
    check("the details of the health are not public",
          "components" not in body and '"db"' not in body,
          "the body of %d characters without the components" % len(body))

    code, _, _ = anonymous.get("/portal/qa-token-that-does-not-exist")
    check("an unknown portal token is not found", code == 404, "the status %s" % code)

    code, headers, _ = anonymous.get("/admin/settings/system")
    check("the administration needs a session",
          code in (302, 401) and "login" in headers.get("Location", ""),
          "the status %s to %s" % (code, headers.get("Location", "-")))

    # ---- the authentication -------------------------------------------------
    code, headers, _ = anonymous.post("/login", {"username": "admin", "password": "x"})
    # a stale or missing token of a browser form bounces back to the login form
    check("the login without a CSRF token is refused",
          code in (302, 403) and "csrf" in headers.get("Location", ""),
          "the status %s to %s" % (code, headers.get("Location", "-")))

    wrong = Session()
    code, location = wrong.login("admin", "definitely-not-the-password")
    denied, _, _ = wrong.get("/admin/settings/system")
    # a session cookie may exist (it carries the CSRF token), but the access
    # must be refused
    check("a wrong password gives no access",
          code == 302 and "error" in location and denied in (302, 401),
          "the login %s to %s, the administration %s" % (code, location, denied))

    admin = Session()
    code, location = admin.login("admin", ENV.get("ULTIBIZ_ADMIN_PASSWORD", ""))
    check("the administrator can log in", code == 302 and "error" not in location,
          "the status %s to %s" % (code, location))
    check("the session cookie is set", bool(admin.cookie("SESSION")),
          "the cookie of %d characters" % len(admin.cookie("SESSION")))

    code, _, _ = admin.get("/")
    check("the dashboard of the administrator", code == 200, "the status %s" % code)

    code, _, body = admin.get("/admin/settings/system")
    fields = len(re.findall(r'name="value_', body))
    groups = len(re.findall(r'<h[23][^>]*>\s*The (database|tenancy|single sign-on|e-mail|security|reports)', body))
    check("the configuration page lists the whole registry",
          code == 200 and fields >= 30, "the status %s with %d fields" % (code, fields))
    check("the configuration page has the six groups", groups == 6,
          "%d groups" % groups)

    # ---- the authorisation --------------------------------------------------
    admin_id = sql("SELECT id FROM %s.app_user WHERE username='admin'" % DB)
    role_admin = sql("SELECT id FROM %s.role WHERE code='ADMIN'" % DB)
    role_user = sql("SELECT id FROM %s.role WHERE code='USER'" % DB)
    if admin_id and role_admin and role_user:
        sql("DELETE FROM %s.user_role WHERE user_id=%s AND role_id=%s"
            % (DB, admin_id[0][0], role_admin[0][0]))
        sql("INSERT INTO %s.user_role (user_id, role_id) VALUES (%s, %s)"
            % (DB, admin_id[0][0], role_user[0][0]))
        plain = Session()
        code, _ = plain.login("admin", ENV.get("ULTIBIZ_ADMIN_PASSWORD", ""))
        denied, _, _ = plain.get("/admin/settings/system")
        check("a user without the role admin is refused on the administration",
              code == 302 and denied == 403,
              "the login %s, the page %s" % (code, denied))
        sql("DELETE FROM %s.user_role WHERE user_id=%s AND role_id=%s"
            % (DB, admin_id[0][0], role_user[0][0]))
        sql("INSERT INTO %s.user_role (user_id, role_id) VALUES (%s, %s)"
            % (DB, admin_id[0][0], role_admin[0][0]))
    else:
        info("the authorisation of a user without the role admin",
             "the user or the role is missing in the scratch database")

    # ---- the configuration --------------------------------------------------
    admin.save_setting("rate.limit.requests", "61")
    rows = sql("SELECT value_text, secret, updated_by FROM %s.system_setting "
               "WHERE setting_key='rate.limit.requests'" % DB)
    check("a setting is stored in the database",
          bool(rows) and rows[0][0] == "61" and rows[0][2] == "admin",
          "the row %s" % (rows[0] if rows else "none"))

    secret_value = "qa-secret-" + str(int(time.time()))
    admin.save_setting("exchange.webhook.secret", secret_value)
    rows = sql("SELECT value_text, secret FROM %s.system_setting "
               "WHERE setting_key='exchange.webhook.secret'" % DB)
    sealed = rows[0][0] if rows else ""
    check("a secret is sealed in the database",
          bool(rows) and rows[0][1] == "1" and sealed.startswith("v1:")
          and secret_value not in sealed,
          "the value of %d characters, sealed %s" % (len(sealed), sealed[:3]))
    _, _, body = admin.get("/admin/settings/system")
    check("a secret is never sent back to the browser", secret_value not in body,
          "the page of %d characters" % len(body))

    admin.clear_setting("exchange.webhook.secret")
    rows = sql("SELECT COUNT(*) FROM %s.system_setting "
               "WHERE setting_key='exchange.webhook.secret'" % DB)
    check("a secret can be removed", bool(rows) and rows[0][0] == "0",
          "%s rows left" % (rows[0][0] if rows else "?"))

    # ---- the rate limit at runtime -----------------------------------------
    admin.save_setting("rate.limit.requests", "2")
    codes = []
    for _ in range(3):
        codes.append(anonymous.call(
            "POST", "/api/exchange/inbound/rate-probe",
            data={"body": "{}"},
            headers={"X-Exchange-Secret": "wrong"})[0])
    check("the rate limit is read at runtime", 429 in codes,
          "the status of the three requests %s" % codes)
    admin.clear_setting("rate.limit.requests")
    rows = sql("SELECT COUNT(*) FROM %s.system_setting "
               "WHERE setting_key='rate.limit.requests'" % DB)
    check("the setting of the rate limit is removed again",
          bool(rows) and rows[0][0] == "0", "%s rows left" % (rows[0][0] if rows else "?"))

    # ---- the contract of the exchange ---------------------------------------
    secret = ENV.get("ULTIBIZ_EXCHANGE_WEBHOOK_SECRET", "")
    # the fixture of the partner: the code is unique in the installation
    sql("DELETE FROM %s.exchange_partner WHERE code='qa-partner'" % DB)
    sql("INSERT INTO %s.exchange_partner (organization_id, code, name, protocol, direction, "
        "auth_type, is_active) SELECT o.id, 'qa-partner', 'QA Partner', 'rest', 'BOTH', "
        "'NONE', 1 FROM %s.organization o LIMIT 1" % (DB, DB))
    rows = sql("SELECT COUNT(*) FROM %s.exchange_partner WHERE code='qa-partner'" % DB)
    check("the fixture of the partner exists", bool(rows) and rows[0][0] == "1",
          "%s rows" % (rows[0][0] if rows else "?"))
    code, _, body = anonymous.call(
        "POST", "/api/exchange/inbound/qa-partner", data=b"{}",
        headers={"X-Exchange-Secret": "wrong-secret",
                 "Content-Type": "application/json"})
    check("the exchange refuses a wrong secret", code == 401,
          "the status %s (%s)" % (code, body.strip()[:60]))

    key = "qa-idem-" + str(int(time.time()))
    payload = b'{"invoice":"INV-QA-1","total":"1000.00"}'
    code, _, body = anonymous.call(
        "POST", "/api/exchange/inbound/qa-partner", data=payload,
        headers={"X-Idempotency-Key": key, "X-Exchange-Secret": secret,
                 "Content-Type": "application/json"})
    accepted = code == 202 and '"messageId"' in body
    check("the exchange accepts a partner payload", accepted,
          "the status %s (%s)" % (code, body.strip()[:70]))
    rows = sql("SELECT COUNT(*) FROM %s.exchange_message "
               "WHERE idempotency_key='%s'" % (DB, key))
    check("the message is stored in the database",
          bool(rows) and int(rows[0][0]) >= 1, "%s rows" % (rows[0][0] if rows else "?"))

    code, _, body = anonymous.call(
        "POST", "/api/exchange/inbound/qa-partner", data=payload,
        headers={"X-Idempotency-Key": key, "X-Exchange-Secret": secret,
                 "Content-Type": "application/json"})
    duplicate = code == 200 and '"duplicate":true' in body.replace(" ", "")
    check("the exchange is idempotent per idempotency key", duplicate,
          "the status %s (%s)" % (code, body.strip()[:70]))
    rows = sql("SELECT COUNT(*) FROM %s.exchange_message "
               "WHERE idempotency_key='%s'" % (DB, key))
    check("the repeated delivery creates no second message",
          bool(rows) and rows[0][0] == "1", "%s rows" % (rows[0][0] if rows else "?"))

    # ---- the locale ---------------------------------------------------------
    code, _, body = anonymous.get("/login?lang=id")
    indonesian = bool(re.search(r"Nama pengguna|Kata sandi|>Masuk<", body))
    check("the locale can be switched to indonesian",
          code == 200 and indonesian,
          "the status %s, the indonesian text %s" % (code, indonesian))

    # ---- the session across a restart ---------------------------------------
    if RESTART:
        subprocess.run(["systemctl", "restart", "ultibiz-qa"], capture_output=True)
        code = 0
        for _ in range(60):
            time.sleep(3)
            code = anonymous.get("/login")[0]
            if code == 200:
                break
        again, _, _ = admin.get("/")
        check("the session survives a restart of the instance",
              again == 200, "the status %s after the restart" % again)
    else:
        info("the session across a restart", "not run (QA_RESTART is not 1)")

    # ---- the summary ---------------------------------------------------------
    passed = len([r for r in RESULTS if r[0] == "PASS"])
    failed = len([r for r in RESULTS if r[0] == "FAIL"])
    print("== %d checks: %d passed, %d failed ==" % (len(RESULTS), passed, failed))
    for status, name, evidence in RESULTS:
        if status == "FAIL":
            print("FAILED: %s (%s)" % (name, evidence))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
