package com.trv.quoter;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuoterLifecycleIntegrationTest {

    private static final String SENDER = "QUOTE001";

    @Test
    void addEventActivatesCurrentSlotWithoutUsingAVolumeAsRemainingQty() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        manager.beginAdd(
            OrderManager.Side.BID,
            "BID00001",
            10, 100L);

        router.accept(md(
            "100 A QUOTE001:BID00001 B 999 500"));

        assertEquals(
            OrderManager.State.ACTIVE,
            manager.state(OrderManager.Side.BID));

        assertEquals(
            10,
            manager.remainingQty(OrderManager.Side.BID));

        assertEquals(
            0,
            router.ownPosition());
    }

    @Test
    void tBuyUsesRawAggressorSideAndIncreasesOwnInventory() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        manager.beginAdd(
            OrderManager.Side.BID,
            "BID00001",
            10, 100L);

        router.accept(md(
            "100 A QUOTE001:BID00001 B 10 500"));

        router.accept(md(
            "101 T QUOTE001:BID00001 "
                + "OTHER001:ASK00001 3 500 M1 B"));

        assertEquals(
            OrderManager.State.ACTIVE,
            manager.state(OrderManager.Side.BID));

        assertEquals(
            7,
            manager.remainingQty(OrderManager.Side.BID));

        assertEquals(
            3,
            router.ownPosition());
    }

    @Test
    void tSellUsesRawAggressorSideAndDecreasesOwnInventory() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        manager.beginAdd(
            OrderManager.Side.ASK,
            "ASK00002",
            10, 100L);

        router.accept(md(
            "100 A QUOTE001:ASK00002 S 10 500"));

        router.accept(md(
            "101 T QUOTE001:ASK00002 "
                + "OTHER001:BID00002 3 500 M1S S"));

        assertEquals(
            OrderManager.State.ACTIVE,
            manager.state(OrderManager.Side.ASK));

        assertEquals(
            7,
            manager.remainingQty(OrderManager.Side.ASK));

        assertEquals(
            -3,
            router.ownPosition());
    }

    @Test
    void eBuyAggressorMeansRestingSellAndDecreasesOwnInventory() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        manager.beginAdd(
            OrderManager.Side.ASK,
            "ASK00001",
            10, 100L);

        router.accept(md(
            "100 A QUOTE001:ASK00001 S 10 500"));

        router.accept(md(
            "101 E OTHER001:BUY00001 "
                + "QUOTE001:ASK00001 4 500 M2 B"));

        assertEquals(
            OrderManager.State.ACTIVE,
            manager.state(OrderManager.Side.ASK));

        assertEquals(
            6,
            manager.remainingQty(OrderManager.Side.ASK));

        assertEquals(
            -4,
            router.ownPosition());
    }

    @Test
    void eSellAggressorMeansRestingBuyAndIncreasesOwnInventory() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        manager.beginAdd(
            OrderManager.Side.BID,
            "BID00002",
            10, 100L);

        router.accept(md(
            "100 A QUOTE001:BID00002 B 10 500"));

        router.accept(md(
            "101 E OTHER001:SELL0001 "
                + "QUOTE001:BID00002 4 500 M2S S"));

        assertEquals(
            OrderManager.State.ACTIVE,
            manager.state(OrderManager.Side.BID));

        assertEquals(
            6,
            manager.remainingQty(OrderManager.Side.BID));

        assertEquals(
            4,
            router.ownPosition());
    }

    @Test
    void fullExecutionBeforeLateAEndsEmptyAndDoesNotResurrect() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        manager.beginAdd(
            OrderManager.Side.BID,
            "BID00001",
            3, 100L);

        router.accept(md(
            "100 T QUOTE001:BID00001 "
                + "OTHER001:ASK00001 3 500 M3 B"));

        assertEquals(
            OrderManager.State.EMPTY,
            manager.state(OrderManager.Side.BID));

        assertEquals(
            3,
            router.ownPosition());

        router.accept(md(
            "100 A QUOTE001:BID00001 B 3 500"));

        assertEquals(
            OrderManager.State.EMPTY,
            manager.state(OrderManager.Side.BID));

        assertEquals(
            3,
            router.ownPosition());
    }

    @Test
    void duplicatePartialExecutionIsIgnoredForLifecycleAndInventory() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        manager.beginAdd(
            OrderManager.Side.BID,
            "BID00001",
            10, 100L);

        router.accept(md(
            "100 A QUOTE001:BID00001 B 10 500"));

        byte[] event = md(
            "101 T QUOTE001:BID00001 "
                + "OTHER001:ASK00001 3 500 M4 B");

        router.accept(event);
        router.accept(event);

        assertEquals(
            7,
            manager.remainingQty(OrderManager.Side.BID));

        assertEquals(
            3,
            router.ownPosition());
    }

    @Test
    void multipleDistinctPartialExecutionsAccumulateLifecycleAndInventory() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        manager.beginAdd(
            OrderManager.Side.BID,
            "BID00001",
            10, 100L);

        router.accept(md(
            "100 A QUOTE001:BID00001 B 10 500"));

        router.accept(md(
            "101 T QUOTE001:BID00001 "
                + "OTHER001:ASK00001 3 500 M5 B"));

        router.accept(md(
            "102 T QUOTE001:BID00001 "
                + "OTHER001:ASK00002 2 500 M6 B"));

        assertEquals(
            5,
            manager.remainingQty(OrderManager.Side.BID));

        assertEquals(
            5,
            router.ownPosition());
    }

    @Test
    void eventTypeIsPartOfCompanyDedupAndSelfTradeNetsInventoryToZero() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        manager.beginAdd(
            OrderManager.Side.BID,
            "BID00001",
            5, 100L);

        manager.beginAdd(
            OrderManager.Side.ASK,
            "ASK00001",
            5, 100L);

        router.accept(md(
            "100 A QUOTE001:BID00001 B 5 500"));

        router.accept(md(
            "100 A QUOTE001:ASK00001 S 5 500"));

        String trade =
            "101 %s QUOTE001:BID00001 "
                + "QUOTE001:ASK00001 2 500 M7 B";

        router.accept(md(
            String.format(trade, "T")));

        router.accept(md(
            String.format(trade, "E")));

        assertEquals(
            3,
            manager.remainingQty(OrderManager.Side.BID));

        assertEquals(
            3,
            manager.remainingQty(OrderManager.Side.ASK));

        assertEquals(
            0,
            router.ownPosition());
    }

    @Test
    void eventTimestampIsPartOfCompanyV1AccountingDedupKey() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        router.accept(md(
            "101 T QUOTE001:OLD00001 "
                + "OTHER001:ASK00001 1 500 SAMEKEY B"));

        router.accept(md(
            "102 T QUOTE001:OLD00001 "
                + "OTHER001:ASK00001 1 500 SAMEKEY B"));

        assertEquals(
            2,
            router.ownPosition());
    }

    @Test
    void currentCancelClearsSlot() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        manager.beginAdd(
            OrderManager.Side.ASK,
            "ASK00001",
            10, 100L);

        router.accept(md(
            "100 A QUOTE001:ASK00001 S 10 500"));

        manager.beginCancel(
            OrderManager.Side.ASK,
            "ASK00001");

        router.accept(md(
            "101 C QUOTE001:ASK00001"));

        assertEquals(
            OrderManager.State.EMPTY,
            manager.state(OrderManager.Side.ASK));

        assertEquals(
            0,
            router.ownPosition());
    }

    @Test
    void validLateExecutionUpdatesInventoryWithoutChangingCurrentLifecycle() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        manager.beginAdd(
            OrderManager.Side.BID,
            "BIDCUR01",
            10, 100L);

        router.accept(md(
            "101 T QUOTE001:OLD00001 "
                + "OTHER001:ASK00001 2 500 M8 B"));

        assertEquals(
            2,
            router.ownPosition());

        assertEquals(
            OrderManager.State.PENDING_ADD,
            manager.state(OrderManager.Side.BID));

        assertEquals(
            10,
            manager.remainingQty(OrderManager.Side.BID));

        assertTrue(manager.isReconciled());
    }

    @Test
    void boundedAccountingDedupEvictsOldestInsteadOfStoppingAccounting() {
        OrderManager manager = new OrderManager();

        QuoterIntegration.OwnLifecycleRouter router =
            new QuoterIntegration.OwnLifecycleRouter(
                SENDER,
                manager,
                2);

        router.accept(md(
            "101 T QUOTE001:OLD00001 "
                + "OTHER001:ASK00001 1 500 LRU1 B"));

        router.accept(md(
            "102 T QUOTE001:OLD00002 "
                + "OTHER001:ASK00002 1 500 LRU2 B"));

        router.accept(md(
            "103 T QUOTE001:OLD00003 "
                + "OTHER001:ASK00003 1 500 LRU3 B"));

        assertEquals(
            3,
            router.ownPosition());

        router.accept(md(
            "103 T QUOTE001:OLD00003 "
                + "OTHER001:ASK00003 1 500 LRU3 B"));

        assertEquals(
            3,
            router.ownPosition());
    }

    @Test
    void reconciliationLifecycleDedupClearDoesNotClearInventoryDedup() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        byte[] execution = md(
            "101 T QUOTE001:OLD00001 "
                + "OTHER001:ASK00001 2 500 RECLEAR B");

        router.accept(execution);

        assertEquals(
            2,
            router.ownPosition());

        router.clearExecutionDedupForReconciledEpoch();

        router.accept(execution);

        assertEquals(
            2,
            router.ownPosition());
    }

    @Test
    void malformedOwnLifecycleEventMarksAllOccupiedSlotsUnknown() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        manager.beginAdd(
            OrderManager.Side.BID,
            "BID00001",
            10, 100L);

        manager.beginAdd(
            OrderManager.Side.ASK,
            "ASK00001",
            10, 100L);

        router.accept(md(
            "not-a-valid-event"));

        assertEquals(
            OrderManager.State.UNKNOWN,
            manager.state(OrderManager.Side.BID));

        assertEquals(
            OrderManager.State.UNKNOWN,
            manager.state(OrderManager.Side.ASK));

        assertEquals(
            0,
            router.ownPosition());
    }

    @Test
    void semanticSideConflictMarksOccupiedSlotUnknown() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        manager.beginAdd(
            OrderManager.Side.ASK,
            "ASK00001",
            10, 100L);

        router.accept(md(
            "100 A QUOTE001:ASK00001 B 10 500"));

        assertEquals(
            OrderManager.State.UNKNOWN,
            manager.state(OrderManager.Side.ASK));

        assertEquals(
            0,
            router.ownPosition());
    }

    @Test
    void trustLossMarksEveryOccupiedSlotUnknown() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        manager.beginAdd(
            OrderManager.Side.BID,
            "BID00001",
            10, 100L);

        manager.beginAdd(
            OrderManager.Side.ASK,
            "ASK00001",
            10, 100L);

        router.accept(md(
            "100 A QUOTE001:BID00001 B 10 500"));

        manager.beginCancel(
            OrderManager.Side.BID,
            "BID00001");

        router.markUnknownOnTrustLoss();

        assertEquals(
            OrderManager.State.UNKNOWN,
            manager.state(OrderManager.Side.BID));

        assertEquals(
            OrderManager.State.UNKNOWN,
            manager.state(OrderManager.Side.ASK));
    }

    @Test
    void lifecycleDedupCapacityExhaustionStillFailsLifecycleClosed() {
        OrderManager manager = new OrderManager();

        QuoterIntegration.OwnLifecycleRouter router =
            new QuoterIntegration.OwnLifecycleRouter(
                SENDER,
                manager,
                1);

        manager.beginAdd(
            OrderManager.Side.BID,
            "BID00001",
            10, 100L);

        router.accept(md(
            "100 A QUOTE001:BID00001 B 10 500"));

        router.accept(md(
            "101 T QUOTE001:BID00001 "
                + "OTHER001:ASK00001 1 500 M9 B"));

        assertEquals(
            9,
            manager.remainingQty(OrderManager.Side.BID));

        assertEquals(
            1,
            router.ownPosition());

        router.accept(md(
            "102 T QUOTE001:BID00001 "
                + "OTHER001:ASK00002 1 500 M10 B"));

        assertEquals(
            OrderManager.State.UNKNOWN,
            manager.state(OrderManager.Side.BID));

        assertEquals(
            9,
            manager.remainingQty(OrderManager.Side.BID));

        assertEquals(
            2,
            router.ownPosition());
    }

    @Test
    void tThatDoesNotTrackOwnIncomingOrderFailsClosedBeforeInventoryMutation() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        manager.beginAdd(
            OrderManager.Side.BID,
            "BID00001",
            10, 100L);

        router.accept(md(
            "101 T OTHER001:BUY00001 "
                + "QUOTE001:BID00001 1 500 M11 B"));

        assertEquals(
            OrderManager.State.UNKNOWN,
            manager.state(OrderManager.Side.BID));

        assertEquals(
            0,
            router.ownPosition());
    }

    @Test
    void localRiskLimitsDefaultsMatchCompanyV1() {
        QuoterIntegration.LocalRiskLimits limits =
            QuoterIntegration.LocalRiskLimits.fromMap(
                Map.of());

        assertEquals(
            6,
            limits.softPosition());
        assertEquals(
            12,
            limits.hardPosition());
        assertEquals(
            15,
            limits.deskHardPosition());
    }

    @Test
    void localRiskLimitsAllowQuoterHardEqualToDeskHard() {
        QuoterIntegration.LocalRiskLimits limits =
            QuoterIntegration.LocalRiskLimits.fromMap(
                Map.of(
                    "QUOTER_SOFT_POS", "6",
                    "QUOTER_HARD_POS", "15",
                    "DESK_HARD_POS", "15"));

        assertEquals(
            15,
            limits.hardPosition());
        assertEquals(
            15,
            limits.deskHardPosition());
    }

    @Test
    void localRiskLimitsRejectInvalidRelationshipsAndValues() {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                QuoterIntegration.LocalRiskLimits.fromMap(
                    Map.of(
                        "QUOTER_SOFT_POS", "0")));

        assertThrows(
            IllegalArgumentException.class,
            () ->
                QuoterIntegration.LocalRiskLimits.fromMap(
                    Map.of(
                        "QUOTER_SOFT_POS", "6",
                        "QUOTER_HARD_POS", "6")));

        assertThrows(
            IllegalArgumentException.class,
            () ->
                QuoterIntegration.LocalRiskLimits.fromMap(
                    Map.of(
                        "QUOTER_SOFT_POS", "6",
                        "QUOTER_HARD_POS", "16",
                        "DESK_HARD_POS", "15")));

        assertThrows(
            IllegalArgumentException.class,
            () ->
                QuoterIntegration.LocalRiskLimits.fromMap(
                    Map.of(
                        "QUOTER_HARD_POS", "not-an-int")));
    }

    @Test
    void localSoftBoundaryBlocksRiskIncreasingSideUsingFreshOwnPosition() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        router.accept(md(
            "101 T QUOTE001:OLD00001 "
                + "OTHER001:ASK00001 6 500 SOFT1 B"));

        assertFalse(
            router.allowsLocalAdd(
                OrderManager.Side.BID,
                1,
                6,
                12));

        assertTrue(
            router.allowsLocalAdd(
                OrderManager.Side.ASK,
                1,
                6,
                12));
    }

    @Test
    void hardEnvelopeIncludesExistingBidExposureAndCandidate() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        router.accept(md(
            "101 T QUOTE001:OLD00001 "
                + "OTHER001:ASK00001 5 500 HARD1 B"));

        manager.beginAdd(
            OrderManager.Side.BID,
            "BIDCUR01",
            7,
            100L);

        /*
         * Existing upper bound = own 5 + pending BID 7 = +12.
         * A new ASK 1 does not increase the upper bound and stays inside both
         * hard bounds.
         */
        assertTrue(
            router.allowsLocalAdd(
                OrderManager.Side.ASK,
                1,
                6,
                12));

        OrderManager manager2 = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router2 =
            router(manager2);

        router2.accept(md(
            "101 T QUOTE001:OLD00001 "
                + "OTHER001:ASK00001 5 500 HARD2 B"));

        manager2.beginAdd(
            OrderManager.Side.BID,
            "BIDCUR02",
            8,
            100L);

        /*
         * Existing upper bound = +13, so no new Add is admitted while the
         * currently known exposure envelope is outside local hard.
         */
        assertFalse(
            router2.allowsLocalAdd(
                OrderManager.Side.ASK,
                1,
                6,
                12));
    }

    @Test
    void hardEnvelopeAllowsBoundaryButBlocksCandidateOvershoot() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        router.accept(md(
            "101 T QUOTE001:OLD00001 "
                + "OTHER001:ASK00001 5 500 HARD3 B"));

        /*
         * own +5, candidate BID 7 => +12 exactly: allowed.
         */
        assertTrue(
            router.allowsLocalAdd(
                OrderManager.Side.BID,
                7,
                6,
                12));

        /*
         * own +5, candidate BID 8 => +13: blocked.
         */
        assertFalse(
            router.allowsLocalAdd(
                OrderManager.Side.BID,
                8,
                6,
                12));
    }

    @Test
    void hardEnvelopeChecksNegativeSideSymmetrically() {
        OrderManager manager = new OrderManager();
        QuoterIntegration.OwnLifecycleRouter router =
            router(manager);

        router.accept(md(
            "101 T QUOTE001:OLD00001 "
                + "OTHER001:BID00001 5 500 HARD4 S"));

        assertTrue(
            router.allowsLocalAdd(
                OrderManager.Side.ASK,
                7,
                6,
                12));

        assertFalse(
            router.allowsLocalAdd(
                OrderManager.Side.ASK,
                8,
                6,
                12));
    }

    @Test
    void lateRestingAckAfterEmergencyTriggersImmediateRiskIncreasingCancel() {
        OrderManager manager =
            new OrderManager();

        Metadata metadata =
            new Metadata(
                "AAH6",
                BigDecimal.ONE,
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(100),
                Map.of());

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
            new Bbo(
                BigDecimal.valueOf(100),
                BigDecimal.TEN,
                BigDecimal.valueOf(110),
                BigDecimal.TEN,
                Instant.now()));

        runtime.acceptRisk(
            new DeskRiskMessage(
                1L,
                1L,
                "AAH6",
                0,
                6,
                15,
                HedgerState.SAFE,
                HedgeDirection.X));

        AtomicInteger cancelCount =
            new AtomicInteger();

        AtomicReference<QuoterIntegration.AutomaticQuoteEngine>
            engineRef =
                new AtomicReference<>();

        QuoterIntegration.OwnLifecycleRouter router =
            new QuoterIntegration.OwnLifecycleRouter(
                SENDER,
                manager,
                4096,
                () -> {
                    QuoterIntegration.AutomaticQuoteEngine engine =
                        engineRef.get();

                    if (engine != null) {
                        engine.evaluateOnce();
                    }
                });

        QuoterIntegration.AutomaticQuoteEngine engine =
            new QuoterIntegration.AutomaticQuoteEngine(
                runtime,
                metadata,
                manager,
                router::ownPosition,
                6,
                12,
                () -> true,
                new Object(),
                (side, orderId, quantity, price) -> {
                    throw new AssertionError(
                        "new Add was not expected");
                },
                side -> {
                    cancelCount.incrementAndGet();
                    manager.beginCancel(
                        side,
                        manager.orderId(side));
                },
                () -> {
                });

        engineRef.set(engine);

        manager.beginAdd(
            OrderManager.Side.BID,
            "BIDEM001",
            1,
            101L);

        runtime.acceptRisk(
            new DeskRiskMessage(
                2L,
                2L,
                "AAH6",
                4,
                6,
                15,
                HedgerState.EMERGENCY,
                HedgeDirection.S));

        /*
         * The Add already crossed the request boundary earlier. Authoritative A
         * arrives only after the desk has become EMERGENCY long.
         */
        router.accept(md(
            "200 A QUOTE001:BIDEM001 B 1 101"));

        assertEquals(
            1,
            cancelCount.get());

        assertEquals(
            OrderManager.State.PENDING_CANCEL,
            manager.state(
                OrderManager.Side.BID));
    }

    @Test
    void lateRestingAckAfterUnknownTriggersImmediateCancel() {
        OrderManager manager =
            new OrderManager();

        Metadata metadata =
            new Metadata(
                "AAH6",
                BigDecimal.ONE,
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(100),
                Map.of());

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
            new Bbo(
                BigDecimal.valueOf(100),
                BigDecimal.TEN,
                BigDecimal.valueOf(110),
                BigDecimal.TEN,
                Instant.now()));

        runtime.acceptRisk(
            new DeskRiskMessage(
                1L,
                1L,
                "AAH6",
                0,
                6,
                15,
                HedgerState.SAFE,
                HedgeDirection.X));

        AtomicInteger cancelCount =
            new AtomicInteger();

        AtomicReference<QuoterIntegration.AutomaticQuoteEngine>
            engineRef =
                new AtomicReference<>();

        QuoterIntegration.OwnLifecycleRouter router =
            new QuoterIntegration.OwnLifecycleRouter(
                SENDER,
                manager,
                4096,
                () -> {
                    QuoterIntegration.AutomaticQuoteEngine engine =
                        engineRef.get();

                    if (engine != null) {
                        engine.evaluateOnce();
                    }
                });

        QuoterIntegration.AutomaticQuoteEngine engine =
            new QuoterIntegration.AutomaticQuoteEngine(
                runtime,
                metadata,
                manager,
                router::ownPosition,
                6,
                12,
                () -> true,
                new Object(),
                (side, orderId, quantity, price) -> {
                    throw new AssertionError(
                        "new Add was not expected");
                },
                side -> {
                    cancelCount.incrementAndGet();
                    manager.beginCancel(
                        side,
                        manager.orderId(side));
                },
                () -> {
                });

        engineRef.set(engine);

        manager.beginAdd(
            OrderManager.Side.ASK,
            "ASKUN001",
            1,
            109L);

        runtime.acceptRisk(
            new DeskRiskMessage(
                2L,
                2L,
                "AAH6",
                0,
                6,
                15,
                HedgerState.UNKNOWN,
                HedgeDirection.X));

        router.accept(md(
            "200 A QUOTE001:ASKUN001 S 1 109"));

        assertEquals(
            1,
            cancelCount.get());

        assertEquals(
            OrderManager.State.PENDING_CANCEL,
            manager.state(
                OrderManager.Side.ASK));
    }

    @Test
    void safePairRiskChangeAfterFirstAddPreventsSecondReservedAdd() {
        Metadata metadata =
            Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=105 band=100 "
                    + "min_volume=1 max_volume=100 "
                    + "position_limit=12 max_tps=3");

        OrderManager manager =
            new OrderManager();

        java.util.concurrent.atomic.AtomicLong now =
            new java.util.concurrent.atomic.AtomicLong(
                1_000_000_000L);

        RuntimeState runtimeState =
            new RuntimeState(
                "AAH6",
                metadata,
                now::get);

        runtimeState.markConnected();

        runtimeState.acceptBbo(
            Bbo.parse(
                "1 AAH6 100 10 110 10",
                metadata));

        runtimeState.acceptRisk(
            new DeskRiskMessage(
                1L,
                1L,
                "AAH6",
                0,
                4,
                5,
                HedgerState.SAFE,
                HedgeDirection.X));

        java.util.concurrent.atomic.AtomicInteger addCalls =
            new java.util.concurrent.atomic.AtomicInteger();

        java.util.concurrent.atomic.AtomicInteger recoveryCalls =
            new java.util.concurrent.atomic.AtomicInteger();

        java.util.concurrent.ScheduledExecutorService scheduler =
            java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor();

        try (OrderRequestClient client =
                new OrderRequestClient(
                    "QUOTE001",
                    "AAH6",
                    metadata,
                    manager,
                    runtimeState::isReady,
                    () -> true,
                    (subject, payload, timeout) ->
                        new java.util.concurrent.CompletableFuture<>(),
                    scheduler,
                    java.time.Duration.ofHours(1),
                    java.time.Duration.ofHours(1),
                    () -> {
                    },
                    now::get)) {

            QuoterIntegration.AutomaticQuoteEngine engine =
                new QuoterIntegration.AutomaticQuoteEngine(
                    runtimeState,
                    metadata,
                    manager,
                    () -> 0,
                    6,
                    12,
                    () -> true,
                    new Object(),
                    client::tryReserveAddCapacity,
                    (side, orderId, quantity, price, reservation) -> {
                        client.requestAdd(
                            side,
                            orderId,
                            quantity,
                            price,
                            reservation);

                        if (addCalls.incrementAndGet() == 1) {
                            runtimeState.acceptRisk(
                                new DeskRiskMessage(
                                    2L,
                                    2L,
                                    "AAH6",
                                    5,
                                    4,
                                    5,
                                    HedgerState.EMERGENCY,
                                    HedgeDirection.S));
                        }
                    },
                    client::requestCancel,
                    recoveryCalls::incrementAndGet);

            engine.evaluateOnce();

            assertEquals(
                1,
                addCalls.get());

            assertEquals(
                1,
                client.currentTpsUsageForTest());

            assertEquals(
                0,
                client.outstandingAddReservationsForTest());

            assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(
                    OrderManager.Side.BID));

            assertTrue(
                recoveryCalls.get() >= 1);
        }
    }
    private static QuoterIntegration.OwnLifecycleRouter router(
            OrderManager manager) {

        return new QuoterIntegration.OwnLifecycleRouter(
            SENDER,
            manager);
    }

    private static byte[] md(String payload) {
        return payload.getBytes(
            StandardCharsets.UTF_8);
    }

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
}
