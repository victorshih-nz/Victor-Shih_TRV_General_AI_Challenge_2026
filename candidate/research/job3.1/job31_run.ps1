param(
    [int]$Seed = 3,
    [int]$Duration = 360,
    [int]$ReadyTimeout = 60,
    [string]$RepoRoot = "D:\TRV_General_AI_Challenge_2026",
    [string]$EvidenceRoot = "D:\TRV_General_AI_Challenge_2026_evidence\job3.1A"
)

$ErrorActionPreference = "Stop"

$candidate = Join-Path $RepoRoot "candidate"
Set-Location $candidate

$python = (Resolve-Path ".\.probe-venv\Scripts\python.exe").Path
$probe = Join-Path $env:TEMP "trv-job31\job31_probe.py"
$analyzer = Join-Path $candidate "job31_analyze.py"

if (-not (Test-Path $probe)) {
    throw "Probe not found: $probe"
}
if (-not (Test-Path $analyzer)) {
    throw "Analyzer not found: $analyzer"
}

function Get-ServiceContainerId {
    param([string]$Service)
    $value = (& docker compose --profile sim --profile strategy ps -q $Service 2>$null | Select-Object -First 1)
    if ($null -eq $value) { return "" }
    return "$value".Trim()
}

function Get-ContainerSeed {
    param([string]$ContainerId)
    if ([string]::IsNullOrWhiteSpace($ContainerId)) { return $null }

    $line = & docker inspect $ContainerId `
        --format '{{range .Config.Env}}{{println .}}{{end}}' |
        Where-Object { $_ -match '^SIM_SEED=' } |
        Select-Object -First 1

    if (-not $line) { return $null }

    $value = ($line -split '=', 2)[1]
    $parsed = 0
    if ([int]::TryParse($value, [ref]$parsed)) {
        return $parsed
    }
    return $value
}

function Save-RuntimeEvidence {
    param(
        [string]$RunDir,
        [string]$Label = "runtime"
    )

    New-Item -ItemType Directory -Force $RunDir | Out-Null

    & docker compose --profile sim --profile strategy ps -a |
        Set-Content (Join-Path $RunDir "compose-ps-final.txt")

    & docker compose logs --timestamps --no-color 2>&1 |
        Set-Content (Join-Path $RunDir "compose-all.log")

    $stateFile = Join-Path $RunDir "container-states.txt"
    Remove-Item $stateFile -ErrorAction SilentlyContinue

    $services = @("nats", "exchange", "sim", "taker", "strategy", "hedger")

    foreach ($service in $services) {
        $cid = Get-ServiceContainerId $service
        if (-not [string]::IsNullOrWhiteSpace($cid)) {
            $fmt = "Service=$service Status={{.State.Status}} Running={{.State.Running}} RestartCount={{.RestartCount}} ExitCode={{.State.ExitCode}} StartedAt={{.State.StartedAt}}"
            & docker inspect --format $fmt $cid |
                Add-Content $stateFile
        }
    }

    $simCid = Get-ServiceContainerId "sim"
    if (-not [string]::IsNullOrWhiteSpace($simCid)) {
        & docker inspect $simCid `
            --format '{{range .Config.Env}}{{println .}}{{end}}' |
            Where-Object { $_ -match '^SIM_SEED=' } |
            Set-Content (Join-Path $RunDir "sim-seed.txt")
    }

    $logPath = Join-Path $RunDir "compose-all.log"
    $scanPath = Join-Path $RunDir "hard-gate-scan.txt"
    $scan = Select-String `
        -Path $logPath `
        -Pattern "FATAL|SEVERE|Traceback|RECOVERING|became UNKNOWN|outcome uncertain" `
        -Context 2,4

    if ($scan) {
        $scan | Out-String | Set-Content $scanPath
    } else {
        "NO_MATCHES" | Set-Content $scanPath
    }

    Write-Host "EVIDENCE_SAVED label=$Label dir=$RunDir"
}

function Wait-ForProbeText {
    param(
        [System.Diagnostics.Process]$Process,
        [string]$Path,
        [string]$Wanted,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)

    while ((Get-Date) -lt $deadline) {
        if (Test-Path $Path) {
            $text = Get-Content $Path -Raw -ErrorAction SilentlyContinue
            if ($text -match [regex]::Escape($Wanted)) {
                return $true
            }
            if ($text -match "MEASUREMENT_ABORT") {
                return $false
            }
        }

        if ($Process.HasExited) {
            return $false
        }

        Start-Sleep -Milliseconds 250
    }

    return $false
}

Write-Host "JOB31_RUNNER_START target_seed=$Seed duration=$Duration"

# 1. Detect and preserve any active previous session.
$activeSimCid = Get-ServiceContainerId "sim"
if (-not [string]::IsNullOrWhiteSpace($activeSimCid)) {
    $activeSeed = Get-ContainerSeed $activeSimCid
    Write-Host "ACTIVE_SESSION_DETECTED seed=$activeSeed"

    if ($activeSeed -eq $Seed) {
        $targetDir = Join-Path $EvidenceRoot ("run-{0:D2}-seed-{0}" -f $Seed)
        $existingRaw = Join-Path $targetDir "raw.ndjson"

        if (Test-Path $existingRaw) {
            $existingText = Get-Content $existingRaw -Raw -ErrorAction SilentlyContinue
            if ($existingText -match "MEASUREMENT_END") {
                Write-Host "TARGET_RUN_ALREADY_COMPLETE seed=$Seed"
                Save-RuntimeEvidence -RunDir $targetDir -Label "target-existing"

                $analysisOut = Join-Path $targetDir "analysis.stdout.txt"
                & $python $analyzer $existingRaw `
                    --candidate $candidate `
                    --json-out (Join-Path $targetDir "analysis.json") |
                    Tee-Object $analysisOut
                Write-Host "JOB31_RUNNER_DONE existing_complete=true"
                exit 0
            }
        }

        throw "Seed $Seed is already active but no completed target evidence exists. Refusing to overwrite an in-progress/partial run."
    }

    if ($null -ne $activeSeed -and "$activeSeed" -match '^\d+$') {
        $previousDir = Join-Path $EvidenceRoot ("run-{0:D2}-seed-{0}" -f [int]$activeSeed)
        Save-RuntimeEvidence -RunDir $previousDir -Label "previous-seed-$activeSeed"
    } else {
        $unknownDir = Join-Path $EvidenceRoot ("pre-run-{0:D2}-unknown-active-" -f $Seed) + (Get-Date -Format "yyyyMMdd-HHmmss")
        Save-RuntimeEvidence -RunDir $unknownDir -Label "previous-unknown"
    }
}

# 2. Fresh isolation.
& docker compose --profile sim --profile strategy down --remove-orphans
if ($LASTEXITCODE -ne 0) {
    throw "docker compose down failed"
}

$runDir = Join-Path $EvidenceRoot ("run-{0:D2}-seed-{0}" -f $Seed)
if (Test-Path $runDir) {
    $existingRaw = Join-Path $runDir "raw.ndjson"
    if (Test-Path $existingRaw) {
        $existingText = Get-Content $existingRaw -Raw -ErrorAction SilentlyContinue
        if ($existingText -match "MEASUREMENT_END") {
            throw "Completed evidence already exists at $runDir; refusing to overwrite it."
        }
    }
}

New-Item -ItemType Directory -Force $runDir | Out-Null

$override = Join-Path $env:TEMP ("trv-job31-seed{0}.yml" -f $Seed)

@"
services:
  sim:
    environment:
      SIM_SEED: "$Seed"
"@ | Set-Content -Encoding UTF8 $override

# 3. Freeze metadata before starting the target run.
git -C $RepoRoot status --short --branch |
    Set-Content (Join-Path $runDir "git-status-before.txt")
git -C $RepoRoot rev-parse HEAD |
    Set-Content (Join-Path $runDir "git-head.txt")
git -C $RepoRoot diff --stat |
    Set-Content (Join-Path $runDir "git-diff-stat.txt")
docker compose version |
    Set-Content (Join-Path $runDir "docker-compose-version.txt")
docker image inspect sim-exchange:candidate --format '{{.Id}}' |
    Set-Content (Join-Path $runDir "exchange-image.txt")

# 4. Fresh NATS only.
& docker compose `
    -f docker-compose.yml `
    -f $override `
    --profile sim `
    --profile strategy `
    up -d nats

