package com.trv.quoter;

import java.util.Objects;
import java.util.function.LongSupplier;

public final class RuntimeState {

    private static final long MARKET_DATA_STALE_NS = 3_000_000_000L;
    private static final long RISK_STALE_NS = 1_000_000_000L;

    private final String feed;
    private final Metadata metadata;
    private final LongSupplier nanoClock;

    private boolean connectionTrusted;

    private Bbo trustedBbo;
    private long bboReceivedAtNs;

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

    public void markConnected() {
        connectionTrusted = true;
    }

    public void resetTrust() {
        connectionTrusted = false;

        trustedBbo = null;
        bboReceivedAtNs = 0L;

        trustedRisk = null;
        riskReceivedAtNs = 0L;

        lastAcceptedRiskSeq = null;
    }

    public void acceptBbo(Bbo bbo) {
        if (bbo == null || !bbo.isValid(metadata)) {
            return;
        }

        trustedBbo = bbo;
        bboReceivedAtNs = nanoClock.getAsLong();
    }

    public void acceptRisk(DeskRiskMessage risk) {
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

    public boolean isReady() {
        if (!connectionTrusted) {
            return false;
        }

        if (trustedBbo == null || !trustedBbo.isValid(metadata)) {
            return false;
        }

        long now = nanoClock.getAsLong();

        if (now - bboReceivedAtNs > MARKET_DATA_STALE_NS) {
            return false;
        }

        if (trustedRisk == null) {
            return false;
        }

        if (now - riskReceivedAtNs > RISK_STALE_NS) {
            trustedRisk = null;
            riskReceivedAtNs = 0L;
            lastAcceptedRiskSeq = null;
            return false;
        }

        return trustedRisk.getState() != HedgerState.UNKNOWN;
    }
}
