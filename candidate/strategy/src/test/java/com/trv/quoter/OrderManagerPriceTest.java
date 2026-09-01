package com.trv.quoter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OrderManagerPriceTest {

    private static final String BID_ID = "BID00001";
    private static final long PRICE = 500L;

    @Test
    void emptySlotHasNoPrice() {
        OrderManager manager =
            new OrderManager();

        assertNull(
            manager.price(
                OrderManager.Side.BID));
    }

    @Test
    void beginAddStoresRequestedPrice() {
        OrderManager manager =
            new OrderManager();

        manager.beginAdd(
            OrderManager.Side.BID,
            BID_ID,
            10,
            PRICE);

        assertEquals(
            Long.valueOf(PRICE),
            manager.price(
                OrderManager.Side.BID));
    }

    @Test
    void partialFillPreservesOriginalPrice() {
        OrderManager manager =
            activeBid();

        manager.onExecution(
            OrderManager.Side.BID,
            BID_ID,
            3);

        assertEquals(
            Long.valueOf(PRICE),
            manager.price(
                OrderManager.Side.BID));
    }

    @Test
    void pendingCancelPreservesOriginalPrice() {
        OrderManager manager =
            activeBid();

        manager.beginCancel(
            OrderManager.Side.BID,
            BID_ID);

        assertEquals(
            Long.valueOf(PRICE),
            manager.price(
                OrderManager.Side.BID));
    }

    @Test
    void unknownPreservesOriginalPrice() {
        OrderManager manager =
            new OrderManager();

        manager.beginAdd(
            OrderManager.Side.BID,
            BID_ID,
            10,
            PRICE);

        manager.markRequestUncertain(
            OrderManager.Side.BID,
            BID_ID);

        assertEquals(
            Long.valueOf(PRICE),
            manager.price(
                OrderManager.Side.BID));
    }

    @Test
    void fullFillClearsPrice() {
        OrderManager manager =
            activeBid();

        manager.onExecution(
            OrderManager.Side.BID,
            BID_ID,
            10);

        assertNull(
            manager.price(
                OrderManager.Side.BID));
    }

    @Test
    void authoritativeCancelClearsPrice() {
        OrderManager manager =
            activeBid();

        manager.onCancelled(
            OrderManager.Side.BID,
            BID_ID);

        assertNull(
            manager.price(
                OrderManager.Side.BID));
    }

    @Test
    void nonPositivePriceIsRejectedWithoutOccupyingSlot() {
        OrderManager manager =
            new OrderManager();

        assertThrows(
            IllegalArgumentException.class,
            () -> manager.beginAdd(
                OrderManager.Side.BID,
                BID_ID,
                10,
                0L));

        assertEquals(
            OrderManager.State.EMPTY,
            manager.state(
                OrderManager.Side.BID));

        assertNull(
            manager.price(
                OrderManager.Side.BID));
    }

    private OrderManager activeBid() {
        OrderManager manager =
            new OrderManager();

        manager.beginAdd(
            OrderManager.Side.BID,
            BID_ID,
            10,
            PRICE);

        manager.onResting(
            OrderManager.Side.BID,
            BID_ID);

        return manager;
    }
}
