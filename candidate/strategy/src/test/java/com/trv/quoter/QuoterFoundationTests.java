package com.trv.quoter;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Production contract tests retained from the original Quoter foundation.
 * Superseded pricing/readiness experiments were retired before submission.
 */
class QuoterFoundationTests {

    private static Metadata validMetadata() {
        return Metadata.parse(
            "AAH6",
            "ticksize=1 ref_price=1000 band=50 "
                + "min_volume=1 max_volume=100 "
                + "position_limit=200 max_tps=100");
    }

    @Test
    void completeExchangeMetadataParsesIntoTypedContract() {
        Metadata m = Metadata.parse(
            "AAH6",
            "ticksize=0.01 ref_price=100.25 band=20.50 "
                + "min_volume=2 max_volume=25 "
                + "position_limit=12 max_tps=40 "
                + "last_traded_price=101");
        assertEquals(new BigDecimal("0.01"), m.getTickSize());
        assertEquals(new BigDecimal("100.25"), m.getRefPrice());
        assertEquals(new BigDecimal("20.50"), m.getBand());
        assertEquals(2, m.getMinVolume());
        assertEquals(25, m.getMaxVolume());
        assertEquals(12, m.getPositionLimit());
        assertEquals(40, m.getMaxTps());
        assertEquals("101", m.getRawValues().get("last_traded_price"));
        assertTrue(m.isValid());
    }

    @Test
    void missingTradingCriticalMetadataFailsClosed() {
        String complete =
            "ticksize=1 ref_price=100 band=20 "
                + "min_volume=1 max_volume=10 "
                + "position_limit=12 max_tps=40";
        for (String key : new String[] {
                "ticksize","ref_price","band","min_volume",
                "max_volume","position_limit","max_tps"}) {
            String withoutKey =
                java.util.Arrays.stream(complete.split("\\s+"))
                    .filter(part -> !part.startsWith(key + "="))
                    .collect(java.util.stream.Collectors.joining(" "));
            assertThrows(
                IllegalArgumentException.class,
                () -> Metadata.parse("AAH6", withoutKey),
                "missing " + key + " must fail closed");
        }
    }

