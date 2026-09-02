package com.trv.quoter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Async Add/Cancel request layer for the Quoter.
 *
 * This class shares the single OrderManager instance with lifecycle routing.
 * It does not keep shadow order state and never uses request replies as
 * position authority.
 */
final class OrderRequestClient implements AutoCloseable {

    private static final Logger logger =
        Logger.getLogger(OrderRequestClient.class.getName());

    private static final long TPS_WINDOW_NANOS =
        TimeUnit.SECONDS.toNanos(1);

    @FunctionalInterface
    interface RequestTransport {
        CompletableFuture<byte[]> request(
            String subject,
            byte[] payload,
            Duration replyTimeout);
    }

    private final String sender;
    private final String feed;
    private final Metadata metadata;
    private final OrderManager orderManager;
    private final BooleanSupplier addEnvironmentReady;
    private final BooleanSupplier transportTrusted;
    private final RequestTransport transport;
    private final ScheduledExecutorService deadlineScheduler;
    private final Duration addUncertaintyTimeout;
    private final Duration cancelUncertaintyTimeout;
    private final Runnable lifecycleStateChanged;
    private final LongSupplier monotonicClock;

    /*
     * Exchange TPS accounting is per feed and uses a monotonic one-second
     * rolling window. requestTimesNanos contains only requests that actually
     * crossed the transport boundary. Reservations are tracked separately and
     * are never counted as usage until the request attempt is made.
     */
    private final Object tpsLock = new Object();
    private final Deque<Long> requestTimesNanos =
        new ArrayDeque<>();

    private AddReservation activeAddReservation;
    private int outstandingAddReservations;
    private int outstandingCancelReservations;

    private volatile boolean closed;

    OrderRequestClient(
            String sender,
            String feed,
            Metadata metadata,
            OrderManager orderManager,
            BooleanSupplier addEnvironmentReady,
            BooleanSupplier transportTrusted,
            RequestTransport transport,
            Duration addUncertaintyTimeout,
            Duration cancelUncertaintyTimeout) {

        this(
            sender,
            feed,
            metadata,
            orderManager,
            addEnvironmentReady,
            transportTrusted,
            transport,
            newDeadlineScheduler(),
            addUncertaintyTimeout,
            cancelUncertaintyTimeout,
            () -> {
            });
    }

    /**
     * Production constructor: request uncertainty wakes reconciliation
     * immediately rather than waiting for its periodic scheduler tick.
     */
    OrderRequestClient(
            String sender,
            String feed,
            Metadata metadata,
            OrderManager orderManager,
            BooleanSupplier addEnvironmentReady,
            BooleanSupplier transportTrusted,
            RequestTransport transport,
            Duration addUncertaintyTimeout,
            Duration cancelUncertaintyTimeout,
            Runnable lifecycleStateChanged) {

        this(
            sender,
            feed,
            metadata,
            orderManager,
            addEnvironmentReady,
            transportTrusted,
            transport,
            newDeadlineScheduler(),
            addUncertaintyTimeout,
            cancelUncertaintyTimeout,
            lifecycleStateChanged);
    }

    OrderRequestClient(
            String sender,
            String feed,
            Metadata metadata,
            OrderManager orderManager,
            BooleanSupplier addEnvironmentReady,
            BooleanSupplier transportTrusted,
            RequestTransport transport,
            ScheduledExecutorService deadlineScheduler,
            Duration addUncertaintyTimeout,
            Duration cancelUncertaintyTimeout) {

        this(
            sender,
            feed,
            metadata,
            orderManager,
            addEnvironmentReady,
            transportTrusted,
            transport,
            deadlineScheduler,
            addUncertaintyTimeout,
            cancelUncertaintyTimeout,
            () -> {
            });
    }

    OrderRequestClient(
            String sender,
            String feed,
            Metadata metadata,
            OrderManager orderManager,
            BooleanSupplier addEnvironmentReady,
            BooleanSupplier transportTrusted,
            RequestTransport transport,
            ScheduledExecutorService deadlineScheduler,
            Duration addUncertaintyTimeout,
            Duration cancelUncertaintyTimeout,
            Runnable lifecycleStateChanged) {

        this(
            sender,
            feed,
            metadata,
            orderManager,
            addEnvironmentReady,
            transportTrusted,
            transport,
            deadlineScheduler,
            addUncertaintyTimeout,
            cancelUncertaintyTimeout,
            lifecycleStateChanged,
            System::nanoTime);
    }

