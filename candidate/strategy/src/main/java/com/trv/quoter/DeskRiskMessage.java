package com.trv.quoter;

public final class DeskRiskMessage {
    private final long timestampNs;
    private final long sequence;
    private final String feed;
    private final int netPosition;
    private final int softLimit;
    private final int hardLimit;
    private final HedgerState state;
    private final HedgeDirection hedgeDirection;

    public DeskRiskMessage(long timestampNs, long sequence, String feed, int netPosition, int softLimit, int hardLimit, HedgerState state, HedgeDirection hedgeDirection) {
        if (sequence < 0) {
            throw new IllegalArgumentException("Sequence must be non-negative");
        }
        if (feed == null || feed.isBlank()) {
            throw new IllegalArgumentException("Feed must be non-null and non-blank");
        }
        if (softLimit <= 0 || hardLimit <= 0) {
            throw new IllegalArgumentException("Limits must be positive integers");
        }
        if (softLimit >= hardLimit) {
            throw new IllegalArgumentException("softLimit must be less than hardLimit");
        }
        if (state == null) {
            throw new IllegalArgumentException("State must not be null");
        }
        if (hedgeDirection == null) {
            throw new IllegalArgumentException("HedgeDirection must not be null");
        }

        this.timestampNs = timestampNs;
        this.sequence = sequence;
        this.feed = feed;
        this.netPosition = netPosition;
        this.softLimit = softLimit;
        this.hardLimit = hardLimit;
        this.state = state;
        this.hedgeDirection = hedgeDirection;
    }

    public long getTimestampNs() {
        return timestampNs;
    }

    public long getSequence() {
        return sequence;
    }

    public String getFeed() {
        return feed;
    }

    public int getNetPosition() {
        return netPosition;
    }

    public int getSoftLimit() {
        return softLimit;
    }

    public int getHardLimit() {
        return hardLimit;
    }

    public HedgerState getState() {
        return state;
    }

    public HedgeDirection getHedgeDirection() {
        return hedgeDirection;
    }

    public static DeskRiskMessage parse(String message) {
        if (message == null) {
            throw new IllegalArgumentException("Input cannot be null");
        }

        message = message.trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("Input cannot be blank");
        }

        String[] fields = message.split("\\s+");
        if (fields.length != 8) {
            throw new IllegalArgumentException("Invalid message format");
        }

        long timestampNs = Long.parseLong(fields[0]);
        long sequence = Long.parseLong(fields[1]);
        String feed = fields[2];
        int netPosition = Integer.parseInt(fields[3]);
        int softLimit = Integer.parseInt(fields[4]);
        int hardLimit = Integer.parseInt(fields[5]);
        HedgerState state = HedgerState.valueOf(fields[6]);
        HedgeDirection hedgeDirection = HedgeDirection.valueOf(fields[7]);

        return new DeskRiskMessage(timestampNs, sequence, feed, netPosition, softLimit, hardLimit, state, hedgeDirection);
    }
}