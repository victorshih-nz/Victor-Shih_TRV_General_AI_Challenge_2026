$ErrorActionPreference = "Stop"

Set-Location D:\TRV_General_AI_Challenge_2026\candidate\strategy

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

Set-Location D:\TRV_General_AI_Challenge_2026

Write-Host ""
Write-Host "=== 4. DIFF CHECK / STATUS ==="
git diff --check
if ($LASTEXITCODE -ne 0) {
    throw "git diff --check failed"
}

git status --short
git diff --stat -- candidate/strategy

Write-Host ""
Write-Host "=============================================="
Write-Host "JOB_3_2A3_RETRY_FINISHED"
Write-Host "No commit created."
Write-Host "No push performed."
Write-Host "=============================================="
