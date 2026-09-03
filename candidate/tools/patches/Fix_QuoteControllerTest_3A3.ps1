$ErrorActionPreference = "Stop"

Set-Location D:\TRV_General_AI_Challenge_2026

$path = "candidate\strategy\src\test\java\com\trv\quoter\QuoteControllerTest.java"

if (-not (Test-Path $path)) {
    throw "Missing $path"
}

$text = [System.IO.File]::ReadAllText((Resolve-Path $path))

$old = @'
    @Test
    void emergencyCancelsBothActiveSides() {
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

        assertEquals(
            QuoteController.Action.CANCEL,
            decision.bid().action());

        assertEquals(
            QuoteController.Action.CANCEL,
            decision.ask().action());
    }
'@

$new = @'
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
         * Desk is EMERGENCY long. BID would increase long exposure and must
         * be cancelled in the same evaluation cycle.
         */
        assertEquals(
            QuoteController.Action.CANCEL,
            decision.bid().action());

        /*
         * ASK is desk-risk-reducing. Company v1 permits emergency reducing
         * activity, and this existing ASK still satisfies normal economics,
         * so it may remain resting.
         */
        assertEquals(
            QuoteController.Action.KEEP,
            decision.ask().action());
    }
'@

$count = [regex]::Matches(
    $text,
    [regex]::Escape($old)
).Count

if ($count -ne 1) {
    throw "Expected exactly one legacy emergency test block; found $count"
}

$backup = "$path.pre-3A3-fix1"
Copy-Item $path $backup -Force

$text = $text.Replace($old, $new)

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText(
    (Resolve-Path $path),
    $text,
    $utf8NoBom
)

Write-Host "QUOTE_CONTROLLER_3A3_TEST_FIXED"
Write-Host "Production code unchanged."
Write-Host "Backup: $backup"
