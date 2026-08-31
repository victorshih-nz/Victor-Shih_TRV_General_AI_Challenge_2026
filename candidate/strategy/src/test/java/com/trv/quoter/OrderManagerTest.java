package com.trv.quoter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderManagerTest {

    @Test
    void startsWithBothSlotsEmpty() {
        OrderManager manager = new OrderManager();

        assertEquals(
                OrderManager.State.EMPTY,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                OrderManager.State.EMPTY,
                manager.state(OrderManager.Side.ASK));

        assertNull(
                manager.orderId(OrderManager.Side.BID));

        assertNull(
                manager.orderId(OrderManager.Side.ASK));

        assertTrue(manager.isReconciled());
    }

    @Test
    void beginAddOccupiesOnlyRequestedSide() {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                "BID00001");

        assertEquals(
                OrderManager.State.PENDING_ADD,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                "BID00001",
                manager.orderId(OrderManager.Side.BID));

        assertEquals(
                OrderManager.State.EMPTY,
                manager.state(OrderManager.Side.ASK));

        assertNull(
                manager.orderId(OrderManager.Side.ASK));
    }

    @Test
    void bidAndAskOperateIndependently() {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                "BID00001");

        manager.beginAdd(
                OrderManager.Side.ASK,
                "ASK00001");

        manager.markActive(
                OrderManager.Side.BID,
                "BID00001");

        assertEquals(
                OrderManager.State.ACTIVE,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                OrderManager.State.PENDING_ADD,
                manager.state(OrderManager.Side.ASK));

        manager.markActive(
                OrderManager.Side.ASK,
                "ASK00001");

        assertEquals(
                OrderManager.State.ACTIVE,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                OrderManager.State.ACTIVE,
                manager.state(OrderManager.Side.ASK));
    }

    @Test
    void occupiedSlotRejectsSecondBeginAdd() {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                "BID00001");

        assertThrows(
                IllegalStateException.class,
                () -> manager.beginAdd(
                        OrderManager.Side.BID,
                        "BID00002"));

        assertEquals(
                OrderManager.State.PENDING_ADD,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                "BID00001",
                manager.orderId(OrderManager.Side.BID));
    }

    @Test
    void pendingAddCanBecomeActive() {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                "BID00001");

        manager.markActive(
                OrderManager.Side.BID,
                "BID00001");

        assertEquals(
                OrderManager.State.ACTIVE,
                manager.state(OrderManager.Side.BID));
    }

    @Test
    void activeCanBeginCancel() {
        OrderManager manager = activeBid();

        manager.beginCancel(
                OrderManager.Side.BID,
                "BID00001");

        assertEquals(
                OrderManager.State.PENDING_CANCEL,
                manager.state(OrderManager.Side.BID));
    }

    @Test
    void pendingAddCanBecomeUnknown() {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                "BID00001");

        manager.markUnknown(
                OrderManager.Side.BID,
                "BID00001");

        assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(OrderManager.Side.BID));

        assertFalse(manager.isReconciled());
    }

    @Test
    void activeCanBecomeUnknown() {
        OrderManager manager = activeBid();

        manager.markUnknown(
                OrderManager.Side.BID,
                "BID00001");

        assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(OrderManager.Side.BID));
    }

    @Test
    void pendingCancelCanBecomeUnknown() {
        OrderManager manager = activeBid();

        manager.beginCancel(
                OrderManager.Side.BID,
                "BID00001");

        manager.markUnknown(
                OrderManager.Side.BID,
                "BID00001");

        assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(OrderManager.Side.BID));
    }

    @Test
    void unknownCanRemainUnknown() {
        OrderManager manager = activeBid();

        manager.markUnknown(
                OrderManager.Side.BID,
                "BID00001");

        manager.markUnknown(
                OrderManager.Side.BID,
                "BID00001");

        assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(OrderManager.Side.BID));
    }

    @Test
    void unknownCanBecomeActive() {
        OrderManager manager = activeBid();

        manager.markUnknown(
                OrderManager.Side.BID,
                "BID00001");

        manager.markActive(
                OrderManager.Side.BID,
                "BID00001");

        assertEquals(
                OrderManager.State.ACTIVE,
                manager.state(OrderManager.Side.BID));

        assertTrue(manager.isReconciled());
    }

    @Test
    void unknownCanBeginCancel() {
        OrderManager manager = activeBid();

        manager.markUnknown(
                OrderManager.Side.BID,
                "BID00001");

        manager.beginCancel(
                OrderManager.Side.BID,
                "BID00001");

        assertEquals(
                OrderManager.State.PENDING_CANCEL,
                manager.state(OrderManager.Side.BID));

        assertTrue(manager.isReconciled());
    }

    @Test
    void eitherUnknownMakesManagerUnreconciled() {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                "BID00001");

        manager.beginAdd(
                OrderManager.Side.ASK,
                "ASK00001");

        manager.markUnknown(
                OrderManager.Side.BID,
                "BID00001");

        assertFalse(manager.isReconciled());

        manager.markUnknown(
                OrderManager.Side.ASK,
                "ASK00001");

        assertFalse(manager.isReconciled());

        manager.markTerminal(
                OrderManager.Side.BID,
                "BID00001");

        assertFalse(manager.isReconciled());

        manager.markTerminal(
                OrderManager.Side.ASK,
                "ASK00001");

        assertTrue(manager.isReconciled());
    }

    @Test
    void terminalFromPendingAddClearsSlot() {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                "BID00001");

        manager.markTerminal(
                OrderManager.Side.BID,
                "BID00001");

        assertEmptyBid(manager);
    }

    @Test
    void terminalFromActiveClearsSlot() {
        OrderManager manager = activeBid();

        manager.markTerminal(
                OrderManager.Side.BID,
                "BID00001");

        assertEmptyBid(manager);
    }

    @Test
    void terminalFromPendingCancelClearsSlot() {
        OrderManager manager = activeBid();

        manager.beginCancel(
                OrderManager.Side.BID,
                "BID00001");

        manager.markTerminal(
                OrderManager.Side.BID,
                "BID00001");

        assertEmptyBid(manager);
    }

    @Test
    void terminalFromUnknownClearsSlot() {
        OrderManager manager = activeBid();

        manager.markUnknown(
                OrderManager.Side.BID,
                "BID00001");

        manager.markTerminal(
                OrderManager.Side.BID,
                "BID00001");

        assertEmptyBid(manager);
    }

    @Test
    void wrongOrderIdIsRejectedWithoutMutation() {
        OrderManager manager = activeBid();

        assertThrows(
                IllegalStateException.class,
                () -> manager.beginCancel(
                        OrderManager.Side.BID,
                        "WRONG001"));

        assertEquals(
                OrderManager.State.ACTIVE,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                "BID00001",
                manager.orderId(OrderManager.Side.BID));
    }

    @Test
    void invalidTransitionDoesNotMutateSlot() {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                "BID00001");

        assertThrows(
                IllegalStateException.class,
                () -> manager.beginCancel(
                        OrderManager.Side.BID,
                        "BID00001"));

        assertEquals(
                OrderManager.State.PENDING_ADD,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                "BID00001",
                manager.orderId(OrderManager.Side.BID));
    }

    @Test
    void nullSideIsRejected() {
        OrderManager manager = new OrderManager();

        assertThrows(
                IllegalArgumentException.class,
                () -> manager.beginAdd(
                        null,
                        "BID00001"));
    }

    @Test
    void nullAndBlankOrderIdsAreRejected() {
        OrderManager manager = new OrderManager();

        assertThrows(
                IllegalArgumentException.class,
                () -> manager.beginAdd(
                        OrderManager.Side.BID,
                        null));

        assertThrows(
                IllegalArgumentException.class,
                () -> manager.beginAdd(
                        OrderManager.Side.BID,
                        "   "));
    }

    @Test
    void acceptedAddRemainsPendingUntilLifecycleEvidence() {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                "BID00001");

        manager.markAddAccepted(
                OrderManager.Side.BID,
                "BID00001");

        assertEquals(
                OrderManager.State.PENDING_ADD,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                "BID00001",
                manager.orderId(OrderManager.Side.BID));

        assertTrue(manager.isReconciled());
    }

    @Test
    void rejectedAddBecomesUnknown() {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                "BID00001");

        manager.markAddRejected(
                OrderManager.Side.BID,
                "BID00001");

        assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                "BID00001",
                manager.orderId(OrderManager.Side.BID));

        assertFalse(manager.isReconciled());
    }

    @Test
    void addTimeoutBecomesUnknown() {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                "BID00001");

        manager.markAddTimeout(
                OrderManager.Side.BID,
                "BID00001");

        assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                "BID00001",
                manager.orderId(OrderManager.Side.BID));

        assertFalse(manager.isReconciled());
    }

    @Test
    void acceptedCancelRemainsPendingUntilLifecycleEvidence() {
        OrderManager manager = activeBid();

        manager.beginCancel(
                OrderManager.Side.BID,
                "BID00001");

        manager.markCancelAccepted(
                OrderManager.Side.BID,
                "BID00001");

        assertEquals(
                OrderManager.State.PENDING_CANCEL,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                "BID00001",
                manager.orderId(OrderManager.Side.BID));

        assertTrue(manager.isReconciled());
    }

    @Test
    void rejectedCancelBecomesUnknown() {
        OrderManager manager = activeBid();

        manager.beginCancel(
                OrderManager.Side.BID,
                "BID00001");

        manager.markCancelRejected(
                OrderManager.Side.BID,
                "BID00001");

        assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                "BID00001",
                manager.orderId(OrderManager.Side.BID));

        assertFalse(manager.isReconciled());
    }

    @Test
    void cancelTimeoutBecomesUnknown() {
        OrderManager manager = activeBid();

        manager.beginCancel(
                OrderManager.Side.BID,
                "BID00001");

        manager.markCancelTimeout(
                OrderManager.Side.BID,
                "BID00001");

        assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                "BID00001",
                manager.orderId(OrderManager.Side.BID));

        assertFalse(manager.isReconciled());
    }

    @Test
    void addOutcomeWithWrongOrderIdDoesNotMutateSlot() {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                "BID00001");

        assertThrows(
                IllegalStateException.class,
                () -> manager.markAddTimeout(
                        OrderManager.Side.BID,
                        "WRONG001"));

        assertEquals(
                OrderManager.State.PENDING_ADD,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                "BID00001",
                manager.orderId(OrderManager.Side.BID));
    }

    @Test
    void cancelOutcomeWithWrongOrderIdDoesNotMutateSlot() {
        OrderManager manager = activeBid();

        manager.beginCancel(
                OrderManager.Side.BID,
                "BID00001");

        assertThrows(
                IllegalStateException.class,
                () -> manager.markCancelRejected(
                        OrderManager.Side.BID,
                        "WRONG001"));

        assertEquals(
                OrderManager.State.PENDING_CANCEL,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                "BID00001",
                manager.orderId(OrderManager.Side.BID));
    }

    @Test
    void addOutcomeRequiresPendingAddState() {
        OrderManager manager = activeBid();

        assertThrows(
                IllegalStateException.class,
                () -> manager.markAddAccepted(
                        OrderManager.Side.BID,
                        "BID00001"));

        assertEquals(
                OrderManager.State.ACTIVE,
                manager.state(OrderManager.Side.BID));
    }

    @Test
    void cancelOutcomeRequiresPendingCancelState() {
        OrderManager manager = activeBid();

        assertThrows(
                IllegalStateException.class,
                () -> manager.markCancelAccepted(
                        OrderManager.Side.BID,
                        "BID00001"));

        assertEquals(
                OrderManager.State.ACTIVE,
                manager.state(OrderManager.Side.BID));
    }

    private static OrderManager activeBid() {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                "BID00001");

        manager.markActive(
                OrderManager.Side.BID,
                "BID00001");

        return manager;
    }

    private static void assertEmptyBid(
            OrderManager manager) {

        assertEquals(
                OrderManager.State.EMPTY,
                manager.state(OrderManager.Side.BID));

        assertNull(
                manager.orderId(OrderManager.Side.BID));
    }
}