if ($LASTEXITCODE -ne 0) {
    throw "Failed to start NATS"
}

# 5. Start passive probe before any trading service.
$raw = Join-Path $runDir "raw.ndjson"
$pout = Join-Path $runDir "probe.stdout.txt"
$perr = Join-Path $runDir "probe.stderr.txt"

$probeProcess = Start-Process `
    -FilePath $python `
    -ArgumentList @(
        $probe,
        "--duration", "$Duration",
        "--ready-timeout", "$ReadyTimeout",
        "--output", $raw
    ) `
    -RedirectStandardOutput $pout `
    -RedirectStandardError $perr `
    -PassThru

if (-not (Wait-ForProbeText -Process $probeProcess -Path $pout -Wanted "PROBE_READY" -TimeoutSeconds 15)) {
    Save-RuntimeEvidence -RunDir $runDir -Label "probe-start-failed"
    throw "Probe did not become ready"
}

Write-Host "PROBE_READY_CONFIRMED"

# 6. Start the trading runtime.
& docker compose `
    -f docker-compose.yml `
    -f $override `
    --profile sim `
    --profile strategy `
    up -d exchange sim taker strategy hedger

if ($LASTEXITCODE -ne 0) {
    Save-RuntimeEvidence -RunDir $runDir -Label "runtime-start-failed"
    throw "Failed to start trading runtime"
}

