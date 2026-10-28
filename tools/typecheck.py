#!/usr/bin/env python3
"""Type-check as much of the app as possible without the Android SDK.

`gradlew` cannot run in the sandbox that maintains this repo (services.gradle.org,
Google Maven and Maven Central are all blocked), which is how three separate
"String? into a String" and one unclosed-comment defect reached `main` unseen.

The two registries that *are* reachable carry everything needed for a real compiler:

    pip install jdk4py                     # a JDK
    npm pack kotlin-compiler@2.4.21        # kotlinc

This script bootstraps those into a cache dir, generates tiny stubs for the handful of
library types the pure layers touch (javax.inject, kotlinx.coroutines.flow, Firebase
Timestamp + annotations), and then type-checks the app iteratively:

  1. start with every `app/src/main` source
  2. compile; for each "unresolved reference X" where X is neither defined by the active
     files nor by a stub, that file depends on a library we do not have (androidx,
     firebase SDK, hilt) - drop it
  3. repeat until no more files can be dropped

Whatever is left must actually compile. Errors reported at that point are real.

Usage:
    python3 tools/typecheck.py            # bootstrap if needed, then check
    python3 tools/typecheck.py --list     # also print the files that survived
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = Path(os.environ.get("DAYMARK_TOOLCHAIN", "/tmp/daymark-toolchain"))
KOTLIN_VERSION = "2.4.21"
SRC = ROOT / "app/src/main"

STUBS = {
    "Inject.kt": """package javax.inject
@Target(AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION,
        AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Inject
annotation class Singleton
""",
    "Flow.kt": """package kotlinx.coroutines.flow
interface Flow<out T>
interface StateFlow<out T> : Flow<T>
""",
    "Firebase.kt": """package com.google.firebase
import java.util.Date
class Timestamp(val date: Date) {
    constructor(seconds: Long, nanoseconds: Int) : this(Date(seconds * 1000))
    fun toDate(): Date = date
    fun toMillis(): Long = date.time
}
""",
    "Firestore.kt": """package com.google.firebase.firestore
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class ServerTimestamp

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class PropertyName(val value: String)

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class DocumentId
""",
}

DEFINITION = re.compile(r"\b(?:class|object|interface|fun|val|var|enum class|typealias)\s+([A-Za-z_]\w*)")
ERROR = re.compile(r"^(?P<file>[^:]+):(?P<line>\d+):(?P<col>\d+): error: (?P<msg>.*)$")
UNRESOLVED = re.compile(r"unresolved reference '([A-Za-z_]\w*)'")


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def bootstrap() -> tuple[str, Path]:
    """Make a JDK and kotlinc available, returning (JAVA_HOME, kotlinc dir)."""
    CACHE.mkdir(parents=True, exist_ok=True)

    try:
        import jdk4py  # type: ignore
    except ImportError:
        print("bootstrapping JDK from PyPI (jdk4py) ...")
        run([sys.executable, "-m", "pip", "install", "--quiet", "--break-system-packages", "jdk4py"])
        import jdk4py  # type: ignore
    java_home = str(jdk4py.JAVA_HOME)

    kotlinc_home = CACHE / f"kotlin-compiler-{KOTLIN_VERSION}"
    if not (kotlinc_home / "bin" / "kotlinc").exists():
        print(f"bootstrapping kotlinc {KOTLIN_VERSION} from npm ...")
        tgz = run(["npm", "pack", f"kotlin-compiler@{KOTLIN_VERSION}", "--silent"], cwd=CACHE)
        tarball = CACHE / (tgz.stdout.strip() or f"kotlin-compiler-{KOTLIN_VERSION}.tgz")
        if not tarball.exists():
            sys.exit(f"could not download kotlin-compiler ({tgz.stderr.strip()[:200]})")
        run(["tar", "xzf", tarball.name], cwd=CACHE)
        (CACHE / "package").rename(kotlinc_home)
        (kotlinc_home / "bin" / "kotlinc").chmod(0o755)
    return java_home, kotlinc_home


def build_stubs(java_home: str, kotlinc_home: Path) -> Path:
    stub_dir = CACHE / "stubs"
    stub_dir.mkdir(exist_ok=True)
    sources = []
    for name, body in STUBS.items():
        p = stub_dir / name
        if not p.exists() or p.read_text() != body:
            p.write_text(body)
        sources.append(str(p))

    jar = stub_dir / "stubs.jar"
    if not jar.exists() or jar.stat().st_mtime < max(Path(s).stat().st_mtime for s in sources):
        env = {**os.environ, "JAVA_HOME": java_home, "PATH": f"{java_home}/bin:" + os.environ.get("PATH", "")}
        r = run([str(kotlinc_home / "bin" / "kotlinc"), *sources, "-d", str(jar), "-nowarn"], env=env)
        if not jar.exists():
            sys.exit(f"stub compilation failed:\n{(r.stdout + r.stderr)[:1500]}")
    return jar


def names_in(paths: list[Path]) -> set[str]:
    found: set[str] = set()
    for p in paths:
        found |= set(DEFINITION.findall(p.read_text(encoding="utf-8")))
    return found


def rel(path: Path) -> str:
    """kotlinc echoes paths relative to the working directory; normalise for display."""
    try:
        return str(path.resolve().relative_to(ROOT))
    except ValueError:
        return str(path)


def compile_files(java_home: str, kotlinc_home: Path, files: list[Path], stubs: Path):
    argfile = CACHE / "sources.txt"
    argfile.write_text("\n".join(str(f) for f in files))
    env = {**os.environ, "JAVA_HOME": java_home, "PATH": f"{java_home}/bin:" + os.environ.get("PATH", "")}
    r = run([str(kotlinc_home / "bin" / "kotlinc"), f"@{argfile}", "-cp", str(stubs),
             "-d", str(CACHE / "out"), "-nowarn"], env=env)
    return (r.stdout + r.stderr).splitlines()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", action="store_true", help="print the surviving files")
    args = ap.parse_args()

    java_home, kotlinc_home = bootstrap()
    stubs = build_stubs(java_home, kotlinc_home)

    active = sorted(SRC.rglob("*.kt"))
    print(f"starting from {len(active)} source files")

    for round_no in range(1, 30):
        lines = compile_files(java_home, kotlinc_home, active, stubs)
        errors = [m.groupdict() | {"msg": m.group("msg")} for m in map(ERROR.match, lines) if m]
        if not errors:
            print(f"\nTYPE-CHECK PASSED: {len(active)} files compile against the stubs.")
            if args.list:
                for f in active:
                    print("  " + rel(f))
            return 0

        defined = names_in(active)
        droppable: set[Path] = set()
        real: list[str] = []
        for e in errors:
            file = (ROOT / e["file"]).resolve() if not Path(e["file"]).is_absolute() else Path(e["file"]).resolve()
            m = UNRESOLVED.search(e["msg"])
            if m and m.group(1) not in defined:
                droppable.add(file)
            else:
                real.append(f"{rel(file)}:{e['line']}:{e['col']}: {e['msg']}")

        droppable &= set(active)
        if not droppable:
            print(f"\nREAL ERRORS ({len(real)}) - these are not missing-dependency noise:\n")
            for line in real[:40]:
                print("  " + line)
            return 1

        active = [f for f in active if f not in droppable]
        print(f"  round {round_no}: {len(errors)} unresolved, dropped {len(droppable)} file(s) needing absent libraries")

    print("did not converge", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
