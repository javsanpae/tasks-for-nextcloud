#!/usr/bin/env python3
# Copyright (C) 2026 Javier Sánchez
# SPDX-License-Identifier: GPL-3.0-or-later
"""Prints a Markdown summary of JUnit XML results (for $GITHUB_STEP_SUMMARY)."""
import glob
import sys
import xml.etree.ElementTree as ET


def main(pattern: str) -> int:
    tests = failures = skipped = 0
    failed: list[str] = []
    for path in glob.glob(pattern, recursive=True):
        root = ET.parse(path).getroot()
        tests += int(root.get("tests", 0))
        skipped += int(root.get("skipped", 0))
        for case in root.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                failures += 1
                failed.append(f"{case.get('classname')}.{case.get('name')}")
    print("### Unit & UI tests")
    print(f"{tests} tests, **{failures} failed**, {skipped} skipped")
    for name in failed[:50]:
        print(f"- ❌ `{name}`")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "app/build/test-results/**/*.xml"))
