package com.trv.quoter;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.MessageInfo;
import io.nats.client.api.StreamInfo;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class QuoterIntegration implements ConnectionListener {
    private static final Logger logger =
        Logger.getLogger(QuoterIntegration.class.getName());

    private static final int MAX_EXECUTION_DEDUP_ENTRIES = 4096;
    private static final long DEFAULT_ADD_REQUEST_TIMEOUT_MS = 1000L;
    private static final long DEFAULT_CANCEL_REQUEST_TIMEOUT_MS = 1000L;
    private static final long QUOTE_EVALUATION_INTERVAL_MS = 250L;

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
    private ReconciliationCoordinator reconciliationCoordinator;
    private AutomaticQuoteEngine automaticQuoteEngine;
    private ScheduledExecutorService quoteEvaluationExecutor;

    private final Object addRegistrationLock = new Object();
    private final Object bboRecoveryLock = new Object();
    private final Object quoteSignalLock = new Object();

    private boolean liveBboSeen;
    private boolean quoteEvaluationScheduled;
    private boolean quoteEvaluationDirty;
    private volatile boolean quoteEvaluationClosed;
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
                MAX_EXECUTION_DEDUP_ENTRIES,
                () -> {
                    ReconciliationCoordinator coordinator =
                        reconciliationCoordinator;

                    if (coordinator != null) {
                        coordinator.signal();
                    }

                    signalQuoteEvaluation();
                });

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

        JetStreamManagement jsm =
            natsConnection.jetStreamManagement();

        ReconciliationCoordinator.ReplaySource replaySource =
            new ReconciliationCoordinator.ReplaySource() {
                @Override
                public ReconciliationCoordinator.StreamWindow snapshot()
                        throws Exception {

                    StreamInfo info =
                        jsm.getStreamInfo("EX_MD");

                    return new ReconciliationCoordinator.StreamWindow(
                        info.getStreamState().getFirstSequence(),
                        info.getStreamState().getLastSequence());
                }

                @Override
                public ReconciliationCoordinator.ReplayMessage next(
                        long cursor)
                        throws Exception {

                    try {
                        MessageInfo message =
                            jsm.getNextMessage(
                                "EX_MD",
                                cursor,
                                ownMarketDataSubject);

                        if (message == null) {
                            return null;
                        }

                        if (!ownMarketDataSubject.equals(
                                message.getSubject())) {

                            throw new IllegalStateException(
                                "replay subject mismatch: "
                                    + message.getSubject());
                        }

                        return new ReconciliationCoordinator.ReplayMessage(
                            message.getSeq(),
                            message.getData());

                    } catch (JetStreamApiException e) {
                        if (e.getApiErrorCode() == 10037) {
                            return null;
                        }

                        throw e;
                    }
                }
            };

        orderRequestClient =
            new OrderRequestClient(
                sender,
                feed,
                metadata,
                orderManager,
                () -> runtimeState != null
                    && runtimeState.isReady()
                    && reconciliationCoordinator != null
                    && reconciliationCoordinator.isHealthy(),
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
                        DEFAULT_CANCEL_REQUEST_TIMEOUT_MS)),
                () -> {
                    ReconciliationCoordinator coordinator =
                        reconciliationCoordinator;

                    if (coordinator != null) {
                        coordinator.signal();
                    }

                    signalQuoteEvaluation();
                });

        reconciliationCoordinator =
            new ReconciliationCoordinator(
                orderManager,
                this::isRecoveryInfrastructureUsable,
                replaySource,
                new ReconciliationCoordinator.LifecycleSink() {
                    @Override
                    public void accept(byte[] data) {
                        ownLifecycleRouter.accept(data);
                    }

                    @Override
                    public void clearExecutionDedupForReconciledEpoch() {
                        ownLifecycleRouter
                            .clearExecutionDedupForReconciledEpoch();
                    }
                },
                side -> requireOrderRequestClient()
                    .requestCancel(side));

        reconciliationCoordinator.initialize();

        automaticQuoteEngine =
            new AutomaticQuoteEngine(
                runtimeState,
                metadata,
                orderManager,
                () -> reconciliationCoordinator != null
                    && reconciliationCoordinator.isHealthy(),
                addRegistrationLock,
                this::requestAdd,
                this::requestCancel,
                () -> {
                    ReconciliationCoordinator coordinator =
                        reconciliationCoordinator;

                    if (coordinator != null) {
                        coordinator.signal();
                    }
                });

        startQuoteEvaluation();
        signalQuoteEvaluation();
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

                    boolean accepted = false;

                    synchronized (bboRecoveryLock) {
                        if (!liveBboSeen) {
                            runtimeState.acceptBbo(bbo);
                            accepted = true;
                        }
                    }

                    if (accepted) {
                        signalQuoteEvaluation();
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

            /*
             * "- 0" is a legitimate empty Exchange side.
             * Protocol-valid one-sided / empty books are not quote-ready,
             * but must not be classified as malformed.
             */
            if (!bbo.isProtocolStateValid(metadata)) {
                synchronized (bboRecoveryLock) {
                    liveBboSeen = true;
                    runtimeState.invalidateBbo();
                }

                logger.warning(
                    "Invalid BBO state: " + payload);

                signalQuoteEvaluation();
                return;
            }

            if (!bbo.isValid(metadata)) {
                synchronized (bboRecoveryLock) {
                    liveBboSeen = true;
                    runtimeState.invalidateBbo();
                }

                /*
                 * Legitimate one-sided / empty market.
                 * Fail closed for quoting without malformed-input noise.
                 */
                signalQuoteEvaluation();
                return;
            }

            synchronized (bboRecoveryLock) {
                liveBboSeen = true;
                runtimeState.acceptBbo(bbo);
            }

            signalQuoteEvaluation();

        } catch (Exception e) {
            synchronized (bboRecoveryLock) {
                liveBboSeen = true;
                runtimeState.invalidateBbo();
            }

            logger.warning(
                "Invalid BBO state: " + e.getMessage());

            signalQuoteEvaluation();
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
            signalQuoteEvaluation();
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

        resetAutomaticQuoteTrust();
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

        resetAutomaticQuoteTrust();
        ownLifecycleRouter.markUnknownOnTrustLoss();

        logger.info("Connection closed");
    }

    private void handleLameDuck() {
        subscriptionsReady = false;

        if (runtimeState != null) {
            runtimeState.resetTrust();
        }

        resetAutomaticQuoteTrust();
        ownLifecycleRouter.markUnknownOnTrustLoss();

        logger.info("Lame duck mode");
    }

    private void handleReconnected() {
        subscriptionsReady = false;

        if (runtimeState != null) {
            runtimeState.resetTrust();
        }

        resetAutomaticQuoteTrust();

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
                resetAutomaticQuoteTrust();
                ownLifecycleRouter
                    .markUnknownOnTrustLoss();
                return;
            }

            natsConnection.flush(
                Duration.ofSeconds(5));

            subscriptionsReady = true;
            runtimeState.markConnected();

            recoverRetainedBbo();

            if (reconciliationCoordinator != null) {
                reconciliationCoordinator.signal();
            }

            signalQuoteEvaluation();

            /*
             * Do NOT clear the Quoter execution dedup set here.
             *
             * Reconciliation owns epoch rollover. It clears dedup only after
             * authoritative recovery has completed with both order slots EMPTY,
             * while lifecycle routing remains serialized and before new
             * exposure is re-enabled.
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

            resetAutomaticQuoteTrust();
            ownLifecycleRouter
                .markUnknownOnTrustLoss();
        }
    }

    private void startQuoteEvaluation() {
        ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread =
                        new Thread(
                            runnable,
                            "quoter-quote-evaluator");

                    thread.setDaemon(true);
                    return thread;
                });

        synchronized (quoteSignalLock) {
            quoteEvaluationClosed = false;
            quoteEvaluationExecutor = executor;
        }

        executor.scheduleWithFixedDelay(
            this::signalQuoteEvaluation,
            QUOTE_EVALUATION_INTERVAL_MS,
            QUOTE_EVALUATION_INTERVAL_MS,
            TimeUnit.MILLISECONDS);
    }

    private void stopQuoteEvaluation() {
        ScheduledExecutorService executor;

        synchronized (quoteSignalLock) {
            quoteEvaluationClosed = true;
            quoteEvaluationDirty = false;
            executor = quoteEvaluationExecutor;
            quoteEvaluationExecutor = null;
        }

        if (executor != null) {
            executor.shutdownNow();

            try {
                executor.awaitTermination(
                    1L,
                    TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void signalQuoteEvaluation() {
        ScheduledExecutorService executor;

        synchronized (quoteSignalLock) {
            if (quoteEvaluationClosed) {
                return;
            }

            quoteEvaluationDirty = true;

            if (quoteEvaluationScheduled
                    || quoteEvaluationExecutor == null) {

                return;
            }

            quoteEvaluationScheduled = true;
            executor = quoteEvaluationExecutor;
        }

        try {
            executor.execute(
                this::drainQuoteEvaluations);
        } catch (RuntimeException e) {
            boolean closed;

            synchronized (quoteSignalLock) {
                quoteEvaluationScheduled = false;
                closed = quoteEvaluationClosed;
            }

            if (!closed) {
                failClosedQuoteEvaluation(
                    "failed to schedule quote evaluation",
                    e);
            }
        }
    }

    private void drainQuoteEvaluations() {
        while (true) {
            synchronized (quoteSignalLock) {
                if (quoteEvaluationClosed) {
                    quoteEvaluationScheduled = false;
                    quoteEvaluationDirty = false;
                    return;
                }

                if (!quoteEvaluationDirty) {
                    quoteEvaluationScheduled = false;
                    return;
                }

                quoteEvaluationDirty = false;
            }

            try {
                AutomaticQuoteEngine engine =
                    automaticQuoteEngine;

                if (engine != null) {
                    engine.evaluateOnce();
                }
            } catch (RuntimeException e) {
                failClosedQuoteEvaluation(
                    "unexpected automatic quote evaluation failure",
                    e);
            }
        }
    }

    private void resetAutomaticQuoteTrust() {
        AutomaticQuoteEngine engine =
            automaticQuoteEngine;

        if (engine != null) {
            engine.resetTrust();
        }

        signalQuoteEvaluation();
    }

    private void failClosedQuoteEvaluation(
            String reason,
            Throwable error) {

        logger.log(
            Level.SEVERE,
            reason,
            error);

        synchronized (orderManager) {
            markCurrentQuoteUnknown(
                OrderManager.Side.BID);

            markCurrentQuoteUnknown(
                OrderManager.Side.ASK);
        }

        ReconciliationCoordinator coordinator =
            reconciliationCoordinator;

        if (coordinator != null) {
            coordinator.signal();
        }
    }

    private void markCurrentQuoteUnknown(
            OrderManager.Side side) {

        OrderManager.State state =
            orderManager.state(side);

        if (state == OrderManager.State.EMPTY
                || state == OrderManager.State.UNKNOWN) {

            return;
        }

        String orderId =
            orderManager.orderId(side);

        if (orderId != null) {
            orderManager.markUnknown(
                side,
                orderId);
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
     * Exact Add primitive used by the automatic quote engine and focused
     * tests. Pricing/profitability/inventory policy is decided before this
     * request layer.
     */
    public void requestAdd(
            OrderManager.Side side,
            String orderId,
            int quantity,
            long price) {

        synchronized (addRegistrationLock) {
            ReconciliationCoordinator coordinator =
                reconciliationCoordinator;

            if (coordinator == null) {
                throw new IllegalStateException(
                    "reconciliation coordinator is not initialized");
            }

            coordinator.prepareForNewExposure();

            requireOrderRequestClient()
                .requestAdd(
                    side,
                    orderId,
                    quantity,
                    price);
        }
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
        stopQuoteEvaluation();

        if (reconciliationCoordinator != null) {
            reconciliationCoordinator.close();
        }

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

    private boolean isRecoveryInfrastructureUsable() {
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

    /**
     * Serialized production quote orchestrator.
     *
     * QuotePolicy remains the pricing/risk domain policy.
     * QuoteController remains the pure lifecycle decision layer.
     * This integration-layer component only turns those decisions into
     * Add/Cancel side effects and contains the non-atomic SAFE pair safeguard.
     */
    static final class AutomaticQuoteEngine {

        @FunctionalInterface
        interface AddAction {
            void add(
                OrderManager.Side side,
                String orderId,
                int quantity,
                long price);
        }

        @FunctionalInterface
        interface CancelAction {
            void cancel(
                OrderManager.Side side);
        }

        private final RuntimeState runtimeState;
        private final QuotePolicy quotePolicy;
        private final QuoteController quoteController;
        private final OrderManager orderManager;
        private final OrderIdGenerator orderIdGenerator;
        private final BooleanSupplier reconciliationHealthy;
        private final Object addRegistrationLock;
        private final AddAction addAction;
        private final CancelAction cancelAction;
        private final Runnable recoverySignal;

        private long lastEvaluatedBboVersion =
            Long.MIN_VALUE;

        AutomaticQuoteEngine(
                RuntimeState runtimeState,
                Metadata metadata,
                OrderManager orderManager,
                BooleanSupplier reconciliationHealthy,
                Object addRegistrationLock,
                AddAction addAction,
                CancelAction cancelAction,
                Runnable recoverySignal) {

            this.runtimeState =
                Objects.requireNonNull(
                    runtimeState,
                    "runtimeState is required");

            this.orderManager =
                Objects.requireNonNull(
                    orderManager,
                    "orderManager is required");

            this.quotePolicy =
                new QuotePolicy(
                    Objects.requireNonNull(
                        metadata,
                        "metadata is required"));

            this.quoteController =
                new QuoteController(
                    quotePolicy,
                    orderManager);

            this.orderIdGenerator =
                new OrderIdGenerator();

            this.reconciliationHealthy =
                Objects.requireNonNull(
                    reconciliationHealthy,
                    "reconciliationHealthy is required");

            this.addRegistrationLock =
                Objects.requireNonNull(
                    addRegistrationLock,
                    "addRegistrationLock is required");

            this.addAction =
                Objects.requireNonNull(
                    addAction,
                    "addAction is required");

            this.cancelAction =
                Objects.requireNonNull(
                    cancelAction,
                    "cancelAction is required");

            this.recoverySignal =
                Objects.requireNonNull(
                    recoverySignal,
                    "recoverySignal is required");
        }

        synchronized void resetTrust() {
            quotePolicy.reset();
            lastEvaluatedBboVersion =
                Long.MIN_VALUE;
        }

        synchronized void evaluateOnce() {
            /*
             * During reconciliation the coordinator owns order reduction and
             * replay. Do not race it with new quote decisions.
             */
            if (!reconciliationHealthy
                    .getAsBoolean()) {

                return;
            }

            RuntimeState.Snapshot snapshot =
                runtimeState.snapshot();

            QuotePolicy.QuotePlan plan;

            if (!snapshot.ready()) {
                /*
                 * WAITING market/risk state means existing ACTIVE quotes are
                 * no longer safe to keep. QuoteController converts this
                 * no-permission plan into CANCEL for ACTIVE slots while
                 * PENDING/UNKNOWN lifecycle stays fail-closed.
                 */
                plan =
                    QuotePolicy.QuotePlan.noQuote(
                        HedgerState.UNKNOWN);
            } else {
                boolean newBboObservation =
                    snapshot.bboVersion()
                        != lastEvaluatedBboVersion;

                plan =
                    quotePolicy.evaluate(
                        snapshot.bbo(),
                        snapshot.risk(),
                        newBboObservation);

                lastEvaluatedBboVersion =
                    snapshot.bboVersion();
            }

            QuoteController.Decision decision =
                quoteController.decide(plan);

            executeCancel(
                OrderManager.Side.BID,
                decision.bid());

            executeCancel(
                OrderManager.Side.ASK,
                decision.ask());

            /*
             * QuoteController already suppresses ADD whenever a cancel or busy
             * lifecycle exists. Revalidate the runtime snapshot anyway so a
             * BBO/risk change that raced this evaluation can never create
             * exposure from stale inputs.
             */
            if (!hasAdd(decision)
                    || !snapshot.ready()
                    || !reconciliationHealthy
                        .getAsBoolean()) {

                return;
            }

            RuntimeState.Snapshot latest =
                runtimeState.snapshot();

            if (!latest.ready()
                    || latest.bboVersion()
                        != snapshot.bboVersion()
                    || latest.riskVersion()
                        != snapshot.riskVersion()
                    || !reconciliationHealthy
                        .getAsBoolean()) {

                return;
            }

            boolean bidAdd =
                decision.bid().action()
                    == QuoteController.Action.ADD;

            boolean askAdd =
                decision.ask().action()
                    == QuoteController.Action.ADD;

            if (bidAdd && askAdd) {
                dispatchSafePair(
                    decision,
                    latest.risk());
                return;
            }

            if (bidAdd) {
                dispatchSingleAdd(
                    OrderManager.Side.BID,
                    decision.bid());
            }

            if (askAdd) {
                dispatchSingleAdd(
                    OrderManager.Side.ASK,
                    decision.ask());
            }
        }

        private void executeCancel(
                OrderManager.Side side,
                QuoteController.SideDecision decision) {

            if (decision.action()
                    != QuoteController.Action.CANCEL) {

                return;
            }

            try {
                cancelAction.cancel(side);
            } catch (RuntimeException e) {
                OrderManager.State current =
                    orderManager.state(side);

                if (current == OrderManager.State.EMPTY
                        || current
                            == OrderManager.State.PENDING_CANCEL
                        || current
                            == OrderManager.State.UNKNOWN) {

                    return;
                }

                forceRecoveryForCurrent(
                    side,
                    orderManager.orderId(side),
                    "automatic quote cancel failed",
                    e);
            }
        }

        private boolean hasAdd(
                QuoteController.Decision decision) {

            return decision.bid().action()
                        == QuoteController.Action.ADD
                || decision.ask().action()
                        == QuoteController.Action.ADD;
        }

        private void dispatchSingleAdd(
                OrderManager.Side side,
                QuoteController.SideDecision decision) {

            Long price =
                decision.price();

            if (price == null) {
                throw new IllegalStateException(
                    "ADD decision has no price");
            }

            String orderId =
                orderIdGenerator.nextId();

            try {
                addAction.add(
                    side,
                    orderId,
                    QuotePolicy.QUOTE_CLIP,
                    price);
            } catch (RuntimeException e) {
                forceRecoveryForCurrent(
                    side,
                    orderId,
                    "automatic quote Add failed",
                    e);
                return;
            }

            if (isCurrentUnknown(
                    side,
                    orderId)) {

                recoverySignal.run();
            }
        }

        private void dispatchSafePair(
                QuoteController.Decision decision,
                DeskRiskMessage risk) {

            OrderManager.Side first =
                preferredFirstSide(risk);

            OrderManager.Side second =
                opposite(first);

            QuoteController.SideDecision
                firstDecision =
                    decisionFor(
                        decision,
                        first);

            QuoteController.SideDecision
                secondDecision =
                    decisionFor(
                        decision,
                        second);

            Long firstPrice =
                firstDecision.price();

            Long secondPrice =
                secondDecision.price();

            if (firstPrice == null
                    || secondPrice == null) {

                throw new IllegalStateException(
                    "SAFE pair ADD decision is missing a price");
            }

            /*
             * The exchange protocol has no atomic two-order request. Serialize
             * both Add registrations against the same exposure-floor lock.
             *
             * At non-zero SAFE inventory, send the risk-reducing side first.
             * At zero inventory BID is the deterministic first side.
             */
            synchronized (addRegistrationLock) {
                String firstId =
                    orderIdGenerator.nextId();

                try {
                    addAction.add(
                        first,
                        firstId,
                        QuotePolicy.QUOTE_CLIP,
                        firstPrice);
                } catch (RuntimeException e) {
                    forceRecoveryForCurrent(
                        first,
                        firstId,
                        "first SAFE pair Add failed",
                        e);
                    return;
                }

                if (!isLiveAddState(
                        first,
                        firstId)) {

                    if (isCurrentOccupied(
                            first,
                            firstId)) {

                        forceRecoveryForCurrent(
                            first,
                            firstId,
                            "first SAFE pair Add did not remain live",
                            null);
                    }

                    return;
                }

                String secondId =
                    orderIdGenerator.nextId();

                try {
                    addAction.add(
                        second,
                        secondId,
                        QuotePolicy.QUOTE_CLIP,
                        secondPrice);
                } catch (RuntimeException e) {
                    abortPairToRecovery(
                        first,
                        firstId,
                        second,
                        secondId,
                        "second SAFE pair Add failed",
                        e);
                    return;
                }

                /*
                 * A/T/C can race either request. A successful pair handoff
                 * requires both sides to remain PENDING_ADD or ACTIVE after the
                 * second dispatch. Otherwise deliberately stop quoting and let
                 * exact-cancel/replay reconciliation flatten the orphan.
                 */
                if (!isLiveAddState(
                        first,
                        firstId)
                        || !isLiveAddState(
                            second,
                            secondId)) {

                    abortPairToRecovery(
                        first,
                        firstId,
                        second,
                        secondId,
                        "SAFE pair became asymmetric during dispatch",
                        null);
                }
            }
        }

        private OrderManager.Side preferredFirstSide(
                DeskRiskMessage risk) {

            if (risk != null
                    && risk.getNetPosition() > 0) {

                return OrderManager.Side.ASK;
            }

            if (risk != null
                    && risk.getNetPosition() < 0) {

                return OrderManager.Side.BID;
            }

            return OrderManager.Side.BID;
        }

        private QuoteController.SideDecision decisionFor(
                QuoteController.Decision decision,
                OrderManager.Side side) {

            return side == OrderManager.Side.BID
                ? decision.bid()
                : decision.ask();
        }

        private OrderManager.Side opposite(
                OrderManager.Side side) {

            return side == OrderManager.Side.BID
                ? OrderManager.Side.ASK
                : OrderManager.Side.BID;
        }

        private boolean isLiveAddState(
                OrderManager.Side side,
                String orderId) {

            synchronized (orderManager) {
                if (!isCurrent(
                        side,
                        orderId)) {

                    return false;
                }

                OrderManager.State state =
                    orderManager.state(side);

                return state
                        == OrderManager.State.PENDING_ADD
                    || state
                        == OrderManager.State.ACTIVE;
            }
        }

        private boolean isCurrentUnknown(
                OrderManager.Side side,
                String orderId) {

            synchronized (orderManager) {
                return isCurrent(
                        side,
                        orderId)
                    && orderManager.state(side)
                        == OrderManager.State.UNKNOWN;
            }
        }

        private boolean isCurrentOccupied(
                OrderManager.Side side,
                String orderId) {

            synchronized (orderManager) {
                return isCurrent(
                    side,
                    orderId)
                    && orderManager.state(side)
                        != OrderManager.State.EMPTY;
            }
        }

        private boolean isCurrent(
                OrderManager.Side side,
                String orderId) {

            String current =
                orderManager.orderId(side);

            return orderId != null
                && orderId.equals(current);
        }

        private void abortPairToRecovery(
                OrderManager.Side first,
                String firstId,
                OrderManager.Side second,
                String secondId,
                String reason,
                Throwable error) {

            boolean changed = false;

            changed |=
                markCurrentUnknown(
                    first,
                    firstId);

            changed |=
                markCurrentUnknown(
                    second,
                    secondId);

            if (changed
                    || isCurrentOccupied(
                        first,
                        firstId)
                    || isCurrentOccupied(
                        second,
                        secondId)) {

                recoverySignal.run();
            }

            logPairAbort(
                reason,
                error);
        }

        private void forceRecoveryForCurrent(
                OrderManager.Side side,
                String orderId,
                String reason,
                Throwable error) {

            boolean changed =
                markCurrentUnknown(
                    side,
                    orderId);

            if (changed
                    || isCurrentOccupied(
                        side,
                        orderId)) {

                recoverySignal.run();
            }

            if (error == null) {
                logger.warning(
                    reason
                        + " side="
                        + side
                        + " orderId="
                        + orderId);
            } else {
                logger.log(
                    Level.WARNING,
                    reason
                        + " side="
                        + side
                        + " orderId="
                        + orderId,
                    error);
            }
        }

        private boolean markCurrentUnknown(
                OrderManager.Side side,
                String orderId) {

            synchronized (orderManager) {
                if (!isCurrent(
                        side,
                        orderId)) {

                    return false;
                }

                OrderManager.State state =
                    orderManager.state(side);

                if (state == OrderManager.State.EMPTY
                        || state
                            == OrderManager.State.UNKNOWN) {

                    return false;
                }

                orderManager.markUnknown(
                    side,
                    orderId);

                return true;
            }
        }

        private void logPairAbort(
                String reason,
                Throwable error) {

            if (error == null) {
                logger.warning(reason);
            } else {
                logger.log(
                    Level.WARNING,
                    reason,
                    error);
            }
        }
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
        private final Runnable lifecycleStateChanged;

        private final Set<ExecutionKey>
            executionDedup = new HashSet<>();

        OwnLifecycleRouter(
                String sender,
                OrderManager orderManager) {

            this(
                sender,
                orderManager,
                MAX_EXECUTION_DEDUP_ENTRIES,
                () -> {
                });
        }

        OwnLifecycleRouter(
                String sender,
                OrderManager orderManager,
                int maxExecutionDedupEntries) {

            this(
                sender,
                orderManager,
                maxExecutionDedupEntries,
                () -> {
                });
        }

        OwnLifecycleRouter(
                String sender,
                OrderManager orderManager,
                int maxExecutionDedupEntries,
                Runnable lifecycleStateChanged) {

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
            this.lifecycleStateChanged =
                Objects.requireNonNull(
                    lifecycleStateChanged,
                    "lifecycleStateChanged is required");
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
            } finally {
                lifecycleStateChanged.run();
            }
        }

        synchronized void markUnknownOnTrustLoss() {
            try {
                markAllOccupiedUnknown();
            } finally {
                lifecycleStateChanged.run();
            }
        }

        synchronized void clearExecutionDedupForReconciledEpoch() {
            if (orderManager.state(OrderManager.Side.BID)
                        != OrderManager.State.EMPTY
                    || orderManager.state(OrderManager.Side.ASK)
                        != OrderManager.State.EMPTY) {

                throw new IllegalStateException(
                    "cannot clear lifecycle execution dedup "
                        + "while an order slot is occupied");
            }

            executionDedup.clear();
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
         * executionDedup is cleared only by
         * clearExecutionDedupForReconciledEpoch(), after authoritative
         * reconciliation has completed with both slots EMPTY. It is never
         * cleared merely because reconnect/resubscribe occurred.
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

