package com.trv.quoter;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.MessageInfo;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class QuoterIntegration implements ConnectionListener {
    private static final Logger logger =
        Logger.getLogger(QuoterIntegration.class.getName());

    private static final int MAX_EXECUTION_DEDUP_ENTRIES = 4096;
    private static final long DEFAULT_ADD_REQUEST_TIMEOUT_MS = 1000L;
    private static final long DEFAULT_CANCEL_REQUEST_TIMEOUT_MS = 1000L;

    private final String feed;
    private final String natsUrl;
    private final String sender;
    private final OrderManager orderManager;
    private final OwnLifecycleRouter ownLifecycleRouter;

    private Connection natsConnection;
    private Dispatcher dispatcher;
    private Metadata metadata;
    private RuntimeState runtimeState;
    private OrderRequestClient orderRequestClient;

    private final Object bboRecoveryLock = new Object();
    private boolean liveBboSeen;
    private volatile boolean subscriptionsReady;

    public QuoterIntegration() throws Exception {
        String natsUrlEnv = System.getenv("NATS_URL");
        String feedEnv = System.getenv("TAKER_FEED");
        String senderEnv = System.getenv("SENDER");

        if (natsUrlEnv == null || natsUrlEnv.isBlank()) {
            throw new IllegalArgumentException(
                "NATS_URL environment variable is required");
        }
        if (feedEnv == null || feedEnv.isBlank()) {
            throw new IllegalArgumentException(
                "TAKER_FEED environment variable is required");
        }
        if (senderEnv == null || senderEnv.isBlank()) {
            throw new IllegalArgumentException(
                "SENDER environment variable is required");
        }
        if (senderEnv.length() != 8) {
            throw new IllegalArgumentException(
                "SENDER must be exactly 8 characters");
        }

        this.natsUrl = natsUrlEnv;
        this.feed = feedEnv;
        this.sender = senderEnv;
        this.orderManager = new OrderManager();
        this.ownLifecycleRouter =
            new OwnLifecycleRouter(
                sender,
                orderManager,
                MAX_EXECUTION_DEDUP_ENTRIES);

        this.liveBboSeen = false;
        this.subscriptionsReady = false;

        startup();
    }

    private void startup() throws Exception {
        Options options = new Options.Builder()
            .server(natsUrl)
            .connectionListener(this)
            /*
             * Order-entry requests must not be deliberately buffered across
             * disconnect/reconnect and appear later after lifecycle trust was
             * lost. requestAdd/requestCancel still re-check transport trust
             * immediately before dispatch.
             */
            .reconnectBufferSize(0)
            .build();

        natsConnection = Nats.connect(options);

        metadata = loadMetadata();
        runtimeState = new RuntimeState(feed, metadata);

        dispatcher = natsConnection.createDispatcher();

        String bboSubject = "ex.bbo." + feed;
        String riskSubject = "desk.risk." + feed;
        String ownMarketDataSubject =
            "ex.md." + feed + "." + sender;

        synchronized (bboRecoveryLock) {
            liveBboSeen = false;
        }

        dispatcher.subscribe(
            bboSubject,
            msg -> handleBboMessage(msg.getData()));

        dispatcher.subscribe(
            riskSubject,
            msg -> handleRiskMessage(msg.getData()));

        /*
         * Own lifecycle evidence is handled independently from RuntimeState
         * readiness. During reconnect an authoritative terminal E/T/C can still
         * safely reduce lifecycle uncertainty; partial evidence preserves
         * UNKNOWN.
         */
        dispatcher.subscribe(
            ownMarketDataSubject,
            msg -> ownLifecycleRouter.accept(msg.getData()));

        natsConnection.flush(Duration.ofSeconds(5));

        subscriptionsReady = true;
        runtimeState.markConnected();
        recoverRetainedBbo();

        orderRequestClient =
            new OrderRequestClient(
                sender,
                feed,
                metadata,
                orderManager,
                () -> runtimeState != null
                    && runtimeState.isReady(),
                this::isOrderRequestTransportTrusted,
                (subject, payload, timeout) ->
                    natsConnection
                        .requestWithTimeout(
                            subject,
                            payload,
                            timeout)
                        .thenApply(
                            message -> message.getData()),
                Duration.ofMillis(
                    readPositiveTimeoutMillis(
                        "ADD_REQUEST_TIMEOUT_MS",
                        DEFAULT_ADD_REQUEST_TIMEOUT_MS)),
                Duration.ofMillis(
                    readPositiveTimeoutMillis(
                        "CANCEL_REQUEST_TIMEOUT_MS",
                        DEFAULT_CANCEL_REQUEST_TIMEOUT_MS)));
    }

    private void recoverRetainedBbo() {
        try {
            MessageInfo messageInfo = natsConnection
                .jetStreamManagement()
                .getLastMessage(
                    "EX_MD",
                    "ex.bbo." + feed);

            if (messageInfo != null) {
                byte[] data = messageInfo.getData();
                String payload =
                    new String(
                        data,
                        StandardCharsets.UTF_8);

                try {
                    Bbo bbo =
                        Bbo.parse(payload, metadata);

                    synchronized (bboRecoveryLock) {
                        if (!liveBboSeen) {
                            runtimeState.acceptBbo(bbo);
                        }
                    }
                } catch (Exception e) {
                    logger.log(
                        Level.WARNING,
                        "Malformed retained BBO: " + payload,
                        e);
                }
            }
        } catch (Exception e) {
            logger.log(
                Level.WARNING,
                "Failed to recover retained BBO",
                e);
        }
    }

    private void handleBboMessage(byte[] data) {
        if (!subscriptionsReady) {
            return;
        }

        String payload =
            new String(
                data,
                StandardCharsets.UTF_8);

        try {
            Bbo bbo =
                Bbo.parse(payload, metadata);

            if (!bbo.isValid(metadata)) {
                synchronized (bboRecoveryLock) {
                    liveBboSeen = true;
                    runtimeState.invalidateBbo();
                }

                logger.warning(
                    "Invalid BBO state: " + payload);
                return;
            }

            synchronized (bboRecoveryLock) {
                liveBboSeen = true;
                runtimeState.acceptBbo(bbo);
            }

        } catch (Exception e) {
            synchronized (bboRecoveryLock) {
                liveBboSeen = true;
                runtimeState.invalidateBbo();
            }

            logger.warning(
                "Invalid BBO state: " + e.getMessage());
        }
    }

    private void handleRiskMessage(byte[] data) {
        if (!subscriptionsReady) {
            return;
        }

        try {
            String payload =
                new String(
                    data,
                    StandardCharsets.UTF_8);

            DeskRiskMessage risk =
                DeskRiskMessage.parse(payload);

            runtimeState.acceptRisk(risk);
        } catch (Exception e) {
            logger.warning(
                "Ignoring malformed risk message: "
                    + e.getMessage());
        }
    }

    @Override
    public void connectionEvent(
            Connection conn,
            Events type) {

        switch (type) {
            case CONNECTED:
                handleConnected();
                break;
            case DISCONNECTED:
                handleDisconnected();
                break;
            case CLOSED:
                handleClosed();
                break;
            case LAME_DUCK:
                handleLameDuck();
                break;
            case RECONNECTED:
                handleReconnected();
                break;
            case RESUBSCRIBED:
                handleResubscribed();
                break;
            case DISCOVERED_SERVERS:
                // no readiness action
                break;
        }
    }

    private void handleConnected() {
        if (runtimeState != null) {
            logger.fine("Connected to NATS");
        }
    }

    private void handleDisconnected() {
        subscriptionsReady = false;

        if (runtimeState != null) {
            runtimeState.resetTrust();
        }

        ownLifecycleRouter.markUnknownOnTrustLoss();

        synchronized (bboRecoveryLock) {
            liveBboSeen = false;
        }

        logger.info("Disconnected from NATS");
    }

    private void handleClosed() {
        subscriptionsReady = false;

        if (runtimeState != null) {
            runtimeState.resetTrust();
        }

        ownLifecycleRouter.markUnknownOnTrustLoss();

        logger.info("Connection closed");
    }

    private void handleLameDuck() {
        subscriptionsReady = false;

        if (runtimeState != null) {
            runtimeState.resetTrust();
        }

        ownLifecycleRouter.markUnknownOnTrustLoss();

        logger.info("Lame duck mode");
    }

    private void handleReconnected() {
        subscriptionsReady = false;

        if (runtimeState != null) {
            runtimeState.resetTrust();
        }

        /*
         * Idempotent after DISCONNECTED. Keeping this here makes trust loss
         * explicit even if callback sequences differ.
         */
        ownLifecycleRouter.markUnknownOnTrustLoss();

        logger.info(
            "Reconnected to NATS; waiting for subscriptions "
                + "to resynchronize");
    }

    private void handleResubscribed() {
        try {
            Metadata newMetadata = loadMetadata();

            if (!metadataEquals(
                    metadata,
                    newMetadata)) {

                logger.severe(
                    "Metadata changed after reconnect; "
                        + "process restart required");

                subscriptionsReady = false;
                runtimeState.resetTrust();
                ownLifecycleRouter
                    .markUnknownOnTrustLoss();
                return;
            }

            natsConnection.flush(
                Duration.ofSeconds(5));

            subscriptionsReady = true;
            runtimeState.markConnected();

            recoverRetainedBbo();

            /*
             * Do NOT clear the Quoter execution dedup set here.
             *
             * Future reconciliation optimization:
             * clear it only after authoritative own-order reconciliation has
             * completed successfully, in the same serialized lifecycle
             * context, before new exposure is re-enabled.
             *
             * Never clear merely because RECONNECTED/RESUBSCRIBED occurred.
             */
        } catch (Exception e) {
            subscriptionsReady = false;

            logger.severe(
                "Reconnect recovery failed: "
                    + e.getMessage());

            if (runtimeState != null) {
                runtimeState.resetTrust();
            }

            ownLifecycleRouter
                .markUnknownOnTrustLoss();
        }
    }

    private boolean metadataEquals(
            Metadata m1,
            Metadata m2) {

        if (m1 == m2) {
            return true;
        }
        if (m1 == null || m2 == null) {
            return false;
        }

        return m1.getFeed().equals(m2.getFeed())
            && m1.getTickSize()
                .compareTo(m2.getTickSize()) == 0
            && ((m1.getRefPrice() == null
                    && m2.getRefPrice() == null)
                || (m1.getRefPrice() != null
                    && m2.getRefPrice() != null
                    && m1.getRefPrice()
                        .compareTo(
                            m2.getRefPrice()) == 0))
            && ((m1.getBand() == null
                    && m2.getBand() == null)
                || (m1.getBand() != null
                    && m2.getBand() != null
                    && m1.getBand()
                        .compareTo(
                            m2.getBand()) == 0));
    }

    public RuntimeState getRuntimeState() {
        return runtimeState;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public OrderManager getOrderManager() {
        return orderManager;
    }

    /*
     * Foundation request primitive only.
     * Profitability and inventory policy are intentionally deferred.
     */
    public void requestAdd(
            OrderManager.Side side,
            String orderId,
            int quantity,
            long price) {

        requireOrderRequestClient()
            .requestAdd(
                side,
                orderId,
                quantity,
                price);
    }

    /*
     * Risk-reducing exact cancel. The request layer chooses the current id
     * atomically from the shared OrderManager.
     */
    public void requestCancel(
            OrderManager.Side side) {

        requireOrderRequestClient()
            .requestCancel(side);
    }

    public Connection getConnection() {
        return natsConnection;
    }

    public void close() throws Exception {
        if (orderRequestClient != null) {
            orderRequestClient.close();
        }

        if (natsConnection != null) {
            natsConnection.close();
        }
    }

    private OrderRequestClient requireOrderRequestClient() {
        if (orderRequestClient == null) {
            throw new IllegalStateException(
                "order request client is not initialized");
        }

        return orderRequestClient;
    }

    private boolean isOrderRequestTransportTrusted() {
        return subscriptionsReady
            && natsConnection != null
            && natsConnection.getStatus()
                == Connection.Status.CONNECTED;
    }

    private long readPositiveTimeoutMillis(
            String envName,
            long defaultValue) {

        String raw =
            System.getenv(envName);

        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }

        final long parsed;

        try {
            parsed =
                Long.parseLong(
                    raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                envName
                    + " must be a positive integer milliseconds value",
                e);
        }

        if (parsed <= 0L) {
            throw new IllegalArgumentException(
                envName
                    + " must be positive");
        }

        return parsed;
    }

    private Metadata loadMetadata()
            throws Exception {

        var entry = natsConnection
            .keyValue("EX_META")
            .get(feed);

        if (entry == null) {
            throw new IllegalStateException(
                "Missing EX_META entry for feed "
                    + feed);
        }

        String payload =
            entry.getValueAsString();

        if (payload == null
                || payload.isBlank()) {

            throw new IllegalStateException(
                "Empty EX_META entry for feed "
                    + feed);
        }

        return Metadata.parse(feed, payload);
    }

    /*
     * Own-sender lifecycle adapter.
     *
     * Package-private so focused tests can exercise deterministic parsing,
     * routing and dedup without a live NATS connection.
     *
     * This class does NOT maintain desk position. The Hedger remains the
     * authoritative desk-position consumer.
     */
    static final class OwnLifecycleRouter {
        private final String sender;
        private final OrderManager orderManager;
        private final int maxExecutionDedupEntries;

        private final Set<ExecutionKey>
            executionDedup = new HashSet<>();

        OwnLifecycleRouter(
                String sender,
                OrderManager orderManager) {

            this(
                sender,
                orderManager,
                MAX_EXECUTION_DEDUP_ENTRIES);
        }

        OwnLifecycleRouter(
                String sender,
                OrderManager orderManager,
                int maxExecutionDedupEntries) {

            if (sender == null
                    || sender.length() != 8) {

                throw new IllegalArgumentException(
                    "sender must be exactly 8 characters");
            }

            if (orderManager == null) {
                throw new IllegalArgumentException(
                    "orderManager is required");
            }

            if (maxExecutionDedupEntries <= 0) {
                throw new IllegalArgumentException(
                    "maxExecutionDedupEntries "
                        + "must be positive");
            }

            this.sender = sender;
            this.orderManager = orderManager;
            this.maxExecutionDedupEntries =
                maxExecutionDedupEntries;
        }

        synchronized void accept(byte[] data) {
            String raw = data == null
                ? "<null>"
                : new String(
                    data,
                    StandardCharsets.UTF_8);

            try {
                if (data == null) {
                    throw new IllegalArgumentException(
                        "market-data payload is null");
                }

                parseAndRoute(raw);
            } catch (RuntimeException e) {
                markAllOccupiedUnknown();

                logger.log(
                    Level.SEVERE,
                    "Malformed or inconsistent own-sender "
                        + "lifecycle event; Quoter lifecycle "
                        + "marked UNKNOWN. raw=" + raw,
                    e);
            }
        }

        synchronized void markUnknownOnTrustLoss() {
            markAllOccupiedUnknown();
        }

        private void parseAndRoute(String raw) {
            String trimmed = raw.trim();

            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException(
                    "market-data payload is empty");
            }

            String[] parts =
                trimmed.split("\\s+");

            if (parts.length < 2) {
                throw new IllegalArgumentException(
                    "market-data payload has too few fields");
            }

            long eventTimestamp =
                parseLong(
                    parts[0],
                    "event timestamp");

            String eventType = parts[1];

            switch (eventType) {
                case "A":
                    routeAdd(parts);
                    break;
                case "C":
                    routeCancel(parts);
                    break;
                case "E":
                case "T":
                    routeExecution(
                        eventType,
                        eventTimestamp,
                        parts);
                    break;
                default:
                    throw new IllegalArgumentException(
                        "unsupported own lifecycle event type "
                            + eventType);
            }
        }

        private void routeAdd(String[] parts) {
            requireFieldCount(
                parts,
                6,
                "A");

            PublicOrderId publicOrderId =
                parsePublicOrderId(parts[2]);

            requireOwnSender(publicOrderId);

            OrderManager.Side side =
                parseOrderSide(parts[3]);

            /*
             * A.volume is validated but deliberately not used as
             * remaining-quantity authority.
             */
            parsePositiveInt(
                parts[4],
                "A volume");

            parseLong(
                parts[5],
                "A price");

            synchronized (orderManager) {
                if (!isCurrentOnExpectedSide(
                        side,
                        publicOrderId.orderId())) {
                    return;
                }

                orderManager.onResting(
                    side,
                    publicOrderId.orderId());
            }
        }

        private void routeCancel(
                String[] parts) {

            requireFieldCount(
                parts,
                3,
                "C");

            PublicOrderId publicOrderId =
                parsePublicOrderId(parts[2]);

            requireOwnSender(publicOrderId);

            synchronized (orderManager) {
                boolean bidMatch =
                    isCurrent(
                        OrderManager.Side.BID,
                        publicOrderId.orderId());

                boolean askMatch =
                    isCurrent(
                        OrderManager.Side.ASK,
                        publicOrderId.orderId());

                if (bidMatch && askMatch) {
                    throw new IllegalStateException(
                        "current order id is ambiguous "
                            + "across BID and ASK");
                }

                if (bidMatch) {
                    orderManager.onCancelled(
                        OrderManager.Side.BID,
                        publicOrderId.orderId());
                } else if (askMatch) {
                    orderManager.onCancelled(
                        OrderManager.Side.ASK,
                        publicOrderId.orderId());
                }

                /*
                 * No current match = valid late/stale C.
                 * Ignore without forcing UNKNOWN.
                 */
            }
        }

        private void routeExecution(
                String eventType,
                long eventTimestamp,
                String[] parts) {

            requireFieldCount(
                parts,
                8,
                eventType);

            PublicOrderId incoming =
                parsePublicOrderId(parts[2]);

            PublicOrderId resting =
                parsePublicOrderId(parts[3]);

            int quantity =
                parsePositiveInt(
                    parts[4],
                    eventType + " volume");

            long price =
                parseLong(
                    parts[5],
                    eventType + " price");

            String matchId = parts[6];

            if (matchId.isBlank()) {
                throw new IllegalArgumentException(
                    eventType
                        + " matchId is required");
            }

            char aggressorSide =
                parseAggressorSide(parts[7]);

            final PublicOrderId tracked;
            final OrderManager.Side side;

            if ("T".equals(eventType)) {
                tracked = incoming;
                requireOwnSender(tracked);

                side =
                    aggressorSide == 'B'
                        ? OrderManager.Side.BID
                        : OrderManager.Side.ASK;
            } else {
                tracked = resting;
                requireOwnSender(tracked);

                side =
                    aggressorSide == 'B'
                        ? OrderManager.Side.ASK
                        : OrderManager.Side.BID;
            }

            ExecutionKey key =
                new ExecutionKey(
                    eventType,
                    eventTimestamp,
                    matchId,
                    incoming.fullId(),
                    resting.fullId(),
                    quantity,
                    price,
                    aggressorSide);

            synchronized (orderManager) {
                if (!isCurrentOnExpectedSide(
                        side,
                        tracked.orderId())) {

                    /*
                     * Structurally valid event for a non-current order
                     * is a normal late/stale lifecycle event.
                     */
                    return;
                }

                if (executionDedup.contains(key)) {
                    return;
                }

                if (executionDedup.size()
                        >= maxExecutionDedupEntries) {

                    throw new IllegalStateException(
                        "Quoter execution dedup capacity "
                            + "exhausted");
                }

                executionDedup.add(key);

                orderManager.onExecution(
                    side,
                    tracked.orderId(),
                    quantity);
            }
        }

        private boolean isCurrentOnExpectedSide(
                OrderManager.Side expectedSide,
                String orderId) {

            OrderManager.Side otherSide =
                expectedSide
                    == OrderManager.Side.BID
                    ? OrderManager.Side.ASK
                    : OrderManager.Side.BID;

            boolean expectedMatch =
                isCurrent(
                    expectedSide,
                    orderId);

            boolean otherMatch =
                isCurrent(
                    otherSide,
                    orderId);

            if (expectedMatch && otherMatch) {
                throw new IllegalStateException(
                    "current order id is ambiguous "
                        + "across BID and ASK");
            }

            if (otherMatch) {
                throw new IllegalStateException(
                    "event side conflicts with current "
                        + "Quoter slot");
            }

            return expectedMatch;
        }

        private boolean isCurrent(
                OrderManager.Side side,
                String orderId) {

            String current =
                orderManager.orderId(side);

            return orderId.equals(current);
        }

        private void markAllOccupiedUnknown() {
            synchronized (orderManager) {
                markSideUnknown(
                    OrderManager.Side.BID);

                markSideUnknown(
                    OrderManager.Side.ASK);
            }
        }

        private void markSideUnknown(
                OrderManager.Side side) {

            if (orderManager.state(side)
                    == OrderManager.State.EMPTY) {
                return;
            }

            String orderId =
                orderManager.orderId(side);

            if (orderId == null) {
                throw new IllegalStateException(
                    "occupied slot has no order id");
            }

            orderManager.markUnknown(
                side,
                orderId);
        }

        private PublicOrderId parsePublicOrderId(
                String value) {

            if (value == null
                    || value.length() != 17
                    || value.charAt(8) != ':') {

                throw new IllegalArgumentException(
                    "invalid public order id "
                        + value);
            }

            String eventSender =
                value.substring(0, 8);

            String orderId =
                value.substring(9);

            return new PublicOrderId(
                eventSender,
                orderId);
        }

        private void requireOwnSender(
                PublicOrderId publicOrderId) {

            if (!sender.equals(
                    publicOrderId.sender())) {

                throw new IllegalArgumentException(
                    "own-sender lifecycle event "
                        + "references unexpected tracked "
                        + "sender "
                        + publicOrderId.sender());
            }
        }

        private OrderManager.Side parseOrderSide(
                String value) {

            if ("B".equals(value)) {
                return OrderManager.Side.BID;
            }
            if ("S".equals(value)) {
                return OrderManager.Side.ASK;
            }

            throw new IllegalArgumentException(
                "invalid order side " + value);
        }

        private char parseAggressorSide(
                String value) {

            if ("B".equals(value)) {
                return 'B';
            }
            if ("S".equals(value)) {
                return 'S';
            }

            throw new IllegalArgumentException(
                "invalid aggressor side " + value);
        }

        private int parsePositiveInt(
                String value,
                String fieldName) {

            final int parsed;

            try {
                parsed = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    fieldName
                        + " must be an integer",
                    e);
            }

            if (parsed <= 0) {
                throw new IllegalArgumentException(
                    fieldName
                        + " must be positive");
            }

            return parsed;
        }

        private long parseLong(
                String value,
                String fieldName) {

            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    fieldName
                        + " must be an integer",
                    e);
            }
        }

        private void requireFieldCount(
                String[] parts,
                int expected,
                String eventType) {

            if (parts.length != expected) {
                throw new IllegalArgumentException(
                    eventType
                        + " event expected "
                        + expected
                        + " fields but got "
                        + parts.length);
            }
        }

        /*
         * Future reconciliation optimization:
         *
         * executionDedup.clear()
         *
         * is safe only after authoritative reconciliation has completed
         * successfully, while lifecycle routing is serialized, and before
         * new exposure is enabled. Do not clear on reconnect/resubscribe
         * alone.
         */

        private record PublicOrderId(
            String sender,
            String orderId) {

            String fullId() {
                return sender + ":" + orderId;
            }
        }

        private record ExecutionKey(
            String eventType,
            long eventTimestamp,
            String matchId,
            String incomingOrderId,
            String restingOrderId,
            int quantity,
            long price,
            char aggressorSide) {
        }
    }
}
