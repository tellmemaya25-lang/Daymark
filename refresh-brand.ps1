# refresh-brand.ps1 — pull the daymark rebrand and reinstall it on a connected device
#
# Why this exists: the rename lives in code (app_name, splash, Home top bar) and in
# resources (mipmap icons). An already-installed app keeps its cached label/icon, and
# an aborted `git pull` leaves you building old sources — both make it look like
# "the changes aren't showing".
#
# Usage (from the repo folder, PowerShell):
#   .\refresh-brand.ps1              # stash local edits, sync to GitHub main, uninstall + reinstall debug
#   .\refresh-brand.ps1 -KeepStash   # same, but stop after syncing so you can inspect the stash
#   .\refresh-brand.ps1 -SkipDevice  # only sync sources + clean build, no device work
#
# Nothing is deleted: local edits go into a `git stash` entry first.

param(
    [switch]$SkipDevice,
    [switch]$KeepStash,
    [string]$Remote = "https://github.com/tellmemaya25-lang/Daymark.git",
    [string]$Package = "com.sabihon.todo"
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

function Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host "`n[FAILED] $text" -ForegroundColor Red; exit 1 }

if (-not (Test-Path ".git")) { Fail "No .git folder here. Run this script from the Daymark repo root." }

# --- 0. sanity: warn if the repo sits inside OneDrive -------------------------
if ((Get-Location).Path -match "OneDrive") {
    Write-Host "[warning] This repo is inside OneDrive. File sync can lock .git and cause" -ForegroundColor Yellow
    Write-Host "          half-finished pulls. Consider moving the clone to e.g. C:\dev\Daymark." -ForegroundColor Yellow
}

# --- 1. park local edits ------------------------------------------------------
Step "Stashing local edits (safe, reversible)"
$dirty = (git status --porcelain)
if ($dirty) {
    git stash push -u -m "pre-refresh $(Get-Date -Format s)"
    if ($LASTEXITCODE -ne 0) { Fail "git stash failed." }
    Write-Host "Your edits are saved. Restore later with: git stash list / git stash show -p stash@{0}"
} else {
    Write-Host "Working tree already clean."
}

# --- 2. force-sync to the published main -------------------------------------
Step "Fetching $Remote"
git fetch "$Remote" main
if ($LASTEXITCODE -ne 0) { Fail "git fetch failed (network or OneDrive lock)." }

Step "Resetting to origin main"
git reset --hard FETCH_HEAD
if ($LASTEXITCODE -ne 0) { Fail "git reset failed." }

Step "HEAD is now"
git log --oneline -1

# --- 3. verify the rebrand actually landed ------------------------------------
Step "Verifying the rebrand is present in the sources"
$strings = Get-Content "app\src\main\res\values\strings.xml" -Raw
if ($strings -notmatch '<string name="app_name">daymark \.</string>') {
    Fail "app_name is not 'daymark .' - the checkout does not contain the rebrand."
}
if (-not (Test-Path "app\src\main\java\com\sabihon\todo\core\ui\components\Branding.kt")) {
    Fail "Branding.kt is missing - the checkout does not contain the rebrand."
}
Write-Host "OK: app_name = 'daymark .' and Branding.kt present." -ForegroundColor Green

if ($KeepStash) {
    Step "Stopping here (-KeepStash)"
    Write-Host "Inspect your old edits with: git stash show -p stash@{0}"
    Write-Host "Drop them with:             git stash drop"
    exit 0
}

# --- 4. rebuild from scratch --------------------------------------------------
Step "Clean build"
& ".\gradlew.bat" clean
if ($LASTEXITCODE -ne 0) { Fail "Gradle clean failed." }

if ($SkipDevice) {
    Step "Syncing sources only (-SkipDevice)"
    Write-Host "Open the project in Android Studio, Sync, then Run." -ForegroundColor Green
    exit 0
}

# --- 5. uninstall the stale app so the launcher drops the cached icon/label ---
Step "Checking adb"
$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    Write-Host "adb not on PATH. Skipping device steps - press Run in Android Studio instead." -ForegroundColor Yellow
    Write-Host "Tip: uninstall the app manually first so the launcher label/icon refresh." -ForegroundColor Yellow
    exit 0
}

$devices = (adb devices) -split "`n" | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
if (-not $devices) {
    Write-Host "No device/emulator connected. Skipping device steps." -ForegroundColor Yellow
    exit 0
}

Step "Uninstalling $Package (clears the cached launcher label + icon)"
adb uninstall $Package | Out-Null   # exit code 1 is fine when it isn't installed

Step "Installing the fresh debug build"
& ".\gradlew.bat" :app:installDebug
if ($LASTEXITCODE -ne 0) { Fail "Install failed - the build output above has the real error." }

Step "Launching"
adb shell am start -n "$Package/.MainActivity" | Out-Null

Write-Host "`nDone. On the Home screen you should now see:" -ForegroundColor Green
Write-Host "  * the lavender daymark mark left of 'Hello, <name>,'"
Write-Host "  * Settings -> About reading 'daymark . v1.0'"
Write-Host "  * the launcher icon/label updated (needs the uninstall above)"
Write-Host "`nSet the phone to light mode and kill the app once to see the new splash lockup."
