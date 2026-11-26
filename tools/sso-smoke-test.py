#!/usr/bin/env python3
"""The end-to-end test of the single sign-on (the OIDC authorization code flow).

The script drives a real browser flow with urllib: the application redirects to
the identity provider, the login form of the provider is filled in, the provider
redirects back with the code and the application continues with the **local**
session (the local user, its permissions and its organisation).

Usage:
    python3 tools/sso-smoke-test.py [base-url] [issuer]

The credentials are read from the .env of the service:
    ULTIBIZ_SSO_TEST_PASSWORD  the password of the demo user of the realm
    ULTIBIZ_SSO_USER           the user of the realm (default "budi")

Exit code 0 = the flow worked (the local session is open).
"""

import http.cookiejar
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
ISSUER = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8180/realms/ultibiz"
ENV_FILE = os.environ.get("ULTIBIZ_ENV_FILE", "/home/janedoe/projects/ultibiz/.env")


def env_values():
    values = {}
    try:
        with open(ENV_FILE) as handle:
            for line in handle:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, _, value = line.partition("=")
                    values[key] = value
    except OSError as error:
        print("the .env could not be read: %s" % error)
    return values


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """The redirects are not followed automatically — the flow is checked."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def opener():
    jar = http.cookiejar.CookieJar()
    return urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(jar), NoRedirect()), jar


class FormParser(HTMLParser):
    """The action of the login form and its hidden fields."""

    def __init__(self):
        super().__init__()
        self.action = None
        self.fields = {}
        self._in_form = False

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "form" and self.action is None:
            self.action = attributes.get("action")
            self._in_form = True
        elif tag == "input" and self._in_form:
            name = attributes.get("name")
            if name:
                self.fields[name] = attributes.get("value", "")


def relax_secure_cookies(jar):
    """Keycloak 26 marks the session cookie as *secure* — the provider lives
    behind https in the real setup (a browser would send the cookie there).
    This test speaks http on localhost, so the flag is removed here; without
    it the provider answers 400 ("invalid request") because it does not find
    its own session cookie."""
    for cookie in jar:
        if cookie.secure:
            cookie.secure = False


def request(opener, url, data=None, headers=None):
    """One request without following the redirects; returns (status, headers, body)."""
    body = urllib.parse.urlencode(data).encode() if data else None
    req = urllib.request.Request(url, data=body, headers=headers or {})
    if data:
        req.add_header("Content-Type", "application/x-www-form-urlencoded")
    try:
        response = opener.open(req, timeout=30)
        return response.status, dict(response.headers), response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read().decode("utf-8", "replace")


def main():
    values = env_values()
    password = values.get("ULTIBIZ_SSO_TEST_PASSWORD", "")
    user = values.get("ULTIBIZ_SSO_USER", "budi")
    if not password:
        print("FAIL: ULTIBIZ_SSO_TEST_PASSWORD is not set (the demo user of the realm)")
        return 1

    client, jar = opener()

    # 1. the application starts the flow
    status, headers, _ = request(client, BASE + "/oauth2/authorization/keycloak")
    location = headers.get("Location", "")
    if status not in (302, 303) or "openid-connect/auth" not in location:
        print("FAIL: the application did not start the OIDC flow (status %s, %s)"
              % (status, location[:120]))
        return 1
    print("ok: the application redirects to the provider")

    # 2. the login form of the provider
    status, _, page = request(client, location)
    if status != 200:
        print("FAIL: the login form of the provider is not reachable (status %s)" % status)
        return 1
    parser = FormParser()
    parser.feed(page)
    if not parser.action:
        print("FAIL: no login form at the provider")
        return 1
    print("ok: the provider shows its login form")
    relax_secure_cookies(jar)

    # 3. the credentials
    fields = dict(parser.fields)
    fields["username"] = user
    fields["password"] = password
    print("  the cookies before the login: %s"
          % ", ".join("%s(%s%s)" % (c.name, c.domain, c.path) for c in jar))
    status, headers, body = request(client, parser.action, data=fields)
    location = headers.get("Location", "")
    if "/login/oauth2/code/keycloak" not in location:
        print("FAIL: the provider did not send the code back (status %s, %s)"
              % (status, location[:160]))
        print("  the form action: %s" % parser.action)
        print("  the posted fields: %s" % ", ".join(sorted(fields)))
        text = re.sub(r"<[^>]+>", " ", body)
        text = re.sub(r"\s+", " ", text).strip()
        print("  the provider said: %s" % text[:500])
        return 1
    print("ok: the provider sent the authorization code back")
    relax_secure_cookies(jar)

    # 4. the callback of the application: the local session takes over
    status, headers, _ = request(client, location)
    if status not in (302, 303):
        print("FAIL: the callback of the application failed (status %s)" % status)
        return 1
    print("ok: the application accepted the code (the local session is open)")

    # 5. the session works
    status, _, page = request(client, BASE + "/")
    if status != 200:
        print("FAIL: the home page is not reachable with the SSO session (status %s)" % status)
        return 1
    if "UltiBiz" not in page:
        print("FAIL: the home page does not show the application")
        return 1
    print("ok: the home page is open with the SSO session")
    print("PASS: the single sign-on works end to end")
    return 0


if __name__ == "__main__":
    sys.exit(main())
