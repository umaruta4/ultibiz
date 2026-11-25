#!/usr/bin/env bash
# i18n audit: every key must exist in every bundle, and every #{key} used in a
# template must exist in every bundle.
#   tools/i18n-audit.sh            -> audit en vs id
#   tools/i18n-audit.sh <bundle>   -> audit <bundle> against the reference
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RES="$ROOT/ultibiz-web/src/main/resources"
TPL="$RES/templates"

python3 - "$RES" "$TPL" <<'PY'
import re, sys, pathlib

res, tpl = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
bundles = sorted(res.glob("messages*.properties"))

def keys(path):
    out = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        out.add(line.split("=", 1)[0].strip())
    return out

def used_keys():
    used = set()
    for path in tpl.rglob("*.html"):
        for m in re.finditer(r"#\{([A-Za-z0-9_.]+)", path.read_text(encoding="utf-8")):
            used.add(m.group(1))
    return used

if not bundles:
    print("no message bundles found in", res)
    sys.exit(1)

reference_name = "messages.properties"
reference = keys(res / reference_name)
all_keys = {b.name: keys(b) for b in bundles}
problems = 0

print(f"reference bundle: {reference_name} ({len(reference)} keys)")
for name, k in all_keys.items():
    missing = sorted(reference - k)
    extra = sorted(k - reference)
    status = "OK" if not missing and not extra else "DIFF"
    print(f"  {status:4} {name:28} {len(k):4} keys"
          + (f"  missing={missing[:8]}" if missing else "")
          + (f"  extra={extra[:8]}" if extra else ""))
    problems += len(missing) + len(extra)

used = used_keys()
print(f"\ntemplates reference {len(used)} message keys")
for name, k in all_keys.items():
    unresolved = sorted(used - k)
    if unresolved:
        print(f"  MISSING in {name}: {unresolved}")
        problems += len(unresolved)

unused = sorted(reference - used)
if unused:
    print(f"  (not referenced by any template: {len(unused)} keys, e.g. {unused[:6]})")

print("\nRESULT:", "PASS - no missing keys" if problems == 0 else f"FAIL - {problems} problem(s)")
sys.exit(0 if problems == 0 else 1)
PY
