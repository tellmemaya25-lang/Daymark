# Deploys Daymark's Firestore security rules using the project_id in app\google-services.json.
$ErrorActionPreference = "Stop"

$googleServicesPath = Join-Path $PSScriptRoot "app\google-services.json"
if (-not (Test-Path $googleServicesPath)) {
    Write-Host "ERROR: Could not find app\google-services.json." -ForegroundColor Red
    Write-Host "Run manually with: firebase deploy --only firestore:rules --project <YOUR_FIREBASE_PROJECT_ID>" -ForegroundColor Yellow
    exit 1
}

$json = Get-Content $googleServicesPath -Raw | ConvertFrom-Json
$projectId = $json.project_info.project_id

if ([string]::IsNullOrWhiteSpace($projectId)) {
    Write-Host "ERROR: project_info.project_id is missing in app\google-services.json." -ForegroundColor Red
    exit 1
}

Write-Host "Deploying firestore.rules to Firebase project: $projectId ..." -ForegroundColor Cyan
firebase deploy --only firestore:rules --project $projectId
