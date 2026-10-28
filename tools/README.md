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

## Running the real compiler here (no Android SDK required)

The sandbox blocks `services.gradle.org`, Google Maven and Maven Central, so Gradle
cannot resolve dependencies. A JDK and `kotlinc` can still be obtained from the two
registries that *are* reachable — enough for a genuine parse of the whole tree, and for
compiling anything that does not import androidx:

```bash
pip install --break-system-packages jdk4py            # JDK from PyPI
export JAVA_HOME=$(python3 -c "import jdk4py;print(jdk4py.JAVA_HOME)")
export PATH="$JAVA_HOME/bin:$PATH"

npm pack kotlin-compiler@2.4.21 --silent              # kotlinc from npm
tar xzf kotlin-compiler-2.4.21.tgz

find app/src -name '*.kt' > /tmp/srcs.txt
package/bin/kotlinc @/tmp/srcs.txt -d /tmp/out -nowarn 2>&1 \
  | grep -iE "error:.*(syntax|expecting|unclosed)"   # parse failures only
```

Unresolved `androidx`/`firebase` symbols are expected and noisy; parse-level errors are
not. For a type check of a specific expression, compile it standalone against minimal
stubs of the Compose signatures it uses (see the `Modifier`/`IntSize`/`Dp` probe used to
verify the weekly-calendar slot collapse).
