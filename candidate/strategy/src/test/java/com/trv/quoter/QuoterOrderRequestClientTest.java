package com.trv.quoter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class QuoterOrderRequestClientTest {

    private static final String SENDER = "QUOTE001";
    private static final String FEED = "AAH6";

    @Test
    void addRegistersPendingBeforeAsyncNetworkDispatch() {
        try (Fixture f = new Fixture()) {
            f.client.requestAdd(
                OrderManager.Side.BID,
                "BID00001",
                10,
                500);

            assertEquals(
                OrderManager.State.PENDING_ADD,
                f.manager.state(OrderManager.Side.BID));

            assertEquals(
                "ex.req.QUOTE001",
                f.transport.subject);

            assertEquals(
                "QUOTE001 A AAH6 BID00001 B 10 500 L",
                f.transport.payload());

            assertFalse(
                f.transport.orderManagerLockHeldAtDispatch);
        }
    }

    @Test
    void yReplyDoesNotResolvePendingAndDeadlineMakesUnknown() {
        try (Fixture f = new Fixture()) {
            f.client.requestAdd(
                OrderManager.Side.BID,
                "BID00001",
                10,
                500);

            f.transport.reply.complete(
                bytes("EXCHANGE Y 0"));

            assertEquals(
                OrderManager.State.PENDING_ADD,
                f.manager.state(OrderManager.Side.BID));

            f.client.onRequestDeadline(
                OrderManager.Side.BID,
                "BID00001");

            assertEquals(
                OrderManager.State.UNKNOWN,
                f.manager.state(OrderManager.Side.BID));
        }
    }

    @Test
    void authoritativeAWinningRacePreventsLateDeadlineFromMakingUnknown() {
        try (Fixture f = new Fixture()) {
            f.client.requestAdd(
                OrderManager.Side.BID,
                "BID00001",
                10,
                500);

            f.manager.onResting(
                OrderManager.Side.BID,
                "BID00001");

            f.client.onRequestDeadline(
                OrderManager.Side.BID,
                "BID00001");

            assertEquals(
                OrderManager.State.ACTIVE,
                f.manager.state(OrderManager.Side.BID));
        }
    }

    @Test
    void authoritativeFullExecutionWinningRacePreventsLateExceptionFromReopening() {
        try (Fixture f = new Fixture()) {
            f.client.requestAdd(
                OrderManager.Side.BID,
                "BID00001",
                3,
                500);

            f.manager.onExecution(
                OrderManager.Side.BID,
                "BID00001",
                3);

            f.transport.reply.completeExceptionally(
                new RuntimeException("late request failure"));

            assertEquals(
                OrderManager.State.EMPTY,
                f.manager.state(OrderManager.Side.BID));
        }
    }

    @Test
    void lateNAfterAuthoritativeADoesNotOverrideActive() {
        try (Fixture f = new Fixture()) {
            f.client.requestAdd(
                OrderManager.Side.BID,
                "BID00001",
                10,
                500);

            f.manager.onResting(
                OrderManager.Side.BID,
                "BID00001");

            f.transport.reply.complete(
                bytes("EXCHANGE N 203 re-used order id"));

            assertEquals(
                OrderManager.State.ACTIVE,
                f.manager.state(OrderManager.Side.BID));
        }
    }

    @Test
    void nReplyMakesStillPendingRequestUnknown() {
        try (Fixture f = new Fixture()) {
            f.client.requestAdd(
                OrderManager.Side.BID,
                "BID00001",
                10,
                500);

            f.transport.reply.complete(
                bytes("EXCHANGE N 203 re-used order id"));

            assertEquals(
                OrderManager.State.UNKNOWN,
                f.manager.state(OrderManager.Side.BID));
        }
    }

    @Test
    void malformedReplyMakesStillPendingRequestUnknown() {
        try (Fixture f = new Fixture()) {
            f.client.requestAdd(
                OrderManager.Side.BID,
                "BID00001",
                10,
                500);

            f.transport.reply.complete(
                bytes("EXCHANGE WHAT"));

            assertEquals(
                OrderManager.State.UNKNOWN,
                f.manager.state(OrderManager.Side.BID));
        }
    }

    @Test
    void requestExceptionMakesStillPendingRequestUnknown() {
        try (Fixture f = new Fixture()) {
            f.client.requestAdd(
                OrderManager.Side.BID,
                "BID00001",
                10,
                500);

            f.transport.reply.completeExceptionally(
                new RuntimeException("network failure"));

            assertEquals(
                OrderManager.State.UNKNOWN,
                f.manager.state(OrderManager.Side.BID));
        }
    }

    @Test
    void cancelUsesCurrentOrderAtomicallyAndDoesNotRequireAddReadiness() {
        try (Fixture f = new Fixture()) {
            makeActiveAsk(
                f.manager,
                "ASK00001",
                10);

            f.addReady.set(false);

            f.client.requestCancel(
                OrderManager.Side.ASK);

            assertEquals(
                OrderManager.State.PENDING_CANCEL,
                f.manager.state(OrderManager.Side.ASK));

            assertEquals(
                "QUOTE001 C AAH6 ASK00001",
                f.transport.payload());

            assertFalse(
                f.transport.orderManagerLockHeldAtDispatch);
        }
    }

    @Test
    void cancelYDoesNotResolvePendingCancelButAuthoritativeCDoes() {
        try (Fixture f = new Fixture()) {
            makeActiveAsk(
                f.manager,
                "ASK00001",
                10);

            f.client.requestCancel(
                OrderManager.Side.ASK);

            f.transport.reply.complete(
                bytes("EXCHANGE Y 1"));

            assertEquals(
                OrderManager.State.PENDING_CANCEL,
                f.manager.state(OrderManager.Side.ASK));

            f.manager.onCancelled(
                OrderManager.Side.ASK,
                "ASK00001");

            assertEquals(
                OrderManager.State.EMPTY,
                f.manager.state(OrderManager.Side.ASK));

            f.client.onRequestDeadline(
                OrderManager.Side.ASK,
                "ASK00001");

            assertEquals(
                OrderManager.State.EMPTY,
                f.manager.state(OrderManager.Side.ASK));
        }
    }

    @Test
    void addIsBlockedWhenEnvironmentIsNotReady() {
        try (Fixture f = new Fixture()) {
            f.addReady.set(false);

            assertThrows(
                IllegalStateException.class,
                () -> f.client.requestAdd(
                    OrderManager.Side.BID,
                    "BID00001",
                    10,
                    500));

            assertEquals(
                OrderManager.State.EMPTY,
                f.manager.state(OrderManager.Side.BID));

            assertEquals(
                0,
                f.transport.calls);
        }
    }

    @Test
    void addIsBlockedWhenOtherSlotIsUnknown() {
        try (Fixture f = new Fixture()) {
            f.manager.beginAdd(
                OrderManager.Side.ASK,
                "ASK00001",
                10, 100L);

            f.manager.markUnknown(
                OrderManager.Side.ASK,
                "ASK00001");

            assertThrows(
                IllegalStateException.class,
                () -> f.client.requestAdd(
                    OrderManager.Side.BID,
                    "BID00001",
                    10,
                    500));

            assertEquals(
                OrderManager.State.EMPTY,
                f.manager.state(OrderManager.Side.BID));

            assertEquals(
                0,
                f.transport.calls);
        }
    }

    @Test
    void cancelWithKnownUntrustedTransportDoesNotChangeActiveOrder() {
        try (Fixture f = new Fixture()) {
            makeActiveAsk(
                f.manager,
                "ASK00001",
                10);

            f.transportReady.set(false);

            assertThrows(
                IllegalStateException.class,
                () -> f.client.requestCancel(
                    OrderManager.Side.ASK));

            assertEquals(
                OrderManager.State.ACTIVE,
                f.manager.state(OrderManager.Side.ASK));

            assertEquals(
                0,
                f.transport.calls);
        }
    }

    @Test
    void transportLossBetweenCancelRegistrationAndDispatchFailsClosed() {
        OrderManager manager =
            new OrderManager();

        makeActiveAsk(
            manager,
            "ASK00001",
            10);

        AtomicInteger trustChecks =
            new AtomicInteger();

        BooleanSupplier transportTrust = () ->
            trustChecks.incrementAndGet() == 1;

        FakeTransport transport =
            new FakeTransport(manager);

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        OrderRequestClient client =
            new OrderRequestClient(
                SENDER,
                FEED,
                metadata(1),
                manager,
                () -> true,
                transportTrust,
                transport,
                scheduler,
                Duration.ofHours(1),
                Duration.ofHours(1));

        try (client) {
            client.requestCancel(
                OrderManager.Side.ASK);

            assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(OrderManager.Side.ASK));

            assertEquals(
                0,
                transport.calls);
        }
    }

    @Test
    void addReadinessLossBetweenRegistrationAndDispatchAbortsUndispatchedAdd() {
        OrderManager manager =
            new OrderManager();

        AtomicBoolean addReady =
            new AtomicBoolean(true);

        AtomicInteger transportChecks =
            new AtomicInteger();

        BooleanSupplier transportTrust = () -> {
            transportChecks.incrementAndGet();

            /*
             * Deterministically flip desk/exposure readiness at the existing
             * pre-network transport check. This places the state transition
             * after beginAdd() but before transport.request().
             */
            addReady.set(false);
            return true;
        };

        FakeTransport transport =
            new FakeTransport(manager);

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        OrderRequestClient client =
            new OrderRequestClient(
                SENDER,
                FEED,
                metadata(1),
                manager,
                addReady::get,
                transportTrust,
                transport,
                scheduler,
                Duration.ofHours(1),
                Duration.ofHours(1));

        try (client) {
            client.requestAdd(
                OrderManager.Side.BID,
                "BID00001",
                1,
                500);

            assertFalse(addReady.get());
            assertEquals(
                OrderManager.State.EMPTY,
                manager.state(OrderManager.Side.BID));
            assertEquals(0, transport.calls);
            assertEquals(1, transportChecks.get());

            /*
             * The already-scheduled request deadline must not resurrect an
             * Add that was definitely aborted before network dispatch.
             */
            client.onRequestDeadline(
                OrderManager.Side.BID,
                "BID00001");

            assertEquals(
                OrderManager.State.EMPTY,
                manager.state(OrderManager.Side.BID));
        }
    }

    @Test
    void transportLossBeforeAddDispatchAbortsUndispatchedAdd() {
        OrderManager manager =
            new OrderManager();

        AtomicBoolean addReady =
            new AtomicBoolean(true);

        AtomicInteger transportChecks =
            new AtomicInteger();

        BooleanSupplier transportTrust = () -> {
            transportChecks.incrementAndGet();
            return false;
        };

        FakeTransport transport =
            new FakeTransport(manager);

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        OrderRequestClient client =
            new OrderRequestClient(
                SENDER,
                FEED,
                metadata(1),
                manager,
                addReady::get,
                transportTrust,
                transport,
                scheduler,
                Duration.ofHours(1),
                Duration.ofHours(1));

        try (client) {
            client.requestAdd(
                OrderManager.Side.BID,
                "BID00001",
                1,
                500);

            assertEquals(
                OrderManager.State.EMPTY,
                manager.state(OrderManager.Side.BID));

            assertEquals(0, transport.calls);
            assertEquals(1, transportChecks.get());
        }
    }

    @Test
    void cancelStillDispatchesWhenAddEnvironmentIsNotReady() {
        try (Fixture f = new Fixture()) {
            makeActiveAsk(
                f.manager,
                "ASK00001",
                10);

            f.addReady.set(false);

            f.client.requestCancel(
                OrderManager.Side.ASK);

            assertEquals(
                OrderManager.State.PENDING_CANCEL,
                f.manager.state(OrderManager.Side.ASK));
            assertEquals(1, f.transport.calls);
            assertEquals(
                "QUOTE001 C AAH6 ASK00001",
                f.transport.payload());
        }
    }

    @Test
    void addValidatesTickAlignmentAndPriceBandBeforeRegisteringOrder() {
        OrderManager manager =
            new OrderManager();

        FakeTransport transport =
            new FakeTransport(manager);

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        OrderRequestClient client =
            new OrderRequestClient(
                SENDER,
                FEED,
                metadata(5),
                manager,
                () -> true,
                () -> true,
                transport,
                scheduler,
                Duration.ofHours(1),
                Duration.ofHours(1));

        try (client) {
            assertThrows(
                IllegalArgumentException.class,
                () -> client.requestAdd(
                    OrderManager.Side.BID,
                    "BID00001",
                    10,
                    503));

            assertThrows(
                IllegalArgumentException.class,
                () -> client.requestAdd(
                    OrderManager.Side.BID,
                    "BID00001",
                    10,
                    700));

            assertEquals(
                OrderManager.State.EMPTY,
                manager.state(OrderManager.Side.BID));

            assertEquals(
                0,
                transport.calls);
        }
    }

    @Test
    void unknownOrderCanStillReceiveRiskReducingExactCancel() {
        try (Fixture f = new Fixture()) {
            f.manager.beginAdd(
                OrderManager.Side.BID,
                "BID00001",
                10, 100L);

            f.manager.markRequestUncertain(
                OrderManager.Side.BID,
                "BID00001");

            f.client.requestCancel(
                OrderManager.Side.BID);

            assertEquals(
                OrderManager.State.UNKNOWN,
                f.manager.state(OrderManager.Side.BID));

            assertEquals(
                "QUOTE001 C AAH6 BID00001",
                f.transport.payload());
        }
    }

    @Test
    void tpsRollingWindowUsesMonotonicClockAndExpiresAtOneSecond() {
        OrderManager manager =
            new OrderManager();

        FakeTransport transport =
            new FakeTransport(manager);

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        AtomicLong now =
            new AtomicLong(0L);

        OrderRequestClient client =
            new OrderRequestClient(
                SENDER,
                FEED,
                metadataWithMaxTps(3),
                manager,
                () -> true,
                () -> true,
                transport,
                scheduler,
                Duration.ofHours(1),
                Duration.ofHours(1),
                () -> {
                },
                now::get);

        try (client) {
            OrderRequestClient.AddReservation pair =
                client.tryReserveAddCapacity(2);

            assertNotNull(pair);

            try (pair) {
                client.requestAdd(
                    OrderManager.Side.BID,
                    "BIDTPS01",
                    1,
                    500,
                    pair);

                client.requestAdd(
                    OrderManager.Side.ASK,
                    "ASKTPS01",
                    1,
                    500,
                    pair);
            }

            assertEquals(
                2,
                client.currentTpsUsageForTest());

            manager.onExecution(
                OrderManager.Side.BID,
                "BIDTPS01",
                1);

            manager.onExecution(
                OrderManager.Side.ASK,
                "ASKTPS01",
                1);

            assertNull(
                client.tryReserveAddCapacity(1));

            now.set(999_999_999L);

            assertNull(
                client.tryReserveAddCapacity(1));

            now.set(1_000_000_000L);

            OrderRequestClient.AddReservation next =
                client.tryReserveAddCapacity(1);

            assertNotNull(next);
            next.close();
        }
    }

    @Test
    void safePairRequiresTwoAddsPlusOneImmediateCancelCapacity() {
        OrderManager manager =
            new OrderManager();

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        try (OrderRequestClient client =
                new OrderRequestClient(
                    SENDER,
                    FEED,
                    metadataWithMaxTps(2),
                    manager,
                    () -> true,
                    () -> true,
                    (subject, payload, timeout) ->
                        new CompletableFuture<>(),
                    scheduler,
                    Duration.ofHours(1),
                    Duration.ofHours(1))) {

            assertNull(
                client.tryReserveAddCapacity(2));
        }

        ScheduledExecutorService scheduler2 =
            Executors.newSingleThreadScheduledExecutor();

        try (OrderRequestClient client =
                new OrderRequestClient(
                    SENDER,
                    FEED,
                    metadataWithMaxTps(3),
                    new OrderManager(),
                    () -> true,
                    () -> true,
                    (subject, payload, timeout) ->
                        new CompletableFuture<>(),
                    scheduler2,
                    Duration.ofHours(1),
                    Duration.ofHours(1))) {

            OrderRequestClient.AddReservation pair =
                client.tryReserveAddCapacity(2);

            assertNotNull(pair);
            pair.close();
        }
    }

    @Test
    void cancelCanUseEmergencyCapacityWithoutStealingReservedSecondAdd() {
        OrderManager manager =
            new OrderManager();

        FakeTransport transport =
            new FakeTransport(manager);

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        OrderRequestClient client =
            new OrderRequestClient(
                SENDER,
                FEED,
                metadataWithMaxTps(3),
                manager,
                () -> true,
                () -> true,
                transport,
                scheduler,
                Duration.ofHours(1),
                Duration.ofHours(1));

        try (client) {
            OrderRequestClient.AddReservation pair =
                client.tryReserveAddCapacity(2);

            assertNotNull(pair);

            try (pair) {
                client.requestAdd(
                    OrderManager.Side.BID,
                    "BIDTPS02",
                    1,
                    500,
                    pair);

                manager.onResting(
                    OrderManager.Side.BID,
                    "BIDTPS02");

                assertEquals(
                    1,
                    client.outstandingAddReservationsForTest());

                client.requestCancel(
                    OrderManager.Side.BID);

                assertEquals(
                    2,
                    client.currentTpsUsageForTest());

                assertEquals(
                    1,
                    client.outstandingAddReservationsForTest());
            }

            assertEquals(
                0,
                client.outstandingAddReservationsForTest());
        }
    }

    @Test
    void unknownCurrentOrderContributesCancelObligationToAddAdmission() {
        OrderManager manager =
            new OrderManager();

        manager.beginAdd(
            OrderManager.Side.BID,
            "BIDTPS03",
            1,
            500L);

        manager.markRequestUncertain(
            OrderManager.Side.BID,
            "BIDTPS03");

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        try (OrderRequestClient client =
                new OrderRequestClient(
                    SENDER,
                    FEED,
                    metadataWithMaxTps(3),
                    manager,
                    () -> true,
                    () -> true,
                    (subject, payload, timeout) ->
                        new CompletableFuture<>(),
                    scheduler,
                    Duration.ofHours(1),
                    Duration.ofHours(1))) {

            assertNull(
                client.tryReserveAddCapacity(2));
        }
    }

    @Test
    void preTransportAddRejectionDoesNotConsumeTps() {
        OrderManager manager =
            new OrderManager();

        AtomicBoolean addReady =
            new AtomicBoolean(false);

        AtomicInteger transportCalls =
            new AtomicInteger();

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        try (OrderRequestClient client =
                new OrderRequestClient(
                    SENDER,
                    FEED,
                    metadataWithMaxTps(2),
                    manager,
                    addReady::get,
                    () -> true,
                    (subject, payload, timeout) -> {
                        transportCalls.incrementAndGet();
                        return new CompletableFuture<>();
                    },
                    scheduler,
                    Duration.ofHours(1),
                    Duration.ofHours(1))) {

            assertThrows(
                IllegalStateException.class,
                () -> client.requestAdd(
                    OrderManager.Side.BID,
                    "BIDTPS04",
                    1,
                    500));

            assertEquals(
                0,
                client.currentTpsUsageForTest());

            assertEquals(
                0,
                transportCalls.get());

            assertEquals(
                OrderManager.State.EMPTY,
                manager.state(
                    OrderManager.Side.BID));
        }
    }

    @Test
    void transportAttemptFailureStillConsumesTps() {
        OrderManager manager =
            new OrderManager();

        AtomicInteger transportCalls =
            new AtomicInteger();

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        try (OrderRequestClient client =
                new OrderRequestClient(
                    SENDER,
                    FEED,
                    metadataWithMaxTps(2),
                    manager,
                    () -> true,
                    () -> true,
                    (subject, payload, timeout) -> {
                        transportCalls.incrementAndGet();
                        throw new RuntimeException("transport failed");
                    },
                    scheduler,
                    Duration.ofHours(1),
                    Duration.ofHours(1))) {

            client.requestAdd(
                OrderManager.Side.BID,
                "BIDTPS05",
                1,
                500);

            assertEquals(
                1,
                transportCalls.get());

            assertEquals(
                1,
                client.currentTpsUsageForTest());

            assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(
                    OrderManager.Side.BID));
        }
    }

    @Test
    void pendingCancelDoesNotDoubleReserveFutureCancelCapacity() {
        OrderManager manager =
            new OrderManager();

        manager.beginAdd(
            OrderManager.Side.BID,
            "BIDTPS06",
            1,
            500L);

        manager.onResting(
            OrderManager.Side.BID,
            "BIDTPS06");

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        try (OrderRequestClient client =
                new OrderRequestClient(
                    SENDER,
                    FEED,
                    metadataWithMaxTps(3),
                    manager,
                    () -> true,
                    () -> true,
                    (subject, payload, timeout) ->
                        new CompletableFuture<>(),
                    scheduler,
                    Duration.ofHours(1),
                    Duration.ofHours(1))) {

            client.requestCancel(
                OrderManager.Side.BID);

            assertEquals(
                OrderManager.State.PENDING_CANCEL,
                manager.state(
                    OrderManager.Side.BID));

            OrderRequestClient.AddReservation next =
                client.tryReserveAddCapacity(1);

            assertNotNull(next);
            next.close();
        }
    }

    @Test
    void eachUnknownCancelRetryConsumesFreshTpsPermit() {
        OrderManager manager =
            new OrderManager();

        manager.beginAdd(
            OrderManager.Side.BID,
            "BIDTPS07",
            1,
            500L);

        manager.onResting(
            OrderManager.Side.BID,
            "BIDTPS07");

        AtomicInteger transportCalls =
            new AtomicInteger();

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        try (OrderRequestClient client =
                new OrderRequestClient(
                    SENDER,
                    FEED,
                    metadataWithMaxTps(2),
                    manager,
                    () -> true,
                    () -> true,
                    (subject, payload, timeout) -> {
                        transportCalls.incrementAndGet();
                        throw new RuntimeException("cancel transport failed");
                    },
                    scheduler,
                    Duration.ofHours(1),
                    Duration.ofHours(1))) {

            client.requestCancel(
                OrderManager.Side.BID);

            assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(
                    OrderManager.Side.BID));

            client.requestCancel(
                OrderManager.Side.BID);

            assertEquals(
                2,
                client.currentTpsUsageForTest());

            assertEquals(
                2,
                transportCalls.get());

            client.requestCancel(
                OrderManager.Side.BID);

            assertEquals(
                2,
                transportCalls.get());
        }
    }

    private static Metadata metadataWithMaxTps(
            int maxTps) {

        return Metadata.parse(
            FEED,
            "ticksize=1 ref_price=500 band=100 "
                + "min_volume=1 max_volume=100 "
                + "position_limit=12 max_tps="
                + maxTps);
    }
    private static void makeActiveAsk(
            OrderManager manager,
            String orderId,
            int quantity) {

        manager.beginAdd(
            OrderManager.Side.ASK,
            orderId,
            quantity, 100L);

        manager.onResting(
            OrderManager.Side.ASK,
            orderId);
    }

    private static Metadata metadata(
            int tickSize) {

        return Metadata.parse(
            FEED,
            "ticksize="
                + tickSize
                + " ref_price=500 band=100 min_volume=1 max_volume=100 position_limit=200 max_tps=100");
    }

    private static byte[] bytes(
            String value) {

        return value.getBytes(
            StandardCharsets.UTF_8);
    }

    private static final class Fixture
            implements AutoCloseable {

        final OrderManager manager =
            new OrderManager();

        final AtomicBoolean addReady =
            new AtomicBoolean(true);

        final AtomicBoolean transportReady =
            new AtomicBoolean(true);

        final FakeTransport transport =
            new FakeTransport(manager);

        final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        final OrderRequestClient client =
            new OrderRequestClient(
                SENDER,
                FEED,
                metadata(1),
                manager,
                addReady::get,
                transportReady::get,
                transport,
                scheduler,
                Duration.ofHours(1),
                Duration.ofHours(1));

        @Override
        public void close() {
            client.close();
        }
    }

    private static final class FakeTransport
            implements OrderRequestClient.RequestTransport {

        private final OrderManager manager;

        private CompletableFuture<byte[]> reply =
            new CompletableFuture<>();

        private String subject;
        private byte[] requestPayload;
        private int calls;
        private boolean orderManagerLockHeldAtDispatch;

        FakeTransport(
                OrderManager manager) {

            this.manager = manager;
        }

        @Override
        public CompletableFuture<byte[]> request(
                String subject,
                byte[] payload,
                Duration replyTimeout) {

            this.calls++;
            this.subject = subject;
            this.requestPayload = payload.clone();
            this.orderManagerLockHeldAtDispatch =
                Thread.holdsLock(manager);

            return reply;
        }

        String payload() {
            return requestPayload == null
                ? null
                : new String(
                    requestPayload,
                    StandardCharsets.UTF_8);
        }
    }

