package com.trv.quoter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public final class Bbo {
    private final BigDecimal bidPrice;
    private final BigDecimal bidQty;
    private final BigDecimal askPrice;
    private final BigDecimal askQty;
    private final Instant receivedAt;

    public Bbo(BigDecimal bidPrice, BigDecimal bidQty, BigDecimal askPrice, BigDecimal askQty, Instant receivedAt) {
        this.bidPrice = bidPrice;
        this.bidQty = bidQty;
        this.askPrice = askPrice;
        this.askQty = askQty;
        this.receivedAt = receivedAt;
    }

    public BigDecimal getBidPrice() {
        return bidPrice;
    }

    public BigDecimal getBidQty() {
        return bidQty;
    }

    public BigDecimal getAskPrice() {
        return askPrice;
    }

    public BigDecimal getAskQty() {
        return askQty;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public long getReceivedAtEpochMs() {
        return receivedAt.toEpochMilli();
    }

    public boolean isValid(Metadata metadata) {
        if (metadata == null || !metadata.isValid()) {
            return false;
        }
        if (receivedAt == null) {
            return false;
        }
        if (bidPrice == null || askPrice == null || bidQty == null || askQty == null) {
            return false;
        }
        if (bidQty.compareTo(BigDecimal.ZERO) <= 0 || askQty.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (bidPrice.compareTo(askPrice) >= 0) {
            return false;
        }
        if (!metadata.isPriceWithinBounds(bidPrice) || !metadata.isPriceWithinBounds(askPrice)) {
            return false;
        }
        return true;
    }

    public BigDecimal midpoint() {
        return bidPrice.add(askPrice).divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
    }

    public static Bbo parse(String line, Metadata metadata) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("BBO line is blank");
        }
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 6) {
            throw new IllegalArgumentException("Malformed BBO: expected 6 fields");
        }
        long exchangeTimestamp;
        try {
            exchangeTimestamp = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed BBO timestamp: " + parts[0], e);
        }
        String feed = parts[1];
        if (metadata != null && !metadata.getFeed().equals(feed)) {
            throw new IllegalArgumentException("BBO feed does not match metadata feed");
        }
        BigDecimal bidPrice = parsePrice(parts[2]);
        BigDecimal bidQty = parseQty(parts[3]);
        BigDecimal askPrice = parsePrice(parts[4]);
        BigDecimal askQty = parseQty(parts[5]);
        return new Bbo(bidPrice, bidQty, askPrice, askQty, Instant.now());
    }

    private static BigDecimal parsePrice(String text) {
        if (text == null || text.equals("-")) {
            throw new IllegalArgumentException("Price is missing");
        }
        try {
            long price = Long.parseLong(text);
            return BigDecimal.valueOf(price);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Price must be an integer, saw " + text, e);
        }
    }

    private static BigDecimal parseQty(String text) {
        if (text == null || text.equals("-")) {
            throw new IllegalArgumentException("Quantity is missing");
        }
        try {
            long qty = Long.parseLong(text);
            if (qty <= 0) {
                throw new IllegalArgumentException("Quantity must be positive, saw " + text);
            }
            return BigDecimal.valueOf(qty);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Quantity must be an integer, saw " + text, e);
        }
    }
}
