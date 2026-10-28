#!/usr/bin/env python3
"""Catch two kinds of compile error that need no Gradle build to spot.

1. A symbol that is used but never imported (`Modifier.clip`, `Icons.Filled.Edit`,
   `Arrangement`). A curated Compose/AndroidX list is checked.
2. A `StateFlow.update { }` lambda whose last statement is an assignment rather
   than the new state. `update` requires `(T) -> T`, and an assignment returns
   Unit, so this is a hard error:

       _uiState.update {
           it.copy(...)
           originalTask = task      // <- lambda now returns Unit
       }

Usage:
    python3 tools/check_imports.py            # exit 1 on findings
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE_ROOT = ROOT / "app" / "src" / "main" / "java"

# Symbols that always need an explicit import when they appear in code.
# Scope members (RowScope.weight, Arrangement.spacedBy, Modifier.offset …) are
# deliberately absent — they resolve off the receiver, not an import.
# Members that genuinely hang off the Modifier / layout-scope receiver and need no import.
# NOTE: Foundation layout extensions (width, height, size, padding, fillMax*, offset,
# wrapContent*, clipToBounds) DO require explicit `androidx.compose.foundation.layout.*`
# / `androidx.compose.ui.draw.*` imports and must NOT be listed here.
MODIFIER_SCOPE_MEMBERS = {
    "menuAnchor",      # ExposedDropdownMenuBoxScope
    "weight",          # RowScope / ColumnScope
    "align",           # BoxScope / RowScope / ColumnScope
    "matchParentSize", # BoxScope
    "then",            # Modifier interface member
}

REQUIRED = [
    # layout / drawing
    "Arrangement", "Alignment", "RoundedCornerShape", "CircleShape",
    "WindowInsets", "fontScale",
    # Modifier extensions that resolve only via an import, and whose names are not
    # also common parameter names. "clip" and "border" have each caused a red build
    # once; "alpha"/"offset"/"padding" are deliberately absent because they appear as
    # arguments (`copy(alpha = 0.6f)`) and would cry wolf.
    "clip", "graphicsLayer",
    # text / graphics
    "Color", "FontWeight", "TextOverflow", "TextAlign", "TextDecoration",
    "ContentScale", "ImageVector", "Stroke", "StrokeCap",
    # runtime
    "combinedClickable", "ExperimentalFoundationApi", "ExperimentalMaterial3Api",
    "ExperimentalLayoutApi", "selectable", "selectableGroup", "semantics",
    # material3
    "ModalBottomSheet", "rememberModalBottomSheetState", "PullToRefreshBox",
    "OutlinedTextFieldDefaults", "SwitchDefaults", "ButtonDefaults",
    "SnackbarDuration", "SnackbarResult", "rememberDatePickerState",
    "rememberTimePickerState", "AlertDialog", "HorizontalDivider",
    # pager / navigation
    "HorizontalPager", "rememberPagerState", "BackHandler",
    # haptics / platform
    "LocalHapticFeedback", "HapticFeedbackType", "LocalDensity",
]


def strip_comments_and_strings(text: str) -> str:
    """Blank out comments and string literals in one pass, preserving everything else.

    This was three regexes, and it was wrong: `"image/*"` contains a block-comment
    opener, so the comment pass deleted everything from that string to the next `*/`
    - silently swallowing real code and hiding exactly the kind of missing import this
    script exists to find. A single left-to-right scan cannot make that mistake.
    """
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            if text.startswith('"""', i):
                end = text.find('"""', i + 3)
                i = n if end == -1 else end + 3
            else:
                j = i + 1
                while j < n:
                    if text[j] == "\\":
                        j += 2
                        continue
                    if text[j] == '"':
                        j += 1
                        break
                    j += 1
                i = j
            out.append('""')
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            end = text.find("*/", i + 2)
            i = n if end == -1 else end + 2
            out.append(" ")
        elif c == "/" and i + 1 < n and text[i + 1] == "/":
            end = text.find("\n", i)
            i = n if end == -1 else end
            out.append(" ")
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_file(path: Path) -> list[str]:
    source = path.read_text(encoding="utf-8")
    imports = set(re.findall(r"^import\s+([\w.]+)", source, re.M))
    body = strip_comments_and_strings(re.sub(r"^import .*$", "", source, flags=re.M))
    declared = set(re.findall(r"\b(?:fun|val|var|class|object|interface)\s+(\w+)", body))
    # Extension functions: `fun Modifier.entrance(...)` declares "entrance", not
    # "Modifier", and resolves without an import.
    declared |= set(re.findall(r"\bfun\s+[\w.<>]+\.(\w+)\s*\(", body))

    problems: list[str] = []

    def imported(name: str) -> bool:
        return any(spec.endswith("." + name) for spec in imports)

    # Icons.Filled.X / Icons.AutoMirrored.Filled.X
    for name in sorted(set(re.findall(r"Icons\.(?:AutoMirrored\.)?Filled\.(\w+)", body))):
        if not imported(name):
            problems.append(f"Icons.Filled.{name} is used but not imported")

    # `Modifier.someExtension(` (including chained `.ext(` calls) is unambiguous:
    # these always need an import unless they are receiver/scope members.
    modifier_calls: set[str] = set()
    for chain_match in re.finditer(r"\b[Mm]odifier(?:\s*\.\s*\w+\s*\([^()]*\))+", body):
        modifier_calls.update(re.findall(r"\.\s*(\w+)\s*\(", chain_match.group(0)))
    modifier_calls.update(re.findall(r"Modifier\.(\w+)\s*\(", body))

    for name in sorted(modifier_calls):
        if name in MODIFIER_SCOPE_MEMBERS or imported(name):
            continue
        declared_here = name in declared
        if not declared_here:
            problems.append(f"Modifier.{name}(...) is used but not imported")

    for name in REQUIRED:
        if name in declared or imported(name):
            continue
        # Skip fully-qualified use (androidx.compose.material3.AlertDialog) – that
        # resolves without an import.
        if re.search(r"(?<!\.)\b" + re.escape(name) + r"\b", body):
            problems.append(f"{name} is used but not imported")

    return problems


def check_update_lambdas(path: Path) -> list[str]:
    """Reports `update { }` lambdas whose last statement returns Unit."""
    source = path.read_text(encoding="utf-8")
    body = strip_comments_and_strings(source)
    problems: list[str] = []

    for match in re.finditer(r"\.update\s*\{", body):
        start = match.end() - 1
        depth = 0
        end = start
        while end < len(body):
            if body[end] == "{":
                depth += 1
            elif body[end] == "}":
                depth -= 1
                if depth == 0:
                    break
            end += 1

        lines = [line.strip() for line in body[start + 1:end].splitlines() if line.strip()]
        if not lines:
            continue
        last = lines[-1]
        # A bare assignment (not ==, <=, >=, +=) as the final statement.
        if re.match(r"^[A-Za-z_][\w.]*\s*=(?!=)", last):
            problems.append(f"update {{ }} lambda ends with an assignment: {last!r}")

    return problems


def main() -> int:
    if not SOURCE_ROOT.exists():
        print(f"source root not found: {SOURCE_ROOT}", file=sys.stderr)
        return 2

    findings = 0
    for path in sorted(SOURCE_ROOT.rglob("*.kt")):
        rel = path.relative_to(ROOT)
        for problem in check_file(path) + check_update_lambdas(path):
            print(f"{rel}: {problem}")
            findings += 1

    if findings:
        print(f"\n{findings} problem(s).")
        return 1

    print("No missing imports, no update-lambda return errors.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