if (-not (Wait-ForProbeText -Process $probeProcess -Path $pout -Wanted "MEASUREMENT_START" -TimeoutSeconds ($ReadyTimeout + 15))) {
    Save-RuntimeEvidence -RunDir $runDir -Label "measurement-start-failed"
    if (Test-Path $pout) { Get-Content $pout }
    if (Test-Path $perr) { Get-Content $perr }
    throw "Measurement did not start"
}

Write-Host "MEASUREMENT_START_CONFIRMED"

# 7. Wait for the frozen measurement window to finish.
Wait-Process -Id $probeProcess.Id

$probeStdout = Get-Content $pout -Raw -ErrorAction SilentlyContinue
$probeStderr = Get-Content $perr -Raw -ErrorAction SilentlyContinue

Write-Host "----- PROBE STDOUT -----"
Write-Host $probeStdout.Trim()
if (-not [string]::IsNullOrWhiteSpace($probeStderr)) {
    Write-Host "----- PROBE STDERR -----"
    Write-Host $probeStderr.Trim()
}

if ($probeStdout -notmatch "MEASUREMENT_END") {
    Save-RuntimeEvidence -RunDir $runDir -Label "measurement-incomplete"
    throw "Measurement ended without MEASUREMENT_END"
}

# 8. Preserve the live final state before any teardown.
Save-RuntimeEvidence -RunDir $runDir -Label "target-seed-$Seed"

# 9. Verify the actual simulator seed.
$simCid = Get-ServiceContainerId "sim"
$actualSeed = Get-ContainerSeed $simCid
if ("$actualSeed" -ne "$Seed") {
    throw "SIM_SEED mismatch: expected=$Seed actual=$actualSeed"
}
Write-Host "SIM_SEED_CONFIRMED=$actualSeed"

# 10. Analyze without downloading anything or changing strategy code.
$analysisOut = Join-Path $runDir "analysis.stdout.txt"
& $python $analyzer $raw `
    --candidate $candidate `
    --json-out (Join-Path $runDir "analysis.json") |
    Tee-Object $analysisOut

if ($LASTEXITCODE -ne 0) {
    throw "Analyzer failed"
}

Write-Host "JOB31_RUNNER_DONE seed=$Seed"
Write-Host "RUN_DIR=$runDir"
Write-Host "NOTE=Containers intentionally left running for causal review."
