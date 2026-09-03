$ErrorActionPreference = "Stop"

Set-Location D:\TRV_General_AI_Challenge_2026

$testRoot = "candidate\strategy\src\test\java\com\trv\quoter"
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Patch-LiteralIfNeeded {
    param(
        [string]$Path,
        [string]$Old,
        [string]$New
    )

    if (-not (Test-Path $Path)) {
        throw "Missing expected file: $Path"
    }

    $text = [System.IO.File]::ReadAllText((Resolve-Path $Path))
    $oldCount = [regex]::Matches($text, [regex]::Escape($Old)).Count
    $newCount = [regex]::Matches($text, [regex]::Escape($New)).Count

    if ($oldCount -eq 0 -and $newCount -gt 0) {
        Write-Host "Already patched: $Path"
        return
    }

    if ($oldCount -eq 0) {
        throw "Expected fixture literal not found in $Path"
    }

    if (-not (Test-Path "$Path.pre-3B1-safe2")) {
        Copy-Item $Path "$Path.pre-3B1-safe2"
    }

    $text = $text.Replace($Old, $New)

    [System.IO.File]::WriteAllText(
        (Resolve-Path $Path),
        $text,
        $utf8NoBom
    )

    Write-Host "Patched exact fixture only: $Path"
}

Write-Host "=== SAFE 3B.1 FIXTURE PATCH V2 ==="
Write-Host "Existing unrelated local edits are preserved."

$runtime = "$testRoot\RuntimeStateTest.java"

Patch-LiteralIfNeeded `
    $runtime `
    '"ticksize=0.01"' `
    '"ticksize=0.01 ref_price=150 band=100 min_volume=1 max_volume=100 position_limit=200 max_tps=100"'

Patch-LiteralIfNeeded `
    $runtime `
    '"ticksize=1"' `
    '"ticksize=1 ref_price=105 band=100 min_volume=1 max_volume=100 position_limit=200 max_tps=100"'

$orderPrice = "$testRoot\OrderRequestPriceRegistrationTest.java"

Patch-LiteralIfNeeded `
    $orderPrice `
    '"ticksize=1 ref_price=500 band=100"' `
    '"ticksize=1 ref_price=500 band=100 min_volume=1 max_volume=100 position_limit=200 max_tps=100"'

$foundation = "$testRoot\QuoterFoundationTests.java"

Patch-LiteralIfNeeded `
    $foundation `
    '"ticksize=1 ref_price=1000 band=50"' `
    '"ticksize=1 ref_price=1000 band=50 min_volume=1 max_volume=100 position_limit=200 max_tps=100"'

$foundationText = [System.IO.File]::ReadAllText((Resolve-Path $foundation))

