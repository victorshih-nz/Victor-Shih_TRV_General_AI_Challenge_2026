package com.trv.quoter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class QuoteControllerTest {

    private static final String BID_ID =
        "BID00001";
    private static final String ASK_ID =
        "ASK00001";

    private final Metadata metadata =
        new Metadata(
            "AAH6",
            BigDecimal.ONE,
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(100),
            Map.of());

    @Test
    void safeEmptyBookAddsBothOnlyWhenPairIsProfitable() {
        Fixture f = fixture();

        QuoteController.Decision decision =
            f.controller.decide(
                f.policy.evaluate(
                    bbo(
                        100,
                        10,
                        110,
                        10),
                    risk(
                        0,
                        HedgerState.SAFE,
                        HedgeDirection.X)));

        assertEquals(
            QuoteController.Action.ADD,
            decision.bid().action());

        assertEquals(
            101L,
            decision.bid().price());

        assertEquals(
            QuoteController.Action.ADD,
            decision.ask().action());

        assertEquals(
            109L,
            decision.ask().price());
    }

    @Test
    void safeOneSidedEconomicOpportunityDoesNotInitiateNewOneSidedPair() {
        Fixture f = fixture();

        QuoteController.Decision decision =
            f.controller.decide(
                f.policy.evaluate(
                    bbo(
                        100,
                        10,
                        101,
                        10),
                    risk(
                        0,
                        HedgerState.SAFE,
                        HedgeDirection.X)));

        assertEquals(
            QuoteController.Action.WAIT,
            decision.bid().action());

        assertEquals(
            QuoteController.Action.WAIT,
            decision.ask().action());
    }

    @Test
    void safeExistingProfitableSideCanBeKeptEvenWhenNewPairIsSuppressed() {
        Fixture f = fixture();

        active(
            f.orders,
            OrderManager.Side.ASK,
            ASK_ID,
            1,
            101);

        QuoteController.Decision decision =
            f.controller.decide(
                f.policy.evaluate(
                    bbo(
                        100,
                        10,
                        101,
                        10),
                    risk(
                        0,
                        HedgerState.SAFE,
                        HedgeDirection.X)));

        assertEquals(
            QuoteController.Action.WAIT,
            decision.bid().action());

        assertEquals(
            QuoteController.Action.KEEP,
            decision.ask().action());
    }

    @Test
    void activeQuoteOutsideOneTickToleranceIsCancelledNotReplaced() {
        Fixture f = fixture();

        active(
            f.orders,
            OrderManager.Side.BID,
            BID_ID,
            1,
            99);

        QuoteController.Decision decision =
            f.controller.decide(
                f.policy.evaluate(
                    bbo(
                        100,
                        10,
                        110,
                        10),
                    risk(
                        0,
                        HedgerState.SAFE,
                        HedgeDirection.X)));

        assertEquals(
            QuoteController.Action.CANCEL,
            decision.bid().action());

        /*
         * Opposite-side ADD is suppressed in the same evaluator cycle because
         * a cancel is being initiated.
         */
        assertEquals(
            QuoteController.Action.WAIT,
            decision.ask().action());
    }

    @Test
    void activeUnprofitableQuoteCancelsImmediatelyEvenWithinTolerance() {
        Fixture f = fixture();

        active(
            f.orders,
            OrderManager.Side.BID,
            BID_ID,
            1,
            101);

        QuoteController.Decision decision =
            f.controller.decide(
                f.policy.evaluate(
                    bbo(
                        100,
                        10,
                        101,
                        10),
                    risk(
                        0,
                        HedgerState.SAFE,
                        HedgeDirection.X)));

        assertEquals(
            QuoteController.Action.CANCEL,
            decision.bid().action());
    }

    @Test
    void partialFillDoesNotTopUpAndCanKeepReducedActiveOrder() {
        Fixture f = fixture();

        active(
            f.orders,
            OrderManager.Side.ASK,
            ASK_ID,
            2,
            109);

        f.orders.onExecution(
            OrderManager.Side.ASK,
            ASK_ID,
            1);

        QuoteController.Decision decision =
            f.controller.decide(
                f.policy.evaluate(
                    bbo(
                        100,
                        10,
                        110,
                        10),
                    risk(
                        0,
                        HedgerState.SAFE,
                        HedgeDirection.X)));

        assertEquals(
            1,
            f.orders.remainingQty(
                OrderManager.Side.ASK));

        assertEquals(
            QuoteController.Action.KEEP,
            decision.ask().action());
    }

    @Test
    void pendingLifecycleBlocksNewAddOnOtherSide() {
        Fixture f = fixture();

        f.orders.beginAdd(
            OrderManager.Side.BID,
            BID_ID,
            1,
            101);

        QuoteController.Decision decision =
            f.controller.decide(
                f.policy.evaluate(
                    bbo(
                        100,
                        10,
                        110,
                        10),
                    risk(
                        0,
                        HedgerState.SAFE,
                        HedgeDirection.X)));

        assertEquals(
            QuoteController.Action.WAIT,
            decision.bid().action());

        assertEquals(
            QuoteController.Action.WAIT,
            decision.ask().action());
    }

    @Test
    void lifecycleUnknownWaitsForReconciliationAndBlocksOtherAdd() {
        Fixture f = fixture();

        f.orders.beginAdd(
            OrderManager.Side.BID,
            BID_ID,
            1,
            101);

        f.orders.markRequestUncertain(
            OrderManager.Side.BID,
            BID_ID);

        QuoteController.Decision decision =
            f.controller.decide(
                f.policy.evaluate(
                    bbo(
                        100,
                        10,
                        110,
                        10),
                    risk(
                        0,
                        HedgerState.SAFE,
                        HedgeDirection.X)));

        assertEquals(
            QuoteController.Action.WAIT,
            decision.bid().action());

        assertEquals(
            QuoteController.Action.WAIT,
            decision.ask().action());
    }

    @Test
    void controlledPositivePositionCancelsBidBeforeAddingRiskReducingAsk() {
        Fixture f = fixture();

        active(
            f.orders,
            OrderManager.Side.BID,
            BID_ID,
            1,
            100);

        QuoteController.Decision first =
            f.controller.decide(
                f.policy.evaluate(
                    bbo(
                        100,
                        10,
                        110,
                        10),
                    risk(
                        3,
                        HedgerState.CONTROLLED,
                        HedgeDirection.S)));

        assertEquals(
            QuoteController.Action.CANCEL,
            first.bid().action());

        assertEquals(
            QuoteController.Action.WAIT,
            first.ask().action());

        f.orders.onCancelled(
            OrderManager.Side.BID,
            BID_ID);

        QuoteController.Decision second =
            f.controller.decide(
                f.policy.evaluate(
                    bbo(
                        100,
                        10,
                        110,
                        10),
                    risk(
                        3,
                        HedgerState.CONTROLLED,
                        HedgeDirection.S)));

        assertEquals(
            QuoteController.Action.WAIT,
            second.bid().action());

        assertEquals(
            QuoteController.Action.ADD,
            second.ask().action());
    }

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

    @Test
    void authoritativeEmptyIsRequiredBeforeReplacementAddCanAppear() {
        Fixture f = fixture();

        active(
            f.orders,
            OrderManager.Side.BID,
            BID_ID,
            1,
            99);

        QuotePolicy.QuotePlan plan =
            f.policy.evaluate(
                bbo(
                    100,
                    10,
                    110,
                    10),
                risk(
                    0,
                    HedgerState.SAFE,
                    HedgeDirection.X));

        QuoteController.Decision first =
            f.controller.decide(plan);

        assertEquals(
            QuoteController.Action.CANCEL,
            first.bid().action());

        f.orders.beginCancel(
            OrderManager.Side.BID,
            BID_ID);

        QuoteController.Decision whilePending =
            f.controller.decide(plan);

        assertEquals(
            QuoteController.Action.WAIT,
            whilePending.bid().action());

        f.orders.onCancelled(
            OrderManager.Side.BID,
            BID_ID);

        QuoteController.Decision afterEmpty =
            f.controller.decide(plan);

        assertEquals(
            QuoteController.Action.ADD,
            afterEmpty.bid().action());
    }

    @Test
    void automaticEngineDispatchesSafePairWhenRuntimeAndReconciliationAreReady() {
        OrderManager orders =
            new OrderManager();

        AtomicLong now =
            new AtomicLong(
                1_000_000_000L);

        RuntimeState runtime =
            new RuntimeState(
                "AAH6",
                metadata,
                now::get);

        runtime.markConnected();
        runtime.acceptBbo(
            bbo(
                100,
                10,
                110,
                10));

        runtime.acceptRisk(
            risk(
                0,
                HedgerState.SAFE,
                HedgeDirection.X));

        AtomicInteger addCount =
            new AtomicInteger();

        QuoterIntegration.AutomaticQuoteEngine engine =
            new QuoterIntegration.AutomaticQuoteEngine(
                runtime,
                metadata,
                orders,
                () -> true,
                new Object(),
                (side, orderId, quantity, price) -> {
                    addCount.incrementAndGet();
                    orders.beginAdd(
                        side,
                        orderId,
                        quantity,
                        price);
                },
                side -> {
                    throw new AssertionError(
                        "cancel was not expected");
                },
                () -> {
                });

        engine.evaluateOnce();

        assertEquals(
            2,
            addCount.get());

        assertEquals(
            OrderManager.State.PENDING_ADD,
            orders.state(
                OrderManager.Side.BID));

        assertEquals(
            OrderManager.State.PENDING_ADD,
            orders.state(
                OrderManager.Side.ASK));
    }

    @Test
    void automaticEnginePairSecondAddFailureForcesFirstSideIntoRecovery() {
        OrderManager orders =
            new OrderManager();

        AtomicLong now =
            new AtomicLong(
                1_000_000_000L);

        RuntimeState runtime =
            new RuntimeState(
                "AAH6",
                metadata,
                now::get);

        runtime.markConnected();
        runtime.acceptBbo(
            bbo(
                100,
                10,
                110,
                10));

        runtime.acceptRisk(
            risk(
                0,
                HedgerState.SAFE,
                HedgeDirection.X));

        AtomicInteger recoverySignals =
            new AtomicInteger();

        QuoterIntegration.AutomaticQuoteEngine engine =
            new QuoterIntegration.AutomaticQuoteEngine(
                runtime,
                metadata,
                orders,
                () -> true,
                new Object(),
                (side, orderId, quantity, price) -> {
                    if (side == OrderManager.Side.ASK) {
                        throw new IllegalStateException(
                            "simulated second Add failure");
                    }

                    orders.beginAdd(
                        side,
                        orderId,
                        quantity,
                        price);
                },
                side -> {
                },
                recoverySignals::incrementAndGet);

        engine.evaluateOnce();

        assertEquals(
            OrderManager.State.UNKNOWN,
            orders.state(
                OrderManager.Side.BID));

        assertEquals(
            OrderManager.State.EMPTY,
            orders.state(
                OrderManager.Side.ASK));

        assertEquals(
            1,
            recoverySignals.get());
    }

    @Test
    void automaticEngineCancelsActiveQuoteWhenRiskHeartbeatBecomesStale() {
        OrderManager orders =
            new OrderManager();

        active(
            orders,
            OrderManager.Side.BID,
            BID_ID,
            1,
            101);

        AtomicLong now =
            new AtomicLong(
                1_000_000_000L);

        RuntimeState runtime =
            new RuntimeState(
                "AAH6",
                metadata,
                now::get);

        runtime.markConnected();
        runtime.acceptBbo(
            bbo(
                100,
                10,
                110,
                10));

        runtime.acceptRisk(
            risk(
                0,
                HedgerState.SAFE,
                HedgeDirection.X));

        now.addAndGet(
            1_000_000_001L);

        AtomicInteger cancelCount =
            new AtomicInteger();

        QuoterIntegration.AutomaticQuoteEngine engine =
            new QuoterIntegration.AutomaticQuoteEngine(
                runtime,
                metadata,
                orders,
                () -> true,
                new Object(),
                (side, orderId, quantity, price) -> {
                    throw new AssertionError(
                        "Add was not expected");
                },
                side -> {
                    cancelCount.incrementAndGet();

                    orders.beginCancel(
                        side,
                        orders.orderId(side));
                },
                () -> {
                });

        engine.evaluateOnce();

        assertEquals(
            1,
            cancelCount.get());

        assertEquals(
            OrderManager.State.PENDING_CANCEL,
            orders.state(
                OrderManager.Side.BID));
    }

    private Fixture fixture() {
        OrderManager orders =
            new OrderManager();

        QuotePolicy policy =
            new QuotePolicy(metadata);

        return new Fixture(
            orders,
            policy,
            new QuoteController(
                policy,
                orders));
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

    private void active(
            OrderManager orders,
            OrderManager.Side side,
            String orderId,
            int quantity,
            long price) {

        orders.beginAdd(
            side,
            orderId,
            quantity,
            price);

        orders.onResting(
            side,
            orderId);
    }

    private record Fixture(
        OrderManager orders,
        QuotePolicy policy,
        QuoteController controller) {
    }
}
