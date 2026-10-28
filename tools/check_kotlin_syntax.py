#!/usr/bin/env python3
"""Syntax sanity check for Kotlin sources, tuned for the traps this repo has hit.

The sandbox that maintains this repo has no JDK or Android SDK, so `./gradlew` cannot
run there. Naive brace-counters are not enough, because two Kotlin rules make a file
look balanced while the compiler rejects it outright:

1. **Block comments nest.** `/*` inside a KDoc opens a *second* level; the single `*/`
   that follows closes only that inner level, and the rest of the file is swallowed.
   One literal `/*` in a doc comment produced ~80 "Unresolved reference" errors once.
2. **Raw strings and comments take precedence over each other.** A `/*` inside a raw
   string is plain text, while a `"` inside a block comment is plain text. A scanner
   has to decide which state it is in rather than matching each construct independently.

Reports, per file: unterminated strings/comments, depth underflow, and unbalanced
brackets. Exits non-zero if anything is wrong.
"""

from __future__ import annotations

import sys
from pathlib import Path

PAIRS = {"(": ")", "[": "]", "{": "}"}
CLOSERS = {v: k for k, v in PAIRS.items()}


def check_file(path: Path) -> list[str]:
    src = path.read_text(encoding="utf-8")
    problems: list[str] = []

    i, n = 0, len(src)
    line = 1
    comment_depth = 0
    comment_start_line = 0
    stack: list[tuple[str, int]] = []

    while i < n:
        ch = src[i]
        nxt = src[i + 1] if i + 1 < n else ""

        # ---- inside a (possibly nested) block comment: nothing else counts ----
        if comment_depth:
            if ch == "/" and nxt == "*":
                comment_depth += 1
                i += 2
                continue
            if ch == "*" and nxt == "/":
                comment_depth -= 1
                i += 2
                continue
            if ch == "\n":
                line += 1
            i += 1
            continue

        # ---- line comment ----
        if ch == "/" and nxt == "/":
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue

        # ---- block comment (may nest later) ----
        if ch == "/" and nxt == "*":
            comment_depth = 1
            comment_start_line = line
            i += 2
            continue

        # ---- backtick-quoted identifier: may contain apostrophes and spaces ----
        if ch == "`":
            end = src.find("`", i + 1)
            if end < 0:
                problems.append(f"line {line}: unterminated backtick identifier")
                break
            i = end + 1
            continue

        # ---- raw string: """ ... """ ----
        if src.startswith('"""', i):
            end = src.find('"""', i + 3)
            if end < 0:
                problems.append(f"line {line}: unterminated raw string")
                break
            line += src.count("\n", i, end)
            i = end + 3
            continue

        # ---- string or char literal ----
        if ch in ('"', "'"):
            quote = ch
            j = i + 1
            while j < n and src[j] != quote:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "\n":  # unterminated on this line
                    problems.append(f"line {line}: unterminated {quote} literal")
                    j = n
                    break
                j += 1
            if j >= n:
                break
            if j >= n or src[j] != quote:
                i = n
                continue
            line += src.count("\n", i, j)
            i = j + 1
            continue

        # ---- brackets ----
        if ch in PAIRS:
            stack.append((ch, line))
        elif ch in CLOSERS:
            if not stack:
                problems.append(f"line {line}: unmatched '{ch}'")
            elif stack[-1][0] != CLOSERS[ch]:
                problems.append(
                    f"line {line}: '{ch}' closes '{stack[-1][0]}' opened at line {stack[-1][1]}"
                )
                stack.pop()
            else:
                stack.pop()

        if ch == "\n":
            line += 1
        i += 1

    if comment_depth:
        problems.append(
            f"line {comment_start_line}: unclosed block comment "
            f"(depth {comment_depth} at EOF — check for a literal '/*' inside a KDoc)"
        )
    for opener, open_line in stack:
        problems.append(f"line {open_line}: unclosed '{opener}'")

    return problems


def main(argv: list[str]) -> int:
    roots = [Path(a) for a in argv[1:]] or [Path("app/src")]
    files = sorted(p for root in roots for p in root.rglob("*.kt"))
    if not files:
        print("no Kotlin files found", file=sys.stderr)
        return 2

    failed = 0
    for path in files:
        problems = check_file(path)
        if problems:
            failed += 1
            print(f"\n{path}")
            for problem in problems:
                print(f"  {problem}")

    if failed:
        print(f"\nFAILED: {failed} of {len(files)} files have syntax problems.")
        return 1

    print(f"OK: {len(files)} Kotlin files parsed cleanly (nested comments, strings, brackets).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