    /*
     * Package-private deterministic-clock constructor for focused TPS tests.
     */
    OrderRequestClient(
            String sender,
            String feed,
            Metadata metadata,
            OrderManager orderManager,
            BooleanSupplier addEnvironmentReady,
            BooleanSupplier transportTrusted,
            RequestTransport transport,
            ScheduledExecutorService deadlineScheduler,
            Duration addUncertaintyTimeout,
            Duration cancelUncertaintyTimeout,
            Runnable lifecycleStateChanged,
            LongSupplier monotonicClock) {

        if (sender == null
                || sender.length() != 8
                || containsWhitespace(sender)) {

            throw new IllegalArgumentException(
                "sender must be exactly 8 non-whitespace characters");
        }

        if (feed == null
                || feed.length() != 4
                || containsWhitespace(feed)) {

            throw new IllegalArgumentException(
                "feed must be exactly 4 non-whitespace characters");
        }

        this.metadata =
            Objects.requireNonNull(
                metadata,
                "metadata is required");

        if (!feed.equals(metadata.getFeed())) {
            throw new IllegalArgumentException(
                "feed does not match metadata feed");
        }

        if (!metadata.isValid()) {
            throw new IllegalArgumentException(
                "metadata must be valid");
        }

        this.orderManager =
            Objects.requireNonNull(
                orderManager,
                "orderManager is required");

        this.addEnvironmentReady =
            Objects.requireNonNull(
                addEnvironmentReady,
                "addEnvironmentReady is required");

        this.transportTrusted =
            Objects.requireNonNull(
                transportTrusted,
                "transportTrusted is required");

        this.transport =
            Objects.requireNonNull(
                transport,
                "transport is required");

        this.deadlineScheduler =
            Objects.requireNonNull(
                deadlineScheduler,
                "deadlineScheduler is required");

        this.lifecycleStateChanged =
            Objects.requireNonNull(
                lifecycleStateChanged,
                "lifecycleStateChanged is required");

        this.monotonicClock =
            Objects.requireNonNull(
                monotonicClock,
                "monotonicClock is required");

        validateDuration(
            addUncertaintyTimeout,
            "addUncertaintyTimeout");

        validateDuration(
            cancelUncertaintyTimeout,
            "cancelUncertaintyTimeout");

        this.sender = sender;
        this.feed = feed;
        this.addUncertaintyTimeout =
            addUncertaintyTimeout;
        this.cancelUncertaintyTimeout =
            cancelUncertaintyTimeout;
    }

    /**
     * Backward-compatible single-Add entry point.
     *
     * Production automatic quoting reserves the whole candidate batch before
     * the first Add. Direct callers still receive the same safety semantics by
     * reserving a one-Add batch here.
     */
    void requestAdd(
            OrderManager.Side side,
            String orderId,
            int quantity,
            long price) {

        ensureOpen();
        validateSide(side);
        validateOrderId(orderId);
        validateAddQuantity(quantity);
        validateAddPrice(price);

        AddReservation reservation =
            tryReserveAddCapacity(1);

        if (reservation == null) {
            return;
        }

        try (reservation) {
            requestAdd(
                side,
                orderId,
                quantity,
                price,
                reservation);
        }
    }

    /**
     * Add using an already-admitted TPS reservation.
     *
     * A SAFE pair passes the same two-token reservation to both Add calls.
     * Each token becomes actual TPS usage only immediately before the
     * corresponding transport.request() attempt.
     */
    void requestAdd(
            OrderManager.Side side,
            String orderId,
            int quantity,
            long price,
            AddReservation reservation) {

        ensureOpen();
        validateSide(side);
        validateOrderId(orderId);
        validateAddQuantity(quantity);
        validateAddPrice(price);
        validateAddReservation(reservation);

        if (!addEnvironmentReady.getAsBoolean()) {
            throw new IllegalStateException(
                "environment is not ready for new exposure");
        }

        synchronized (orderManager) {
            if (!orderManager.isReconciled()) {
                throw new IllegalStateException(
                    "order lifecycle is not reconciled");
            }

            if (orderManager.state(side)
                    != OrderManager.State.EMPTY) {

                throw new IllegalStateException(
                    "target order slot is not empty");
            }

            orderManager.beginAdd(
                side,
                orderId,
                quantity,
                price);
        }

        String payload =
            sender
                + " A "
                + feed
                + " "
                + orderId
                + " "
                + wireSide(side)
                + " "
                + quantity
                + " "
                + price
                + " L";

        dispatch(
            side,
            orderId,
            payload,
            addUncertaintyTimeout,
            true,
            reservation,
            null);
    }

