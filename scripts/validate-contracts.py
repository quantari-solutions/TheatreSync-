#!/usr/bin/env python3
"""Validate contracts/: every schema is a valid 2020-12 schema, every
examples/valid/*.json passes, every examples/invalid/*.json fails."""
import json, sys
from pathlib import Path
from jsonschema import Draft202012Validator

root = Path(__file__).resolve().parent.parent / "contracts"
schema = json.loads((root / "schemas" / "sync-message.v1.schema.json").read_text())
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema)

failures = 0
for f in sorted((root / "examples" / "valid").glob("*.json")):
    errs = list(validator.iter_errors(json.loads(f.read_text())))
    if errs:
        failures += 1
        print(f"FAIL  valid/{f.name}: {errs[0].message}")
    else:
        print(f"ok    valid/{f.name}")
for f in sorted((root / "examples" / "invalid").glob("*.json")):
    if validator.is_valid(json.loads(f.read_text())):
        failures += 1
        print(f"FAIL  invalid/{f.name}: unexpectedly passed")
    else:
        print(f"ok    invalid/{f.name} (rejected as expected)")
print(f"\n{failures} failure(s)")
sys.exit(1 if failures else 0)
