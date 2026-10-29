#!/usr/bin/env python3
"""Checks the Android resource graph without the Android SDK.

Two failures this catches, both of which are invisible in a code review:

 1. **Duplicate resource names.** `aapt2` refuses the whole build with
        Found item String/reminder more than one time
    The duplicate is usually an addition that nobody noticed already existed two
    hundred lines up in the same file — which is exactly how `reminder` broke a
    real `installDebug` after the reminders work landed.

 2. **Dangling references.** `R.string.foo` / `@string/foo` naming something that
    was renamed or never added. Kotlin cannot see `R` here (no SDK), and Gradle
    only says so after a full configure + resource merge.

Resource types come from two places:
  * `res/values*/*.xml` declarations — `<string>`, `<color>`, `<dimen>`, `<style>`,
    `<bool>`, `<integer>`, `<plurals>`, `<array>`, `<string-array>`, `<attr>`, and
    `<item type="..." name="...">` in files such as `ids.xml`;
  * file-based resources — `res/drawable*/ic_x.xml` declares `R.drawable.ic_x`.

Two files may declare the same name in *different* buckets (`values` vs
`values-night`); that is the point of a qualifier. They may not declare it in the
same one.

Exit code is 1 on any problem, so it can gate a push.

    python3 tools/check_resources.py            # checks app/src/main/res
    python3 tools/check_resources.py path/to/res
    python3 tools/check_resources.py --list     # print what it counted
"""

from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_RES = ROOT / "app/src/main/res"
SOURCE_ROOTS = [ROOT / "app/src/main/java", ROOT / "app/src/main/kotlin"]

# Tag -> R type. `string-array`/`integer-array` are both `R.array`.
TAG_TYPES = {
    "string": "string",
    "color": "color",
    "dimen": "dimen",
    "style": "style",
    "bool": "bool",
    "integer": "integer",
    "plurals": "plurals",
    "array": "array",
    "string-array": "array",
    "integer-array": "array",
    "fraction": "fraction",
    "attr": "attr",
}

# Directory prefix -> R type, for resources that are one file each.
FILE_DIRS = {
    "drawable": "drawable",
    "mipmap": "mipmap",
    "layout": "layout",
    "xml": "xml",
    "raw": "raw",
    "font": "font",
    "anim": "anim",
    "animator": "animator",
    "menu": "menu",
    "navigation": "navigation",
    "transition": "transition",
    "color": "color",
}

DECL_TAG_RE = re.compile(r"<(string|color|dimen|style|bool|integer|plurals|string-array|"
                         r"integer-array|array|fraction|attr)\s+[^>]*?name=\"([^\"]+)\"")
ITEM_TYPE_RE = re.compile(r"<item\s+[^>]*?type=\"([a-z-]+)\"[^>]*?name=\"([^\"]+)\"")
PLUS_ID_RE = re.compile(r"@\+id/([A-Za-z0-9_]+)")

# `(?<![\w.])` so `android.R.drawable.x` is Android's resource, not ours.
KOTLIN_REF_RE = re.compile(
    r"(?<![\w.])R\.(string|color|dimen|style|bool|integer|plurals|array|drawable|mipmap|"
    r"layout|id|font|anim|animator|menu|navigation|raw|xml)\.([A-Za-z_][A-Za-z0-9_]*)"
)

XML_REF_RE = re.compile(
    r"@(string|color|dimen|style|bool|integer|plurals|array|drawable|mipmap|layout|"
    r"id|font|anim|animator|menu|navigation|raw|xml|attr|fraction)/([A-Za-z0-9_.]+)"
)


def bucket_of(directory_name: str) -> str:
    """`values-night` and `values` are different buckets; `values` and `values` are one."""
    if directory_name == "values":
        return "values"
    if directory_name.startswith("values-"):
        return directory_name
    # File-based dirs are bucketed by qualifier too, e.g. drawable-hdpi.
    base, _, qualifier = directory_name.partition("-")
    return f"{base}-{qualifier}" if qualifier else base


