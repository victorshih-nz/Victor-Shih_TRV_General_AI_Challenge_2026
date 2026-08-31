package com.trv.quoter;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fail-closed Quoter lifecycle reconciliation coordinator.
 *
 * OrderManager remains the only order-state authority. This class owns only
 * reconciliation epoch metadata, replay progress and recovery timing.
 */
final class ReconciliationCoordinator implements AutoCloseable {

    enum State {
        HEALTHY,
        RECOVERING,
        FATAL
    }

    record StreamWindow(long firstSequence, long lastSequence) {
        StreamWindow {
            if (firstSequence < 0L || lastSequence < 0L) {
                throw new IllegalArgumentException(
                    "stream sequences must be non-negative");
            }
            if (lastSequence > 0L
                    && firstSequence > lastSequence) {
                throw new IllegalArgumentException(
                    "firstSequence cannot exceed lastSequence");
            }
        }
    }

    record ReplayMessage(long sequence, byte[] data) {
        ReplayMessage {
            if (sequence <= 0L) {
                throw new IllegalArgumentException(
                    "replay sequence must be positive");
            }
            Objects.requireNonNull(data, "replay data is required");
        }
    }

    @FunctionalInterface
    interface CancelAction {
        void cancel(OrderManager.Side side);
    }

    interface ReplaySource {
        StreamWindow snapshot() throws Exception;

        /**
         * Returns the first exact-subject message with stream sequence >= cursor,
         * or null when no matching message currently exists.
         */
        ReplayMessage next(long cursor) throws Exception;
    }

    interface LifecycleSink {
        void accept(byte[] data);

        /**
         * Must serialize with normal live/replay lifecycle routing and must
         * reject clearing unless both OrderManager slots are EMPTY.
         */
        void clearExecutionDedupForReconciledEpoch();
    }

    private static final Logger logger =
        Logger.getLogger(ReconciliationCoordinator.class.getName());

    private static final Duration DEFAULT_RETRY_INTERVAL =
        Duration.ofMillis(500);
    private static final Duration DEFAULT_FINAL_DEADLINE =
        Duration.ofSeconds(4);
    private static final Duration DEFAULT_TICK_INTERVAL =
        Duration.ofMillis(50);
    private static final int MAX_REPLAY_MESSAGES_PER_ATTEMPT = 10_000;

    private final OrderManager orderManager;
    private final BooleanSupplier recoveryInfrastructureUsable;
    private final ReplaySource replaySource;
    private final LifecycleSink lifecycleSink;
    private final CancelAction cancelAction;
    private final LongSupplier nanoTime;
    private final ScheduledExecutorService scheduler;
    private final long retryIntervalNanos;
    private final long finalDeadlineNanos;
    private final long tickIntervalNanos;
    private final boolean backgroundScheduling;

    private volatile State state = State.RECOVERING;
    private volatile String fatalReason;

    /*
     * The earliest stream sequence that can contain lifecycle evidence for the
     * current exposure epoch. New exposure is never registered before this is
     * captured.
     */
    private long epochFloorSeq;

    /*
     * Recovery deadline counts only time during which recovery infrastructure
     * was usable. Full transport outage time is deliberately excluded.
     */
    private long usableRecoveryNanos;
    private long lastCycleNanos;
    private boolean previousCycleUsable;
    private long lastAttemptUsableNanos = -1L;

    private boolean initialized;
    private boolean closed;

    ReconciliationCoordinator(
            OrderManager orderManager,
            BooleanSupplier recoveryInfrastructureUsable,
            ReplaySource replaySource,
            LifecycleSink lifecycleSink,
            CancelAction cancelAction) {

        this(
            orderManager,
            recoveryInfrastructureUsable,
            replaySource,
            lifecycleSink,
            cancelAction,
            System::nanoTime,
            newScheduler(),
            DEFAULT_RETRY_INTERVAL,
            DEFAULT_FINAL_DEADLINE,
            DEFAULT_TICK_INTERVAL,
            true);
    }