    /**
     * Atomically admit one or more candidate Adds against Exchange max_tps.
     *
     * Admission: U + R + K + min(1, K) <= max_tps
     *
     * U = actual requests in the rolling one-second window.
     * R = ACTIVE + PENDING_ADD + UNKNOWN(current) cancel obligations.
     * K = candidate Add count.
     * PENDING_CANCEL is excluded because its cancel request already owns or
     * consumed TPS capacity.
     */
    AddReservation tryReserveAddCapacity(
            int candidateAddCount) {

        ensureOpen();

        if (candidateAddCount <= 0) {
            throw new IllegalArgumentException(
                "candidateAddCount must be positive");
        }

        int cancellationObligations =
            cancellationObligations();

        long now =
            monotonicClock.getAsLong();

        synchronized (tpsLock) {
            pruneTpsWindow(now);

            if (activeAddReservation != null
                    && !activeAddReservation.closed
                    && activeAddReservation.remaining > 0) {

                return null;
            }

            int emergencyReserve =
                Math.min(
                    1,
                    candidateAddCount);

            long required =
                (long) requestTimesNanos.size()
                    + outstandingCancelReservations
                    + cancellationObligations
                    + candidateAddCount
                    + emergencyReserve;

            if (metadata.getMaxTps() > 0
                    && required > metadata.getMaxTps()) {
                return null;
            }

            AddReservation reservation =
                new AddReservation(
                    this,
                    candidateAddCount);

            activeAddReservation = reservation;
            outstandingAddReservations =
                candidateAddCount;

            return reservation;
        }
    }

    void requestCancel(
            OrderManager.Side side) {

        ensureOpen();
        validateSide(side);

        if (!transportTrusted.getAsBoolean()) {
            throw new IllegalStateException(
                "transport is not trusted for cancel");
        }

        CancelReservation reservation =
            tryReserveCancelCapacity();

        if (reservation == null) {
            logger.warning(
                "Quoter cancel deferred by exchange max_tps side="
                    + side);
            return;
        }

        try (reservation) {
            final String orderId;

            synchronized (orderManager) {
                orderId =
                    orderManager.orderId(side);

                if (orderId == null) {
                    throw new IllegalStateException(
                        "no current order to cancel");
                }

                orderManager.beginCancel(
                    side,
                    orderId);
            }

            String payload =
                sender
                    + " C "
                    + feed
                    + " "
                    + orderId;

            dispatch(
                side,
                orderId,
                payload,
                cancelUncertaintyTimeout,
                false,
                null,
                reservation);
        }
    }

    private CancelReservation
            tryReserveCancelCapacity() {

        long now =
            monotonicClock.getAsLong();

        synchronized (tpsLock) {
            pruneTpsWindow(now);

            long committed =
                (long) requestTimesNanos.size()
                    + outstandingAddReservations
                    + outstandingCancelReservations
                    + 1L;

            if (metadata.getMaxTps() > 0
                    && committed > metadata.getMaxTps()) {
                return null;
            }

            outstandingCancelReservations++;

            return new CancelReservation(this);
        }
    }

    /*
     * Package-private for deterministic race tests.
     *
     * The deadline is independent of Y. If Y arrived but no authoritative
     * lifecycle evidence moved the same order out of PENDING_* by this time,
     * the request becomes UNKNOWN.
     */
    void onRequestDeadline(
            OrderManager.Side side,
            String orderId) {

        markPendingUncertain(
            side,
            orderId,
            "authoritative lifecycle deadline expired",
            null);
    }

