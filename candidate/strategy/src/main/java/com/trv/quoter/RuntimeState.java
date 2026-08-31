package com.trv.quoter;

import java.util.Objects;
import java.util.function.LongSupplier;

public final class RuntimeState {

    private static final long RISK_STALE_NS = 1_000_000_000L;

    private final String feed;
    private final Metadata metadata;
    private final LongSupplier nanoClock;

    private boolean connectionTrusted;

    private Bbo trustedBbo;

    private DeskRiskMessage trustedRisk;
    private long riskReceivedAtNs;

    private Long lastAcceptedRiskSeq;

    public RuntimeState(String feed, Metadata metadata) {
        this(feed, metadata, System::nanoTime);
    }

    RuntimeState(
            String feed,
            Metadata metadata,
            LongSupplier nanoClock) {

        if (feed == null || feed.isBlank()) {
            throw new IllegalArgumentException("feed is required");
        }

        this.feed = feed;
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");

        if (!feed.equals(metadata.getFeed())) {
            throw new IllegalArgumentException(
                    "feed does not match metadata feed");
        }

        resetTrust();
    }

    public synchronized void markConnected() {
        connectionTrusted = true;
    }

    public synchronized void resetTrust() {
        connectionTrusted = false;

        trustedBbo = null;

        trustedRisk = null;
        riskReceivedAtNs = 0L;

        lastAcceptedRiskSeq = null;
    }

    public synchronized void acceptBbo(Bbo bbo) {
        if (bbo == null || !bbo.isValid(metadata)) {
            return;
        }

        trustedBbo = bbo;
    }

    public synchronized void invalidateBbo() {
        trustedBbo = null;
    }

    public synchronized void acceptRisk(DeskRiskMessage risk) {
        if (risk == null) {
            return;
        }

        if (!feed.equals(risk.getFeed())) {
            return;
        }

        if (lastAcceptedRiskSeq != null
                && risk.getSequence() <= lastAcceptedRiskSeq) {
            return;
        }

        trustedRisk = risk;
        riskReceivedAtNs = nanoClock.getAsLong();
        lastAcceptedRiskSeq = risk.getSequence();
    }

    public synchronized boolean isReady() {
        if (!connectionTrusted) {
            return false;
        }

        long now = nanoClock.getAsLong();

        if (trustedRisk != null
                && now - riskReceivedAtNs > RISK_STALE_NS) {
            trustedRisk = null;
            riskReceivedAtNs = 0L;
            lastAcceptedRiskSeq = null;
        }

        if (trustedBbo == null || !trustedBbo.isValid(metadata)) {
            return false;
        }

        if (trustedRisk == null) {
            return false;
        }

        return trustedRisk.getState() != HedgerState.UNKNOWN;
    }
}
