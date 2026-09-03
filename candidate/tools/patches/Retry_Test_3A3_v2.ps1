$ErrorActionPreference = "Stop"

function Resolve-CandidateRoot {
    param([string]$Start)

    $candidates = @(
        $Start,
        (Split-Path -Parent $Start)
    )

    foreach ($candidate in $candidates) {
        if (Test-Path (Join-Path $candidate "strategy\pom.xml")) {
            return (Resolve-Path $candidate).Path
        }

        if (Test-Path (Join-Path $candidate "candidate\strategy\pom.xml")) {
            return (Resolve-Path (Join-Path $candidate "candidate")).Path
        }
    }

    throw "Cannot locate candidate\strategy\pom.xml from script location: $Start"
}

$candidateRoot = Resolve-CandidateRoot $PSScriptRoot
$repoRoot = Split-Path -Parent $candidateRoot
$strategyRoot = Join-Path $candidateRoot "strategy"

Push-Location $strategyRoot

try {
    Write-Host "=== 1. EXACT FAILED TEST CLASS ==="
    mvn -B "-Dtest=QuoteControllerTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "QuoteControllerTest still fails"
    }
    Write-Host "QUOTE_CONTROLLER_TEST_PASS"

    Write-Host ""
    Write-Host "=== 2. FOCUSED 3A.3 ==="
    mvn -B "-Dtest=QuotePolicyTest,QuoterLifecycleIntegrationTest,QuoteControllerTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "Focused 3A.3 tests failed"
    }
    Write-Host "FOCUSED_3A3_PASS"

    Write-Host ""
    Write-Host "=== 3. FULL QUOTER ==="
    mvn -B test
    if ($LASTEXITCODE -ne 0) {
        throw "Full Quoter tests failed"
    }
    Write-Host "FULL_QUOTER_TESTS_PASS"
}
finally {
    Pop-Location
}

Set-Location $repoRoot

Write-Host ""
Write-Host "=== 4. DIFF CHECK ==="
git diff --check
if ($LASTEXITCODE -ne 0) {
    throw "git diff --check failed"
}

Write-Host ""
Write-Host "=== 5. STATUS ==="
git status --short

Write-Host ""
Write-Host "=== 6. STRATEGY DIFF STAT ==="
git diff --stat -- candidate/strategy

Write-Host ""
Write-Host "=============================================="
Write-Host "JOB_3_2A3_RETRY_FINISHED"
Write-Host "No commit created."
Write-Host "No push performed."
Write-Host "=============================================="
