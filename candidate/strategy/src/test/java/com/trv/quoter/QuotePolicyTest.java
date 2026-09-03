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
            "105.0",
            plan.weightedMid());
        assertBigDecimal(
            "105.0",
            plan.rawFair());
        assertBigDecimal(
            "105.0",
            plan.ewmaFair());
        assertBigDecimal(
            "105.0",
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

        assertBigDecimal(
            "105.5",
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
            "105.0",
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

        assertBigDecimal(
            "107.00",
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
            "115.0",
            plan.ewmaFair());
    }

    @Test
    void fullPositiveOwnInventorySkewsFairDownByOnePointFiveTicks() {
        QuotePolicy policy =
            new QuotePolicy(
                metadata,
                6,
                12);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    110,
                    10),
                risk(
                    0,
                    HedgerState.SAFE,
                    HedgeDirection.X),
                12);

        assertBigDecimal(
            "103.5",
            plan.finalFair());

        /*
         * Local long inventory is already through soft, so BID is suppressed
         * while the risk-reducing ASK remains eligible.
         */
        assertNull(
            plan.bidPrice());
        assertTrue(
            plan.hasAsk());
    }

    @Test
    void combinedDeskNetDoesNotActAsQuoterInventorySkew() {
        QuotePolicy policy =
            new QuotePolicy(
                metadata,
                6,
                12);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    110,
                    10),
                risk(
                    2,
                    HedgerState.SAFE,
                    HedgeDirection.X),
                0);

        assertBigDecimal(
            "105.0",
            plan.finalFair());
    }

    @Test
    void positiveSoftOwnInventorySuppressesRiskIncreasingBid() {
        QuotePolicy policy =
            new QuotePolicy(
                metadata,
                6,
                12);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    110,
                    10),
                risk(
                    0,
                    HedgerState.SAFE,
                    HedgeDirection.X),
                6);

        assertFalse(
            plan.isAllowed(
                OrderManager.Side.BID));
        assertTrue(
            plan.isAllowed(
                OrderManager.Side.ASK));
        assertNull(
            plan.bidPrice());
        assertTrue(
            plan.hasAsk());
    }

    @Test
    void negativeSoftOwnInventorySuppressesRiskIncreasingAsk() {
        QuotePolicy policy =
            new QuotePolicy(
                metadata,
                6,
                12);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    110,
                    10),
                risk(
                    0,
                    HedgerState.SAFE,
                    HedgeDirection.X),
                -6);

        assertTrue(
            plan.isAllowed(
                OrderManager.Side.BID));
        assertFalse(
            plan.isAllowed(
                OrderManager.Side.ASK));
        assertTrue(
            plan.hasBid());
        assertNull(
            plan.askPrice());
    }

    @Test
    void localAndDeskDirectionConflictFailsClosed() {
        QuotePolicy policy =
            new QuotePolicy(
                metadata,
                6,
                12);

        /*
         * Desk is short / asks Quoter not to increase short exposure by
         * selling; CONTROLLED therefore permits BID only.
         * Quoter itself is long at soft and locally permits ASK only.
         * Permission intersection is empty.
         */
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
                    HedgeDirection.B),
                6);

        assertFalse(
            plan.hasBid());
        assertFalse(
            plan.hasAsk());
    }

    @Test
    void safeDoesNotInitiateOneSidedPairWhenOnlyAskIsProfitableAndLocalInventoryNormal() {
        QuotePolicy policy =
            new QuotePolicy(metadata);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    101,
                    30),
                risk(
                    0,
                    HedgerState.SAFE,
                    HedgeDirection.X));

        assertNull(
            plan.bidPrice());
        assertNull(
            plan.askPrice());
    }

    @Test
    void controlledPositiveDeskPositionPermitsOnlyRiskReducingAsk() {
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
    void controlledNegativeDeskPositionPermitsOnlyRiskReducingBid() {
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
    void emergencyPermitsOnlyDeskRiskReducingSideWhenSemanticsAreConsistent() {
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
                    HedgeDirection.S),
                0);

        assertFalse(
            plan.hasBid());
        assertTrue(
            plan.hasAsk());
    }

    @Test
    void reportedSafeDeskStateIsNotReclassifiedFromDeskNetPosition() {
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
                    4,
                    HedgerState.SAFE,
                    HedgeDirection.X),
                0);

        assertEquals(
            HedgerState.SAFE,
            plan.effectiveRisk());
        assertTrue(
            plan.hasBid());
        assertTrue(
            plan.hasAsk());
    }

    @Test
    void safeWithDirectionalSignalFailsClosed() {
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
                    0,
                    HedgerState.SAFE,
                    HedgeDirection.S),
                0);

        assertFalse(
            plan.hasBid());
        assertFalse(
            plan.hasAsk());
    }

    @Test
    void zeroNetControlledFailsClosed() {
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
                    0,
                    HedgerState.CONTROLLED,
                    HedgeDirection.S),
                0);

        assertFalse(
            plan.hasBid());
        assertFalse(
            plan.hasAsk());
    }

    @Test
    void zeroNetEmergencyFailsClosed() {
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
                    0,
                    HedgerState.EMERGENCY,
                    HedgeDirection.B),
                0);

        assertFalse(
            plan.hasBid());
        assertFalse(
            plan.hasAsk());
    }

    @Test
    void emergencyPositiveDeskPositionPermitsOnlyAskWhenLocalRiskAgrees() {
        QuotePolicy policy =
            new QuotePolicy(
                metadata,
                6,
                12);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    110,
                    10),
                risk(
                    4,
                    HedgerState.EMERGENCY,
                    HedgeDirection.S),
                6);

        assertFalse(
            plan.hasBid());
        assertTrue(
            plan.hasAsk());
    }

    @Test
    void emergencyDeskLongConflictsWithLocalShortAndPausesAdds() {
        QuotePolicy policy =
            new QuotePolicy(
                metadata,
                6,
                12);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    110,
                    10),
                risk(
                    4,
                    HedgerState.EMERGENCY,
                    HedgeDirection.S),
                -6);

        assertFalse(
            plan.hasBid());
        assertFalse(
            plan.hasAsk());
    }

    @Test
    void controlledDeskLongConflictsWithLocalShortAndPausesAdds() {
        QuotePolicy policy =
            new QuotePolicy(
                metadata,
                6,
                12);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    110,
                    10),
                risk(
                    4,
                    HedgerState.CONTROLLED,
                    HedgeDirection.S),
                -6);

        assertFalse(
            plan.hasBid());
        assertFalse(
            plan.hasAsk());
    }

    @Test
    void contradictoryControlledDirectionFailsClosed() {
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
                    4,
                    HedgerState.CONTROLLED,
                    HedgeDirection.B),
                0);

        assertFalse(
            plan.hasBid());
        assertFalse(
            plan.hasAsk());
    }

    @Test
    void contradictoryEmergencyDirectionFailsClosed() {
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
                    -4,
                    HedgerState.EMERGENCY,
                    HedgeDirection.S),
                0);

        assertFalse(
            plan.hasBid());
        assertFalse(
            plan.hasAsk());
    }

    @Test
    void unknownNeverAddsRegardlessOfLocalInventory() {
        QuotePolicy policy =
            new QuotePolicy(
                metadata,
                6,
                12);

        QuotePolicy.QuotePlan plan =
            policy.evaluate(
                bbo(
                    100,
                    10,
                    110,
                    10),
                risk(
                    4,
                    HedgerState.UNKNOWN,
                    HedgeDirection.S),
                0);

        assertFalse(
            plan.hasBid());
        assertFalse(
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

    @Test
    void reevaluationOfSameMarketDoesNotAdvanceEwmaButRecomputesOwnInventory() {
        QuotePolicy policy =
            new QuotePolicy(
                metadata,
                6,
                12);

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
                    HedgeDirection.X),
                0,
                true);

        assertBigDecimal(
            "105.0",
            first.ewmaFair());

        QuotePolicy.QuotePlan reevaluated =
            policy.evaluate(
                bbo(
                    110,
                    10,
                    120,
                    10),
                risk(
                    0,
                    HedgerState.SAFE,
                    HedgeDirection.X),
                3,
                false);

        assertBigDecimal(
            "105.0",
            reevaluated.ewmaFair());

        /*
         * own +3 / hard 12 * -1.5 tick = -0.375 tick.
         */
        assertBigDecimal(
            "104.625",
            reevaluated.finalFair());
    }

    @Test
    void genuineNewBboAdvancesEwmaAfterNonAdvancingReevaluation() {
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
                HedgeDirection.X),
            true);

        policy.evaluate(
            bbo(
                110,
                10,
                120,
                10),
            risk(
                0,
                HedgerState.SAFE,
                HedgeDirection.X),
            false);

        QuotePolicy.QuotePlan next =
            policy.evaluate(
                bbo(
                    110,
                    10,
                    120,
                    10),
                risk(
                    0,
                    HedgerState.SAFE,
                    HedgeDirection.X),
                true);

        assertBigDecimal(
            "107.00",
            next.ewmaFair());
    }

    @Test
    void firstUsableBboSeedsAfterResetEvenForNonAdvancingEvaluation() {
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
                HedgeDirection.X),
            true);

        policy.reset();

        QuotePolicy.QuotePlan seeded =
            policy.evaluate(
                bbo(
                    110,
                    10,
                    120,
                    10),
                risk(
                    0,
                    HedgerState.SAFE,
                    HedgeDirection.X),
                false);

        assertBigDecimal(
            "115.0",
            seeded.ewmaFair());
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
}
