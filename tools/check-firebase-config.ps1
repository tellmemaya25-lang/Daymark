<#
.SYNOPSIS
    Checks that app/google-services.json can actually build, and that Google
    Sign-In has a client to work with.

.DESCRIPTION
    Two separate things go wrong with this file, and the console gives you a
    different error for each:

      "No matching client found for package name 'com.sabihon.todo'"
          The file has no Android app registered under that package. Usually the
          config was downloaded from a different Firebase project, or the Android
          app in that project was registered with another package name. This is a
          hard build failure.

      "default_web_client_id" missing / Google button disabled
          The file matches the package but has an empty oauth_client list, because
          the Google provider was not enabled when it was downloaded. Builds fine,
          Google Sign-In cannot work.

    This script reports both, plus the project the file belongs to - which matters
    if you have more than one Firebase project, because your existing tasks and
    users live in exactly one of them.

.EXAMPLE
    .\tools\check-firebase-config.ps1

.EXAMPLE
    .\tools\check-firebase-config.ps1 -ConfigPath D:\other\google-services.json
#>
param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot '..\app\google-services.json'),
    [string]$PackageName = 'com.sabihon.todo'
)

$ErrorActionPreference = 'Stop'
$problems = 0

function Write-Problem($message) {
    Write-Host "  [X] $message" -ForegroundColor Red
    $script:problems++
}

function Write-Ok($message) {
    Write-Host "  [ok] $message" -ForegroundColor Green
}

function Write-Note($message) {
    Write-Host "  [!] $message" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Firebase config check" -ForegroundColor Cyan
Write-Host "  expected package: $PackageName"
Write-Host "  file: $((Resolve-Path -LiteralPath $ConfigPath -ErrorAction SilentlyContinue).Path)"

if (-not (Test-Path -LiteralPath $ConfigPath)) {
    Write-Problem "config file not found at $ConfigPath"
    Write-Host ""
    Write-Host "Download it from Firebase Console > Project settings > Your apps > google-services.json" -ForegroundColor Gray
    exit 1
}

try {
    $config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
} catch {
    Write-Problem "could not parse the file as JSON: $($_.Exception.Message)"
    exit 1
}

$projectId = $config.project_info.project_id
$projectNumber = $config.project_info.project_number
Write-Host ""
Write-Host "  project_id:     $projectId"
Write-Host "  project_number: $projectNumber"

$clients = @($config.client)
if ($clients.Count -eq 0) {
    Write-Problem "the file contains no apps at all"
    exit 1
}

Write-Host ""
Write-Host "  apps in this file:" -ForegroundColor Cyan
$match = $null
foreach ($client in $clients) {
    $pkg = $client.client_info.android_client_info.package_name
    $oauth = @($client.oauth_client)
    $webClients = @($oauth | Where-Object { $_.client_type -eq 3 })
    $types = ($oauth | ForEach-Object { $_.client_type }) -join ', '
    if (-not $types) { $types = 'none' }

    $isMatch = ($pkg -eq $PackageName)
    if ($isMatch) {
        $match = $client
        Write-Host "    * $pkg  (oauth clients: $types)" -ForegroundColor Green
    } else {
        Write-Host "      $pkg  (oauth clients: $types)"
    }
}

Write-Host ""
if ($null -eq $match) {
    Write-Problem "no app in this file uses the package '$PackageName'"
    Write-Host ""
    Write-Host "  This file is for project '$projectId', which does not contain your Android app." -ForegroundColor Gray
    Write-Host "  Your existing tasks, categories and accounts live in whichever project they" -ForegroundColor Gray
    Write-Host "  were created in - do not move to a new one just to fix this." -ForegroundColor Gray
    Write-Host ""
    Write-Host "  Fix, inside the project that already holds your data:" -ForegroundColor Gray
    Write-Host "    1. Project settings > Your apps > Add app > Android" -ForegroundColor Gray
    Write-Host "    2. Android package name: $PackageName   (exactly this)" -ForegroundColor Gray
    Write-Host "    3. Register, then download google-services.json for THAT app" -ForegroundColor Gray
    Write-Host "    4. Replace app\google-services.json and re-run this script" -ForegroundColor Gray
    exit 1
}

Write-Ok "package '$PackageName' is registered in project '$projectId'"

$webClients = @($match.oauth_client | Where-Object { $_.client_type -eq 3 })
if ($webClients.Count -eq 0) {
    Write-Problem "no Web OAuth client (client_type 3) for this app, so default_web_client_id cannot be generated"
    Write-Host ""
    Write-Host "  The build will succeed but Google Sign-In stays disabled." -ForegroundColor Gray
    Write-Host "    1. Authentication > Sign-in method > Google > Enable > Save" -ForegroundColor Gray
    Write-Host "    2. Project settings > Your apps > download google-services.json AGAIN" -ForegroundColor Gray
    Write-Host "       (enabling the provider is what creates the web client)" -ForegroundColor Gray
    Write-Host "    3. Replace the file, run: ./gradlew clean :app:assembleDebug" -ForegroundColor Gray
} else {
    Write-Ok "Web OAuth client present ($($webClients[0].client_id.Substring(0, [Math]::Min(18, $webClients[0].client_id.Length)))...)"
}

# SHA-1 reminder: a registered key is what stops ApiException 10 at sign-in time.
if ($match.client_info.android_client_info.certificate_hash) {
    $hashes = @($match.client_info.android_client_info.certificate_hash)
    Write-Ok "$($hashes.Count) SHA-1 fingerprint(s) registered for this app"
} else {
    Write-Note "no certificate_hash in this file - older configs omit it. If Google sign-in fails with code 10, run ./gradlew signingReport and add the SHA-1 in Firebase."
}

# The build copies app/cls into place when google-services.json is missing, so a
# mismatched bundled copy is a trap that only fires later, on a clean checkout.
$bundled = Join-Path $PSScriptRoot '..\app\cls'
if (Test-Path -LiteralPath $bundled) {
    Write-Host ""
    Write-Host "  bundled fallback (app\cls):" -ForegroundColor Cyan
    try {
        $clsConfig = Get-Content -LiteralPath $bundled -Raw | ConvertFrom-Json
        $clsPkgs = @($clsConfig.client | ForEach-Object { $_.client_info.android_client_info.package_name })
        $clsWeb = @($clsConfig.client | ForEach-Object { $_.oauth_client } | Where-Object { $_.client_type -eq 3 })
        Write-Host "    project: $($clsConfig.project_info.project_id)  packages: $($clsPkgs -join ', ')"
        if ($clsPkgs -notcontains $PackageName) {
            Write-Note "app\cls has no '$PackageName' app - delete it or replace it, or a clean checkout will fail the same way."
        } elseif ($clsWeb.Count -eq 0) {
            Write-Note "app\cls has the right package but no Web OAuth client, so a fresh clone builds with Google Sign-In disabled."
        } else {
            Write-Ok "app\cls can bootstrap a working config"
        }
    } catch {
        Write-Problem "app\cls is not valid JSON"
    }
}

Write-Host ""
if ($problems -gt 0) {
    Write-Host "$problems problem(s) found." -ForegroundColor Red
    exit 1
}
Write-Host "Config looks usable. Next: ./gradlew clean :app:assembleDebug" -ForegroundColor Green
exit 0
