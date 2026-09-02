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

    private enum UnknownCause {
        NONE,
        ADD_REQUEST,
        CANCEL_REQUEST,
        OTHER
    }

    private static final class Slot {
        private State state = State.EMPTY;
        private String orderId;
        private int requestedQty;
        private int filledQty;
        private Long price;
        private UnknownCause unknownCause = UnknownCause.NONE;
    }

    private final Slot bid = new Slot();
    private final Slot ask = new Slot();

    /**
     * Production Add registration.
     *
     * Price becomes part of the same authoritative slot state as id/quantity so
     * the future QuoteController can compare current vs desired without keeping
     * a shadow order map.
     */
    public synchronized void beginAdd(
            Side side,
            String orderId,
            int quantity,
            long price) {

        beginAddInternal(
            side,
            orderId,
            quantity,
            price);
    }

    private void beginAddInternal(
            Side side,
            String orderId,
            int quantity,
            Long price) {

        validateInputs(side, orderId);

        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "quantity must be positive");
        }

        if (price != null && price <= 0L) {
            throw new IllegalArgumentException(
                    "price must be positive");
        }

        Slot slot = slot(side);

        if (slot.state != State.EMPTY) {
            throw new IllegalStateException(
                    "slot already occupied");
        }

        slot.state = State.PENDING_ADD;
        slot.orderId = orderId;
        slot.requestedQty = quantity;
        slot.filledQty = 0;
        slot.price = price;
        slot.unknownCause = UnknownCause.NONE;
    }

    public synchronized void beginCancel(
            Side side,
            String orderId) {

        validateInputs(side, orderId);

        Slot slot = slot(side);
        requireMatchingOrder(slot, orderId);

        if (slot.state == State.ACTIVE) {
            slot.state = State.PENDING_CANCEL;
            return;
        }

        if (slot.state == State.UNKNOWN) {
            /*
             * Sending a reconciliation cancel does not resolve UNKNOWN.
             * Keep the fail-closed state until authoritative evidence arrives.
             */
            slot.unknownCause = UnknownCause.CANCEL_REQUEST;
            return;
        }

        throw new IllegalStateException(
                "cannot begin cancel from state " + slot.state);
    }

    public synchronized void markRequestUncertain(
            Side side,
            String orderId) {

        validateInputs(side, orderId);

        Slot slot = slot(side);
        requireMatchingOrder(slot, orderId);

        if (slot.state == State.PENDING_ADD) {
            slot.state = State.UNKNOWN;
            slot.unknownCause = UnknownCause.ADD_REQUEST;
            return;
        }

        if (slot.state == State.PENDING_CANCEL) {
            slot.state = State.UNKNOWN;
            slot.unknownCause = UnknownCause.CANCEL_REQUEST;
            return;
        }

        if (slot.state == State.UNKNOWN) {
            // It is already fail-closed.
            return;
        }

        throw new IllegalStateException(
                "request uncertainty only applies to pending or unknown orders");
    }

    /*
     * Abort a locally registered Add only when the caller knows the request
     * has definitely not crossed the network-dispatch boundary.
     *
     * This is not reconciliation and not request uncertainty. If the exact
     * current slot is still PENDING_ADD, no Exchange lifecycle evidence can
     * exist for this request, so restoring EMPTY is authoritative from the
     * local dispatch boundary.
     *
     * Returns true only when this exact pending Add was cleared.
     */
    public synchronized boolean abortPendingAddIfCurrent(
            Side side,
            String orderId) {

        validateInputs(side, orderId);

        Slot slot = slot(side);

        if (!isCurrentOrder(slot, orderId)) {
            return false;
        }

        if (slot.state != State.PENDING_ADD) {
            return false;
        }

        clear(slot);
        return true;
    }

    /*
     * Request-layer race-safe transition.
     *
     * A late N/exception/deadline must never override authoritative lifecycle
     * evidence that already moved the same order to ACTIVE or EMPTY.
     *
     * Returns true only when this call changed a still-pending request to
     * UNKNOWN. Old/different ids, terminal orders, ACTIVE orders and already
     * UNKNOWN orders are no-ops.
     */
    public synchronized boolean markRequestUncertainIfPending(
            Side side,
            String orderId) {

        validateInputs(side, orderId);

        Slot slot = slot(side);

        if (!isCurrentOrder(slot, orderId)) {
            return false;
        }

        if (slot.state == State.PENDING_ADD) {
            slot.state = State.UNKNOWN;
            slot.unknownCause = UnknownCause.ADD_REQUEST;
            return true;
        }

        if (slot.state == State.PENDING_CANCEL) {
            slot.state = State.UNKNOWN;
            slot.unknownCause = UnknownCause.CANCEL_REQUEST;
            return true;
        }

        /*
         * ACTIVE means authoritative A already won the race.
         * UNKNOWN is already fail-closed.
         * EMPTY cannot be current because isCurrentOrder() rejected it.
         */
        return false;
    }

    public synchronized void markUnknown(
            Side side,
            String orderId) {

        validateInputs(side, orderId);

        Slot slot = slot(side);
        requireMatchingOrder(slot, orderId);

        if (slot.state == State.EMPTY) {
            throw new IllegalStateException(
                    "cannot mark EMPTY slot UNKNOWN");
        }

        slot.state = State.UNKNOWN;

        if (slot.unknownCause == UnknownCause.NONE) {
            slot.unknownCause = UnknownCause.OTHER;
        }
    }

    /*
     * Authoritative A event.
     *
     * A confirms the current order has existed as a resting order.
     * It does not override an already-issued cancel intent.
     */
    public synchronized void onResting(
            Side side,
            String orderId) {

        validateInputs(side, orderId);

        Slot slot = slot(side);

        if (!isCurrentOrder(slot, orderId)) {
            // Late/stale/unrelated lifecycle evidence.
            return;
        }

        if (slot.state == State.PENDING_ADD) {
            slot.state = State.ACTIVE;
            slot.unknownCause = UnknownCause.NONE;
            return;
        }

        if (slot.state == State.UNKNOWN
                && slot.unknownCause == UnknownCause.ADD_REQUEST) {
            slot.state = State.ACTIVE;
            slot.unknownCause = UnknownCause.NONE;
            return;
        }

        /*
         * ACTIVE:
         * duplicate/late A changes nothing.
         *
         * PENDING_CANCEL:
         * A does not cancel our cancel intent.
         *
         * UNKNOWN caused by CANCEL/OTHER:
         * A is insufficient to resolve uncertainty.
         */
    }

    /*
     * Authoritative E/T execution evidence for this order.
     *
     * Execution deduplication must happen before this method.
     */
    public synchronized void onExecution(
            Side side,
            String orderId,
            int quantity) {

        validateInputs(side, orderId);

        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "execution quantity must be positive");
        }

        Slot slot = slot(side);

        if (!isCurrentOrder(slot, orderId)) {
            /*
             * Lifecycle ignores late/stale executions for an old order.
             * Desk position accounting is separate and must still process
             * legitimate deduplicated E/T events.
             */
            return;
        }

        int newFilledQty = slot.filledQty + quantity;

        if (newFilledQty > slot.requestedQty) {
            throw new IllegalStateException(
                    "execution quantity exceeds requested order quantity");
        }

        slot.filledQty = newFilledQty;

        if (slot.filledQty == slot.requestedQty) {
            clear(slot);
        }

        /*
         * Partial fill deliberately preserves lifecycle state and original
         * requested price:
         *
         * PENDING_ADD    -> PENDING_ADD
         * ACTIVE         -> ACTIVE
         * PENDING_CANCEL -> PENDING_CANCEL
         * UNKNOWN        -> UNKNOWN
         */
    }

    /*
     * Authoritative C event.
     */
    public synchronized void onCancelled(
            Side side,
            String orderId) {

        validateInputs(side, orderId);

        Slot slot = slot(side);

        if (!isCurrentOrder(slot, orderId)) {
            // Late/stale/unrelated cancellation event.
            return;
        }

        clear(slot);
    }

    public synchronized State state(Side side) {
        validateSide(side);
        return slot(side).state;
    }

    public synchronized String orderId(Side side) {
        validateSide(side);
        return slot(side).orderId;
    }

    /**
     * Requested wire price for the current slot.
     *
     * Returns null when the slot is EMPTY. It can also be null only for the
     * temporary legacy three-argument beginAdd overload used by old tests/probes.
     * Production Add registration always supplies a price.
     */
    public synchronized Long price(Side side) {
        validateSide(side);
        return slot(side).price;
    }

    public synchronized int remainingQty(Side side) {
        validateSide(side);

        Slot slot = slot(side);

        if (slot.state == State.EMPTY) {
            return 0;
        }

        return slot.requestedQty - slot.filledQty;
    }

    public synchronized boolean isReconciled() {
        return bid.state != State.UNKNOWN
                && ask.state != State.UNKNOWN;
    }

    private Slot slot(Side side) {
        return side == Side.BID ? bid : ask;
    }

    private boolean isCurrentOrder(
            Slot slot,
            String orderId) {

        return slot.state != State.EMPTY
                && slot.orderId != null
                && slot.orderId.equals(orderId);
    }

    private void requireMatchingOrder(
            Slot slot,
            String orderId) {

        if (!isCurrentOrder(slot, orderId)) {
            throw new IllegalStateException(
                    "order id does not match current slot");
        }
    }

    private void clear(Slot slot) {
        slot.state = State.EMPTY;
        slot.orderId = null;
        slot.requestedQty = 0;
        slot.filledQty = 0;
        slot.price = null;
        slot.unknownCause = UnknownCause.NONE;
    }

    private void validateInputs(
            Side side,
            String orderId) {

        validateSide(side);

        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException(
                    "order id is required");
        }
    }

    private void validateSide(Side side) {
        if (side == null) {
            throw new IllegalArgumentException(
                    "side is required");
        }
    }
}
