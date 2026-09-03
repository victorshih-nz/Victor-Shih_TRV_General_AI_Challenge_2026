$ErrorActionPreference = "Stop"

Set-Location D:\TRV_General_AI_Challenge_2026

$testRoot = "candidate\strategy\src\test\java\com\trv\quoter"

$files = @(
    "$testRoot\RuntimeStateTest.java",
    "$testRoot\OrderRequestPriceRegistrationTest.java",
    "$testRoot\QuoterFoundationTests.java",
    "$testRoot\QuoterOrderRequestClientTest.java"
)

foreach ($file in $files) {
    if (-not (Test-Path $file)) {
        throw "Missing expected test file: $file"
    }
}

Write-Host "=== CURRENT TARGET TEST STATUS ==="
git status --short -- $files

# These files were not part of 3A. If one is already modified, stop instead of
# overwriting an unexpected local edit.
$unexpected = git status --porcelain -- $files
if ($unexpected) {
    throw "One or more 3B.1 fixture files already have local changes. Refusing to patch over them."
}

foreach ($file in $files) {
    Copy-Item $file "$file.pre-3B1" -Force
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Replace-Exact {
    param(
        [string]$Path,
        [string]$Old,
        [string]$New,
        [int]$ExpectedCount
    )

    $text = [System.IO.File]::ReadAllText((Resolve-Path $Path))
    $count = [regex]::Matches($text, [regex]::Escape($Old)).Count

    if ($count -ne $ExpectedCount) {
        throw "Expected $ExpectedCount occurrences in $Path but found $count for: $Old"
    }

    $text = $text.Replace($Old, $New)
    [System.IO.File]::WriteAllText((Resolve-Path $Path), $text, $utf8NoBom)
}

$runtime = "$testRoot\RuntimeStateTest.java"
Replace-Exact `
    $runtime `
    '"ticksize=0.01"' `
    '"ticksize=0.01 ref_price=150 band=100 min_volume=1 max_volume=100 position_limit=200 max_tps=100"' `
    1

Replace-Exact `
    $runtime `
    '"ticksize=1"' `
    '"ticksize=1 ref_price=105 band=100 min_volume=1 max_volume=100 position_limit=200 max_tps=100"' `
    3

$orderPrice = "$testRoot\OrderRequestPriceRegistrationTest.java"
Replace-Exact `
    $orderPrice `
    '"ticksize=1 ref_price=500 band=100"' `
    '"ticksize=1 ref_price=500 band=100 min_volume=1 max_volume=100 position_limit=200 max_tps=100"' `
    1

$foundation = "$testRoot\QuoterFoundationTests.java"
$text = [System.IO.File]::ReadAllText((Resolve-Path $foundation))

$oldFixture = '"ticksize=1 ref_price=1000 band=50"'
$fixtureCount = [regex]::Matches($text, [regex]::Escape($oldFixture)).Count

if ($fixtureCount -lt 1) {
    throw "Could not find QuoterFoundationTests valid metadata fixture"
}

$text = $text.Replace(
    $oldFixture,
    '"ticksize=1 ref_price=1000 band=50 min_volume=1 max_volume=100 position_limit=200 max_tps=100"'
)

$marker = @'
    @Test
    void validConfigMetadataAccepted() {
'@

if (-not $text.Contains($marker)) {
    throw "Could not find insertion marker in QuoterFoundationTests.java"
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

        assertEquals(
            2,
            metadata.getMinVolume());

        assertEquals(
            25,
            metadata.getMaxVolume());

        assertEquals(
            12,
            metadata.getPositionLimit());

        assertEquals(
            40,
            metadata.getMaxTps());

        assertEquals(
            "101",
            metadata.getRawValues()
                .get("last_traded_price"));

        assertTrue(
            metadata.isValid());
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
         * Do not introduce a sample-market positivity assumption.
         */
        assertTrue(
            metadata.isPriceWithinBounds(
                new BigDecimal("-0.50")));
    }

'@

$text = $text.Replace(
    $marker,
    $contractTests + $marker
)

[System.IO.File]::WriteAllText(
    (Resolve-Path $foundation),
    $text,
    $utf8NoBom
)

$requestClient = "$testRoot\QuoterOrderRequestClientTest.java"
Replace-Exact `
    $requestClient `
    '+ " ref_price=500 band=100");' `
    '+ " ref_price=500 band=100 min_volume=1 max_volume=100 position_limit=200 max_tps=100");' `
    1

Write-Host ""
Write-Host "TEST_METADATA_FIXTURES_3B1_PATCHED"
Write-Host "Backups created with .pre-3B1 suffix."
Write-Host "No production file changed by this script."