    private void dispatch(
            OrderManager.Side side,
            String orderId,
            String payload,
            Duration uncertaintyTimeout,
            boolean requiresAddEnvironmentReady,
            AddReservation addReservation,
            CancelReservation cancelReservation) {

        try {
            deadlineScheduler.schedule(
                () -> onRequestDeadline(
                    side,
                    orderId),
                uncertaintyTimeout.toNanos(),
                TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            if (requiresAddEnvironmentReady) {
                orderManager.abortPendingAddIfCurrent(
                    side,
                    orderId);
            } else {
                markPendingUncertain(
                    side,
                    orderId,
                    "failed to schedule request deadline",
                    e);
            }
            throw e;
        }

        /*
         * Re-check immediately before network dispatch. The production NATS
         * connection also disables reconnect buffering, so a request is not
         * intentionally queued to appear after trust loss.
         *
         * For Add, a failed check here is a definite local abort: the request
         * has not crossed transport.request(), so the Exchange cannot have seen
         * this Add. Restore the exact still-pending Add to EMPTY instead of
         * inventing lifecycle uncertainty. Cancel keeps the conservative
         * UNKNOWN behavior because the resting order may already exist remotely.
         */
        if (!transportTrusted.getAsBoolean()) {
            if (requiresAddEnvironmentReady) {
                orderManager.abortPendingAddIfCurrent(
                    side,
                    orderId);
            } else {
                markPendingUncertain(
                    side,
                    orderId,
                    "transport lost before request dispatch",
                    null);
            }
            return;
        }

        /*
         * Re-check exposure readiness at the actual network-dispatch boundary.
         * A quote decision may have been valid when requestAdd() started but
         * become unsafe after local PENDING_ADD registration and before the
         * request leaves the process.
         *
         * Cancel is risk-reducing and deliberately bypasses this gate.
         */
        if (requiresAddEnvironmentReady
                && !addEnvironmentReady.getAsBoolean()) {

            orderManager.abortPendingAddIfCurrent(
                side,
                orderId);
            return;
        }

        /*
         * Convert reservation into actual TPS usage immediately before the
         * transport attempt. A transport exception still consumes the token
         * because the Exchange may have observed the request.
         */
        if (requiresAddEnvironmentReady) {
            if (!consumeAddReservation(
                    addReservation)) {

                orderManager.abortPendingAddIfCurrent(
                    side,
                    orderId);
                return;
            }
        } else if (!consumeCancelReservation(
                cancelReservation)) {

            markPendingUncertain(
                side,
                orderId,
                "cancel TPS reservation was lost before dispatch",
                null);
            return;
        }

        final CompletableFuture<byte[]> replyFuture;

        try {
            replyFuture =
                transport.request(
                    "ex.req." + sender,
                    payload.getBytes(
                        StandardCharsets.UTF_8),
                    uncertaintyTimeout);

            if (replyFuture == null) {
                throw new IllegalStateException(
                    "request transport returned null future");
            }
        } catch (RuntimeException e) {
            markPendingUncertain(
                side,
                orderId,
                "request dispatch failed",
                e);
            return;
        }

        replyFuture.whenComplete(
            (reply, error) -> {
                if (error != null) {
                    markPendingUncertain(
                        side,
                        orderId,
                        "request completed exceptionally",
                        error);
                    return;
                }

                handleReply(
                    side,
                    orderId,
                    reply);
            });
    }

    private void handleReply(
            OrderManager.Side side,
            String orderId,
            byte[] replyBytes) {

        String raw = replyBytes == null
            ? "<null>"
            : new String(
                replyBytes,
                StandardCharsets.UTF_8);

        try {
            if (replyBytes == null) {
                throw new IllegalArgumentException(
                    "reply payload is null");
            }

            String trimmed = raw.trim();

            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException(
                    "reply payload is empty");
            }

            String[] parts =
                trimmed.split("\\s+");

            if (parts.length < 3) {
                throw new IllegalArgumentException(
                    "reply has too few fields");
            }

            String outcome = parts[1];

            if ("Y".equals(outcome)) {
                if (parts.length != 3) {
                    throw new IllegalArgumentException(
                        "Y reply must have exactly 3 fields");
                }

                parseNonNegativeLong(
                    parts[2],
                    "Y quantity");

                /*
                 * Accepted request reply is deliberately NOT lifecycle
                 * authority. The scheduled authoritative-evidence deadline
                 * remains active.
                 */
                return;
            }

            if ("N".equals(outcome)) {
                if (parts.length < 4) {
                    throw new IllegalArgumentException(
                        "N reply must include code and text");
                }

                parseNonNegativeLong(
                    parts[2],
                    "N code");

                markPendingUncertain(
                    side,
                    orderId,
                    "exchange rejected request: " + raw,
                    null);
                return;
            }

            throw new IllegalArgumentException(
                "unsupported request reply outcome "
                    + outcome);

        } catch (RuntimeException e) {
            markPendingUncertain(
                side,
                orderId,
                "malformed request reply: " + raw,
                e);
        }
    }

