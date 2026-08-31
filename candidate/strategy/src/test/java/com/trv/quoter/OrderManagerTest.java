package com.trv.quoter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderManagerTest {

    private static final String BID_ID = "BID00001";
    private static final String ASK_ID = "ASK00001";

    @Test
    void startsEmpty() {
        OrderManager manager = new OrderManager();

        assertEquals(
                OrderManager.State.EMPTY,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                OrderManager.State.EMPTY,
                manager.state(OrderManager.Side.ASK));

        assertNull(manager.orderId(OrderManager.Side.BID));
        assertNull(manager.orderId(OrderManager.Side.ASK));

        assertEquals(0, manager.remainingQty(OrderManager.Side.BID));
        assertEquals(0, manager.remainingQty(OrderManager.Side.ASK));

        assertTrue(manager.isReconciled());
    }

    @Test
    void beginAddStoresRequestedQuantity() {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                BID_ID,
                10);

        assertEquals(
                OrderManager.State.PENDING_ADD,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                BID_ID,
                manager.orderId(OrderManager.Side.BID));

        assertEquals(
                10,
                manager.remainingQty(OrderManager.Side.BID));
    }

    @Test
    void bidAndAskRemainIndependent() {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                BID_ID,
                10);

        manager.beginAdd(
                OrderManager.Side.ASK,
                ASK_ID,
                20);

        manager.onResting(
                OrderManager.Side.BID,
                BID_ID);

        assertEquals(
                OrderManager.State.ACTIVE,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                OrderManager.State.PENDING_ADD,
                manager.state(OrderManager.Side.ASK));

        assertEquals(
                10,
                manager.remainingQty(OrderManager.Side.BID));

        assertEquals(
                20,
                manager.remainingQty(OrderManager.Side.ASK));
    }

    @Test
    void restingEvidenceActivatesPendingAdd() {
        OrderManager manager = pendingBid(10);

        manager.onResting(
                OrderManager.Side.BID,
                BID_ID);

        assertEquals(
                OrderManager.State.ACTIVE,
                manager.state(OrderManager.Side.BID));
    }

    @Test
    void addUncertaintyCanBeResolvedByRestingEvidence() {
        OrderManager manager = pendingBid(10);

        manager.markRequestUncertain(
                OrderManager.Side.BID,
                BID_ID);

        assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(OrderManager.Side.BID));

        manager.onResting(
                OrderManager.Side.BID,
                BID_ID);

        assertEquals(
                OrderManager.State.ACTIVE,
                manager.state(OrderManager.Side.BID));

        assertTrue(manager.isReconciled());
    }

    @Test
    void cancelUncertaintyIsNotResolvedByRestingEvidence() {
        OrderManager manager = activeBid(10);

        manager.beginCancel(
                OrderManager.Side.BID,
                BID_ID);

        manager.markRequestUncertain(
                OrderManager.Side.BID,
                BID_ID);

        manager.onResting(
                OrderManager.Side.BID,
                BID_ID);

        assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(OrderManager.Side.BID));

        assertFalse(manager.isReconciled());
    }

    @Test
    void restingEvidenceDoesNotOverridePendingCancel() {
        OrderManager manager = activeBid(10);

        manager.beginCancel(
                OrderManager.Side.BID,
                BID_ID);

        manager.onResting(
                OrderManager.Side.BID,
                BID_ID);

        assertEquals(
                OrderManager.State.PENDING_CANCEL,
                manager.state(OrderManager.Side.BID));
    }

    @Test
    void partialFillKeepsActiveOrderOpen() {
        OrderManager manager = activeBid(10);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                3);

        assertEquals(
                OrderManager.State.ACTIVE,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                7,
                manager.remainingQty(OrderManager.Side.BID));
    }

    @Test
    void multiplePartialFillsAccumulate() {
        OrderManager manager = activeBid(10);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                3);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                4);

        assertEquals(
                OrderManager.State.ACTIVE,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                3,
                manager.remainingQty(OrderManager.Side.BID));
    }

    @Test
    void fullFillClearsActiveOrder() {
        OrderManager manager = activeBid(10);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                10);

        assertEmptyBid(manager);
    }

    @Test
    void fullFillAfterPartialsClearsOrder() {
        OrderManager manager = activeBid(10);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                3);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                7);

        assertEmptyBid(manager);
    }

    @Test
    void partialFillDuringPendingAddKeepsPendingAdd() {
        OrderManager manager = pendingBid(10);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                3);

        assertEquals(
                OrderManager.State.PENDING_ADD,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                7,
                manager.remainingQty(OrderManager.Side.BID));
    }

    @Test
    void fullFillDuringPendingAddIsTerminal() {
        OrderManager manager = pendingBid(10);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                10);

        assertEmptyBid(manager);
    }

    @Test
    void partialFillDuringPendingCancelKeepsCancelPending() {
        OrderManager manager = activeBid(10);

        manager.beginCancel(
                OrderManager.Side.BID,
                BID_ID);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                4);

        assertEquals(
                OrderManager.State.PENDING_CANCEL,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                6,
                manager.remainingQty(OrderManager.Side.BID));
    }

    @Test
    void fullFillDuringPendingCancelIsTerminal() {
        OrderManager manager = activeBid(10);

        manager.beginCancel(
                OrderManager.Side.BID,
                BID_ID);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                10);

        assertEmptyBid(manager);
    }

    @Test
    void partialFillDoesNotResolveUnknown() {
        OrderManager manager = pendingBid(10);

        manager.markRequestUncertain(
                OrderManager.Side.BID,
                BID_ID);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                4);

        assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                6,
                manager.remainingQty(OrderManager.Side.BID));

        assertFalse(manager.isReconciled());
    }

    @Test
    void fullFillResolvesUnknownToEmpty() {
        OrderManager manager = pendingBid(10);

        manager.markRequestUncertain(
                OrderManager.Side.BID,
                BID_ID);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                10);

        assertEmptyBid(manager);
        assertTrue(manager.isReconciled());
    }

    @Test
    void cancellationClearsActiveOrder() {
        OrderManager manager = activeBid(10);

        manager.onCancelled(
                OrderManager.Side.BID,
                BID_ID);

        assertEmptyBid(manager);
    }

    @Test
    void cancellationClearsPendingCancel() {
        OrderManager manager = activeBid(10);

        manager.beginCancel(
                OrderManager.Side.BID,
                BID_ID);

        manager.onCancelled(
                OrderManager.Side.BID,
                BID_ID);

        assertEmptyBid(manager);
    }

    @Test
    void cancellationResolvesUnknown() {
        OrderManager manager = activeBid(10);

        manager.beginCancel(
                OrderManager.Side.BID,
                BID_ID);

        manager.markRequestUncertain(
                OrderManager.Side.BID,
                BID_ID);

        manager.onCancelled(
                OrderManager.Side.BID,
                BID_ID);

        assertEmptyBid(manager);
        assertTrue(manager.isReconciled());
    }

    @Test
    void cancelRequestedWhileUnknownRemainsUnknown() {
        OrderManager manager = pendingBid(10);

        manager.markRequestUncertain(
                OrderManager.Side.BID,
                BID_ID);

        manager.beginCancel(
                OrderManager.Side.BID,
                BID_ID);

        assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(OrderManager.Side.BID));

        assertFalse(manager.isReconciled());
    }

    @Test
    void fullFillAfterUnknownCancelRequestClearsOrder() {
        OrderManager manager = pendingBid(10);

        manager.markRequestUncertain(
                OrderManager.Side.BID,
                BID_ID);

        manager.beginCancel(
                OrderManager.Side.BID,
                BID_ID);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                10);

        assertEmptyBid(manager);
    }

    @Test
    void lateRestingAfterFullFillIsIgnored() {
        OrderManager manager = activeBid(10);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                10);

        manager.onResting(
                OrderManager.Side.BID,
                BID_ID);

        assertEmptyBid(manager);
    }

    @Test
    void lateCancelAfterFullFillIsIgnored() {
        OrderManager manager = activeBid(10);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                10);

        manager.onCancelled(
                OrderManager.Side.BID,
                BID_ID);

        assertEmptyBid(manager);
    }

    @Test
    void lateExecutionAfterTerminalDoesNotReopenOrder() {
        OrderManager manager = activeBid(10);

        manager.onCancelled(
                OrderManager.Side.BID,
                BID_ID);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                1);

        assertEmptyBid(manager);
    }

    @Test
    void staleEventForDifferentOrderDoesNotAffectCurrentOrder() {
        OrderManager manager = activeBid(10);

        manager.onExecution(
                OrderManager.Side.BID,
                "OLD00001",
                5);

        manager.onResting(
                OrderManager.Side.BID,
                "OLD00001");

        manager.onCancelled(
                OrderManager.Side.BID,
                "OLD00001");

        assertEquals(
                OrderManager.State.ACTIVE,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                BID_ID,
                manager.orderId(OrderManager.Side.BID));

        assertEquals(
                10,
                manager.remainingQty(OrderManager.Side.BID));
    }

    @Test
    void overfillIsRejectedWithoutMutation() {
        OrderManager manager = activeBid(10);

        manager.onExecution(
                OrderManager.Side.BID,
                BID_ID,
                7);

        assertThrows(
                IllegalStateException.class,
                () -> manager.onExecution(
                        OrderManager.Side.BID,
                        BID_ID,
                        4));

        assertEquals(
                OrderManager.State.ACTIVE,
                manager.state(OrderManager.Side.BID));

        assertEquals(
                3,
                manager.remainingQty(OrderManager.Side.BID));
    }

    @Test
    void nonPositiveExecutionQuantityIsRejected() {
        OrderManager manager = activeBid(10);

        assertThrows(
                IllegalArgumentException.class,
                () -> manager.onExecution(
                        OrderManager.Side.BID,
                        BID_ID,
                        0));

        assertEquals(
                10,
                manager.remainingQty(OrderManager.Side.BID));
    }

    @Test
    void nonPositiveAddQuantityIsRejected() {
        OrderManager manager = new OrderManager();

        assertThrows(
                IllegalArgumentException.class,
                () -> manager.beginAdd(
                        OrderManager.Side.BID,
                        BID_ID,
                        0));

        assertEquals(
                OrderManager.State.EMPTY,
                manager.state(OrderManager.Side.BID));
    }

    @Test
    void occupiedSlotRejectsAnotherAdd() {
        OrderManager manager = pendingBid(10);

        assertThrows(
                IllegalStateException.class,
                () -> manager.beginAdd(
                        OrderManager.Side.BID,
                        "BID00002",
                        5));

        assertEquals(
                BID_ID,
                manager.orderId(OrderManager.Side.BID));

        assertEquals(
                10,
                manager.remainingQty(OrderManager.Side.BID));
    }

    @Test
    void eitherUnknownBlocksReconciliation() {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                BID_ID,
                10);

        manager.beginAdd(
                OrderManager.Side.ASK,
                ASK_ID,
                10);

        manager.markRequestUncertain(
                OrderManager.Side.BID,
                BID_ID);

        assertFalse(manager.isReconciled());

        manager.onResting(
                OrderManager.Side.BID,
                BID_ID);

        assertTrue(manager.isReconciled());
    }

    private static OrderManager pendingBid(int quantity) {
        OrderManager manager = new OrderManager();

        manager.beginAdd(
                OrderManager.Side.BID,
                BID_ID,
                quantity);

        return manager;
    }

    private static OrderManager activeBid(int quantity) {
        OrderManager manager = pendingBid(quantity);

        manager.onResting(
                OrderManager.Side.BID,
                BID_ID);

        return manager;
    }

    private static void assertEmptyBid(
            OrderManager manager) {

        assertEquals(
                OrderManager.State.EMPTY,
                manager.state(OrderManager.Side.BID));

        assertNull(
                manager.orderId(OrderManager.Side.BID));

        assertEquals(
                0,
                manager.remainingQty(OrderManager.Side.BID));
    }
}