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
$path = Join-Path $candidateRoot `
    "strategy\src\test\java\com\trv\quoter\QuoteControllerTest.java"

if (-not (Test-Path $path)) {
    throw "Missing $path"
}

$text = [System.IO.File]::ReadAllText((Resolve-Path $path))

$correctName =
    "emergencyCancelsRiskIncreasingBidAndKeepsProfitableReducingAsk"

if ($text.Contains("void $correctName()")) {
    Write-Host "QUOTE_CONTROLLER_3A3_TEST_ALREADY_FIXED"
    Write-Host "No file change needed."
    exit 0
}

if (-not $text.Contains("void emergencyCancelsBothActiveSides()")) {
    Write-Host "=== Emergency-related test methods found ==="
    $matches = [regex]::Matches(
        $text,
        'void\s+\w*[Ee]mergency\w*\s*\(\)'
    )

    foreach ($m in $matches) {
        Write-Host $m.Value
    }

    throw "Legacy method emergencyCancelsBothActiveSides() was not found. No file was changed."
}

$replacement = @'
    @Test
    void emergencyCancelsRiskIncreasingBidAndKeepsProfitableReducingAsk() {
        Fixture f = fixture();

        active(
            f.orders,
            OrderManager.Side.BID,
            BID_ID,
            1,
            100);

        active(
            f.orders,
            OrderManager.Side.ASK,
            ASK_ID,
            1,
            110);

        QuoteController.Decision decision =
            f.controller.decide(
                f.policy.evaluate(
                    bbo(
                        100,
                        10,
                        110,
                        10),
                    risk(
                        5,
                        HedgerState.EMERGENCY,
                        HedgeDirection.S)));

        /*
         * Desk is EMERGENCY long. BID increases long exposure and must be
         * cancelled in the same evaluation cycle.
         */
        assertEquals(
            QuoteController.Action.CANCEL,
            decision.bid().action());

        /*
         * ASK reduces desk long exposure. It may remain resting while it still
         * satisfies the existing profitability and keep rules.
         */
        assertEquals(
            QuoteController.Action.KEEP,
            decision.ask().action());
    }
'@

# Replace the complete legacy method independent of CRLF/LF and whitespace.
$pattern =
    '(?ms)^    @Test\s*\r?\n' +
    '    void\s+emergencyCancelsBothActiveSides\(\)\s*\{' +
    '.*?' +
    '^    \}(?=\r?\n\r?\n    @Test)'

$matches = [regex]::Matches($text, $pattern)

if ($matches.Count -ne 1) {
    throw "Expected one emergencyCancelsBothActiveSides() method; regex found $($matches.Count). No file was changed."
}

$backup = "$path.pre-3A3-fix2"
Copy-Item $path $backup -Force

$text = [regex]::Replace(
    $text,
    $pattern,
    $replacement,
    1
)

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText(
    (Resolve-Path $path),
    $text,
    $utf8NoBom
)

Write-Host "QUOTE_CONTROLLER_3A3_TEST_FIXED"
Write-Host "Production Java unchanged."
Write-Host "Backup: $backup"
