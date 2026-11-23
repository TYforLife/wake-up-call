#!/usr/bin/env python3
"""Checks a pull request against docs/WORKFLOW.md "PR checks". Each failure says what to do instead.

CI passes the PR's title and body in the environment (PR_TITLE, PR_BODY), and the commit range as arguments:
    python3 tools/check_pr.py <base-sha> <head-sha>
"""
import os
import re
import subprocess
import sys

TYPES = "feat|fix|chore|docs|refactor|test|perf|build|ci|style|revert"
TITLE = re.compile(rf"^({TYPES})(\([a-z0-9-]+\))?!?: \S")
FIX = re.compile(r"^fix(\(|!?:)")
TEST_PATH = re.compile(r"(^|/)src/[A-Za-z]*[Tt]est[A-Za-z]*/|^tools/test_")
PHONE = re.compile(r"^#+\s*Test on phone\b", re.IGNORECASE | re.MULTILINE)
APK = "debug-latest"
NO_TEST = re.compile(r"^No test: *\S", re.MULTILINE)


def problems(title: str, body: str, subjects: list[str], files: list[str]) -> list[str]:
    out = []
    if not TITLE.match(title):
        out.append(
            f'PR title "{title}" is not a conventional commit. Write `type(scope): subject`, one type from '
            f"{TYPES.replace('|', ', ')}, for example `fix(alarm): the ring starts with the screen off`."
        )
    if not PHONE.search(body):
        out.append('PR body has no "## Test on phone" section. End it with the checks CI can\'t run (CLAUDE.md).')
    elif APK not in body.split("Test on phone", 1)[1]:
        out.append(
            'The "Test on phone" section has no APK link. Add '
            "https://github.com/tyforlife/wake-up-call/releases/download/debug-latest/app-debug.apk"
        )
    is_fix = FIX.match(title) or any(FIX.match(s) for s in subjects)
    if is_fix and not any(TEST_PATH.search(f) for f in files) and not NO_TEST.search(body):
        out.append(
            "A fix with no test. Reproduce the bug as a failing test first, then fix it (docs/WORKFLOW.md \"Bug "
            'fix"). If no test can catch it, add a line to the PR body: `No test: <why, and what would catch it>`.'
        )
    return out


def git(*args: str) -> list[str]:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout.splitlines()


def main() -> int:
    base, head = sys.argv[1], sys.argv[2]
    found = problems(
        os.environ.get("PR_TITLE", ""),
        os.environ.get("PR_BODY", ""),
        git("log", "--format=%s", f"{base}..{head}"),
        git("diff", "--name-only", f"{base}...{head}"),
    )
    for p in found:
        print(f"::error::{p}")
    if not found:
        print("PR checks passed.")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
