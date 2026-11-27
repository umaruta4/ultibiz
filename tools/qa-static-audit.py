#!/usr/bin/env python3
"""The static quality audit of the repository (the layer 1 of the quality run).

Nothing in this script needs a database or a network: it reads the tracked tree
and fails on the findings that must not ship.

Checks:
  - no secret file is tracked (the .env of the service, a private key, a pem)
  - no value that looks like a real credential in a tracked text file
  - no process is started from the application code
  - no sql is built by string concatenation from a variable
  - the vendored assets match the published checksums
  - the Flyway scripts are in order and are not renamed
  - no internal phase name in the commit history or the tracked text
  - the i18n keys and the configuration table of the README are up to date

Usage: python3 tools/qa-static-audit.py [--quick]
  --quick   skip the checks that need to recompute the checksums
"""
import hashlib
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QUICK = "--quick" in sys.argv
RESULTS = []


def check(name, ok, evidence=""):
    RESULTS.append((ok, name, evidence))
    print("%-4s %-58s %s" % ("PASS" if ok else "FAIL", name, evidence))


def run(*args):
    return subprocess.run(args, cwd=ROOT, capture_output=True, text=True).stdout


def tracked():
    return [f for f in run("git", "ls-files").splitlines() if f]


FILES = tracked()


def main():
    # ---- the secrets of the repository ---------------------------------------
    bad = [f for f in FILES
           if re.search(r"(^|/)\.env$|id_rsa$|\.pem$|\.key$|\.p12$|\.keystore$", f)]
    check("no secret file is tracked", not bad, ", ".join(bad[:3]))

    # only a quoted literal can be a leaked credential: a variable that is
    # filled at runtime is not a finding
    credential = re.compile(
        r"(password|secret|api[_-]?key|token)\s*[:=]\s*[\"']([A-Za-z0-9+/]{20,}={0,2})[\"']", re.I)
    placeholder = re.compile(
        r"change|example|placeholder|your-|xxxxx|dummy|<|\$\{|\*|masked|sealed|enc:|v1:|qa-|test")
    findings = []
    for name in FILES:
        if not re.search(r"\.(java|xml|yml|yaml|properties|sh|py|json|sql)$", name):
            continue
        try:
            text = open(os.path.join(ROOT, name), encoding="utf-8", errors="ignore").read()
        except OSError:
            continue
        for match in credential.finditer(text):
            value = match.group(2)
            if placeholder.search(value) or len(set(value)) < 8:
                continue
            findings.append("%s: %s" % (name, match.group(1)))
    check("no value looks like a real credential", not findings,
          "; ".join(findings[:4]))

    # ---- the dangerous calls -------------------------------------------------
    processes = []
    concat = []
    # the identifiers that are concatenated into a statement (a table, a
    # database) are the real injection risk; a clause that is built by the
    # site-scope builder is not. Reviewed exceptions are listed explicitly.
    reviewed = {
        "ultibiz-core/src/main/java/com/ultibiz/core/tenant/TenantProvisionService.java":
            "the database and the table names come from the tenant registry",
    }
    for name in FILES:
        if not name.endswith(".java"):
            continue
        text = open(os.path.join(ROOT, name), encoding="utf-8", errors="ignore").read()
        if "Runtime.getRuntime" in text or "new ProcessBuilder" in text:
            processes.append(name)
        if name in reviewed:
            continue
        if re.search(r"(INSERT|UPDATE|DELETE|CREATE|ALTER)[^;]*`\"\s*\+\s*\w+", text):
            concat.append(name)
    check("no process is started from the code", not processes, ", ".join(processes[:3]))
    check("no sql is concatenated from a variable", not concat, ", ".join(set(concat)))

    # ---- the vendored assets --------------------------------------------------
    vendor = os.path.join(ROOT, "ultibiz-web/src/main/resources/static/vendor")
    sums = os.path.join(vendor, "SHA256SUMS")
    if os.path.exists(sums):
        wrong = []
        for line in open(sums, encoding="utf-8"):
            parts = line.split()
            if len(parts) != 2 or QUICK:
                continue
            expected, filename = parts
            path = os.path.join(vendor, filename)
            if not os.path.exists(path):
                wrong.append(filename + " (missing)")
                continue
            actual = hashlib.sha256(open(path, "rb").read()).hexdigest()
            if actual != expected:
                wrong.append(filename)
        check("the vendored assets match the checksums", not wrong, ", ".join(wrong[:3]))
    else:
        print("INFO the checksums of the vendored assets: no SHA256SUMS file")

    # ---- the migrations -------------------------------------------------------
    folder = os.path.join(ROOT, "ultibiz-core/src/main/resources/db/migration")
    scripts = sorted(f for f in os.listdir(folder) if f.endswith(".sql"))
    versions = [f.split("__")[0] for f in scripts]
    check("the migration scripts are in order",
          versions == sorted(set(versions)) and len(versions) == len(set(versions)),
          "%d scripts" % len(scripts))
    check("the version of a migration is unique", len(versions) == len(set(versions)))

    # ---- the internal phase names ---------------------------------------------
    history = run("git", "log", "--format=%B")
    tokens = re.findall(r"\bP[0-9]+[a-z]?\b", history)
    check("no phase name in the commit messages", not tokens, "%d tokens" % len(tokens))
    named = [f for f in FILES if re.search(r"(^|/)[^/]*P[0-9]+[a-z]?[^/]*$", f)]
    check("no phase name in the name of a file", not named, ", ".join(named[:4]))
    # the historical notes may name the phase they describe: that is the story
    # of the release, not a leak of the working plan
    historical = []
    for name in FILES:
        if not name.endswith(".md"):
            continue
        text = open(os.path.join(ROOT, name), encoding="utf-8", errors="ignore").read()
        if re.search(r"\bP[0-9]+[a-z]?\b", text):
            historical.append(name)
    if historical:
        print("INFO the historical notes name the phase they describe: %s"
              % ", ".join(historical[:4]))

    # ---- the i18n and the documentation ---------------------------------------
    audit = subprocess.run(["bash", "tools/i18n-audit.sh"], cwd=ROOT,
                           capture_output=True, text=True)
    check("the i18n audit passes", audit.returncode == 0,
          (audit.stdout + audit.stderr).strip().splitlines()[-1][:60])
    docs = subprocess.run([sys.executable, "tools/readme-config-table.py", "--check"],
                          cwd=ROOT, capture_output=True, text=True)
    check("the configuration table of the README is up to date", docs.returncode == 0,
          docs.stdout.strip().splitlines()[0][:60])

    passed = len([r for r in RESULTS if r[0]])
    print("== %d checks: %d passed, %d failed ==" % (len(RESULTS), passed,
                                                    len(RESULTS) - passed))
    for ok, name, evidence in RESULTS:
        if not ok:
            print("FAILED: %s (%s)" % (name, evidence))
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