@Test
    void addEnforcesExchangeMinAndMaxVolumeBeforeRegistration() {
        OrderManager manager =
            new OrderManager();

        FakeTransport transport =
            new FakeTransport(manager);

        java.util.concurrent.ScheduledExecutorService scheduler =
            java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor();

        Metadata exchangeMetadata =
            Metadata.parse(
                FEED,
                "ticksize=1 ref_price=500 band=100 "
                    + "min_volume=5 max_volume=10 "
                    + "position_limit=12 max_tps=40");

        OrderRequestClient client =
            new OrderRequestClient(
                SENDER,
                FEED,
                exchangeMetadata,
                manager,
                () -> true,
                () -> true,
                transport,
                scheduler,
                java.time.Duration.ofHours(1),
                java.time.Duration.ofHours(1));

        try (client) {
            assertThrows(
                IllegalArgumentException.class,
                () -> client.requestAdd(
                    OrderManager.Side.BID,
                    "BID00001",
                    4,
                    500));

            assertThrows(
                IllegalArgumentException.class,
                () -> client.requestAdd(
                    OrderManager.Side.BID,
                    "BID00001",
                    11,
                    500));

            assertEquals(
                OrderManager.State.EMPTY,
                manager.state(
                    OrderManager.Side.BID));

            assertEquals(
                0,
                transport.calls);

            client.requestAdd(
                OrderManager.Side.BID,
                "BID00001",
                5,
                500);

            assertEquals(
                OrderManager.State.PENDING_ADD,
                manager.state(
                    OrderManager.Side.BID));

            assertEquals(
                1,
                transport.calls);

            assertEquals(
                "QUOTE001 A AAH6 BID00001 B 5 500 L",
                transport.payload());
        }
    }
}