    ReconciliationCoordinator(
            OrderManager orderManager,
            BooleanSupplier recoveryInfrastructureUsable,
            ReplaySource replaySource,
            LifecycleSink lifecycleSink,
            CancelAction cancelAction,
            LongSupplier nanoTime,
            ScheduledExecutorService scheduler,
            Duration retryInterval,
            Duration finalDeadline,
            Duration tickInterval,
            boolean backgroundScheduling) {

        this.orderManager =
            Objects.requireNonNull(orderManager, "orderManager is required");
        this.recoveryInfrastructureUsable =
            Objects.requireNonNull(
                recoveryInfrastructureUsable,
                "recoveryInfrastructureUsable is required");
        this.replaySource =
            Objects.requireNonNull(replaySource, "replaySource is required");
        this.lifecycleSink =
            Objects.requireNonNull(lifecycleSink, "lifecycleSink is required");
        this.cancelAction =
            Objects.requireNonNull(cancelAction, "cancelAction is required");
        this.nanoTime =
            Objects.requireNonNull(nanoTime, "nanoTime is required");
        this.scheduler =
            Objects.requireNonNull(scheduler, "scheduler is required");

        validateDuration(retryInterval, "retryInterval");
        validateDuration(finalDeadline, "finalDeadline");
        validateDuration(tickInterval, "tickInterval");

        if (finalDeadline.compareTo(retryInterval) <= 0) {
            throw new IllegalArgumentException(
                "finalDeadline must exceed retryInterval");
        }

        this.retryIntervalNanos = retryInterval.toNanos();
        this.finalDeadlineNanos = finalDeadline.toNanos();
        this.tickIntervalNanos = tickInterval.toNanos();
        this.backgroundScheduling = backgroundScheduling;
    }

    /**
     * Establishes the first epoch floor before Quoter exposure can be opened.
     */
    synchronized void initialize() {
        ensureOpen();

        if (initialized) {
            return;
        }

        if (!bothSlotsEmpty()) {
            throw new IllegalStateException(
                "reconciliation must initialize with empty order slots");
        }

        try {
            epochFloorSeq = nextSequence(replaySource.snapshot().lastSequence());
        } catch (Exception e) {
            throw new IllegalStateException(
                "failed to capture initial reconciliation epoch floor",
                e);
        }

        state = State.HEALTHY;
        initialized = true;
        resetRecoveryTiming();

        if (backgroundScheduling) {
            scheduler.scheduleWithFixedDelay(
                this::runCycleSafely,
                tickIntervalNanos,
                tickIntervalNanos,
                TimeUnit.NANOSECONDS);
        }
    }

    State state() {
        return state;
    }

    boolean isHealthy() {
        return state == State.HEALTHY;
    }

    String fatalReason() {
        return fatalReason;
    }

    synchronized long epochFloorSeqForTest() {
        return epochFloorSeq;
    }

    /**
     * Called immediately before production registers a new Add.
     *
     * When there is no current Quoter exposure, refresh the floor so a long
     * idle period cannot make the next recovery depend on already-evicted
     * unrelated EX_MD history.
     *
     * QuoterIntegration must serialize this call with requestAdd registration.
     */
    synchronized void prepareForNewExposure() {
        ensureInitializedAndOpen();

        if (state != State.HEALTHY) {
            throw new IllegalStateException(
                "reconciliation is not healthy");
        }

        if (!orderManager.isReconciled()) {
            throw new IllegalStateException(
                "order lifecycle is not reconciled");
        }

        if (!bothSlotsEmpty()) {
            return;
        }

        try {
            epochFloorSeq = nextSequence(replaySource.snapshot().lastSequence());
        } catch (Exception e) {
            throw new IllegalStateException(
                "failed to refresh reconciliation epoch floor",
                e);
        }
    }

    /**
     * Fast notification after lifecycle/request state may have changed.
     * The scheduler remains the fallback, but UNKNOWN recovery should not wait
     * for the next periodic tick.
     */
    void signal() {
        if (!initialized || closed) {
            return;
        }

        try {
            scheduler.execute(this::runCycleSafely);
        } catch (RuntimeException e) {
            if (!closed) {
                failFatal(
                    "failed to schedule reconciliation cycle",
                    e);
            }
        }
    }

