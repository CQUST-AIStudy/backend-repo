# Grading Worker Startup Script
# Detached startup that keeps both processes alive after this shell exits.

$PYTHON = if ($env:GRADING_WORKER_PYTHON) { $env:GRADING_WORKER_PYTHON } else { "python" }
$ROOT = Split-Path -Parent $PSScriptRoot
$LOG_DIR = Join-Path $ROOT "logs"
$localEnvScript = Join-Path $ROOT "local.env.ps1"

if (Test-Path $localEnvScript) {
    . $localEnvScript
}

if ($PYTHON -ne "python" -and -not (Test-Path $PYTHON)) {
    throw "Python interpreter not found: $PYTHON"
}

if (-not (Test-Path $LOG_DIR)) {
    New-Item -ItemType Directory -Path $LOG_DIR | Out-Null
}

& $PYTHON -c "import cryptography" 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "Missing Python dependency: cryptography. Install grading_worker/requirements.txt in $PYTHON before starting the grading worker."
}

if (-not $env:CELERY_CONCURRENCY) { $env:CELERY_CONCURRENCY = "3" }
if (-not $env:CELERY_POOL) { $env:CELERY_POOL = "threads" }
if (-not $env:DIMENSION_SCORE_CONCURRENCY) { $env:DIMENSION_SCORE_CONCURRENCY = "1" }
if (-not $env:OCR_STRATEGY) { $env:OCR_STRATEGY = "vlm_only" }

$workerOut = Join-Path $LOG_DIR "grading_worker_daemon.out.log"
$workerErr = Join-Path $LOG_DIR "grading_worker_daemon.err.log"
$consumerOut = Join-Path $LOG_DIR "grading_consumer_daemon.out.log"
$consumerErr = Join-Path $LOG_DIR "grading_consumer_daemon.err.log"

foreach ($path in @($workerOut, $workerErr, $consumerOut, $consumerErr)) {
    if (Test-Path $path) {
        Remove-Item $path -Force
    }
}

Write-Host "Starting grading worker with OCR_STRATEGY=$env:OCR_STRATEGY, CELERY_POOL=$env:CELERY_POOL, CELERY_CONCURRENCY=$env:CELERY_CONCURRENCY" -ForegroundColor Green
Write-Host "Logs: $workerOut / $consumerOut" -ForegroundColor DarkCyan

$workerCmd = "cd /d `"$PSScriptRoot`" && `"$PYTHON`" -u run_worker.py 1>>`"$workerOut`" 2>>`"$workerErr`""
$consumerCmd = "cd /d `"$PSScriptRoot`" && `"$PYTHON`" -u run_consumer.py 1>>`"$consumerOut`" 2>>`"$consumerErr`""

$workerProc = Start-Process `
    -FilePath "C:\Windows\System32\cmd.exe" `
    -ArgumentList "/c", $workerCmd `
    -WindowStyle Hidden `
    -PassThru
Start-Sleep -Seconds 3
$consumerProc = Start-Process `
    -FilePath "C:\Windows\System32\cmd.exe" `
    -ArgumentList "/c", $consumerCmd `
    -WindowStyle Hidden `
    -PassThru

Start-Sleep -Seconds 2
$workerAlive = Get-Process -Id $workerProc.Id -ErrorAction SilentlyContinue
$consumerAlive = Get-Process -Id $consumerProc.Id -ErrorAction SilentlyContinue

Write-Host "Worker launcher PID: $($workerProc.Id)" -ForegroundColor Cyan
Write-Host "Consumer launcher PID: $($consumerProc.Id)" -ForegroundColor Cyan
Write-Host "Use Get-Content -Tail on the log files to inspect progress." -ForegroundColor Yellow
if (-not $workerAlive) {
    Write-Host "Worker process exited unexpectedly. Check: $workerErr" -ForegroundColor Red
}
if (-not $consumerAlive) {
    Write-Host "Consumer process exited unexpectedly. Check: $consumerErr" -ForegroundColor Red
}