    private void markPendingUncertain(
            OrderManager.Side side,
            String orderId,
            String reason,
            Throwable error) {

        boolean changed =
            orderManager
                .markRequestUncertainIfPending(
                    side,
                    orderId);

        if (!changed) {
            /*
             * Authoritative lifecycle evidence or an earlier fail-closed
             * transition already won this race.
             */
            return;
        }

        /*
         * Wake reconciliation immediately after the shared OrderManager has
         * become UNKNOWN. This callback owns no order state.
         */
        lifecycleStateChanged.run();

        if (error == null) {
            logger.warning(
                "Quoter request became UNKNOWN: "
                    + reason
                    + " side="
                    + side
                    + " orderId="
                    + orderId);
        } else {
            logger.log(
                Level.WARNING,
                "Quoter request became UNKNOWN: "
                    + reason
                    + " side="
                    + side
                    + " orderId="
                    + orderId,
                error);
        }
    }

    private int cancellationObligations() {
        synchronized (orderManager) {
            int count = 0;

            count +=
                hasFutureCancelObligation(
                    OrderManager.Side.BID)
                    ? 1
                    : 0;

            count +=
                hasFutureCancelObligation(
                    OrderManager.Side.ASK)
                    ? 1
                    : 0;

            return count;
        }
    }

    private boolean hasFutureCancelObligation(
            OrderManager.Side side) {

        OrderManager.State state =
            orderManager.state(side);

        return state == OrderManager.State.ACTIVE
            || state == OrderManager.State.PENDING_ADD
            || state == OrderManager.State.UNKNOWN;
    }

    private boolean consumeAddReservation(
            AddReservation reservation) {

        if (reservation == null
                || reservation.owner != this) {

            return false;
        }

        long now =
            monotonicClock.getAsLong();

        synchronized (tpsLock) {
            pruneTpsWindow(now);

            if (reservation.closed
                    || reservation != activeAddReservation
                    || reservation.remaining <= 0
                    || outstandingAddReservations <= 0) {

                return false;
            }

            reservation.remaining--;
            outstandingAddReservations--;

            if (reservation.remaining == 0) {
                activeAddReservation = null;
            }

            requestTimesNanos.addLast(now);
            assertTpsCommitmentWithinLimit();

            return true;
        }
    }

    private boolean consumeCancelReservation(
            CancelReservation reservation) {

        if (reservation == null
                || reservation.owner != this) {

            return false;
        }

        long now =
            monotonicClock.getAsLong();

        synchronized (tpsLock) {
            pruneTpsWindow(now);

            if (reservation.closed
                    || reservation.consumed
                    || outstandingCancelReservations <= 0) {

                return false;
            }

            reservation.consumed = true;
            outstandingCancelReservations--;
            requestTimesNanos.addLast(now);
            assertTpsCommitmentWithinLimit();

            return true;
        }
    }

    private void releaseAddReservation(
            AddReservation reservation) {

        synchronized (tpsLock) {
            if (reservation.closed) {
                return;
            }

            reservation.closed = true;

            if (reservation == activeAddReservation) {
                outstandingAddReservations -=
                    reservation.remaining;

                if (outstandingAddReservations < 0) {
                    throw new IllegalStateException(
                        "negative outstanding Add TPS reservation");
                }

                activeAddReservation = null;
            }

            reservation.remaining = 0;
        }
    }

    private void releaseCancelReservation(
            CancelReservation reservation) {

        synchronized (tpsLock) {
            if (reservation.closed) {
                return;
            }

            reservation.closed = true;

            if (!reservation.consumed) {
                outstandingCancelReservations--;

                if (outstandingCancelReservations < 0) {
                    throw new IllegalStateException(
                        "negative outstanding Cancel TPS reservation");
                }
            }
        }
    }

    private void validateAddReservation(
            AddReservation reservation) {

        if (reservation == null
                || reservation.owner != this) {

            throw new IllegalArgumentException(
                "valid Add TPS reservation is required");
        }

        synchronized (tpsLock) {
            if (reservation.closed
                    || reservation != activeAddReservation
                    || reservation.remaining <= 0) {

                throw new IllegalStateException(
                    "Add TPS reservation is not active");
            }
        }
    }

