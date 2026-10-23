#!/usr/bin/env python3
"""Catch missing-import compile errors without a Gradle build.

Every so often an edit here lands a symbol that is used but never imported
(`Modifier.clip`, `Icons.Filled.Edit`, `Arrangement`) and the only way to find
out is a red build. This walks the Kotlin sources and reports references to a
curated set of Compose/AndroidX symbols that have no matching import.

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
REQUIRED = [
    # layout / drawing
    "Arrangement", "Alignment", "RoundedCornerShape", "CircleShape",
    "WindowInsets", "fontScale",
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
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', text)


def check_file(path: Path) -> list[str]:
    source = path.read_text(encoding="utf-8")
    imports = set(re.findall(r"^import\s+([\w.]+)", source, re.M))
    body = strip_comments_and_strings(re.sub(r"^import .*$", "", source, flags=re.M))
    declared = set(re.findall(r"\b(?:fun|val|var|class|object|interface)\s+(\w+)", body))

    problems: list[str] = []

    def imported(name: str) -> bool:
        return any(spec.endswith("." + name) for spec in imports)

    # Icons.Filled.X / Icons.AutoMirrored.Filled.X
    for name in sorted(set(re.findall(r"Icons\.(?:AutoMirrored\.)?Filled\.(\w+)", body))):
        if not imported(name):
            problems.append(f"Icons.Filled.{name} is used but not imported")

    for name in REQUIRED:
        if name in declared or imported(name):
            continue
        # Skip fully-qualified use (androidx.compose.material3.AlertDialog) – that
        # resolves without an import.
        if re.search(r"(?<!\.)\b" + re.escape(name) + r"\b", body):
            problems.append(f"{name} is used but not imported")

    return problems


def main() -> int:
    if not SOURCE_ROOT.exists():
        print(f"source root not found: {SOURCE_ROOT}", file=sys.stderr)
        return 2

    findings = 0
    for path in sorted(SOURCE_ROOT.rglob("*.kt")):
        for problem in check_file(path):
            rel = path.relative_to(ROOT)
            print(f"{rel}: {problem}")
            findings += 1

    if findings:
        print(f"\n{findings} missing import(s).")
        return 1

    print("No missing imports found.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
