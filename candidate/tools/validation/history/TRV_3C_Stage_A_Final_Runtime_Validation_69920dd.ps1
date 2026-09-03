param(
    [int]$ObservationSeconds = 120,
    [int]$StartupTimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoUrl = "https://github.com/victorshih-nz/Victor-Shih_TRV_General_AI_Challenge_2026.git"
$Branch = "batch3/job-3.1-repeated-trading-evaluation"
$ExpectedHead = "69920dd4f7ea485737b4388ffdb7654aba79fb4f"

$StageRoot = "D:\TRV_General_AI_Challenge_2026_3C_STAGE_A_69920dd"
$CandidateDir = Join-Path $StageRoot "candidate"
$EvidenceBase = "D:\TRV_General_AI_Challenge_2026_evidence"
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$EvidenceDir = Join-Path $EvidenceBase "3C-StageA-$Stamp"

$ComposeProject = "trv3cstagea69920dd"
$RequiredServices = @(
    "nats",
    "exchange",
    "sim",
    "taker",
    "strategy",
    "hedger"
)

$SavedEnv = @{}
$EnvNames = @(
    "COMPOSE_PROJECT_NAME",
    "NATS_URL",
    "TAKER_FEED",
    "SENDER",
    "TAKER_SENDER",
    "HEDGER_SENDER",
    "QUOTER_SOFT_POS",
    "QUOTER_HARD_POS",
    "DESK_SOFT_POS",
    "DESK_HARD_POS",
    "TAKER_THRESH"
)

$ComposeStarted = $false
$RunProcess = $null
$StagePassed = $false

function Save-Environment {
    foreach ($name in $EnvNames) {
        $SavedEnv[$name] =
            [Environment]::GetEnvironmentVariable(
                $name,
                "Process"
            )
    }
}

function Restore-Environment {
    foreach ($name in $EnvNames) {
        [Environment]::SetEnvironmentVariable(
            $name,
            $SavedEnv[$name],
            "Process"
        )
    }
}

function Invoke-InDirectory {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Directory,

        [Parameter(Mandatory=$true)]
        [string]$Command,

        [Parameter(Mandatory=$false)]
        [string[]]$Arguments = @(),

        [switch]$Capture
    )

    Push-Location $Directory
    try {
        if ($Capture) {
            $output = & $Command @Arguments 2>&1
            $exit = $LASTEXITCODE

            if ($exit -ne 0) {
                throw (
                    "$Command failed with exit code $exit`n" +
                    ($output -join "`n")
                )
            }

            return $output
        }

        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Command failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

function Get-ComposeOutput {
    param(
        [Parameter(Mandatory=$true)]
        [string[]]$Arguments
    )

    return Invoke-InDirectory `
        -Directory $CandidateDir `
        -Command "docker" `
        -Arguments (@("compose") + $Arguments) `
        -Capture
}

function Test-ContainsCrlf {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Path
    )

    $bytes = [System.IO.File]::ReadAllBytes($Path)

    for ($i = 0; $i -lt ($bytes.Length - 1); $i++) {
        if ($bytes[$i] -eq 13 -and $bytes[$i + 1] -eq 10) {
            return $true
        }
    }

    return $false
}

function Assert-ShellExecutableBit {
    param(
        [Parameter(Mandatory=$true)]
        [string]$RelativePath
    )

    $stageLine =
        Invoke-InDirectory `
            -Directory $CandidateDir `
            -Command "git" `
            -Arguments @(
                "ls-files",
                "--stage",
                "--",
                $RelativePath
            ) `
            -Capture

    $text = $stageLine -join "`n"

    if (-not $text.StartsWith("100755 ")) {
        throw (
            "$RelativePath is not stored with executable mode 100755. " +
            "Linux grader direct execution may fail."
        )
    }
}

function Get-RunningServices {
    try {
        $services =
            Get-ComposeOutput `
                -Arguments @(
                    "--profile", "sim",
                    "--profile", "strategy",
                    "ps",
                    "--services",
                    "--status", "running"
                )

        return @(
            $services |
                ForEach-Object { $_.ToString().Trim() } |
                Where-Object { $_ -ne "" }
        )
    }
    catch {
        return @()
    }
}

function Get-ServiceContainerId {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Service
    )

    $id =
        Get-ComposeOutput `
            -Arguments @(
                "--profile", "sim",
                "--profile", "strategy",
                "ps",
                "-q",
                $Service
            )

    return (($id -join "").Trim())
}

function Assert-DeskImageArchitecture {
    foreach ($service in @("taker", "strategy", "hedger")) {
        $containerId = Get-ServiceContainerId -Service $service

        if ([string]::IsNullOrWhiteSpace($containerId)) {
            throw "No container id found for $service"
        }

        $imageId =
            (& docker inspect `
                --format "{{.Image}}" `
                $containerId 2>&1) -join ""

        if ($LASTEXITCODE -ne 0) {
            throw "Could not inspect image id for $service"
        }

        $architecture =
            (& docker image inspect `
                --format "{{.Os}}/{{.Architecture}}" `
                $imageId 2>&1) -join ""

        if ($LASTEXITCODE -ne 0) {
            throw "Could not inspect architecture for $service"
        }

        $architecture = $architecture.Trim()

        Write-Host "$service image: $architecture"

        if ($architecture -ne "linux/amd64") {
            throw (
                "$service image architecture is $architecture; " +
                "expected linux/amd64"
            )
        }
    }
}

function Assert-NoRestartLoop {
    foreach ($service in $RequiredServices) {
        $containerId = Get-ServiceContainerId -Service $service

        if ([string]::IsNullOrWhiteSpace($containerId)) {
            throw "No container id found for $service"
        }

        $restartCountText =
            (& docker inspect `
                --format "{{.RestartCount}}" `
                $containerId 2>&1) -join ""

        if ($LASTEXITCODE -ne 0) {
            throw "Could not inspect restart count for $service"
        }

        $restartCount = [int]$restartCountText.Trim()

        Write-Host "$service restartCount=$restartCount"

        if ($restartCount -gt 3) {
            throw (
                "$service entered a restart loop " +
                "(restartCount=$restartCount)"
            )
        }
    }
}

function Write-ComposeLogs {
    $allLog =
        Get-ComposeOutput `
            -Arguments @(
                "--profile", "sim",
                "--profile", "strategy",
                "logs",
                "--no-color",
                "--timestamps"
            )

    $deskLog =
        Get-ComposeOutput `
            -Arguments @(
                "--profile", "strategy",
                "logs",
                "--no-color",
                "--timestamps",
                "taker",
                "strategy",
                "hedger"
            )

    [System.IO.File]::WriteAllText(
        (Join-Path $EvidenceDir "runtime-all.log"),
        ($allLog -join "`n")
    )

    [System.IO.File]::WriteAllText(
        (Join-Path $EvidenceDir "runtime-desk.log"),
        ($deskLog -join "`n")
    )

    return @{
        All = ($allLog -join "`n")
        Desk = ($deskLog -join "`n")
    }
}

function Assert-NoHardRuntimeSignatures {
    param(
        [Parameter(Mandatory=$true)]
        [string]$DeskLog
    )

    $hardPatterns = @(
        @{
            Name = "Exchange reject 306/307"
            Pattern = '(?m)(^|[^0-9])(306|307)([^0-9]|$)'
        },
        @{
            Name = "TPS violation"
            Pattern = '(?i)TPS\s+violation|transaction[- ]rate\s+violation'
        },
        @{
            Name = "Unhandled Java exception"
            Pattern = '(?i)Exception in thread'
        },
        @{
            Name = "Unhandled Python traceback"
            Pattern = '(?i)Traceback \(most recent call last\)'
        },
        @{
            Name = "Fatal JVM error"
            Pattern = '(?i)OutOfMemoryError|StackOverflowError'
        },
        @{
            Name = "Severe Quoter failure"
            Pattern = '(?m)\bSEVERE\b'
        }
    )

    foreach ($entry in $hardPatterns) {
        if ($DeskLog -match $entry.Pattern) {
            throw "Hard runtime signature detected: $($entry.Name)"
        }
    }

    $disconnectCount =
        ([regex]::Matches(
            $DeskLog,
            '(?i)Disconnected from NATS'
        )).Count

    Write-Host "Quoter disconnect events before shutdown: $disconnectCount"

    if ($disconnectCount -gt 1) {
        throw (
            "Possible NATS disconnect loop: " +
            "$disconnectCount disconnect events"
        )
    }

    $deferredCancelCount =
        ([regex]::Matches(
            $DeskLog,
            '(?i)cancel deferred by exchange max_tps'
        )).Count

    Write-Host "TPS-deferred Quoter cancels observed: $deferredCancelCount"

    if ($deferredCancelCount -gt 5) {
        throw (
            "Repeated TPS-deferred cancels observed: " +
            "$deferredCancelCount"
        )
    }
}

function Run-PassiveRuntimeProbe {
    $probeCode = @'
import asyncio
import collections
import json
import os
import time

import nats

async def main():
    nats_url = os.environ["NATS_URL"]
    feed = os.environ["TAKER_FEED"]
    quoter = os.environ["SENDER"]
    taker = os.environ["TAKER_SENDER"]
    hedger = os.environ["HEDGER_SENDER"]
    observe = float(os.environ.get("OBS_SECONDS", "120"))

    nc = await nats.connect(nats_url)

    risk_states = collections.Counter()
    risk_messages = 0
    risk_transitions = []
    last_risk_state = None

    bbo_messages = 0

    md_events = {
        "quoter": collections.Counter(),
        "taker": collections.Counter(),
        "hedger": collections.Counter(),
    }

    quoter_add_sides = collections.Counter()

    first_risk_time = None
    first_new_exposure_time = None

    async def on_risk(msg):
        nonlocal risk_messages, last_risk_state, first_risk_time

        try:
            text = msg.data.decode("ascii", errors="strict")
            parts = text.strip().split()
            if len(parts) != 8:
                return

            state = parts[6]
            if state not in {
                "UNKNOWN",
                "SAFE",
                "CONTROLLED",
                "EMERGENCY",
            }:
                return

            now = time.monotonic()
            risk_messages += 1
            risk_states[state] += 1

            if first_risk_time is None:
                first_risk_time = now

            if state != last_risk_state:
                risk_transitions.append({
                    "state": state,
                    "net_position": parts[3],
                    "direction": parts[7],
                })
                last_risk_state = state

        except Exception:
            return

    async def on_bbo(msg):
        nonlocal bbo_messages
        bbo_messages += 1

    def make_md_handler(label):
        async def on_md(msg):
            nonlocal first_new_exposure_time

            try:
                text = msg.data.decode("ascii", errors="strict")
            except Exception:
                return

            parts = text.strip().split()
            if not parts:
                return

            event_type = parts[0]
            md_events[label][event_type] += 1

            # Exchange lifecycle Add:
            # A <sender:order-id> <B|S> <qty> <price>
            if (
                event_type == "A"
                and label in {"quoter", "taker"}
            ):
                if first_new_exposure_time is None:
                    first_new_exposure_time = time.monotonic()

            if (
                label == "quoter"
                and event_type == "A"
                and len(parts) >= 3
                and parts[2] in {"B", "S"}
            ):
                quoter_add_sides[parts[2]] += 1

        return on_md

    await nc.subscribe(
        f"desk.risk.{feed}",
        cb=on_risk,
    )
    await nc.subscribe(
        f"ex.bbo.{feed}",
        cb=on_bbo,
    )
    await nc.subscribe(
        f"ex.md.{feed}.{quoter}",
        cb=make_md_handler("quoter"),
    )
    await nc.subscribe(
        f"ex.md.{feed}.{taker}",
        cb=make_md_handler("taker"),
    )
    await nc.subscribe(
        f"ex.md.{feed}.{hedger}",
        cb=make_md_handler("hedger"),
    )

    await nc.flush()
    await asyncio.sleep(observe)

    summary = {
        "risk_messages": risk_messages,
        "risk_states": dict(risk_states),
        "risk_transitions": risk_transitions,
        "bbo_messages": bbo_messages,
        "md_events": {
            label: dict(counter)
            for label, counter in md_events.items()
        },
        "quoter_add_sides": dict(quoter_add_sides),
        "first_observed_risk_before_first_observed_new_exposure": (
            first_risk_time is not None
            and (
                first_new_exposure_time is None
                or first_risk_time <= first_new_exposure_time
            )
        ),
    }

    print(
        "RUNTIME_PROBE_SUMMARY=" +
        json.dumps(summary, sort_keys=True),
        flush=True,
    )

    await nc.drain()

asyncio.run(main())
'@

    Push-Location $CandidateDir
    try {
        $probeOutput =
            $probeCode |
                & docker compose `
                    --profile sim `
                    --profile strategy `
                    exec `
                    -T `
                    -e "OBS_SECONDS=$ObservationSeconds" `
                    hedger `
                    python - 2>&1

        $exit = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }

    [System.IO.File]::WriteAllText(
        (Join-Path $EvidenceDir "runtime-passive-probe.log"),
        ($probeOutput -join "`n")
    )

    if ($exit -ne 0) {
        throw (
            "Passive runtime probe failed`n" +
            ($probeOutput -join "`n")
        )
    }

    $summaryLine =
        $probeOutput |
            Where-Object {
                $_.ToString().StartsWith(
                    "RUNTIME_PROBE_SUMMARY="
                )
            } |
            Select-Object -Last 1

    if ($null -eq $summaryLine) {
        throw "Passive runtime probe produced no summary"
    }

    $json =
        $summaryLine.ToString().Substring(
            "RUNTIME_PROBE_SUMMARY=".Length
        )

    $summary = $json | ConvertFrom-Json

    if ([int]$summary.risk_messages -le 0) {
        throw "No desk-risk heartbeat observed"
    }

    if ([int]$summary.bbo_messages -le 0) {
        throw "No BBO messages observed"
    }

    $safeCount = 0
    if (
        $null -ne $summary.risk_states.PSObject.Properties["SAFE"]
    ) {
        $safeCount = [int]$summary.risk_states.SAFE
    }

    if ($safeCount -le 0) {
        throw "No SAFE desk-risk state observed"
    }

    $bidAdds = 0
    $askAdds = 0

    if (
        $null -ne $summary.quoter_add_sides.PSObject.Properties["B"]
    ) {
        $bidAdds = [int]$summary.quoter_add_sides.B
    }

    if (
        $null -ne $summary.quoter_add_sides.PSObject.Properties["S"]
    ) {
        $askAdds = [int]$summary.quoter_add_sides.S
    }

    Write-Host "Observed Quoter BID Adds: $bidAdds"
    Write-Host "Observed Quoter ASK Adds: $askAdds"

    if ($bidAdds -le 0 -or $askAdds -le 0) {
        throw (
            "Could not observe normal two-sided Quoter Adds " +
            "during the runtime window"
        )
    }

    Write-Host (
        "First observed risk before first observed new exposure: " +
        $summary.first_observed_risk_before_first_observed_new_exposure
    )

    Write-Host "Observed risk states:"
    $summary.risk_states |
        ConvertTo-Json -Compress |
        Write-Host

    Write-Host "Observed risk transitions:"
    $summary.risk_transitions |
        ConvertTo-Json -Compress |
        Write-Host

    foreach ($state in @(
        "UNKNOWN",
        "CONTROLLED",
        "EMERGENCY"
    )) {
        $property =
            $summary.risk_states.PSObject.Properties[$state]

        if ($null -eq $property -or [int]$property.Value -eq 0) {
            Write-Host (
                "OBSERVATION_ONLY: $state was not naturally " +
                "reached during this sample-market window."
            )
        }
    }

    return $summary
}

Save-Environment

try {
    Write-Host "=================================================="
    Write-Host "3C STAGE A - FINAL RUNTIME VALIDATION"
    Write-Host "Expected branch: $Branch"
    Write-Host "Expected HEAD:   $ExpectedHead"
    Write-Host "=================================================="
    Write-Host ""

    foreach ($tool in @("git", "docker", "bash")) {
        if ($null -eq (Get-Command $tool -ErrorAction SilentlyContinue)) {
            throw "Required validation tool not found on host: $tool"
        }
    }

    New-Item `
        -ItemType Directory `
        -Force `
        -Path $EvidenceDir |
        Out-Null

    if (Test-Path $StageRoot) {
        throw (
            "Stage-A clean-checkout path already exists: $StageRoot`n" +
            "Do not reuse it. Preserve or remove it manually, then rerun."
        )
    }

    # Local sample validation must not inherit stale grader/experiment overrides.
    foreach ($name in $EnvNames) {
        if ($name -ne "COMPOSE_PROJECT_NAME") {
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
        }
    }

    $env:COMPOSE_PROJECT_NAME = $ComposeProject

    Write-Host "=== A1 CLEAN CHECKOUT ==="

    & git `
        -c core.autocrlf=false `
        clone `
        --branch $Branch `
        --single-branch `
        $RepoUrl `
        $StageRoot

    if ($LASTEXITCODE -ne 0) {
        throw "git clone failed"
    }

    if (-not (Test-Path $CandidateDir)) {
        throw "candidate directory missing from clean checkout"
    }

    $head =
        (
            Invoke-InDirectory `
                -Directory $StageRoot `
                -Command "git" `
                -Arguments @("rev-parse", "HEAD") `
                -Capture
        ) -join ""

    $head = $head.Trim()

    if ($head -ne $ExpectedHead) {
        throw (
            "Clean checkout HEAD mismatch. " +
            "Expected $ExpectedHead, got $head"
        )
    }

    $branchNow =
        (
            Invoke-InDirectory `
                -Directory $StageRoot `
                -Command "git" `
                -Arguments @(
                    "branch",
                    "--show-current"
                ) `
                -Capture
        ) -join ""

    if ($branchNow.Trim() -ne $Branch) {
        throw "Clean checkout branch mismatch"
    }

    $status =
        Invoke-InDirectory `
            -Directory $StageRoot `
            -Command "git" `
            -Arguments @(
                "status",
                "--porcelain"
            ) `
            -Capture

    if (($status -join "").Trim() -ne "") {
        throw "Clean checkout is not clean"
    }

    Write-Host "CLEAN_CHECKOUT_PASS"

    Write-Host ""
    Write-Host "=== A2 LINE ENDINGS + EXECUTABLE MODE ==="

    $trackedRuntimeSources =
        Invoke-InDirectory `
            -Directory $CandidateDir `
            -Command "git" `
            -Arguments @(
                "ls-files",
                "--",
                "*.sh",
                "*.py"
            ) `
            -Capture

    $crlfFiles = @()

    foreach ($relative in $trackedRuntimeSources) {
        $relative = $relative.ToString().Trim()

        if ($relative -eq "") {
            continue
        }

        $fullPath =
            Join-Path $CandidateDir $relative

        if (
            (Test-Path $fullPath) -and
            (Test-ContainsCrlf -Path $fullPath)
        ) {
            $crlfFiles += $relative
        }
    }

    if ($crlfFiles.Count -gt 0) {
        throw (
            "CRLF detected in Linux runtime source(s): " +
            ($crlfFiles -join ", ")
        )
    }

    Assert-ShellExecutableBit -RelativePath "run.sh"
    Assert-ShellExecutableBit -RelativePath "setup.sh"

    Invoke-InDirectory `
        -Directory $StageRoot `
        -Command "git" `
        -Arguments @("diff", "--check")

    Write-Host "LINE_ENDING_AND_MODE_PASS"

    Write-Host ""
    Write-Host "=== A3 HOST / DOCKER PREFLIGHT ==="

    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker engine is not available"
    }

    $existing4222 =
        (& docker ps `
            --format "{{.Names}} {{.Ports}}" 2>&1) |
        Where-Object {
            $_.ToString() -match '(:|\[::\]:)4222->'
        }

    if ($existing4222) {
        throw (
            "Host port 4222 is already published by another container:`n" +
            ($existing4222 -join "`n") +
            "`nStop it manually before Stage A."
        )
    }

    Write-Host "DOCKER_PREFLIGHT_PASS"

    Write-Host ""
    Write-Host "=== A4 START EXACT GRADING ENTRYPOINT ==="

    $runStdout =
        Join-Path $EvidenceDir "run-sh.stdout.log"
    $runStderr =
        Join-Path $EvidenceDir "run-sh.stderr.log"

    $RunProcess =
        Start-Process `
            -FilePath "bash" `
            -ArgumentList @(
                "./run.sh",
                "--sim",
                "--strategy"
            ) `
            -WorkingDirectory $CandidateDir `
            -RedirectStandardOutput $runStdout `
            -RedirectStandardError $runStderr `
            -PassThru

    $ComposeStarted = $true

    $startupDeadline =
        [DateTime]::UtcNow.AddSeconds(
            $StartupTimeoutSeconds
        )

    $allRunning = $false

    while ([DateTime]::UtcNow -lt $startupDeadline) {
        if ($RunProcess.HasExited) {
            throw (
                "run.sh exited before the full stack became healthy. " +
                "See $runStdout and $runStderr"
            )
        }

        $running = Get-RunningServices

        $missing =
            @(
                $RequiredServices |
                    Where-Object {
                        $_ -notin $running
                    }
            )

        if ($missing.Count -eq 0) {
            $allRunning = $true
            break
        }

        Start-Sleep -Seconds 2
    }

    if (-not $allRunning) {
        throw (
            "Full stack did not reach running state. Missing: " +
            ($missing -join ", ")
        )
    }

    Write-Host "ALL_SERVICES_RUNNING_PASS"

    Get-ComposeOutput `
        -Arguments @(
            "--profile", "sim",
            "--profile", "strategy",
            "ps"
        ) |
        Tee-Object `
            -FilePath (
                Join-Path $EvidenceDir "compose-ps.txt"
            ) |
        Write-Host

    Write-Host ""
    Write-Host "=== A5 IMAGE ARCHITECTURE + RESTART HEALTH ==="

    Assert-DeskImageArchitecture
    Assert-NoRestartLoop

    Write-Host "IMAGE_AND_RESTART_HEALTH_PASS"

    Write-Host ""
    Write-Host "=== A6 PASSIVE RUNTIME / RISK / TWO-SIDED OBSERVATION ==="

    $probeSummary = Run-PassiveRuntimeProbe

    Write-Host "PASSIVE_RUNTIME_OBSERVATION_PASS"

    Write-Host ""
    Write-Host "=== A7 PRE-SHUTDOWN LOG SCAN ==="

    $logs = Write-ComposeLogs

    Assert-NoHardRuntimeSignatures `
        -DeskLog $logs.Desk

    Write-Host "RUNTIME_LOG_SCAN_PASS"

    Write-Host ""
    Write-Host "=== A8 PRE-SHUTDOWN FINAL SERVICE HEALTH ==="

    $running = Get-RunningServices

    $missing =
        @(
            $RequiredServices |
                Where-Object {
                    $_ -notin $running
                }
        )

    if ($missing.Count -gt 0) {
        throw (
            "Service stopped during observation: " +
            ($missing -join ", ")
        )
    }

    Assert-NoRestartLoop

    Write-Host "FINAL_SERVICE_HEALTH_PASS"

    $StagePassed = $true
}
finally {
    Write-Host ""
    Write-Host "=== A9 GRACEFUL SHUTDOWN + CLEANUP ==="

    if ($ComposeStarted -and (Test-Path $CandidateDir)) {
        try {
            Push-Location $CandidateDir
            try {
                & docker compose `
                    --profile sim `
                    --profile strategy `
                    down `
                    --remove-orphans
            }
            finally {
                Pop-Location
            }
        }
        catch {
            Write-Warning (
                "docker compose down reported an error: " +
                $_.Exception.Message
            )
        }
    }

    if ($null -ne $RunProcess) {
        try {
            $RunProcess.Refresh()

            if (-not $RunProcess.HasExited) {
                if (-not $RunProcess.WaitForExit(10000)) {
                    Stop-Process `
                        -Id $RunProcess.Id `
                        -Force `
                        -ErrorAction SilentlyContinue
                }
            }
        }
        catch {
            Write-Warning (
                "run.sh process cleanup warning: " +
                $_.Exception.Message
            )
        }
    }

    if (Test-Path $StageRoot) {
        try {
            $postStatus =
                Invoke-InDirectory `
                    -Directory $StageRoot `
                    -Command "git" `
                    -Arguments @(
                        "status",
                        "--porcelain"
                    ) `
                    -Capture

            [System.IO.File]::WriteAllText(
                (Join-Path $EvidenceDir "post-runtime-git-status.txt"),
                ($postStatus -join "`n")
            )

            if (
                $StagePassed -and
                (($postStatus -join "").Trim() -ne "")
            ) {
                $StagePassed = $false
                Write-Error (
                    "Stage A altered the clean checkout. " +
                    "Working tree is no longer clean."
                )
            }
        }
        catch {
            if ($StagePassed) {
                $StagePassed = $false
                Write-Error (
                    "Could not verify post-runtime git status: " +
                    $_.Exception.Message
                )
            }
        }
    }

    Restore-Environment

    Write-Host "CLEANUP_FINISHED"
    Write-Host "Evidence: $EvidenceDir"
}

Write-Host ""
Write-Host "=================================================="

if ($StagePassed) {
    Write-Host "STAGE_A_FINAL_RUNTIME_VALIDATION_PASS"
    Write-Host "Baseline HEAD: $ExpectedHead"
    Write-Host "No production files were modified."
    Write-Host ""
    Write-Host (
        "CONTROLLED / EMERGENCY / UNKNOWN are observation-only " +
        "in this sample-market run; absence is not treated as a failure."
    )
}
else {
    Write-Host "STAGE_A_FINAL_RUNTIME_VALIDATION_FAILED"
    throw (
        "Stage A did not pass. Do not enter Stage B. " +
        "Inspect the evidence directory and fix only confirmed blockers."
    )
}

Write-Host "=================================================="
