package com.trv.quoter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReconciliationCoordinatorTest {

    private ScheduledExecutorService scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    void initializeCapturesFloorBeforeExposure() {
        Fixture f = fixture();

        f.replay.window =
            new ReconciliationCoordinator.StreamWindow(90, 100);

        f.coordinator.initialize();

        assertEquals(
            ReconciliationCoordinator.State.HEALTHY,
            f.coordinator.state());
        assertEquals(
            101L,
            f.coordinator.epochFloorSeqForTest());
    }

    @Test
    void prepareForNewExposureRefreshesFloorWhenBothSlotsEmpty() {
        Fixture f = fixture();

        f.replay.window =
            new ReconciliationCoordinator.StreamWindow(90, 100);
        f.coordinator.initialize();

        f.replay.window =
            new ReconciliationCoordinator.StreamWindow(190, 200);

        f.coordinator.prepareForNewExposure();

        assertEquals(
            201L,
            f.coordinator.epochFloorSeqForTest());
    }

    @Test
    void unknownImmediatelyEntersRecoveryAndCancelsWithoutMinimumWait() {
        Fixture f = fixture();

        f.replay.window =
            new ReconciliationCoordinator.StreamWindow(90, 100);
        f.coordinator.initialize();

        f.orders.beginAdd(
            OrderManager.Side.BID,
            "BID00001",
            10);
        f.orders.markRequestUncertain(
            OrderManager.Side.BID,
            "BID00001");

        f.coordinator.runOneCycleForTest();

        assertEquals(
            ReconciliationCoordinator.State.RECOVERING,
            f.coordinator.state());
        assertEquals(1, f.cancel.bidCancels);
    }

    @Test
    void replayCanResolveUnknownToEmptyAndCloseEpoch() {
        Fixture f = fixture();

        f.replay.window =
            new ReconciliationCoordinator.StreamWindow(90, 100);
        f.coordinator.initialize();

        f.orders.beginAdd(
            OrderManager.Side.BID,
            "BID00001",
            10);
        f.orders.markRequestUncertain(
            OrderManager.Side.BID,
            "BID00001");

        f.replay.window =
            new ReconciliationCoordinator.StreamWindow(90, 105);
        f.replay.messages.add(
            new ReconciliationCoordinator.ReplayMessage(
                103,
                "103 C QUOTE001:BID00001"
                    .getBytes()));

        f.sink.onAccept =
            data -> f.orders.onCancelled(
                OrderManager.Side.BID,
                "BID00001");

        f.replay.nextWindowAfterReplay =
            new ReconciliationCoordinator.StreamWindow(90, 110);

        f.coordinator.runOneCycleForTest();

        assertEquals(
            OrderManager.State.EMPTY,
            f.orders.state(OrderManager.Side.BID));
        assertEquals(
            ReconciliationCoordinator.State.HEALTHY,
            f.coordinator.state());
        assertTrue(f.sink.dedupCleared);
        assertEquals(
            111L,
            f.coordinator.epochFloorSeqForTest());
    }

    @Test
    void activeAfterReplayIsNotSuccessfulRecovery() {
        Fixture f = fixture();

        f.replay.window =
            new ReconciliationCoordinator.StreamWindow(90, 100);
        f.coordinator.initialize();

        f.orders.beginAdd(
            OrderManager.Side.BID,
            "BID00001",
            10);
        f.orders.markRequestUncertain(
            OrderManager.Side.BID,
            "BID00001");

        f.replay.window =
            new ReconciliationCoordinator.StreamWindow(90, 105);
        f.replay.messages.add(
            new ReconciliationCoordinator.ReplayMessage(
                103,
                "103 A QUOTE001:BID00001 B 10 500"
                    .getBytes()));

        /*
         * Simulate authoritative A winning before cancel intent is recorded.
         * Recovery still must not succeed merely because UNKNOWN resolved.
         */
        f.sink.onAccept =
            data -> f.orders.onResting(
                OrderManager.Side.BID,
                "BID00001");

        f.coordinator.runOneCycleForTest();

        assertEquals(
            OrderManager.State.UNKNOWN,
            f.orders.state(OrderManager.Side.BID));
        assertEquals(
            ReconciliationCoordinator.State.RECOVERING,
            f.coordinator.state());
        assertFalse(f.sink.dedupCleared);
    }

    @Test
    void requiredHistoryEvictionIsImmediatelyFatal() {
        Fixture f = fixture();

        f.replay.window =
            new ReconciliationCoordinator.StreamWindow(90, 100);
        f.coordinator.initialize();

        f.orders.beginAdd(
            OrderManager.Side.ASK,
            "ASK00001",
            5);
        f.orders.markRequestUncertain(
            OrderManager.Side.ASK,
            "ASK00001");

        f.replay.window =
            new ReconciliationCoordinator.StreamWindow(150, 200);

        f.coordinator.runOneCycleForTest();

        assertEquals(
            ReconciliationCoordinator.State.FATAL,
            f.coordinator.state());
        assertTrue(
            f.coordinator.fatalReason()
                .contains("evicted"));
    }

    @Test
    void outageTimeDoesNotConsumeRecoveryDeadline() {
        Fixture f = fixture();

        f.replay.window =
            new ReconciliationCoordinator.StreamWindow(90, 100);
        f.coordinator.initialize();

        f.orders.beginAdd(
            OrderManager.Side.BID,
            "BID00001",
            1);
        f.orders.markRequestUncertain(
            OrderManager.Side.BID,
            "BID00001");

        f.usable.set(false);
        f.coordinator.runOneCycleForTest();

        f.clock.addAndGet(
            Duration.ofSeconds(30).toNanos());
        f.coordinator.runOneCycleForTest();

        assertEquals(
            ReconciliationCoordinator.State.RECOVERING,
            f.coordinator.state());

        f.usable.set(true);
        f.coordinator.runOneCycleForTest();

        assertEquals(
            ReconciliationCoordinator.State.RECOVERING,
            f.coordinator.state());
    }

    @Test
    void fourSecondsOfUsableRecoveryEndsFatalIfStillOccupied() {
        Fixture f = fixture();

        f.replay.window =
            new ReconciliationCoordinator.StreamWindow(90, 100);
        f.coordinator.initialize();

        f.orders.beginAdd(
            OrderManager.Side.BID,
            "BID00001",
            1);
        f.orders.markRequestUncertain(
            OrderManager.Side.BID,
            "BID00001");

        f.coordinator.runOneCycleForTest();

        for (int i = 0; i < 9; i++) {
            f.clock.addAndGet(
                Duration.ofMillis(500).toNanos());
            f.coordinator.runOneCycleForTest();
        }

        assertEquals(
            ReconciliationCoordinator.State.FATAL,
            f.coordinator.state());
        assertTrue(
            f.coordinator.fatalReason()
                .contains("usable recovery time"));
    }

    @Test
    void prepareForExposureIsRejectedDuringRecovery() {
        Fixture f = fixture();

        f.replay.window =
            new ReconciliationCoordinator.StreamWindow(90, 100);
        f.coordinator.initialize();

        f.orders.beginAdd(
            OrderManager.Side.BID,
            "BID00001",
            1);
        f.orders.markRequestUncertain(
            OrderManager.Side.BID,
            "BID00001");

        f.coordinator.runOneCycleForTest();

        assertThrows(
            IllegalStateException.class,
            f.coordinator::prepareForNewExposure);
    }

    private Fixture fixture() {
        OrderManager orders =
            new OrderManager();
        AtomicBoolean usable =
            new AtomicBoolean(true);
        AtomicLong clock =
            new AtomicLong(1L);

        FakeReplay replay =
            new FakeReplay();
        FakeSink sink =
            new FakeSink();
        FakeCancel cancel =
            new FakeCancel(orders);

        scheduler =
            Executors.newSingleThreadScheduledExecutor();

        ReconciliationCoordinator coordinator =
            new ReconciliationCoordinator(
                orders,
                usable::get,
                replay,
                sink,
                cancel,
                clock::get,
                scheduler,
                Duration.ofMillis(500),
                Duration.ofSeconds(4),
                Duration.ofMillis(50),
                false);

        return new Fixture(
            orders,
            usable,
            clock,
            replay,
            sink,
            cancel,
            coordinator);
    }

    private record Fixture(
        OrderManager orders,
        AtomicBoolean usable,
        AtomicLong clock,
        FakeReplay replay,
        FakeSink sink,
        FakeCancel cancel,
        ReconciliationCoordinator coordinator) {
    }

    private static final class FakeReplay
            implements ReconciliationCoordinator.ReplaySource {

        private ReconciliationCoordinator.StreamWindow window =
            new ReconciliationCoordinator.StreamWindow(0, 0);

        private ReconciliationCoordinator.StreamWindow nextWindowAfterReplay;

        private final Queue<ReconciliationCoordinator.ReplayMessage>
            messages = new ArrayDeque<>();

        private int snapshotCount;

        @Override
        public ReconciliationCoordinator.StreamWindow snapshot() {
            snapshotCount++;

            if (nextWindowAfterReplay != null
                    && snapshotCount >= 3) {
                return nextWindowAfterReplay;
            }

            return window;
        }

        @Override
        public ReconciliationCoordinator.ReplayMessage next(
                long cursor) {

            while (!messages.isEmpty()) {
                ReconciliationCoordinator.ReplayMessage message =
                    messages.peek();

                if (message.sequence() < cursor) {
                    messages.remove();
                    continue;
                }

                return messages.remove();
            }

            return null;
        }
    }

    private static final class FakeSink
            implements ReconciliationCoordinator.LifecycleSink {

        private java.util.function.Consumer<byte[]> onAccept =
            data -> {
            };

        private boolean dedupCleared;

        @Override
        public void accept(byte[] data) {
            onAccept.accept(data);
        }

        @Override
        public void clearExecutionDedupForReconciledEpoch() {
            dedupCleared = true;
        }
    }

    private static final class FakeCancel
            implements ReconciliationCoordinator.CancelAction {

        private final OrderManager orders;
        private int bidCancels;
        private int askCancels;

        FakeCancel(OrderManager orders) {
            this.orders = orders;
        }

        @Override
        public void cancel(OrderManager.Side side) {
            if (side == OrderManager.Side.BID) {
                bidCancels++;
            } else {
                askCancels++;
            }

            String id =
                orders.orderId(side);

            if (id != null) {
                orders.beginCancel(side, id);
            }
        }
    }
}