    /**
     * Deterministic unit-test hook. Production uses signal + background tick.
     */
    void runOneCycleForTest() {
        runCycleSafely();
    }

    private void runCycleSafely() {
        try {
            runCycle();
        } catch (Exception e) {
            failFatal(
                "unexpected reconciliation coordinator failure",
                e);
        }
    }

    private synchronized void runCycle() throws Exception {
        if (!initialized || closed || state == State.FATAL) {
            return;
        }

        long now = nanoTime.getAsLong();
        boolean usable =
            recoveryInfrastructureUsable.getAsBoolean();

        if (state == State.HEALTHY) {
            if (!hasUnknownSlot()) {
                return;
            }

            enterRecovering(now, usable);
        } else {
            accumulateUsableRecoveryTime(now, usable);
        }

        if (state != State.RECOVERING || !usable) {
            return;
        }

        boolean attemptDue =
            lastAttemptUsableNanos < 0L
                || usableRecoveryNanos - lastAttemptUsableNanos
                    >= retryIntervalNanos
                || usableRecoveryNanos >= finalDeadlineNanos;

        if (!attemptDue) {
            return;
        }

        lastAttemptUsableNanos = usableRecoveryNanos;
        boolean completed = attemptRecovery();

        if (completed) {
            return;
        }

        if (usableRecoveryNanos >= finalDeadlineNanos) {
            failFatalLocked(
                "reconciliation unresolved after "
                    + TimeUnit.NANOSECONDS.toMillis(
                        usableRecoveryNanos)
                    + " ms of usable recovery time",
                null);
        }
    }

    private void enterRecovering(
            long now,
            boolean usable) {

        state = State.RECOVERING;
        fatalReason = null;
        usableRecoveryNanos = 0L;
        lastAttemptUsableNanos = -1L;
        lastCycleNanos = now;
        previousCycleUsable = usable;

        logger.warning(
            "Quoter lifecycle entered RECOVERING");
    }

    private void accumulateUsableRecoveryTime(
            long now,
            boolean usable) {

        if (lastCycleNanos != 0L
                && previousCycleUsable
                && usable
                && now >= lastCycleNanos) {

            usableRecoveryNanos +=
                now - lastCycleNanos;
        }

        lastCycleNanos = now;
        previousCycleUsable = usable;
    }

    /**
     * One bounded recovery attempt:
     *   1. exact-cancel every known occupied slot
     *   2. snapshot EX_MD high-water / retention
     *   3. bounded exact-subject replay through the SAME lifecycle router
     *   4. succeed only if replay reached the snapshot boundary and BOTH slots
     *      are EMPTY
     *   5. clear lifecycle dedup, capture the next epoch floor, then HEALTHY
     */
    private boolean attemptRecovery() throws Exception {
        tryCancel(OrderManager.Side.BID);
        tryCancel(OrderManager.Side.ASK);

        StreamWindow window =
            replaySource.snapshot();

        if (window.firstSequence() > 0L
                && epochFloorSeq < window.firstSequence()) {

            failFatalLocked(
                "required lifecycle history was evicted: epochFloorSeq="
                    + epochFloorSeq
                    + " firstRetainedSeq="
                    + window.firstSequence(),
                null);
            return false;
        }

        long highWaterSeq =
            window.lastSequence();

        long cursor =
            epochFloorSeq;

        int count = 0;

        while (cursor <= highWaterSeq) {
            if (count >= MAX_REPLAY_MESSAGES_PER_ATTEMPT) {
                failFatalLocked(
                    "bounded reconciliation replay exceeded safety limit "
                        + MAX_REPLAY_MESSAGES_PER_ATTEMPT,
                    null);
                return false;
            }

            ReplayMessage message =
                replaySource.next(cursor);

            if (message == null) {
                /*
                 * No exact-subject message exists at/after cursor in the
                 * current stream snapshot. Therefore the bounded subject
                 * window is complete.
                 */
                break;
            }

            long seq =
                message.sequence();

            if (seq < cursor) {
                failFatalLocked(
                    "non-monotonic reconciliation replay: cursor="
                        + cursor
                        + " received="
                        + seq,
                    null);
                return false;
            }

            if (seq > highWaterSeq) {
                break;
            }

            lifecycleSink.accept(
                message.data());

            count++;

            if (seq == Long.MAX_VALUE) {
                break;
            }

            cursor = seq + 1L;
        }

        if (!bothSlotsEmpty()) {
            return false;
        }

        /*
         * New Add exposure is still disabled because state remains RECOVERING.
         * LifecycleSink must serialize this clear with live/replay routing.
         */
        lifecycleSink
            .clearExecutionDedupForReconciledEpoch();

        /*
         * Capture a fresh floor only after the old epoch is authoritatively
         * empty and dedup has been cleared.
         */
        StreamWindow nextWindow =
            replaySource.snapshot();

        epochFloorSeq =
            nextSequence(
                nextWindow.lastSequence());

        state = State.HEALTHY;
        fatalReason = null;
        resetRecoveryTiming();

        logger.info(
            "Quoter lifecycle reconciliation completed; new epoch floor="
                + epochFloorSeq);

        return true;
    }

