package com.trv.quoter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void addReadinessLossBetweenRegistrationAndDispatchFailsClosed() {
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
                OrderManager.State.UNKNOWN,
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
                + " ref_price=500 band=100");
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
}
