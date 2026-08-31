package com.trv.quoter;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.MessageInfo;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class QuoterIntegration implements ConnectionListener {
    private static final Logger logger = Logger.getLogger(QuoterIntegration.class.getName());

    private final String feed;
    private final String natsUrl;
    private Connection natsConnection;
    private Dispatcher dispatcher;
    private Metadata metadata;
    private RuntimeState runtimeState;

    private final Object bboRecoveryLock = new Object();
    private boolean liveBboSeen;
    private volatile boolean subscriptionsReady;

    public QuoterIntegration() throws Exception {
        String natsUrlEnv = System.getenv("NATS_URL");
        String feedEnv = System.getenv("TAKER_FEED");

        if (natsUrlEnv == null || natsUrlEnv.isBlank()) {
            throw new IllegalArgumentException("NATS_URL environment variable is required");
        }
        if (feedEnv == null || feedEnv.isBlank()) {
            throw new IllegalArgumentException("TAKER_FEED environment variable is required");
        }

        this.natsUrl = natsUrlEnv;
        this.feed = feedEnv;
        this.liveBboSeen = false;
        this.subscriptionsReady = false;

        startup();
    }

    private void startup() throws Exception {
        // Step 3: build Options with ConnectionListener
        Options options = new Options.Builder()
            .server(natsUrl)
            .connectionListener(this)
            .build();

        // Step 4: connect NATS
        natsConnection = Nats.connect(options);

        // Step 5: load EX_META
        metadata = loadMetadata();

        // Step 7: create RuntimeState
        runtimeState = new RuntimeState(feed, metadata);

        // Step 8: create ONE Dispatcher
        dispatcher = natsConnection.createDispatcher();

        // Step 9: subscribe
        String bboBboSubject = "ex.bbo." + feed;
        String riskSubject = "desk.risk." + feed;

        synchronized (bboRecoveryLock) {
            liveBboSeen = false;
        }

        dispatcher.subscribe(bboBboSubject, msg -> handleBboMessage(msg.getData()));
        dispatcher.subscribe(riskSubject, msg -> handleRiskMessage(msg.getData()));

        natsConnection.flush(Duration.ofSeconds(5));

        subscriptionsReady = true;
        runtimeState.markConnected();
        recoverRetainedBbo();
    }

    private void recoverRetainedBbo() {
        try {
            MessageInfo messageInfo = natsConnection.jetStreamManagement()
                .getLastMessage("EX_MD", "ex.bbo." + feed);

            if (messageInfo != null) {
                byte[] data = messageInfo.getData();
                String payload = new String(data, StandardCharsets.UTF_8);

                try {
                    Bbo bbo = Bbo.parse(payload, metadata);
                    synchronized (bboRecoveryLock) {
                        if (!liveBboSeen) {
                            runtimeState.acceptBbo(bbo);
                        }
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Malformed retained BBO: " + payload, e);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to recover retained BBO", e);
        }
    }

    private void handleBboMessage(byte[] data) {
        if (!subscriptionsReady) {
            return;
        }

        String payload = new String(data, StandardCharsets.UTF_8);

        try {
            Bbo bbo = Bbo.parse(payload, metadata);

            if (!bbo.isValid(metadata)) {
                synchronized (bboRecoveryLock) {
                    liveBboSeen = true;
                    runtimeState.invalidateBbo();
                }

                logger.warning("Invalid BBO state: " + payload);
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
            String payload = new String(data, StandardCharsets.UTF_8);
            DeskRiskMessage risk = DeskRiskMessage.parse(payload);
            runtimeState.acceptRisk(risk);
        } catch (Exception e) {
            logger.warning("Ignoring malformed risk message: " + e.getMessage());
        }
    }

    @Override
    public void connectionEvent(Connection conn, Events type) {
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

        logger.info("Connection closed");
    }

    private void handleLameDuck() {
        subscriptionsReady = false;

        if (runtimeState != null) {
            runtimeState.resetTrust();
        }

        logger.info("Lame duck mode");
    }

    private void handleReconnected() {
        subscriptionsReady = false;

        if (runtimeState != null) {
            runtimeState.resetTrust();
        }

        logger.info("Reconnected to NATS; waiting for subscriptions to resynchronize");
    }

    private void handleResubscribed() {
        try {
            Metadata newMetadata = loadMetadata();

            if (!metadataEquals(metadata, newMetadata)) {
                logger.severe(
                    "Metadata changed after reconnect; "
                        + "process restart required");

                subscriptionsReady = false;
                runtimeState.resetTrust();
                return;
            }

            natsConnection.flush(Duration.ofSeconds(5));

            subscriptionsReady = true;
            runtimeState.markConnected();

            recoverRetainedBbo();

        } catch (Exception e) {
            subscriptionsReady = false;

            logger.severe(
                "Reconnect recovery failed: " + e.getMessage());

            if (runtimeState != null) {
                runtimeState.resetTrust();
            }
        }
    }

    private boolean metadataEquals(Metadata m1, Metadata m2) {
        if (m1 == m2) return true;
        if (m1 == null || m2 == null) return false;

        return m1.getFeed().equals(m2.getFeed())
            && m1.getTickSize().compareTo(m2.getTickSize()) == 0
            && ((m1.getRefPrice() == null && m2.getRefPrice() == null)
                || (m1.getRefPrice() != null && m2.getRefPrice() != null && m1.getRefPrice().compareTo(m2.getRefPrice()) == 0))
            && ((m1.getBand() == null && m2.getBand() == null)
                || (m1.getBand() != null && m2.getBand() != null && m1.getBand().compareTo(m2.getBand()) == 0));
    }

    public RuntimeState getRuntimeState() {
        return runtimeState;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public Connection getConnection() {
        return natsConnection;
    }

    public void close() throws Exception {
        if (natsConnection != null) {
            natsConnection.close();
        }
    }

    private Metadata loadMetadata() throws Exception {
        var entry = natsConnection.keyValue("EX_META").get(feed);

        if (entry == null) {
            throw new IllegalStateException(
                "Missing EX_META entry for feed " + feed);
        }

        String payload = entry.getValueAsString();

        if (payload == null || payload.isBlank()) {
            throw new IllegalStateException(
                "Empty EX_META entry for feed " + feed);
        }

        return Metadata.parse(feed, payload);
    }
}
