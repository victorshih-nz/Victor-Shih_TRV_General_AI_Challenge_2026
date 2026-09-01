package com.trv.quoter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuotePolicyTest {

    private final Metadata metadata =
        new Metadata(
            "AAH6",
            BigDecimal.ONE,
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(100),
            Map.of());

    @Test
    void firstValidBboSeedsWeightedMicroFairAndQuotesTwoSidedWhenProfitable() {
        QuotePolicy policy =
            new QuotePolicy(metadata);

        Bbo bbo =
            bbo(
                100,
                10,
                110,
                10);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo,
                risk(
                    0,
                    HedgerState.SAFE,
                    HedgeDirection.X));

        assertBigDecimal(
            "102.0",
            plan.weightedMid());
        assertBigDecimal(
            "102.0",
            plan.rawFair());
        assertBigDecimal(
            "102.0",
            plan.ewmaFair());
        assertBigDecimal(
            "102.0",
            plan.finalFair());

        assertEquals(
            101L,
            plan.bidPrice());
        assertEquals(
            109L,
            plan.askPrice());
        assertTrue(
            plan.isTwoSided());
    }

    @Test
    void microAdjustmentUsesTopOfBookImbalanceAndIsBoundedByOneTick() {
        QuotePolicy policy =
            new QuotePolicy(metadata);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    30,
                    110,
                    10),
                risk(
                    0,
                    HedgerState.SAFE,
                    HedgeDirection.X));

        // weighted mid 102.0 + imbalance 0.5 tick
        assertBigDecimal(
            "102.5",
            plan.rawFair());
    }

    @Test
    void ewmaAlphaIsPointTwo() {
        QuotePolicy policy =
            new QuotePolicy(metadata);

        QuotePolicy.QuotePlan first =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    110,
                    10),
                risk(
                    0,
                    HedgerState.SAFE,
                    HedgeDirection.X));

        assertBigDecimal(
            "102.0",
            first.ewmaFair());

        QuotePolicy.QuotePlan second =
            policy.evaluate(
                bbo(
                    110,
                    10,
                    120,
                    10),
                risk(
                    0,
                    HedgerState.SAFE,
                    HedgeDirection.X));

        // RawFair = 112.0; EWMA = 0.2*112 + 0.8*102 = 104.
        assertBigDecimal(
            "104.00",
            second.ewmaFair());
    }

    @Test
    void resetMakesNextValidBboSeedFreshEwma() {
        QuotePolicy policy =
            new QuotePolicy(metadata);

        policy.evaluate(
            bbo(
                100,
                10,
                110,
                10),
            risk(
                0,
                HedgerState.SAFE,
                HedgeDirection.X));

        policy.reset();

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    110,
                    10,
                    120,
                    10),
                risk(
                    0,
                    HedgerState.SAFE,
                    HedgeDirection.X));

        assertBigDecimal(
            "112.0",
            plan.ewmaFair());
    }

    @Test
    void fullPositiveInventorySkewsFairDownByOnePointFiveTicks() {
        QuotePolicy policy =
            new QuotePolicy(metadata);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    110,
                    10),
                risk(
                    5,
                    HedgerState.SAFE,
                    HedgeDirection.S));

        assertBigDecimal(
            "100.5",
            plan.finalFair());

        // Position-derived band fails closed to EMERGENCY.
        assertEquals(
            HedgerState.EMERGENCY,
            plan.effectiveRisk());
        assertNull(
            plan.bidPrice());
        assertNull(
            plan.askPrice());
    }

    @Test
    void safeDoesNotInitiateOneSidedPairWhenOnlyAskIsProfitable() {
        QuotePolicy policy =
            new QuotePolicy(metadata);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    101,
                    10),
                risk(
                    0,
                    HedgerState.SAFE,
                    HedgeDirection.X));

        /*
         * Weighted fair = 100.2.
         * BID 100 has only 0.2 tick edge, ASK 101 has 0.8.
         * SAFE therefore opens neither side.
         */
        assertNull(
            plan.bidPrice());
        assertNull(
            plan.askPrice());
    }

    @Test
    void controlledPositivePositionPermitsOnlyRiskReducingAsk() {
        QuotePolicy policy =
            new QuotePolicy(metadata);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    110,
                    10),
                risk(
                    3,
                    HedgerState.CONTROLLED,
                    HedgeDirection.S));

        assertNull(
            plan.bidPrice());
        assertTrue(
            plan.hasAsk());
    }

    @Test
    void controlledNegativePositionPermitsOnlyRiskReducingBid() {
        QuotePolicy policy =
            new QuotePolicy(metadata);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    110,
                    10),
                risk(
                    -3,
                    HedgerState.CONTROLLED,
                    HedgeDirection.B));

        assertTrue(
            plan.hasBid());
        assertNull(
            plan.askPrice());
    }

    @Test
    void controlledDirectionInconsistentWithPositionFailsClosed() {
        QuotePolicy policy =
            new QuotePolicy(metadata);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    110,
                    10),
                risk(
                    3,
                    HedgerState.CONTROLLED,
                    HedgeDirection.B));

        assertNull(
            plan.bidPrice());
        assertNull(
            plan.askPrice());
    }

    @Test
    void emergencyNeverAdds() {
        QuotePolicy policy =
            new QuotePolicy(metadata);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    110,
                    10),
                risk(
                    5,
                    HedgerState.EMERGENCY,
                    HedgeDirection.S));

        assertFalse(
            plan.hasBid());
        assertFalse(
            plan.hasAsk());
    }

    @Test
    void moreSeverePositionBandCannotBeDowngradedByReportedSafeState() {
        QuotePolicy policy =
            new QuotePolicy(metadata);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    110,
                    10),
                risk(
                    3,
                    HedgerState.SAFE,
                    HedgeDirection.S));

        assertEquals(
            HedgerState.CONTROLLED,
            plan.effectiveRisk());
        assertNull(
            plan.bidPrice());
        assertTrue(
            plan.hasAsk());
    }

    @Test
    void keepToleranceIsAtMostOneTick() {
        QuotePolicy policy =
            new QuotePolicy(metadata);

        assertTrue(
            policy.withinKeepTolerance(
                100,
                101));

        assertFalse(
            policy.withinKeepTolerance(
                100,
                102));
    }

    @Test
    void currentOrderProfitabilityCanBeCheckedBeforeKeepTolerance() {
        QuotePolicy policy =
            new QuotePolicy(metadata);

        assertTrue(
            policy.isStillProfitable(
                OrderManager.Side.BID,
                100,
                new BigDecimal("100.5")));

        assertFalse(
            policy.isStillProfitable(
                OrderManager.Side.BID,
                101,
                new BigDecimal("100.5")));
    }

    private Bbo bbo(
            long bid,
            long bidQty,
            long ask,
            long askQty) {

        return new Bbo(
            BigDecimal.valueOf(bid),
            BigDecimal.valueOf(bidQty),
            BigDecimal.valueOf(ask),
            BigDecimal.valueOf(askQty),
            Instant.now());
    }

    private DeskRiskMessage risk(
            int netPosition,
            HedgerState state,
            HedgeDirection direction) {

        return new DeskRiskMessage(
            1L,
            1L,
            "AAH6",
            netPosition,
            4,
            5,
            state,
            direction);
    }

    private void assertBigDecimal(
            String expected,
            BigDecimal actual) {

        assertEquals(
            0,
            new BigDecimal(expected)
                .compareTo(actual));
    }
}