if ($foundationText.Contains("void completeExchangeMetadataParsesIntoTypedContract()")) {
    Write-Host "3B.1 metadata contract tests already present."
} else {
    if (-not (Test-Path "$foundation.pre-3B1-safe2")) {
        Copy-Item $foundation "$foundation.pre-3B1-safe2"
    }

    # Robust anchor: insert immediately before the @Test that owns
    # validConfigMetadataAccepted(), allowing arbitrary whitespace/comments.
    $pattern = '(?ms)([ \t]*@Test[ \t]*\r?\n[ \t]*void[ \t]+validConfigMetadataAccepted[ \t]*\(\)[ \t]*\{)'

    $match = [regex]::Match(
        $foundationText,
        $pattern
    )

    if (-not $match.Success) {
        throw "Could not locate validConfigMetadataAccepted() in QuoterFoundationTests.java"
    }

    $contractTests = @'
    @Test
    void completeExchangeMetadataParsesIntoTypedContract() {
        Metadata metadata =
            Metadata.parse(
                "AAH6",
                "ticksize=0.01 ref_price=100.25 band=20.50 "
                    + "min_volume=2 max_volume=25 "
                    + "position_limit=12 max_tps=40 "
                    + "last_traded_price=101");

        assertEquals(
            new BigDecimal("0.01"),
            metadata.getTickSize());

        assertEquals(
            new BigDecimal("100.25"),
            metadata.getRefPrice());

        assertEquals(
            new BigDecimal("20.50"),
            metadata.getBand());

        assertEquals(2, metadata.getMinVolume());
        assertEquals(25, metadata.getMaxVolume());
        assertEquals(12, metadata.getPositionLimit());
        assertEquals(40, metadata.getMaxTps());

        assertEquals(
            "101",
            metadata.getRawValues()
                .get("last_traded_price"));

        assertTrue(metadata.isValid());
    }

    @Test
    void missingTradingCriticalMetadataFailsClosed() {
        String complete =
            "ticksize=1 ref_price=100 band=20 "
                + "min_volume=1 max_volume=10 "
                + "position_limit=12 max_tps=40";

        for (String key :
                new String[] {
                    "ticksize",
                    "ref_price",
                    "band",
                    "min_volume",
                    "max_volume",
                    "position_limit",
                    "max_tps"
                }) {

            String withoutKey =
                java.util.Arrays.stream(
                    complete.split("\\s+"))
                    .filter(
                        part ->
                            !part.startsWith(
                                key + "="))
                    .collect(
                        java.util.stream.Collectors.joining(" "));

            assertThrows(
                IllegalArgumentException.class,
                () -> Metadata.parse(
                    "AAH6",
                    withoutKey),
                "missing " + key
                    + " must fail closed");
        }
    }

    @Test
    void invalidExchangeLimitRelationshipsFailClosed() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=100 band=20 "
                    + "min_volume=11 max_volume=10 "
                    + "position_limit=12 max_tps=40"));

        assertThrows(
            IllegalArgumentException.class,
            () -> Metadata.parse(
                "AAH6",
                "ticksize=0 ref_price=100 band=20 "
                    + "min_volume=1 max_volume=10 "
                    + "position_limit=12 max_tps=40"));

        assertThrows(
            IllegalArgumentException.class,
            () -> Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=100 band=-1 "
                    + "min_volume=1 max_volume=10 "
                    + "position_limit=12 max_tps=40"));

        assertThrows(
            IllegalArgumentException.class,
            () -> Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=100 band=20 "
                    + "min_volume=0 max_volume=10 "
                    + "position_limit=12 max_tps=40"));

        assertThrows(
            IllegalArgumentException.class,
            () -> Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=100 band=20 "
                    + "min_volume=1 max_volume=10 "
                    + "position_limit=0 max_tps=40"));

        assertThrows(
            IllegalArgumentException.class,
            () -> Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=100 band=20 "
                    + "min_volume=1 max_volume=10 "
                    + "position_limit=12 max_tps=0"));
    }

    @Test
    void integerExchangeLimitsRejectNonIntegerText() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=100 band=20 "
                    + "min_volume=1.5 max_volume=10 "
                    + "position_limit=12 max_tps=40"));

        assertThrows(
            IllegalArgumentException.class,
            () -> Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=100 band=20 "
                    + "min_volume=1 max_volume=10 "
                    + "position_limit=12 max_tps=40.5"));
    }

    @Test
    void metadataUsesExactBigDecimalTickArithmetic() {
        Metadata metadata =
            Metadata.parse(
                "AAH6",
                "ticksize=0.01 ref_price=1 band=2 "
                    + "min_volume=1 max_volume=10 "
                    + "position_limit=12 max_tps=40");

        assertTrue(
            metadata.isPriceOnTick(
                new BigDecimal("1.23")));

        assertFalse(
            metadata.isPriceOnTick(
                new BigDecimal("1.234")));

        /*
         * Protocol does not state that ref_price - band must be positive.
         */
        assertTrue(
            metadata.isPriceWithinBounds(
                new BigDecimal("-0.50")));
    }

'@

    $foundationText =
        $foundationText.Substring(0, $match.Index) `
        + $contractTests `
        + $foundationText.Substring($match.Index)

    [System.IO.File]::WriteAllText(
        (Resolve-Path $foundation),
        $foundationText,
        $utf8NoBom
    )

    Write-Host "Added 3B.1 strict metadata contract tests."
}

$requestClient =
    "$testRoot\QuoterOrderRequestClientTest.java"

Patch-LiteralIfNeeded `
    $requestClient `
    '+ " ref_price=500 band=100");' `
    '+ " ref_price=500 band=100 min_volume=1 max_volume=100 position_limit=200 max_tps=100");'

Write-Host ""
Write-Host "SAFE_TEST_METADATA_FIXTURES_3B1_V2_PATCHED"
Write-Host "No production Java file changed by this script."
Write-Host "Unrelated local test edits were preserved."
