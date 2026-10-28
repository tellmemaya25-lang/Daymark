# tools/

Verification helpers. None of them need the Android SDK, and both run in a few seconds.

## `check_kotlin_syntax.py`

Lexes every `.kt` file the way the Kotlin compiler does and reports what a brace counter
misses:

- **block comments nest**, so a single literal `/*` inside a KDoc silently comments out
  the rest of the file (`InputSecurityValidator` lost 120 lines to exactly that, and the
  compiler reported it as ~80 unrelated "Unresolved reference" errors)
- raw strings win over comments, and backtick identifiers win over quotes

```bash
python3 tools/check_kotlin_syntax.py          # defaults to app/src
python3 tools/check_kotlin_syntax.py path/to/src
```

## `check_imports.py`

Checks that every symbol used in an `update { }` lambda is imported, and that no
`return@` label dangles.

## `check_write_parity.py`

`TaskRepositoryImpl` writes a task twice — normally as a POJO through `Task.toDto()`, and
as a flattened map through `minimalTaskMap()` when Firestore rejects POJO serialisation.
Those two field lists live in different files and are maintained by hand, so a field added
to one and missed in the other only misbehaves when a particular write happens to fall
back.

```bash
python3 tools/check_write_parity.py
# OK: both task write paths carry all 8 timeline-critical fields.
```

It checks a deliberately small set (the timing/identity fields the Home timeline reads:
`title`, `description`, `dueAt`, `reminderAt`, `durationMinutes`, `isCompleted`,
`isDeleted`, `subTasks`). `minimalTaskMap` is *meant* to be a subset — it drops `repeat`,
`streak` and `seriesId` — so a full diff would be all noise.

## `typecheck.py` — the one that catches real compile errors

```bash
python3 tools/typecheck.py          # add --list to see which files survived
```

Bootstraps a JDK (`jdk4py`, PyPI) and `kotlinc` (`kotlin-compiler`, npm) into
`/tmp/daymark-toolchain`, generates stubs for the few library types the pure layers touch
(`javax.inject`, `kotlinx.coroutines.flow`, Firebase `Timestamp` + annotations), then
type-checks the app iteratively: files whose unresolved symbols come from absent
libraries (androidx, firebase SDK, hilt) are dropped, and whatever is left must compile.

That currently covers **50 files** — the validator, every domain model and DTO, the
mappers, the admin policy/export logic and the AI guards. It is the check that catches
the class of bug a syntax scan cannot see:

```
app/src/main/java/com/sabihon/todo/data/remote/dto/Mappers.kt:61:23: error: argument type
mismatch: actual type is 'String?', but 'String' was expected.
```

Verified by reintroducing that exact defect: the tool reports it at the same line and
column Gradle does, and passes once fixed. Run it before pushing anything that touches
domain/, data/remote/dto/ or data/ai/.

---

## `junit_shim/` — running the real unit tests without JUnit

Maven Central is unreachable from the agent sandbox, so `./gradlew test` cannot resolve
JUnit there. `junit_shim/` closes that gap without touching the tests:

| File | What it is |
|---|---|
| `Test.kt` | `org.junit.Test`, retained at runtime so it can be reflected over |
| `Assert.kt` | `org.junit.Assert` with the eight assertions the suites use: `assertEquals`, `assertTrue`, `assertFalse`, `assertNull`, `assertNotNull`, `fail` |
| `Runner.kt` | `sandbox.Runner` — finds every `@Test` method on the class names given on the command line, runs them, prints PASS/FAIL per test and exits 1 on any failure |

Nothing in `build.gradle` references this directory, so Gradle still uses genuine JUnit and
the two never meet. On a normal machine, run `./gradlew test`; the shim exists only so the
same test files can be executed where Gradle cannot fetch dependencies.

```bash
export JAVA_HOME=$(python3 -c "import jdk4py; print(jdk4py.JAVA_HOME)")
KC=/tmp/daymark-toolchain/kotlin-compiler-2.4.21/bin/kotlinc   # from typecheck.py

$KC -nowarn -cp /tmp/daymark-toolchain/stubs/stubs.jar -d /tmp/t/classes.jar \
  app/src/main/java/com/sabihon/todo/domain/model/*.kt \
  app/src/main/java/com/sabihon/todo/domain/security/InputSecurityValidator.kt \
  app/src/main/java/com/sabihon/todo/ui/activity/TimelineLayout.kt \
  tools/junit_shim/*.kt app/src/test/java/com/sabihon/todo/TimelineLayoutTest.kt

KOTLIN_LIB=/tmp/daymark-toolchain/kotlin-compiler-2.4.21/lib
java -cp "/tmp/t/classes.jar:/tmp/daymark-toolchain/stubs/stubs.jar:$KOTLIN_LIB/kotlin-stdlib.jar" \
  sandbox.Runner com.sabihon.todo.TimelineLayoutTest
```

Last run: **30 passed, 0 failed** (`TimelineLayoutTest` 14, `MappersTest` 10,
`TimelineLabelsTest` 6).

One fidelity gap worth knowing: Kotlin will not resolve `import org.junit.Assert.*`
against an object, so a test using the wildcard import has to be given explicit imports to
run here (the timeline tests import each assertion by name and need no change).
