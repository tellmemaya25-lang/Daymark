# daymark . — why the rebrand "isn't showing"

Short answer: the rename has never reached the build you're looking at. It is not a
theming bug — `Settings → About` is a hardcoded string, and in the current code it
reads `daymark . v1.0`.

## One command

From the repo folder, in PowerShell:

```powershell
.\refresh-brand.ps1
```

It stashes your local edits, force-syncs to the published `main`, verifies the
rebrand is really in the sources, cleans the build, uninstalls the stale app
(so the launcher drops its cached icon + label), installs the fresh debug build,
and launches it.

Useful flags:

| Flag | Effect |
|---|---|
| `-KeepStash` | sync only, then stop so you can review `git stash list` |
| `-SkipDevice` | sync + clean build, no uninstall/install |
| `-Remote <url>` | use a different remote |

## Check yourself, in one line

```powershell
git log --oneline -1
grep -n "app_name" app\src\main\res\values\strings.xml
```

Expected: a recent `Merge pull request` commit, and
`<string name="app_name">daymark .</string>`.

If the second command prints `Just Todo-it`, you're on old code — a pull is failing,
not the UI.

## The three things that make it look unchanged

1. **The pull aborted.** Uncommitted edits to `app/README.md`, `*.gradle.kts`,
   `gradle.properties`, `settings.gradle.kts` and untracked `app/cls`,
   `gradle/wrapper/gradle-wrapper.jar` block `git pull`. Stash first
   (`git stash push -u`) — the script does this for you.
2. **The build failed, silently leaving the old APK installed.** The most common
   cause was the missing `app/google-services.json` (gitignored). `app/build.gradle.kts`
   now copies it from `app/cls` automatically, so a fresh clone builds.
3. **Android caches the launcher label and icon per package.** Even a correct
   rebuild can keep the old tile until the app is uninstalled. The script runs
   `adb uninstall com.sabihon.todo` before installing.

## What should look different

| Surface | Before | After |
|---|---|---|
| Launcher label | `Just Todo-it` | `daymark .` |
| Launcher icon | blank/placeholder tile | lavender `#C4D2FF` tile, navy tick |
| Splash (~1 s, kill the app first) | text title | mark + `daymark` wordmark + tagline |
| Home top bar | greeting only | mark tile left of "Hello, …" |
| Login / Sign up | "Login to Just Todo-it" | "Login to daymark ." |
| Settings → About | `Just Todo-it v1.0` | `daymark . v1.0` |

## Without a device

Open `app/src/main/java/com/sabihon/todo/core/ui/components/Branding.kt` in Android
Studio and switch to the **Preview** pane — the mark and lockup render in light and
dark straight from source.

## Footnote: OneDrive

Cloning inside OneDrive lets the sync client lock `.git` files mid-operation, which
shows up as aborted pulls and "file changed underneath us" errors. Moving the clone
to `C:\dev\Daymark` removes a whole class of these.
