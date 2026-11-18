#!/usr/bin/env python3
"""Fails if a Markdown file links to a repo path that does not exist."""
import re, sys
from pathlib import Path
root = Path(__file__).resolve().parent.parent
bad = 0
for md in [*root.glob("*.md"), *root.glob("docs/**/*.md")]:
    for target in re.findall(r"\]\(([^)#\s]+)", md.read_text()):
        if re.match(r"^[a-z]+:", target):
            continue
        if not (md.parent / target).exists():
            print(f"{md.relative_to(root)}: broken link -> {target}")
            bad += 1
print("docs ok" if not bad else f"{bad} broken link(s)")
sys.exit(1 if bad else 0)
