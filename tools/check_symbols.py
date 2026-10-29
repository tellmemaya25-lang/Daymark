#!/usr/bin/env python3
"""A crude "did you forget an import?" pass for Kotlin files the compiler cannot reach.

`typecheck.py` drops every file that needs androidx, which is where all of the UI lives, so a
missing import in a Compose file is invisible until a device build. This is not a type
checker - it collects capitalised identifiers that look like a type or a top-level function
call and reports the ones that are neither imported, declared locally, defined elsewhere in
the same package, nor in the standard implicit-import set.

It deliberately errs towards silence: `android.R.*` members, enum entries and Java's implicit
imports are all excluded, since a false alarm here costs more than a missed one.

    python3 tools/check_symbols.py                # every file under app/src/main/java
    python3 tools/check_symbols.py ui/home        # only paths containing that substring
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java"

# Types available without an import: kotlin.*, java.lang.*, and the Kotlin compiler's own.
IMPLICIT = set("""
String Int Long Boolean Float Double Byte Short Char Unit Any Nothing List MutableList Set
MutableSet Map MutableMap Array Pair Triple Exception RuntimeException IllegalArgumentException
IllegalStateException Throwable Number Comparable Iterable Sequence Collection CharSequence
StringBuilder Thread Runnable System Object Class Suppress Deprecated OptIn Target Retention
Override Volatile Synchronized Transient JvmStatic JvmField JvmOverloads JvmName Kotlin Result
Error TODO NotImplementedError ArithmeticException IndexOutOfBoundsException
NoSuchElementException UnsupportedOperationException NullPointerException SecurityException
ClassCastException InterruptedException IllegalAccessException InstantComparator Math Regex
RegexOption Charsets ArrayList IntArray BooleanArray ByteArray ShortArray FloatArray
LongArray DoubleArray ArrayDeque LinkedHashMap LinkedHashSet HashMap HashSet Comparator
AnnotationRetention
RetentionPolicy Random UUID Date Calendar Locale TimeUnit CompletableFuture
""".split())

DECL = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:(?:public|internal|private|protected|sealed|abstract|open|data|enum|annotation|"
    r"value|inline|suspend|operator|override|companion|inner|fun|const|lateinit)\s+)*"
    r"(?:class|interface|object|typealias|fun|val|var)\s+"
    r"(?:<[^>]*>\s*)?(?:[\w.]+\.)?([A-Za-z_][A-Za-z0-9_]*)",
    re.M,
)


def declarations(text: str) -> set[str]:
    names = set(DECL.findall(text))
    # Members declared inside a class body are matched too, which only ever makes this
    # check quieter - the failure mode we care about is "declared nowhere at all".
    names |= set(re.findall(r"\b(?:class|interface|object)\s+([A-Z][A-Za-z0-9_]*)", text))
    return names


def strip_noise(text: str) -> str:
    # Interpolations first: `"${if (x) "A" else "B"}"` has quotes inside quotes, which would
    # otherwise leave the inner strings looking like code.
    text = re.sub(r"\$\{[^{}]*\}", "X", text)
    text = re.sub(r'"""(?:.|\n)*?"""', '""', text)
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r"/\*(?:.|\n)*?\*/", "", text)
    return text


def main(argv):
    filters = [a for a in argv[1:] if not a.startswith("-")]
    files = sorted(SRC.rglob("*.kt"))

    by_package: dict[str, set[str]] = {}
    for f in files:
        text = f.read_text(encoding="utf-8", errors="replace")
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        by_package.setdefault(pkg.group(1) if pkg else "", set()).update(declarations(text))

    problems = 0
    checked = 0
    for f in files:
        if filters and not any(flt in str(f) for flt in filters):
            continue
        checked += 1
        text = f.read_text(encoding="utf-8", errors="replace")
        pkg_match = re.search(r"^package\s+([\w.]+)", text, re.M)
        pkg = pkg_match.group(1) if pkg_match else ""

        imported = set()
        for path, alias in re.findall(
            r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?", text, re.M
        ):
            imported.add(alias or path.split(".")[-1])

        known = IMPLICIT | imported | declarations(text) | by_package.get(pkg, set())

        body = strip_noise(text)
        used = set(
            re.findall(
                r"(?<![\w.])([A-Z][A-Za-z0-9_]*)\b(?=\s*[.(:,)<\"\[{]|\s*$)", body, re.M
            )
        )
        # Scope members (Compose DSL receivers such as ExposedDropdownMenuBoxScope) are not
        # types and never appear in an import list; the compiler resolves them from the
        # enclosing lambda.
        scope_members = {"ExposedDropdownMenu"}
        unknown = sorted(
            u for u in used if u not in known and not u.isupper() and u not in scope_members
        )
        if unknown:
            problems += 1
            print(f"{f.relative_to(ROOT)}: {unknown}")

    print(f"checked {checked} file(s), {problems} with suspects")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
