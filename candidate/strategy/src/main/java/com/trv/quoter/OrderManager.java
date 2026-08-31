package com.trv.quoter;

public class OrderManager {

    public enum Side {
        BID,
        ASK
    }

    public enum State {
        EMPTY,
        PENDING_ADD,
        ACTIVE,
        PENDING_CANCEL,
        UNKNOWN
    }

    private static final class Slot {
        private State state = State.EMPTY;
        private String orderId;
    }

    private final Slot bid = new Slot();
    private final Slot ask = new Slot();

    public synchronized void beginAdd(Side side, String orderId) {
        validateInputs(side, orderId);

        Slot slot = slot(side);

        if (slot.state != State.EMPTY) {
            throw new IllegalStateException("slot already occupied");
        }

        slot.state = State.PENDING_ADD;
        slot.orderId = orderId;
    }

    public synchronized void markActive(Side side, String orderId) {
        validateInputs(side, orderId);

        Slot slot = slot(side);
        requireMatchingOrder(slot, orderId);

        if (slot.state != State.PENDING_ADD
                && slot.state != State.UNKNOWN) {
            throw new IllegalStateException(
                    "cannot mark active from state " + slot.state);
        }

        slot.state = State.ACTIVE;
    }

    public synchronized void beginCancel(Side side, String orderId) {
        validateInputs(side, orderId);

        Slot slot = slot(side);
        requireMatchingOrder(slot, orderId);

        if (slot.state != State.ACTIVE
                && slot.state != State.UNKNOWN) {
            throw new IllegalStateException(
                    "cannot begin cancel from state " + slot.state);
        }

        slot.state = State.PENDING_CANCEL;
    }

    public synchronized void markUnknown(Side side, String orderId) {
        validateInputs(side, orderId);

        Slot slot = slot(side);
        requireMatchingOrder(slot, orderId);

        if (slot.state == State.EMPTY) {
            throw new IllegalStateException(
                    "cannot mark EMPTY slot as UNKNOWN");
        }

        slot.state = State.UNKNOWN;
    }

    public synchronized void markTerminal(Side side, String orderId) {
        validateInputs(side, orderId);

        Slot slot = slot(side);
        requireMatchingOrder(slot, orderId);

        if (slot.state == State.EMPTY) {
            throw new IllegalStateException(
                    "cannot mark EMPTY slot terminal");
        }

        slot.state = State.EMPTY;
        slot.orderId = null;
    }

    public synchronized State state(Side side) {
        validateSide(side);
        return slot(side).state;
    }

    public synchronized String orderId(Side side) {
        validateSide(side);
        return slot(side).orderId;
    }

    public synchronized boolean isReconciled() {
        return bid.state != State.UNKNOWN
                && ask.state != State.UNKNOWN;
    }

    private Slot slot(Side side) {
        return side == Side.BID ? bid : ask;
    }

    private void requireMatchingOrder(Slot slot, String orderId) {
        if (slot.orderId == null || !slot.orderId.equals(orderId)) {
            throw new IllegalStateException("order id does not match slot");
        }
    }

    private void validateInputs(Side side, String orderId) {
        validateSide(side);

        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("order id is required");
        }
    }

    private void validateSide(Side side) {
        if (side == null) {
            throw new IllegalArgumentException("side is required");
        }
    }
}