#!/usr/bin/env python3
# Copyright (C) 2026 Javier Sánchez
# SPDX-License-Identifier: GPL-3.0-or-later
"""Decides whether a push to main should produce a release, and which version.

Rules (Conventional Commits, evaluated over every commit since the last v* tag):
  * `type!:` or a `BREAKING CHANGE:` footer -> major   (while 0.x: minor)
  * `feat`                                   -> minor
  * `fix`, `perf`, `security`, `refactor`, `revert`, or an unrecognised message -> patch
  * `docs`, `chore`, `ci`, `test`, `build`, `style` -> no release on their own
  * `[skip release]` anywhere in a message  -> that commit never triggers a release
The first release (no tag yet) is 0.1.0. The highest bump wins.

Usage:
  release_version.py next                 # inspects git, prints and writes $GITHUB_OUTPUT
  release_version.py check-title "<title>"  # validates a PR title
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
from dataclasses import dataclass

TYPES_PATCH = {"fix", "perf", "security", "refactor", "revert"}
TYPES_MINOR = {"feat"}
TYPES_NO_RELEASE = {"docs", "chore", "ci", "test", "build", "style"}
ALL_TYPES = TYPES_PATCH | TYPES_MINOR | TYPES_NO_RELEASE

HEADER = re.compile(r"^(?P<type>[a-zA-Z]+)(\((?P<scope>[^)]+)\))?(?P<bang>!)?: (?P<desc>\S.*)$")
BREAKING_FOOTER = re.compile(r"^BREAKING[ -]CHANGE: ", re.MULTILINE)
SEMVER = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")
ORDER = {"none": 0, "patch": 1, "minor": 2, "major": 3}


@dataclass(frozen=True)
class Change:
    kind: str       # conventional type or "other"
    breaking: bool
    skip: bool


def parse_commit(message: str) -> Change:
    lines = [l for l in message.strip().splitlines()]
    subject = lines[0].strip() if lines else ""
    body_lines = [l for l in lines[1:] if l.strip()]
    # GitHub "Merge pull request #n from x/y" keeps the PR title on the first body line.
    if subject.startswith("Merge ") and body_lines:
        subject = body_lines[0].strip()
    subject = re.sub(r"\s*\(#\d+\)$", "", subject)  # squash merges append the PR number
    skip = "[skip release]" in message.lower()
    breaking = bool(BREAKING_FOOTER.search(message))
    m = HEADER.match(subject)
    if not m:
        return Change("other", breaking, skip)
    kind = m.group("type").lower()
    return Change(kind if kind in ALL_TYPES else "other", breaking or bool(m.group("bang")), skip)


def bump_for(change: Change, major_zero: bool) -> str:
    if change.skip:
        return "none"
    if change.breaking:
        return "minor" if major_zero else "major"
    if change.kind in TYPES_MINOR:
        return "minor"
    if change.kind in TYPES_NO_RELEASE:
        return "none"
    return "patch"


def decide(last_version: tuple[int, int, int] | None, messages: list[str]) -> tuple[str, tuple[int, int, int] | None]:
    """Returns (bump, new_version). new_version is None when nothing should be released."""
    if last_version is None:
        # Nothing released yet: the first release goes out as soon as there is any releasable change.
        releasable = any(bump_for(parse_commit(m), True) != "none" for m in messages)
        return ("initial", (0, 1, 0)) if releasable else ("none", None)
    major, minor, patch = last_version
    bump = max((bump_for(parse_commit(m), major == 0) for m in messages), key=ORDER.__getitem__, default="none")
    if bump == "major":
        return bump, (major + 1, 0, 0)
    if bump == "minor":
        return bump, (major, minor + 1, 0)
    if bump == "patch":
        return bump, (major, minor, patch + 1)
    return "none", None


def version_code(v: tuple[int, int, int]) -> int:
    """Monotonic Android versionCode: 1.2.3 -> 1_002_003 (minor and patch up to 999)."""
    major, minor, patch = v
    if minor > 999 or patch > 999:
        raise ValueError("minor/patch must be <= 999")
    return major * 1_000_000 + minor * 1_000 + patch


def check_title(title: str) -> str | None:
    """Returns an error message, or None when the PR title is a valid Conventional Commit."""
    m = HEADER.match(title.strip())
    if not m or m.group("type").lower() not in ALL_TYPES:
        return (
            "PR title must look like `type: description` or `type(scope): description` "
            f"with type one of: {', '.join(sorted(ALL_TYPES))} (append `!` for breaking changes). Got: {title!r}"
        )
    return None


def _git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def _last_tag() -> tuple[str | None, tuple[int, int, int] | None]:
    tags = [t for t in _git("tag", "--list", "v[0-9]*", "--sort=-v:refname").split() if SEMVER.match(t)]
    if not tags:
        return None, None
    return tags[0], tuple(int(x) for x in SEMVER.match(tags[0]).groups())  # type: ignore[arg-type]


def _write_output(values: dict[str, str]) -> None:
    for k, v in values.items():
        print(f"{k}={v}")
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a", encoding="utf-8") as fh:
            for k, v in values.items():
                fh.write(f"{k}={v}\n")


def main(argv: list[str]) -> int:
    if len(argv) >= 2 and argv[0] == "check-title":
        error = check_title(argv[1])
        if error:
            print(f"::error::{error}")
            return 1
        print("PR title OK")
        return 0
    if argv[:1] == ["next"]:
        tag, last = _last_tag()
        rng = f"{tag}..HEAD" if tag else "HEAD"
        log = _git("log", "--format=%B%x1e", rng)
        messages = [m for m in log.split("\x1e") if m.strip()]
        bump, new = decide(last, messages)
        if new is None:
            _write_output({"release": "false", "bump": bump})
            print(f"No release: {len(messages)} commit(s) since {tag or 'the beginning'} need none.")
            return 0
        _write_output({
            "release": "true", "bump": bump,
            "version": ".".join(map(str, new)), "tag": "v" + ".".join(map(str, new)),
            "version_code": str(version_code(new)),
        })
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
