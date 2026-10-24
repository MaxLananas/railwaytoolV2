"""Print one version's section from CHANGELOG.md, for the release body."""

import argparse
import re
import sys


def sections(text):
    """Map versions to their body: everything up to the next level-2 heading."""
    found = {}
    current = None
    for line in text.splitlines():
        match = re.match(r"^## \[([^\]]+)\]", line)
        if match:
            current = match.group(1)
            found[current] = []
            continue
        if line.startswith("## ") and current is not None:
            current = None
            continue
        if current is not None:
            found[current].append(line)
    return {k: "\n".join(v).strip() for k, v in found.items()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("version", help="version without the leading v, e.g. 2.0.3")
    ap.add_argument("--file", default="CHANGELOG.md")
    args = ap.parse_args()

    try:
        with open(args.file, encoding="utf-8") as f:
            text = f.read()
    except OSError as e:
        print("cannot read %s: %s" % (args.file, e), file=sys.stderr)
        return 2

    body = sections(text).get(args.version)
    if not body:
        print("no section for %s in %s" % (args.version, args.file),
              file=sys.stderr)
        return 1
    print(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
