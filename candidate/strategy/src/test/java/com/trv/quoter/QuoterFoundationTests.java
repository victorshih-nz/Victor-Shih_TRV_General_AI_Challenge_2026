package com.trv.quoter;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuoterFoundationTests {
    private static final QuoterConfig DEFAULT_CONFIG = new QuoterConfig(
            "AAH6",
            3000,
            5,
            4,
            2,
            1,
            20,
            0.25
    );

    private static Metadata validMetadata() {
        return Metadata.parse(
            "AAH6",
            "ticksize=1 ref_price=1000 band=50 min_volume=1 max_volume=100 position_limit=200 max_tps=100");
    }

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

        Metadata zeroMaxTps =
            Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=100 band=20 "
                    + "min_volume=1 max_volume=10 "
                    + "position_limit=12 max_tps=0");

        assertEquals(
            0,
            zeroMaxTps.getMaxTps());

        assertThrows(
            IllegalArgumentException.class,
            () -> Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=100 band=20 "
                    + "min_volume=1 max_volume=10 "
                    + "position_limit=12 max_tps=-1"));
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
    @Test
    void validConfigMetadataAccepted() {
        QuoterConfig config = QuoterConfig.fromMap(Map.of(
                "TAKER_FEED", "AAH6",
                "MARKET_DATA_STALE_MS", "3000"
        ));
        Metadata metadata = Metadata.parse(
            "AAH6",
            "ticksize=1 ref_price=1000 band=50 min_volume=1 max_volume=100 position_limit=200 max_tps=100");

        assertEquals("AAH6", config.getFeed());
        assertTrue(metadata.isValid());
    }

    @Test
    void invalidMissingRequiredConfigFailsClosed() {
        assertThrows(
            IllegalArgumentException.class,
            () -> QuoterConfig.fromMap(
                Map.of(
                    "MARKET_DATA_STALE_MS",
                    "3000")));
    }

    @Test
    void invalidNanEwmaAlphaFailsClosed() {
        assertThrows(
            IllegalArgumentException.class,
            () -> QuoterConfig.fromMap(Map.of(
                "TAKER_FEED", "AAH6",
                "EWMA_ALPHA", "NaN"
            )));
    }

    @Test
    void bboWithNullReceivedAtIsInvalid() {
        Metadata metadata = validMetadata();
        Bbo bbo = new Bbo(
            new BigDecimal("1000"),
            new BigDecimal("9"),
            new BigDecimal("1001"),
            new BigDecimal("10"),
            null);

        assertFalse(bbo.isProtocolStateValid(metadata));
        assertFalse(bbo.isValid(metadata));
        assertFalse(
            MarketReadiness.isReady(
                DEFAULT_CONFIG,
                metadata,
                bbo,
                HedgerState.SAFE,
                Instant.now()));
    }

    @Test
    void validTwoSidedBbo() {
        Metadata metadata = validMetadata();
        Bbo bbo = new Bbo(
            new BigDecimal("1000"),
            new BigDecimal("9"),
            new BigDecimal("1001"),
            new BigDecimal("10"),
            Instant.now());

        assertTrue(bbo.isProtocolStateValid(metadata));
        assertTrue(bbo.isValid(metadata));
    }

    @Test
    void validProtocolBboLineParses() {
        Metadata metadata = validMetadata();

        Bbo bbo =
            Bbo.parse(
                "1700000000000 AAH6 1000 9 1001 10",
                metadata);

        assertNotNull(bbo);
        assertTrue(bbo.isProtocolStateValid(metadata));
        assertTrue(bbo.isValid(metadata));
    }

    @Test
    void oneSidedBidBboIsLegitimateButNotQuoteReady() {
        Metadata metadata = validMetadata();

        Bbo bbo =
            Bbo.parse(
                "1700000000000 AAH6 1000 9 - 0",
                metadata);

        assertTrue(bbo.isProtocolStateValid(metadata));
        assertFalse(bbo.isValid(metadata));

        assertEquals(
            new BigDecimal("1000"),
            bbo.getBidPrice());
        assertEquals(
            new BigDecimal("9"),
            bbo.getBidQty());
        assertNull(bbo.getAskPrice());
        assertEquals(
            BigDecimal.ZERO,
            bbo.getAskQty());

        assertFalse(
            MarketReadiness.isReady(
                DEFAULT_CONFIG,
                metadata,
                bbo,
                HedgerState.SAFE,
                Instant.now()));
    }

    @Test
    void oneSidedAskBboIsLegitimateButNotQuoteReady() {
        Metadata metadata = validMetadata();

        Bbo bbo =
            Bbo.parse(
                "1700000000000 AAH6 - 0 1001 10",
                metadata);

        assertTrue(bbo.isProtocolStateValid(metadata));
        assertFalse(bbo.isValid(metadata));

        assertNull(bbo.getBidPrice());
        assertEquals(
            BigDecimal.ZERO,
            bbo.getBidQty());
        assertEquals(
            new BigDecimal("1001"),
            bbo.getAskPrice());
        assertEquals(
            new BigDecimal("10"),
            bbo.getAskQty());

        assertFalse(
            MarketReadiness.isReady(
                DEFAULT_CONFIG,
                metadata,
                bbo,
                HedgerState.SAFE,
                Instant.now()));
    }

    @Test
    void fullyEmptyBboIsLegitimateButNotQuoteReady() {
        Metadata metadata = validMetadata();

        Bbo bbo =
            Bbo.parse(
                "1700000000000 AAH6 - 0 - 0",
                metadata);

        assertTrue(bbo.isProtocolStateValid(metadata));
        assertFalse(bbo.isValid(metadata));

        assertNull(bbo.getBidPrice());
        assertNull(bbo.getAskPrice());
        assertEquals(
            BigDecimal.ZERO,
            bbo.getBidQty());
        assertEquals(
            BigDecimal.ZERO,
            bbo.getAskQty());

        assertFalse(
            MarketReadiness.isReady(
                DEFAULT_CONFIG,
                metadata,
                bbo,
                HedgerState.SAFE,
                Instant.now()));
    }

    @Test
    void oneSidedBboCannotProduceMidpoint() {
        Metadata metadata = validMetadata();

        Bbo bbo =
            Bbo.parse(
                "1700000000000 AAH6 1000 9 - 0",
                metadata);

        assertThrows(
            IllegalStateException.class,
            bbo::midpoint);
    }

    @Test
    void malformedProtocolBboRejected() {
        Metadata metadata = validMetadata();

        assertThrows(
            IllegalArgumentException.class,
            () -> Bbo.parse(
                "abc AAH6 1000 9 1001 10",
                metadata));

        assertThrows(
            IllegalArgumentException.class,
            () -> Bbo.parse(
                "1700000000000 AAH6 1000.5 9 1001 10",
                metadata));

        assertThrows(
            IllegalArgumentException.class,
            () -> Bbo.parse(
                "1700000000000 AAH6 1000 9.5 1001 10",
                metadata));

        assertThrows(
            IllegalArgumentException.class,
            () -> Bbo.parse(
                "1700000000000 AAH6 - 9 1001 10",
                metadata));

        assertThrows(
            IllegalArgumentException.class,
            () -> Bbo.parse(
                "1700000000000 AAH6 1000 9 1001 -",
                metadata));

        assertThrows(
            IllegalArgumentException.class,
            () -> Bbo.parse(
                "1700000000000 OTHER 1000 9 1001 10",
                metadata));
    }

    @Test
    void malformedEmptySideCombinationsRejected() {
        Metadata metadata = validMetadata();

        assertThrows(
            IllegalArgumentException.class,
            () -> Bbo.parse(
                "1700000000000 AAH6 - 1 1001 10",
                metadata));

        assertThrows(
            IllegalArgumentException.class,
            () -> Bbo.parse(
                "1700000000000 AAH6 1000 9 - 1",
                metadata));

        assertThrows(
            IllegalArgumentException.class,
            () -> Bbo.parse(
                "1700000000000 AAH6 1000 0 1001 10",
                metadata));

        assertThrows(
            IllegalArgumentException.class,
            () -> Bbo.parse(
                "1700000000000 AAH6 1000 9 1001 0",
                metadata));
    }

    @Test
    void crossedOrInvalidBboRejected() {
        Metadata metadata = validMetadata();

        assertFalse(
            new Bbo(
                new BigDecimal("1001"),
                new BigDecimal("7"),
                new BigDecimal("1000"),
                new BigDecimal("10"),
                Instant.now())
                .isProtocolStateValid(metadata));

        assertFalse(
            new Bbo(
                new BigDecimal("1000"),
                new BigDecimal("0"),
                new BigDecimal("1001"),
                new BigDecimal("10"),
                Instant.now())
                .isProtocolStateValid(metadata));

        assertFalse(
            new Bbo(
                new BigDecimal("1000"),
                new BigDecimal("9"),
                null,
                new BigDecimal("10"),
                Instant.now())
                .isProtocolStateValid(metadata));
    }

    @Test
    void feedMismatchNotReady() {
        Metadata metadata = Metadata.parse(
            "AAH6",
            "ticksize=1 ref_price=1000 band=50 min_volume=1 max_volume=100 position_limit=200 max_tps=100");

        Bbo fresh = new Bbo(
            new BigDecimal("1000"),
            new BigDecimal("9"),
            new BigDecimal("1001"),
            new BigDecimal("10"),
            Instant.now());

        QuoterConfig mismatched =
            new QuoterConfig(
                "OTHER",
                3000,
                5,
                4,
                2,
                1,
                20,
                0.25);

        assertFalse(
            MarketReadiness.isReady(
                mismatched,
                metadata,
                fresh,
                HedgerState.SAFE,
                Instant.now()));
    }

    @Test
    void validMetadataFreshBboAndNonUnknownHedgerIsReady() {
        Metadata metadata = validMetadata();

        Bbo fresh = new Bbo(
            new BigDecimal("1000"),
            new BigDecimal("9"),
            new BigDecimal("1001"),
            new BigDecimal("10"),
            Instant.now());

        assertTrue(
            MarketReadiness.isReady(
                DEFAULT_CONFIG,
                metadata,
                fresh,
                HedgerState.SAFE,
                Instant.now()));
    }

    @Test
    void unknownHedgerNotReady() {
        Metadata metadata = validMetadata();

        Bbo fresh = new Bbo(
            new BigDecimal("1000"),
            new BigDecimal("9"),
            new BigDecimal("1001"),
            new BigDecimal("10"),
            Instant.now());

        assertFalse(
            MarketReadiness.isReady(
                DEFAULT_CONFIG,
                metadata,
                fresh,
                HedgerState.UNKNOWN,
                Instant.now()));
    }

    @Test
    void midpointCalculation() {
        Bbo bbo = new Bbo(
            new BigDecimal("1000"),
            new BigDecimal("7"),
            new BigDecimal("1002"),
            new BigDecimal("8"),
            Instant.now());

        BigDecimal midpoint =
            bbo.midpoint();

        assertEquals(
            0,
            midpoint.compareTo(
                new BigDecimal("1001.0")));
    }

    @Test
    void symmetricBookProducesZeroMicropriceAdjustment() {
        Metadata metadata = validMetadata();

        Bbo symmetric = new Bbo(
            new BigDecimal("1000"),
            new BigDecimal("10"),
            new BigDecimal("1001"),
            new BigDecimal("10"),
            Instant.now());

        FairValueCalculator calculator =
            new FairValueCalculator();

        BigDecimal adjustment =
            calculator.calculateMicropriceAdjustment(
                symmetric,
                metadata,
                DEFAULT_CONFIG);

        assertEquals(
            0,
            adjustment.compareTo(BigDecimal.ZERO));
    }

    @Test
    void bidHeavyBookShiftsFairValueUpward() {
        Metadata metadata = validMetadata();

        Bbo bidHeavy = new Bbo(
            new BigDecimal("1000"),
            new BigDecimal("40"),
            new BigDecimal("1001"),
            new BigDecimal("10"),
            Instant.now());

        FairValueCalculator calculator =
            new FairValueCalculator();

        BigDecimal fairValue =
            calculator.calculateFairValue(
                bidHeavy,
                metadata,
                DEFAULT_CONFIG);

        assertTrue(
            fairValue.compareTo(
                bidHeavy.midpoint()) > 0);

        assertTrue(
            fairValue.compareTo(
                bidHeavy.getAskPrice()) < 0);
    }

    @Test
    void askHeavyBookShiftsFairValueDownward() {
        Metadata metadata = validMetadata();

        Bbo askHeavy = new Bbo(
            new BigDecimal("1000"),
            new BigDecimal("10"),
            new BigDecimal("1001"),
            new BigDecimal("40"),
            Instant.now());

        FairValueCalculator calculator =
            new FairValueCalculator();

        BigDecimal fairValue =
            calculator.calculateFairValue(
                askHeavy,
                metadata,
                DEFAULT_CONFIG);

        assertTrue(
            fairValue.compareTo(
                askHeavy.midpoint()) < 0);

        assertTrue(
            fairValue.compareTo(
                askHeavy.getBidPrice()) > 0);
    }

    @Test
    void adjustmentRespectsBound() {
        Metadata metadata = validMetadata();

        Bbo extreme = new Bbo(
            new BigDecimal("990"),
            new BigDecimal("1000"),
            new BigDecimal("1010"),
            new BigDecimal("1"),
            Instant.now());

        FairValueCalculator calculator =
            new FairValueCalculator();

        BigDecimal adjustment =
            calculator.calculateMicropriceAdjustment(
                extreme,
                metadata,
                DEFAULT_CONFIG);

        assertEquals(
            0,
            adjustment.compareTo(
                BigDecimal.valueOf(4)));

        BigDecimal fairValue =
            calculator.calculateFairValue(
                extreme,
                metadata,
                DEFAULT_CONFIG);

        assertTrue(
            fairValue.compareTo(
                extreme.getBidPrice()) > 0);

        assertTrue(
            fairValue.compareTo(
                extreme.getAskPrice()) < 0);
    }

    @Test
    void ewmaMovementInTickUnits() {
        Metadata metadata = validMetadata();

        EwmaMovement ewma =
            new EwmaMovement();

        BigDecimal first =
            ewma.update(
                new BigDecimal("1000"),
                metadata.getTickSize(),
                0.5);

        BigDecimal second =
            ewma.update(
                new BigDecimal("1001"),
                metadata.getTickSize(),
                0.5);

        assertEquals(
            new BigDecimal("0.5"),
            second.setScale(
                1,
                java.math.RoundingMode.HALF_UP));

        assertTrue(
            first.compareTo(BigDecimal.ZERO) >= 0);
    }

    @Test
    void adaptiveBandLowerAndUpperBound() {
        Metadata metadata = validMetadata();

        Bbo bbo = new Bbo(
            new BigDecimal("1000"),
            new BigDecimal("9"),
            new BigDecimal("1001"),
            new BigDecimal("10"),
            Instant.now());

        AdaptiveValueBand adaptiveValueBand =
            new AdaptiveValueBand();

        BigDecimal lowerBound =
            adaptiveValueBand
                .calculateValueBandTicks(
                    bbo,
                    metadata,
                    DEFAULT_CONFIG,
                    BigDecimal.ZERO);

        BigDecimal upperBound =
            adaptiveValueBand
                .calculateValueBandTicks(
                    bbo,
                    metadata,
                    DEFAULT_CONFIG,
                    BigDecimal.valueOf(100.0));

        assertTrue(
            lowerBound.compareTo(
                BigDecimal.valueOf(1)) >= 0);

        assertTrue(
            upperBound.compareTo(
                BigDecimal.valueOf(20)) <= 0);
    }

    @Test
    void cheapFairExpensiveOrdering() {
        Metadata metadata = validMetadata();

        Bbo bbo = new Bbo(
            new BigDecimal("1000"),
            new BigDecimal("9"),
            new BigDecimal("1001"),
            new BigDecimal("10"),
            Instant.now());

        AdaptiveValueBand adaptiveValueBand =
            new AdaptiveValueBand();

        BigDecimal fair =
            new FairValueCalculator()
                .calculateFairValue(
                    bbo,
                    metadata,
                    DEFAULT_CONFIG);

        BigDecimal band =
            adaptiveValueBand
                .calculateValueBandTicks(
                    bbo,
                    metadata,
                    DEFAULT_CONFIG,
                    BigDecimal.ZERO)
                .multiply(
                    metadata.getTickSize());

        BigDecimal cheap =
            fair.subtract(band);

        BigDecimal expensive =
            fair.add(band);

        assertTrue(cheap.compareTo(fair) < 0);
        assertTrue(fair.compareTo(expensive) < 0);
    }

    @Test
    void cheapSignalProducesPositiveValuationAdjustment() {
        assertTrue(
            ValuationSignal.CHEAP
                .valuationAdjustmentTicks(
                    DEFAULT_CONFIG)
                .compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void fairSignalProducesZeroValuationAdjustment() {
        assertEquals(
            BigDecimal.ZERO,
            ValuationSignal.FAIR
                .valuationAdjustmentTicks(
                    DEFAULT_CONFIG));
    }

    @Test
    void expensiveSignalProducesNegativeValuationAdjustment() {
        assertTrue(
            ValuationSignal.EXPENSIVE
                .valuationAdjustmentTicks(
                    DEFAULT_CONFIG)
                .compareTo(BigDecimal.ZERO) < 0);
    }

    @Test
    void oldBboRemainsReadyWhileStateIsOtherwiseValid() {
        Metadata metadata = validMetadata();

        Bbo old = new Bbo(
            new BigDecimal("1000"),
            new BigDecimal("9"),
            new BigDecimal("1001"),
            new BigDecimal("10"),
            Instant.now().minusSeconds(60));

        assertTrue(
            MarketReadiness.isReady(
                DEFAULT_CONFIG,
                metadata,
                old,
                HedgerState.SAFE,
                Instant.now()));
    }

    @Test
    void bboReceivedAtDoesNotControlReadiness() {
        Metadata metadata = validMetadata();

        Bbo future = new Bbo(
            new BigDecimal("1000"),
            new BigDecimal("9"),
            new BigDecimal("1001"),
            new BigDecimal("10"),
            Instant.now().plusSeconds(60));

        assertTrue(
            MarketReadiness.isReady(
                DEFAULT_CONFIG,
                metadata,
                future,
                HedgerState.SAFE,
                Instant.now()));
    }
}
