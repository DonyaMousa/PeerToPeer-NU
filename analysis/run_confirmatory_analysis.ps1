$ErrorActionPreference = "Stop"

Write-Host "CARBLE FINAL CONFIRMATORY ANALYSIS" -ForegroundColor Cyan

$projectRoot = Split-Path -Parent $PSScriptRoot
$analysisRoot = $PSScriptRoot
$dataDir = Join-Path $analysisRoot "data"
$outputDir = Join-Path $analysisRoot "outputs"

$pre = Join-Path $projectRoot "app\build\research\CARBLE-CONFIRMATORY\PREFAILURE\prefailure_protocol_comparison.csv"
$fullDir = Join-Path $projectRoot "app\build\research\CARBLE-CONFIRMATORY\FULL-DEGRADATION"

$full = Join-Path $fullDir "full_carble_transition_comparison.csv"
$audit = Join-Path $fullDir "full_carble_transition_audit.csv"
$events = Join-Path $fullDir "full_carble_transition_events.csv"
$resource = Join-Path $fullDir "full_transition_resource_summary.csv"
$relay = Join-Path $fullDir "full_transition_relay_burden.csv"

$required = @($pre, $full, $audit, $events, $resource, $relay)

foreach ($f in $required) {
    if (-not (Test-Path $f)) {
        throw "Missing confirmatory file: $f"
    }
}

New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

Copy-Item $pre      (Join-Path $dataDir "prefailure_protocol_comparison.csv") -Force
Copy-Item $full     (Join-Path $dataDir "full_carble_transition_comparison.csv") -Force
Copy-Item $audit    (Join-Path $dataDir "full_carble_transition_audit.csv") -Force
Copy-Item $events   (Join-Path $dataDir "full_carble_transition_events.csv") -Force
Copy-Item $resource (Join-Path $dataDir "full_transition_resource_summary.csv") -Force
Copy-Item $relay    (Join-Path $dataDir "full_transition_relay_burden.csv") -Force

Write-Host "Copied six confirmatory CSVs into analysis\data." -ForegroundColor Green

python .\carble_final_statistics.py `
  --prefailure ".\data\prefailure_protocol_comparison.csv" `
  --full ".\data\full_carble_transition_comparison.csv" `
  --audit ".\data\full_carble_transition_audit.csv" `
  --events ".\data\full_carble_transition_events.csv" `
  --resource ".\data\full_transition_resource_summary.csv" `
  --relay ".\data\full_transition_relay_burden.csv" `
  --outdir ".\outputs\statistics"

if ($LASTEXITCODE -ne 0) {
    throw "Statistics pipeline failed."
}

python .\carble_final_tables.py `
  --stats-dir ".\outputs\statistics" `
  --outdir ".\outputs\tables"

if ($LASTEXITCODE -ne 0) {
    throw "Table pipeline failed."
}

python .\carble_final_figures.py `
  --stats-dir ".\outputs\statistics" `
  --outdir ".\outputs\figures"

if ($LASTEXITCODE -ne 0) {
    throw "Figure pipeline failed."
}

Write-Host ""
Write-Host "FINAL CONFIRMATORY ANALYSIS COMPLETE." -ForegroundColor Green
Write-Host "Statistics: $analysisRoot\outputs\statistics"
Write-Host "Tables:     $analysisRoot\outputs\tables"
Write-Host "Figures:    $analysisRoot\outputs\figures"