    @Test
    void invalidExchangeLimitRelationshipsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> Metadata.parse(
            "AAH6","ticksize=1 ref_price=100 band=20 min_volume=11 max_volume=10 position_limit=12 max_tps=40"));
        assertThrows(IllegalArgumentException.class, () -> Metadata.parse(
            "AAH6","ticksize=0 ref_price=100 band=20 min_volume=1 max_volume=10 position_limit=12 max_tps=40"));
        assertThrows(IllegalArgumentException.class, () -> Metadata.parse(
            "AAH6","ticksize=1 ref_price=100 band=-1 min_volume=1 max_volume=10 position_limit=12 max_tps=40"));
        assertThrows(IllegalArgumentException.class, () -> Metadata.parse(
            "AAH6","ticksize=1 ref_price=100 band=20 min_volume=0 max_volume=10 position_limit=12 max_tps=40"));
        assertThrows(IllegalArgumentException.class, () -> Metadata.parse(
            "AAH6","ticksize=1 ref_price=100 band=20 min_volume=1 max_volume=10 position_limit=0 max_tps=40"));

        Metadata zero = Metadata.parse(
            "AAH6","ticksize=1 ref_price=100 band=20 min_volume=1 max_volume=10 position_limit=12 max_tps=0");
        assertEquals(0, zero.getMaxTps());

        assertThrows(IllegalArgumentException.class, () -> Metadata.parse(
            "AAH6","ticksize=1 ref_price=100 band=20 min_volume=1 max_volume=10 position_limit=12 max_tps=-1"));
    }

    @Test
    void integerExchangeLimitsRejectNonIntegerText() {
        assertThrows(IllegalArgumentException.class, () -> Metadata.parse(
            "AAH6","ticksize=1 ref_price=100 band=20 min_volume=1.5 max_volume=10 position_limit=12 max_tps=40"));
        assertThrows(IllegalArgumentException.class, () -> Metadata.parse(
            "AAH6","ticksize=1 ref_price=100 band=20 min_volume=1 max_volume=10 position_limit=12 max_tps=40.5"));
    }

    @Test
    void metadataUsesExactBigDecimalTickArithmetic() {
        Metadata m = Metadata.parse(
            "AAH6","ticksize=0.01 ref_price=1 band=2 min_volume=1 max_volume=10 position_limit=12 max_tps=40");
        assertTrue(m.isPriceOnTick(new BigDecimal("1.23")));
        assertFalse(m.isPriceOnTick(new BigDecimal("1.234")));
        assertTrue(m.isPriceWithinBounds(new BigDecimal("-0.50")));
    }

    @Test
    void bboWithNullReceivedAtIsInvalid() {
        Metadata m = validMetadata();
        Bbo b = new Bbo(
            new BigDecimal("1000"),new BigDecimal("9"),
            new BigDecimal("1001"),new BigDecimal("10"),null);
        assertFalse(b.isProtocolStateValid(m));
        assertFalse(b.isValid(m));
    }

    @Test
    void validTwoSidedBbo() {
        Metadata m = validMetadata();
        Bbo b = new Bbo(
            new BigDecimal("1000"),new BigDecimal("9"),
            new BigDecimal("1001"),new BigDecimal("10"),Instant.now());
        assertTrue(b.isProtocolStateValid(m));
        assertTrue(b.isValid(m));
    }

    @Test
    void validProtocolBboLineParses() {
        Metadata m = validMetadata();
        Bbo b = Bbo.parse("1700000000000 AAH6 1000 9 1001 10",m);
        assertNotNull(b);
        assertTrue(b.isProtocolStateValid(m));
        assertTrue(b.isValid(m));
    }

    @Test
    void oneSidedBidBboIsLegitimateButNotTwoSided() {
        Metadata m = validMetadata();
        Bbo b = Bbo.parse("1700000000000 AAH6 1000 9 - 0",m);
        assertTrue(b.isProtocolStateValid(m));
        assertFalse(b.isValid(m));
        assertEquals(new BigDecimal("1000"),b.getBidPrice());
        assertEquals(new BigDecimal("9"),b.getBidQty());
        assertNull(b.getAskPrice());
        assertEquals(BigDecimal.ZERO,b.getAskQty());
    }

    @Test
    void oneSidedAskBboIsLegitimateButNotTwoSided() {
        Metadata m = validMetadata();
        Bbo b = Bbo.parse("1700000000000 AAH6 - 0 1001 10",m);
        assertTrue(b.isProtocolStateValid(m));
        assertFalse(b.isValid(m));
        assertNull(b.getBidPrice());
        assertEquals(BigDecimal.ZERO,b.getBidQty());
        assertEquals(new BigDecimal("1001"),b.getAskPrice());
        assertEquals(new BigDecimal("10"),b.getAskQty());
    }

    @Test
    void fullyEmptyBboIsLegitimateButNotTwoSided() {
        Metadata m = validMetadata();
        Bbo b = Bbo.parse("1700000000000 AAH6 - 0 - 0",m);
        assertTrue(b.isProtocolStateValid(m));
        assertFalse(b.isValid(m));
        assertNull(b.getBidPrice());
        assertNull(b.getAskPrice());
        assertEquals(BigDecimal.ZERO,b.getBidQty());
        assertEquals(BigDecimal.ZERO,b.getAskQty());
    }

    @Test
    void oneSidedBboCannotProduceMidpoint() {
        Metadata m = validMetadata();
        Bbo b = Bbo.parse("1700000000000 AAH6 1000 9 - 0",m);
        assertThrows(IllegalStateException.class,b::midpoint);
    }

    @Test
    void malformedProtocolBboRejected() {
        Metadata m = validMetadata();
        assertThrows(IllegalArgumentException.class, () -> Bbo.parse("abc AAH6 1000 9 1001 10",m));
        assertThrows(IllegalArgumentException.class, () -> Bbo.parse("1700000000000 AAH6 1000.5 9 1001 10",m));
        assertThrows(IllegalArgumentException.class, () -> Bbo.parse("1700000000000 AAH6 1000 9.5 1001 10",m));
        assertThrows(IllegalArgumentException.class, () -> Bbo.parse("1700000000000 AAH6 - 9 1001 10",m));
        assertThrows(IllegalArgumentException.class, () -> Bbo.parse("1700000000000 AAH6 1000 9 1001 -",m));
        assertThrows(IllegalArgumentException.class, () -> Bbo.parse("1700000000000 OTHER 1000 9 1001 10",m));
    }

    @Test
    void malformedEmptySideCombinationsRejected() {
        Metadata m = validMetadata();
        assertThrows(IllegalArgumentException.class, () -> Bbo.parse("1700000000000 AAH6 - 1 1001 10",m));
        assertThrows(IllegalArgumentException.class, () -> Bbo.parse("1700000000000 AAH6 1000 9 - 1",m));
        assertThrows(IllegalArgumentException.class, () -> Bbo.parse("1700000000000 AAH6 1000 0 1001 10",m));
        assertThrows(IllegalArgumentException.class, () -> Bbo.parse("1700000000000 AAH6 1000 9 1001 0",m));
    }

    @Test
    void crossedOrInvalidBboRejected() {
        Metadata m = validMetadata();
        assertFalse(new Bbo(
            new BigDecimal("1001"),new BigDecimal("7"),
            new BigDecimal("1000"),new BigDecimal("10"),Instant.now())
            .isProtocolStateValid(m));
        assertFalse(new Bbo(
            new BigDecimal("1000"),new BigDecimal("0"),
            new BigDecimal("1001"),new BigDecimal("10"),Instant.now())
            .isProtocolStateValid(m));
        assertFalse(new Bbo(
            new BigDecimal("1000"),new BigDecimal("9"),
            null,new BigDecimal("10"),Instant.now())
            .isProtocolStateValid(m));
    }
}
