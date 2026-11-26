#!/usr/bin/env python3
"""Generates the configuration table of the README from the registry.

The registry (SettingDefinitions) is the single source of truth: every item is
editable in Administration -> System settings and has an environment variable
as the fallback. This script rewrites the table between the markers in
README.md and checks that every environment variable is present in
.env.example — so the documentation cannot drift away from the code.

Usage: python3 tools/readme-config-table.py [--check]
  --check   do not write; fail when the README is not up to date (for the CI)
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(ROOT, "ultibiz-core/src/main/java/com/ultibiz/core/settings/"
                        "SettingDefinitions.java")
README = os.path.join(ROOT, "README.md")
ENV = os.path.join(ROOT, ".env.example")
BEGIN = "<!-- begin: the configuration table -->"
END = "<!-- end: the configuration table -->"
HEADER = ("| The group | The item | The environment variable | The default | The effect |\n"
          "|---|---|---|---|---|")


def split_args(body):
    args, current, quoted = [], "", False
    for ch in body:
        if ch == '"':
            quoted = not quoted
            current += ch
        elif ch == "," and not quoted:
            args.append(current.strip())
            current = ""
        else:
            current += ch
    if current.strip():
        args.append(current.strip())
    return args


def literal(arg):
    return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', arg))


def read_registry():
    text = open(REGISTRY, encoding="utf-8").read()
    groups = dict(re.findall(r'GROUP_(\w+) = "([^"]+)";', text))
    items = []
    for method, body in re.findall(r"SettingDefinition\.(\w+)\((.*?)\);", text, re.S):
        args = split_args(body)
        secret = method == "password"
        key, label = literal(args[0]), literal(args[1])
        group = groups[args[2].strip().replace("GROUP_", "")]
        if secret:
            env, default, restart = literal(args[3]), "-", args[4] == "true"
        else:
            env, default, restart = literal(args[3]), literal(args[4]), args[5] == "true"
        items.append((group, label + (" *(sealed)*" if secret else ""), key, env,
                      default or "-", "after a restart" if restart else "at runtime"))
    return items


def table(items):
    rows = ["| %s | %s (`%s`) | `%s` | `%s` | %s |" % item for item in items]
    return "\n".join([BEGIN, HEADER] + rows + [END])


def main():
    items = read_registry()
    new = table(items)
    text = open(README, encoding="utf-8").read()
    if BEGIN not in text or END not in text:
        print("the markers of the table are missing in the README", file=sys.stderr)
        return 1
    pattern = re.compile(re.escape(BEGIN) + ".*?" + re.escape(END), re.S)
    current = pattern.search(text)
    if "--check" in sys.argv:
        if not current or current.group(0) != new:
            print("the configuration table is out of date: run tools/readme-config-table.py")
            return 1
        print("the configuration table is up to date (%d items)" % len(items))
    else:
        text = pattern.sub(lambda _: new, text, count=1)
        open(README, "w", encoding="utf-8").write(text)
        print("the configuration table is regenerated (%d items)" % len(items))
    # the guard: every environment variable of the registry is in the template
    template = open(ENV, encoding="utf-8").read()
    missing = [item[3] for item in items if item[3] not in template]
    if missing:
        print("the environment variables are missing in .env.example: " + ", ".join(missing),
              file=sys.stderr)
        return 1
    print("every environment variable of the registry is in .env.example")
    return 0


if __name__ == "__main__":
    sys.exit(main())