    private void pruneTpsWindow(
            long now) {

        while (!requestTimesNanos.isEmpty()) {
            long oldest =
                requestTimesNanos.peekFirst();

            if (now - oldest
                    < TPS_WINDOW_NANOS) {

                break;
            }

            requestTimesNanos.pollFirst();
        }
    }

    private void assertTpsCommitmentWithinLimit() {
        if (metadata.getMaxTps() <= 0) {
            return;
        }

        long committed =
            (long) requestTimesNanos.size()
                + outstandingAddReservations
                + outstandingCancelReservations;

        if (committed > metadata.getMaxTps()) {
            throw new IllegalStateException(
                "internal TPS commitment exceeded exchange max_tps");
        }
    }

    int currentTpsUsageForTest() {
        long now =
            monotonicClock.getAsLong();

        synchronized (tpsLock) {
            pruneTpsWindow(now);
            return requestTimesNanos.size();
        }
    }

    int outstandingAddReservationsForTest() {
        synchronized (tpsLock) {
            return outstandingAddReservations;
        }
    }

    static final class AddReservation
            implements AutoCloseable {

        private final OrderRequestClient owner;
        private int remaining;
        private boolean closed;

        private AddReservation(
                OrderRequestClient owner,
                int remaining) {

            this.owner = owner;
            this.remaining = remaining;
        }

        int remainingForTest() {
            synchronized (owner.tpsLock) {
                return remaining;
            }
        }

        @Override
        public void close() {
            owner.releaseAddReservation(this);
        }
    }

    private static final class CancelReservation
            implements AutoCloseable {

        private final OrderRequestClient owner;
        private boolean consumed;
        private boolean closed;

        private CancelReservation(
                OrderRequestClient owner) {

            this.owner = owner;
        }

        @Override
        public void close() {
            owner.releaseCancelReservation(this);
        }
    }

    private void validateAddQuantity(
            int quantity) {

        if (!metadata.isVolumeWithinBounds(
                quantity)) {

            throw new IllegalArgumentException(
                "quantity must be within metadata volume bounds ["
                    + metadata.getMinVolume()
                    + ", "
                    + metadata.getMaxVolume()
                    + "], saw "
                    + quantity);
        }
    }

    private void validateAddPrice(
            long price) {

        if (price <= 0L) {
            throw new IllegalArgumentException(
                "price must be positive");
        }

        BigDecimal value =
            BigDecimal.valueOf(price);

        if (!metadata.isPriceWithinBounds(value)) {
            throw new IllegalArgumentException(
                "price is outside metadata bounds");
        }

        if (value.remainder(
                metadata.getTickSize())
                .compareTo(BigDecimal.ZERO) != 0) {

            throw new IllegalArgumentException(
                "price is not aligned to tick size");
        }
    }

    private void validateOrderId(
            String orderId) {

        if (orderId == null
                || orderId.length() != 8
                || containsWhitespace(orderId)) {

            throw new IllegalArgumentException(
                "order id must be exactly 8 non-whitespace characters");
        }
    }

    private void validateSide(
            OrderManager.Side side) {

        if (side == null) {
            throw new IllegalArgumentException(
                "side is required");
        }
    }

    private char wireSide(
            OrderManager.Side side) {

        return side == OrderManager.Side.BID
            ? 'B'
            : 'S';
    }

    private static boolean containsWhitespace(
            String value) {

        return value
            .chars()
            .anyMatch(Character::isWhitespace);
    }

    private static long parseNonNegativeLong(
            String value,
            String fieldName) {

        final long parsed;

        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                fieldName + " must be an integer",
                e);
        }

        if (parsed < 0L) {
            throw new IllegalArgumentException(
                fieldName + " must be non-negative");
        }

        return parsed;
    }

    private static void validateDuration(
            Duration duration,
            String fieldName) {

        if (duration == null
                || duration.isZero()
                || duration.isNegative()) {

            throw new IllegalArgumentException(
                fieldName + " must be positive");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                "order request client is closed");
        }
    }

    private static ScheduledExecutorService
            newDeadlineScheduler() {

        return Executors
            .newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread =
                        new Thread(
                            runnable,
                            "quoter-request-deadline");

                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Override
    public void close() {
        closed = true;
        deadlineScheduler.shutdownNow();
    }
}
