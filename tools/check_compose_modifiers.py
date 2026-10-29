#!/usr/bin/env python3
"""Catch Compose `Modifier` calls that match no overload.

Why this exists: `tools/typecheck.py` cannot compile Compose files - androidx is not
resolvable where this repo is maintained - so the 1000+ lines of UI code are dropped by
design and only a real Gradle build checks them. That is how this reached `main`:

    app/src/main/java/com/sabihon/todo/ui/activity/CalendarScreen.kt:133:41
    e: None of the following candidates is applicable:
    fun Modifier.padding(start: Dp = ..., top: Dp = ..., end: Dp = ..., bottom: Dp = ...)
    fun Modifier.padding(horizontal: Dp = ..., vertical: Dp = ...)
    fun Modifier.padding(all: Dp): Modifier
    fun Modifier.padding(paddingValues: PaddingValues): Modifier

    Modifier.padding(start = 20.dp, end = 20.dp, vertical = 14.dp)

Kotlin could not pick an overload because the call mixes the axis shorthand with the
per-side form. Neither half is wrong on its own; only the combination is, which is why
reading the line does not reveal it.

The check is purely name-based: for `padding` / `absolutePadding`, the named arguments
must fit inside one of the declared overloads, and there is no two-positional overload.
It is intentionally narrow - it only claims to know the four signatures above - because a
checker that guesses is worse than no checker.

Usage:
    python3 tools/check_compose_modifiers.py           # exit 1 on any finding
    python3 tools/check_compose_modifiers.py --list    # also print every call checked
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src"

#: Each accepted overload is the set of named parameters it declares.
OVERLOADS: dict[str, list[set[str]]] = {
    "padding": [
        {"start", "top", "end", "bottom"},
        {"horizontal", "vertical"},
        {"all"},
        {"paddingValues"},
    ],
    "absolutePadding": [
        {"left", "top", "right", "bottom"},
        {"horizontal", "vertical"},
        {"all"},
        {"paddingValues"},
    ],
}

#: Names that mean "the axis shorthand", used only to explain a failure.
AXIS = {"horizontal", "vertical"}
SIDES = {"start", "end", "left", "right", "top", "bottom", "all", "paddingValues"}


def rel(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(ROOT))
    except ValueError:
        return str(path)


def split_arguments(text: str) -> list[str]:
    """Split an argument list on top-level commas.

    Tracks parens, brackets, braces and string literals so a comma inside
    `padding(start = f(a, b).dp)` or `"a, b"` does not split the list.
    """
    parts: list[str] = []
    depth = 0
    current: list[str] = []
    index = 0
    length = len(text)

    while index < length:
        char = text[index]

        if char == '"':
            # Copy the whole string literal, honouring backslash escapes.
            current.append(char)
            index += 1
            while index < length:
                current.append(text[index])
                if text[index] == "\\" and index + 1 < length:
                    index += 1
                    current.append(text[index])
                elif text[index] == '"':
                    break
                index += 1
        elif char in "([{":
            depth += 1
            current.append(char)
        elif char in ")]}":
            depth -= 1
            current.append(char)
        elif char == "," and depth == 0:
            parts.append("".join(current).strip())
            current = []
        else:
            current.append(char)
        index += 1

    tail = "".join(current).strip()
    if tail:
        parts.append(tail)
    return parts


def argument_names(arguments: list[str]) -> tuple[set[str], int, bool]:
    """Return (named argument names, positional count, whether a spread is present)."""
    names: set[str] = set()
    positional = 0
    spread = False
    for argument in arguments:
        if argument.startswith("*"):
            spread = True
            continue
        head = argument.split("=", 1)
        if len(head) == 2 and head[0].strip().isidentifier():
            names.add(head[0].strip())
        else:
            positional += 1
    return names, positional, spread


def find_calls(source: str) -> list[tuple[int, str, int, int]]:
    """Yield (line, call name, open paren index, close paren index) for known calls."""
    import re

    found = []
    for match in re.finditer(r"\.(padding|absolutePadding)\s*\(", source):
        open_paren = match.end() - 1
        depth = 0
        close_paren = -1
        index = open_paren
        while index < len(source):
            char = source[index]
            if char == '"':
                index += 1
                while index < len(source):
                    if source[index] == "\\":
                        index += 1
                    elif source[index] == '"':
                        break
                    index += 1
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    close_paren = index
                    break
            index += 1
        if close_paren > 0:
            line = source[: match.start()].count("\n") + 1
            found.append((line, match.group(1), open_paren, close_paren))
    return found


def check_file(path: Path) -> tuple[list[str], int]:
    source = path.read_text(encoding="utf-8")
    problems: list[str] = []
    checked = 0

    for line, name, open_paren, close_paren in find_calls(source):
        raw = source[open_paren + 1: close_paren]
        arguments = split_arguments(raw)
        if not arguments:
            continue  # `.padding()` with no args is a different error Kotlin names clearly
        checked += 1

        names, positional, spread = argument_names(arguments)
        if spread:
            continue

        overloads = OVERLOADS[name]
        # A single positional argument is legal - it binds to `all` or `paddingValues` -
        # but it cannot be combined with named arguments, and two positionals match
        # nothing.
        if positional == 0:
            fits = any(names <= overload for overload in overloads)
        else:
            fits = positional == 1 and not names

        if not fits:
            detail = " ".join(raw.split())
            hint = ""
            if (names & AXIS) and (names & SIDES):
                hint = " (cannot mix `horizontal`/`vertical` with `start`/`end`/`top`/`bottom`)"
            elif positional > 1:
                hint = f" ({positional} positional arguments; no overload takes more than one)"
            elif positional == 1 and names:
                hint = " (a positional argument cannot be combined with named ones)"
            problems.append(f"{rel(path)}:{line}: .{name}({detail}){hint}")

    return problems, checked


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--list", action="store_true", help="print every call that was checked")
    args = parser.parse_args()

    all_problems: list[str] = []
    total = 0
    for path in sorted(SRC.rglob("*.kt")):
        problems, checked = check_file(path)
        total += checked
        all_problems.extend(problems)

    if all_problems:
        print(f"INVALID MODIFIER CALLS ({len(all_problems)}):\n")
        for problem in all_problems:
            print("  " + problem)
        print(
            "\nEach of these matches no `Modifier` overload, so `compileDebugKotlin` will "
            "fail with \"None of the following candidates is applicable\"."
        )
        return 1

    if args.list:
        print(f"checked {total} padding call(s); none mix overload forms")
    else:
        print(f"OK: {total} padding call(s) match a real Modifier overload.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
