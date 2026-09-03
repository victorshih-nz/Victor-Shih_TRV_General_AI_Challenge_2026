$ErrorActionPreference = "Stop"

Set-Location D:\TRV_General_AI_Challenge_2026

$testRoot = "candidate\strategy\src\test\java\com\trv\quoter"
$targets = @(
    "$testRoot\QuotePolicyTest.java",
    "$testRoot\QuoterOrderRequestClientTest.java",
    "$testRoot\QuoterLifecycleIntegrationTest.java"
)

foreach ($file in $targets) {
    if (-not (Test-Path $file)) {
        throw "Missing expected test file: $file"
    }
}

$backupRoot = "D:\TRV_General_AI_Challenge_2026_evidence\3B2-test-backups"
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null

foreach ($file in $targets) {
    Copy-Item `
        $file `
        (Join-Path $backupRoot ([System.IO.Path]::GetFileName($file))) `
        -Force
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Add-TestBeforeFinalBrace {
    param(
        [string]$Path,
        [string]$Marker,
        [string]$TestText
    )

    $resolved = (Resolve-Path $Path).Path
    $text = [System.IO.File]::ReadAllText($resolved)

    if ($text.Contains($Marker)) {
        Write-Host "Already present: $Marker"
        return
    }

    $lastBrace = $text.LastIndexOf("}")
    if ($lastBrace -lt 0) {
        throw "Could not find final class brace in $Path"
    }

    $prefix = $text.Substring(0, $lastBrace).TrimEnd()
    $suffix = $text.Substring($lastBrace)

    $updated =
        $prefix `
        + "`r`n`r`n" `
        + $TestText.Trim() `
        + "`r`n" `
        + $suffix

    [System.IO.File]::WriteAllText(
        $resolved,
        $updated,
        $utf8NoBom
    )

    Write-Host "Added: $Marker"
}

$quotePolicyTest = @'
    @Test
    void normalQuoteQuantityUsesExchangeMinimumVolume() {
        Metadata exchangeMetadata =
            Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=100 band=20 "
                    + "min_volume=5 max_volume=25 "
                    + "position_limit=12 max_tps=40");

        QuotePolicy policy =
            new QuotePolicy(
                exchangeMetadata,
                6,
                12);

        assertEquals(
            5,
            policy.normalQuoteQuantity());
    }
'@

Add-TestBeforeFinalBrace `
    "$testRoot\QuotePolicyTest.java" `
    "normalQuoteQuantityUsesExchangeMinimumVolume" `
    $quotePolicyTest

$orderRequestTest = @'
    @Test
    void addEnforcesExchangeMinAndMaxVolumeBeforeRegistration() {
        OrderManager manager =
            new OrderManager();

        FakeTransport transport =
            new FakeTransport(manager);

        java.util.concurrent.ScheduledExecutorService scheduler =
            java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor();

        Metadata exchangeMetadata =
            Metadata.parse(
                FEED,
                "ticksize=1 ref_price=500 band=100 "
                    + "min_volume=5 max_volume=10 "
                    + "position_limit=12 max_tps=40");

        OrderRequestClient client =
            new OrderRequestClient(
                SENDER,
                FEED,
                exchangeMetadata,
                manager,
                () -> true,
                () -> true,
                transport,
                scheduler,
                java.time.Duration.ofHours(1),
                java.time.Duration.ofHours(1));

        try (client) {
            assertThrows(
                IllegalArgumentException.class,
                () -> client.requestAdd(
                    OrderManager.Side.BID,
                    "BID00001",
                    4,
                    500));

            assertThrows(
                IllegalArgumentException.class,
                () -> client.requestAdd(
                    OrderManager.Side.BID,
                    "BID00001",
                    11,
                    500));

            assertEquals(
                OrderManager.State.EMPTY,
                manager.state(
                    OrderManager.Side.BID));

            assertEquals(
                0,
                transport.calls);

            client.requestAdd(
                OrderManager.Side.BID,
                "BID00001",
                5,
                500);

            assertEquals(
                OrderManager.State.PENDING_ADD,
                manager.state(
                    OrderManager.Side.BID));

            assertEquals(
                1,
                transport.calls);

            assertEquals(
                "QUOTE001 A AAH6 BID00001 B 5 500 L",
                transport.payload());
        }
    }
'@

Add-TestBeforeFinalBrace `
    "$testRoot\QuoterOrderRequestClientTest.java" `
    "addEnforcesExchangeMinAndMaxVolumeBeforeRegistration" `
    $orderRequestTest

$lifecycleTests = @'
    @Test
    void exchangePositionLimitShrinksEffectiveHardAndSoftLimits() {
        QuoterIntegration.LocalRiskLimits configured =
            new QuoterIntegration.LocalRiskLimits(
                6,
                12,
                15);

        Metadata exchangeMetadata =
            Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=100 band=20 "
                    + "min_volume=1 max_volume=10 "
                    + "position_limit=4 max_tps=40");

        configured.validateAgainst(
            exchangeMetadata);

        org.junit.jupiter.api.Assertions.assertEquals(
            4,
            configured.effectiveHardPosition(
                exchangeMetadata));

        org.junit.jupiter.api.Assertions.assertEquals(
            3,
            configured.effectiveSoftPosition(
                exchangeMetadata));
    }

    @Test
    void minimumVolumeAboveEffectiveHardFailsClosed() {
        QuoterIntegration.LocalRiskLimits configured =
            new QuoterIntegration.LocalRiskLimits(
                6,
                12,
                15);

        Metadata exchangeMetadata =
            Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=100 band=20 "
                    + "min_volume=5 max_volume=10 "
                    + "position_limit=3 max_tps=40");

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> configured.validateAgainst(
                exchangeMetadata));
    }

    @Test
    void exchangeHardOneDoesNotWidenRiskAndUsesNarrowestPositiveSoft() {
        QuoterIntegration.LocalRiskLimits configured =
            new QuoterIntegration.LocalRiskLimits(
                6,
                12,
                15);

        Metadata exchangeMetadata =
            Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=100 band=20 "
                    + "min_volume=1 max_volume=10 "
                    + "position_limit=1 max_tps=40");

        configured.validateAgainst(
            exchangeMetadata);

        org.junit.jupiter.api.Assertions.assertEquals(
            1,
            configured.effectiveHardPosition(
                exchangeMetadata));

        org.junit.jupiter.api.Assertions.assertEquals(
            1,
            configured.effectiveSoftPosition(
                exchangeMetadata));
    }

    @Test
    void effectiveHardEnvelopeBlocksCandidateThatWouldExceedExchangeLimit() {
        QuoterIntegration.LocalRiskLimits configured =
            new QuoterIntegration.LocalRiskLimits(
                6,
                12,
                15);

        Metadata exchangeMetadata =
            Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=100 band=20 "
                    + "min_volume=1 max_volume=10 "
                    + "position_limit=4 max_tps=40");

        int effectiveSoft =
            configured.effectiveSoftPosition(
                exchangeMetadata);

        int effectiveHard =
            configured.effectiveHardPosition(
                exchangeMetadata);

        OrderManager manager =
            new OrderManager();

        QuoterIntegration.OwnLifecycleRouter router =
            new QuoterIntegration.OwnLifecycleRouter(
                "QUOTE001",
                manager);

        org.junit.jupiter.api.Assertions.assertTrue(
            router.allowsLocalAdd(
                OrderManager.Side.BID,
                4,
                effectiveSoft,
                effectiveHard));

        org.junit.jupiter.api.Assertions.assertTrue(
            router.allowsLocalAdd(
                OrderManager.Side.ASK,
                4,
                effectiveSoft,
                effectiveHard));

        org.junit.jupiter.api.Assertions.assertFalse(
            router.allowsLocalAdd(
                OrderManager.Side.BID,
                5,
                effectiveSoft,
                effectiveHard));

        org.junit.jupiter.api.Assertions.assertFalse(
            router.allowsLocalAdd(
                OrderManager.Side.ASK,
                5,
                effectiveSoft,
                effectiveHard));
    }
'@

Add-TestBeforeFinalBrace `
    "$testRoot\QuoterLifecycleIntegrationTest.java" `
    "exchangePositionLimitShrinksEffectiveHardAndSoftLimits" `
    $lifecycleTests

Write-Host ""
Write-Host "TEST_3B2_PATCHED"
Write-Host "Backups: $backupRoot"
Write-Host "No new Java test file created."