    private void tryCancel(
            OrderManager.Side side) {

        if (orderManager.orderId(side) == null) {
            return;
        }

        try {
            cancelAction.cancel(side);
        } catch (RuntimeException e) {
            /*
             * A terminal live event may win the race between the current-id
             * check and requestCancel(). If so, there is nothing left to cancel.
             * Otherwise keep RECOVERING and let replay / later attempts resolve.
             */
            if (orderManager.state(side)
                    == OrderManager.State.EMPTY) {
                return;
            }

            logger.log(
                Level.WARNING,
                "Reconciliation exact cancel attempt failed for "
                    + side,
                e);
        }
    }

    private boolean bothSlotsEmpty() {
        return orderManager.state(OrderManager.Side.BID)
                    == OrderManager.State.EMPTY
            && orderManager.state(OrderManager.Side.ASK)
                    == OrderManager.State.EMPTY;
    }

    private boolean hasUnknownSlot() {
        return orderManager.state(OrderManager.Side.BID)
                    == OrderManager.State.UNKNOWN
            || orderManager.state(OrderManager.Side.ASK)
                    == OrderManager.State.UNKNOWN;
    }

    private synchronized void failFatal(
            String reason,
            Throwable error) {

        if (state == State.FATAL) {
            return;
        }

        failFatalLocked(reason, error);
    }

    private void failFatalLocked(
            String reason,
            Throwable error) {

        state = State.FATAL;
        fatalReason = reason;

        if (error == null) {
            logger.severe(
                "Quoter lifecycle reconciliation FATAL: "
                    + reason);
        } else {
            logger.log(
                Level.SEVERE,
                "Quoter lifecycle reconciliation FATAL: "
                    + reason,
                error);
        }
    }

    private void resetRecoveryTiming() {
        usableRecoveryNanos = 0L;
        lastCycleNanos = 0L;
        previousCycleUsable = false;
        lastAttemptUsableNanos = -1L;
    }

    private static long nextSequence(
            long lastSequence) {

        if (lastSequence == Long.MAX_VALUE) {
            throw new IllegalStateException(
                "JetStream sequence exhausted");
        }

        return lastSequence + 1L;
    }

    private static void validateDuration(
            Duration duration,
            String name) {

        if (duration == null
                || duration.isZero()
                || duration.isNegative()) {

            throw new IllegalArgumentException(
                name + " must be positive");
        }
    }

    private void ensureInitializedAndOpen() {
        ensureOpen();

        if (!initialized) {
            throw new IllegalStateException(
                "reconciliation coordinator is not initialized");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                "reconciliation coordinator is closed");
        }
    }

    private static ScheduledExecutorService
            newScheduler() {

        return Executors
            .newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread =
                        new Thread(
                            runnable,
                            "quoter-reconciliation");

                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        scheduler.shutdownNow();
    }
}
