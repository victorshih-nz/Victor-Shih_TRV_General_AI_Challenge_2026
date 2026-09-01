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
    private long bboVersion;

    private DeskRiskMessage trustedRisk;
    private long riskReceivedAtNs;
    private Long lastAcceptedRiskSeq;
    private long riskVersion;

    public RuntimeState(
            String feed,
            Metadata metadata) {

        this(
            feed,
            metadata,
            System::nanoTime);
    }

    RuntimeState(
            String feed,
            Metadata metadata,
            LongSupplier nanoClock) {

        if (feed == null
                || feed.isBlank()) {

            throw new IllegalArgumentException(
                "feed is required");
        }

        this.feed = feed;
        this.metadata =
            Objects.requireNonNull(
                metadata,
                "metadata");
        this.nanoClock =
            Objects.requireNonNull(
                nanoClock,
                "nanoClock");

        if (!feed.equals(
                metadata.getFeed())) {

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
        bboVersion++;

        trustedRisk = null;
        riskReceivedAtNs = 0L;
        lastAcceptedRiskSeq = null;
        riskVersion++;
    }

    public synchronized void acceptBbo(
            Bbo bbo) {

        if (bbo == null
                || !bbo.isValid(metadata)) {

            return;
        }

        trustedBbo = bbo;
        bboVersion++;
    }

    public synchronized void invalidateBbo() {
        if (trustedBbo != null) {
            trustedBbo = null;
            bboVersion++;
        }
    }

    public synchronized void acceptRisk(
            DeskRiskMessage risk) {

        if (risk == null) {
            return;
        }

        if (!feed.equals(
                risk.getFeed())) {

            return;
        }

        if (lastAcceptedRiskSeq != null
                && risk.getSequence()
                    <= lastAcceptedRiskSeq) {

            return;
        }

        trustedRisk = risk;
        riskReceivedAtNs =
            nanoClock.getAsLong();
        lastAcceptedRiskSeq =
            risk.getSequence();
        riskVersion++;
    }

    /**
     * Returns one internally consistent view of the trusted market/risk state.
     *
     * The snapshot also performs the same risk-freshness check as isReady().
     * Therefore a periodic evaluator can detect risk expiry even when no new
     * BBO or risk message arrives.
     *
     * bboVersion changes only when trusted BBO state changes. It lets the quote
     * evaluator distinguish a genuinely new market observation from a
     * risk/lifecycle/timer re-evaluation of the same BBO.
     */
    public synchronized Snapshot snapshot() {
        expireStaleRiskIfNeeded();

        boolean ready =
            connectionTrusted
                && trustedBbo != null
                && trustedBbo.isValid(metadata)
                && trustedRisk != null
                && trustedRisk.getState()
                    != HedgerState.UNKNOWN;

        return new Snapshot(
            trustedBbo,
            trustedRisk,
            ready,
            bboVersion,
            riskVersion);
    }

    public synchronized boolean isReady() {
        return snapshot().ready();
    }

    private void expireStaleRiskIfNeeded() {
        if (trustedRisk == null) {
            return;
        }

        long now =
            nanoClock.getAsLong();

        if (now - riskReceivedAtNs
                > RISK_STALE_NS) {

            trustedRisk = null;
            riskReceivedAtNs = 0L;
            lastAcceptedRiskSeq = null;
            riskVersion++;
        }
    }

    public record Snapshot(
        Bbo bbo,
        DeskRiskMessage risk,
        boolean ready,
        long bboVersion,
        long riskVersion) {
    }
}
