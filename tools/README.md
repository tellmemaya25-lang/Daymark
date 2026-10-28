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

## `typecheck.py` — the one that catches real compile errors

```bash
python3 tools/typecheck.py          # add --list to see which files survived
```

Bootstraps a JDK (`jdk4py`, PyPI) and `kotlinc` (`kotlin-compiler`, npm) into
`/tmp/daymark-toolchain`, generates stubs for the few library types the pure layers touch
(`javax.inject`, `kotlinx.coroutines.flow`, Firebase `Timestamp` + annotations), then
type-checks the app iteratively: files whose unresolved symbols come from absent
libraries (androidx, firebase SDK, hilt) are dropped, and whatever is left must compile.

That currently covers **49 files** — the validator, every domain model and DTO, the
mappers, the admin policy/export logic and the AI guards. It is the check that catches
the class of bug a syntax scan cannot see:

```
app/src/main/java/com/sabihon/todo/data/remote/dto/Mappers.kt:61:23: error: argument type
mismatch: actual type is 'String?', but 'String' was expected.
```

Verified by reintroducing that exact defect: the tool reports it at the same line and
column Gradle does, and passes once fixed. Run it before pushing anything that touches
domain/, data/remote/dto/ or data/ai/.
