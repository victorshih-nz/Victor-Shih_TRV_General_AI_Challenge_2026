package com.trv.quoter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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

        /*
         * This is only the foundation safety gate.
         * Profitability/inventory/adverse-selection policy belongs to the
         * quote-policy stage.
         */
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

            /*
             * Register local intent before network dispatch so an extremely
             * fast A/E/T/C cannot arrive while the slot still appears EMPTY.
             */
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
            addUncertaintyTimeout);
    }

    void requestCancel(
            OrderManager.Side side) {

        ensureOpen();
        validateSide(side);

        /*
         * Cancel is risk-reducing and does not require BBO/risk readiness.
         * It does require a currently trusted transport.
         */
        if (!transportTrusted.getAsBoolean()) {
            throw new IllegalStateException(
                "transport is not trusted for cancel");
        }

        final String orderId;

        synchronized (orderManager) {
            orderId =
                orderManager.orderId(side);

            if (orderId == null) {
                throw new IllegalStateException(
                    "no current order to cancel");
            }

            /*
             * ACTIVE -> PENDING_CANCEL.
             * UNKNOWN remains UNKNOWN while recording cancel intent.
             * Other states are rejected by OrderManager.
             */
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
            cancelUncertaintyTimeout);
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
            Duration uncertaintyTimeout) {

        try {
            deadlineScheduler.schedule(
                () -> onRequestDeadline(
                    side,
                    orderId),
                uncertaintyTimeout.toNanos(),
                TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            markPendingUncertain(
                side,
                orderId,
                "failed to schedule request deadline",
                e);
            throw e;
        }

        /*
         * Re-check immediately before network dispatch. The production NATS
         * connection also disables reconnect buffering, so a request is not
         * intentionally queued to appear after trust loss.
         */
        if (!transportTrusted.getAsBoolean()) {
            markPendingUncertain(
                side,
                orderId,
                "transport lost before request dispatch",
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

    private void validateAddQuantity(
            int quantity) {

        if (quantity <= 0) {
            throw new IllegalArgumentException(
                "quantity must be positive");
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