def collect(res_dir: Path):
    """Returns (declared, ids, duplicates, counts) for one res/ directory."""
    declared = defaultdict(set)
    # (bucket, type, name) -> list of files, one entry per *declaration*, so two
    # declarations in the same file are seen as two - a set of files would hide them.
    declarations = defaultdict(list)
    ids = set()
    duplicates = []

    for path in sorted(res_dir.rglob("*")):
        if not path.is_file():
            continue
        bucket = bucket_of(path.parent.name)
        text = ""

        if path.parent.name.startswith("values") and path.suffix == ".xml":
            text = path.read_text(encoding="utf-8", errors="replace")
            for tag, name in DECL_TAG_RE.findall(text):
                rtype = TAG_TYPES[tag]
                declared[rtype].add(name)
                declarations[(bucket, rtype, name)].append(path)
            for itype, name in ITEM_TYPE_RE.findall(text):
                rtype = TAG_TYPES.get(itype, itype)
                if rtype not in {"id", "string", "color", "dimen", "style", "bool",
                                 "integer", "plurals", "array", "fraction", "attr"}:
                    continue
                declared[rtype].add(name)
                declarations[(bucket, rtype, name)].append(path)

        # Ids are declared inline in any XML, and `@+id/` is the declaration.
        if path.suffix == ".xml":
            if not text:
                text = path.read_text(encoding="utf-8", errors="replace")
            ids.update(PLUS_ID_RE.findall(text))

        # File-based resources: res/drawable-hdpi/ic_launcher.png -> R.drawable.ic_launcher
        for prefix, rtype in FILE_DIRS.items():
            if path.parent.name == prefix or path.parent.name.startswith(prefix + "-"):
                name = path.stem if path.suffix == ".xml" or rtype != "raw" else path.name
                declared[rtype].add(name)

    # A name may only be declared once per (bucket, type). Two declarations in the same
    # bucket - same file or two files in the same qualifier folder - is what aapt2 rejects.
    for (bucket, rtype, name), files in sorted(declarations.items()):
        if len(files) > 1:
            unique = sorted({str(f.relative_to(ROOT)) for f in files})
            where = unique[0] if len(unique) == 1 else ", ".join(unique)
            duplicates.append(f"{rtype}/{name} declared {len(files)}x in {bucket} ({where})")

    declared["id"] |= ids
    return declared, duplicates


def check_references(declared):
    """Returns a list of (location, message) for references that resolve to nothing."""
    problems = []

    for root in SOURCE_ROOTS:
        if not root.exists():
            continue
        for path in sorted(root.rglob("*.kt")):
            text = path.read_text(encoding="utf-8", errors="replace")
            uses_app_r = ("com.sabihon.todo.R" in text) or re.search(
                r"(?<![\w.])R\.", text
            )
            if "import android.R" in text:
                # Bare `R.` in this file means Android's resources, not ours.
                continue
            if not uses_app_r:
                continue
            for line_no, line in enumerate(text.splitlines(), 1):
                for rtype, name in KOTLIN_REF_RE.findall(line):
                    if name not in declared.get(rtype, set()):
                        where = f"{path.relative_to(ROOT)}:{line_no}"
                        problems.append((where, f"R.{rtype}.{name} does not exist"))

    # The manifest lives outside res/ but references themes, drawables and strings, and a
    # typo there fails the build just as hard.
    xml_files = sorted(DEFAULT_RES.rglob("*.xml"))
    manifest = ROOT / "app/src/main/AndroidManifest.xml"
    if manifest.exists():
        xml_files.append(manifest)

    for path in xml_files:
        text = path.read_text(encoding="utf-8", errors="replace")
        for line_no, line in enumerate(text.splitlines(), 1):
            for rtype, name in XML_REF_RE.findall(line):
                # `@style/Theme.Sabihon` is declared as name="Theme.Sabihon".
                if name in declared.get(rtype, set()):
                    continue
                # A style can inherit `@style/Parent`; that is still a declaration.
                where = f"{path.relative_to(ROOT)}:{line_no}"
                problems.append((where, f"@{rtype}/{name} does not exist"))

    return problems


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("-")]
    list_only = "--list" in argv
    res_dir = Path(args[0]).resolve() if args else DEFAULT_RES

    if not res_dir.exists():
        print(f"no resource directory at {res_dir}")
        return 1

    declared, duplicates = collect(res_dir)

    if list_only:
        total = sum(len(v) for v in declared.values())
        print(f"declared in {res_dir}:")
        for rtype in sorted(declared):
            print(f"  {rtype:<12} {len(declared[rtype])}")
        print(f"  {'total':<12} {total}")
        print(f"  {'+ids':<12} {len(declared.get('id', set()))}")
        return 0

    problems = check_references(declared)

    if duplicates:
        print("DUPLICATE RESOURCES (.aapt2 refuses these):")
        for message in duplicates:
            print(f"  {message}")
        print()

    if problems:
        print("MISSING RESOURCES:")
        for where, message in problems:
            print(f"  {where}: {message}")
        print()

    if duplicates or problems:
        print(f"FAILED: {len(duplicates)} duplicate(s), {len(problems)} dangling reference(s).")
        return 1

    total = sum(len(v) for v in declared.values())
    print(f"OK: {total} resources, no duplicates, every reference resolves.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
