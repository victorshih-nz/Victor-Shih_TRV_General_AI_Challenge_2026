package com.trv.quoter;

import java.time.Instant;

public final class MarketReadiness {
    private MarketReadiness() {
    }

    public static boolean isReady(QuoterConfig config, Metadata metadata, Bbo bbo, HedgerState hedgerState, Instant now) {
        if (config == null || metadata == null || bbo == null || hedgerState == null || now == null) {
            return false;
        }
        if (!config.getFeed().equals(metadata.getFeed())) {
            return false;
        }
        if (!metadata.isValid() || !bbo.isValid(metadata)) {
            return false;
        }
        if (hedgerState == HedgerState.UNKNOWN) {
            return false;
        }
        long elapsed = now.toEpochMilli() - bbo.getReceivedAtEpochMs();
        return elapsed >= 0 && elapsed <= config.getMarketDataStaleMs();
    }

    public static boolean isReady(QuoterConfig config, Metadata metadata, Bbo bbo, HedgerState hedgerState) {
        return isReady(config, metadata, bbo, hedgerState, Instant.now());
    }
}
