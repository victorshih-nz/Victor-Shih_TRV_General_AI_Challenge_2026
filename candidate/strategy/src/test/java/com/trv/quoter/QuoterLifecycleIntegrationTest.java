package com.trv.quoter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }

    @Test
    void tPartialUsesAggressorSideAndActualExecutionQty() {
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
    }

    @Test
    void ePartialUsesOppositeAggressorSide() {
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

        router.accept(md(
            "100 A QUOTE001:BID00001 B 3 500"));

        assertEquals(
            OrderManager.State.EMPTY,
            manager.state(OrderManager.Side.BID));
    }

    @Test
    void duplicatePartialExecutionIsIgnored() {
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
    }

    @Test
    void multipleDistinctPartialExecutionsAccumulate() {
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
            "101 T QUOTE001:BID00001 "
                + "OTHER001:ASK00002 2 500 M6 B"));

        assertEquals(
            5,
            manager.remainingQty(OrderManager.Side.BID));
    }

    @Test
    void eventTypeIsPartOfDedupAndCanUpdateBothSidesForSelfTrade() {
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
    }

    @Test
    void validLateExecutionIsIgnoredWithoutCreatingUnknown() {
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
            OrderManager.State.PENDING_ADD,
            manager.state(OrderManager.Side.BID));

        assertTrue(manager.isReconciled());
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

        /*
         * Same current order id, but A claims BUY/BID.
         * This is not a normal late event.
         */
        router.accept(md(
            "100 A QUOTE001:ASK00001 B 10 500"));

        assertEquals(
            OrderManager.State.UNKNOWN,
            manager.state(OrderManager.Side.ASK));
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
    void dedupCapacityExhaustionFailsClosedWithoutApplyingUntrustedNewFill() {
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

        router.accept(md(
            "102 T QUOTE001:BID00001 "
                + "OTHER001:ASK00002 1 500 M10 B"));

        assertEquals(
            OrderManager.State.UNKNOWN,
            manager.state(OrderManager.Side.BID));

        /*
         * The second event was not applied because duplicate detection
         * could no longer be guaranteed safely.
         */
        assertEquals(
            9,
            manager.remainingQty(OrderManager.Side.BID));
    }

    @Test
    void tThatDoesNotTrackOwnIncomingOrderFailsClosed() {
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
}
