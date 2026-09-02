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

    public Bbo(
            BigDecimal bidPrice,
            BigDecimal bidQty,
            BigDecimal askPrice,
            BigDecimal askQty,
            Instant receivedAt) {

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

    /**
     * Protocol validity is intentionally broader than quote readiness.
     *
     * The Exchange represents an empty side as "- 0". That is a legitimate
     * market state, not malformed input. A present price must still carry a
     * strictly positive quantity.
     */
    public boolean isProtocolStateValid(Metadata metadata) {
        if (metadata == null || !metadata.isValid()) {
            return false;
        }

        if (receivedAt == null) {
            return false;
        }

        if (!isProtocolSideValid(
                bidPrice,
                bidQty,
                metadata)) {

            return false;
        }

        if (!isProtocolSideValid(
                askPrice,
                askQty,
                metadata)) {

            return false;
        }

        return bidPrice == null
            || askPrice == null
            || bidPrice.compareTo(askPrice) < 0;
    }

    /**
     * Quote readiness remains strict: Quoter fair-value logic requires both
     * sides. One-sided / empty books parse successfully but are not quote-ready.
     */
    public boolean isValid(Metadata metadata) {
        return isProtocolStateValid(metadata)
            && bidPrice != null
            && askPrice != null;
    }

    public BigDecimal midpoint() {
        if (bidPrice == null || askPrice == null) {
            throw new IllegalStateException(
                "midpoint requires a two-sided BBO");
        }

        return bidPrice
            .add(askPrice)
            .divide(
                BigDecimal.valueOf(2),
                10,
                RoundingMode.HALF_UP);
    }

    public static Bbo parse(
            String line,
            Metadata metadata) {

        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException(
                "BBO line is blank");
        }

        String[] parts =
            line.trim().split("\\s+");

        if (parts.length != 6) {
            throw new IllegalArgumentException(
                "Malformed BBO: expected 6 fields");
        }

        try {
            Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Malformed BBO timestamp: "
                    + parts[0],
                e);
        }

        String feed = parts[1];

        if (metadata != null
                && !metadata.getFeed().equals(feed)) {

            throw new IllegalArgumentException(
                "BBO feed does not match metadata feed");
        }

        ParsedSide bid =
            parseSide(
                parts[2],
                parts[3],
                "bid");

        ParsedSide ask =
            parseSide(
                parts[4],
                parts[5],
                "ask");

        Bbo bbo =
            new Bbo(
                bid.price(),
                bid.quantity(),
                ask.price(),
                ask.quantity(),
                Instant.now());

        if (metadata != null
                && !bbo.isProtocolStateValid(metadata)) {

            throw new IllegalArgumentException(
                "BBO market state is invalid");
        }

        return bbo;
    }

    private static ParsedSide parseSide(
            String priceText,
            String qtyText,
            String sideName) {

        if ("-".equals(priceText)) {
            long qty =
                parseNonNegativeInteger(
                    qtyText,
                    sideName + " quantity");

            if (qty != 0L) {
                throw new IllegalArgumentException(
                    sideName
                        + " empty price must have zero quantity");
            }

            return new ParsedSide(
                null,
                BigDecimal.ZERO);
        }

        long price =
            parsePositiveInteger(
                priceText,
                sideName + " price");

        long qty =
            parsePositiveInteger(
                qtyText,
                sideName + " quantity");

        return new ParsedSide(
            BigDecimal.valueOf(price),
            BigDecimal.valueOf(qty));
    }

    private static long parsePositiveInteger(
            String text,
            String fieldName) {

        long value =
            parseNonNegativeInteger(
                text,
                fieldName);

        if (value <= 0L) {
            throw new IllegalArgumentException(
                fieldName
                    + " must be positive, saw "
                    + text);
        }

        return value;
    }

    private static long parseNonNegativeInteger(
            String text,
            String fieldName) {

        if (text == null || "-".equals(text)) {
            throw new IllegalArgumentException(
                fieldName + " is missing");
        }

        final long value;

        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                fieldName
                    + " must be an integer, saw "
                    + text,
                e);
        }

        if (value < 0L) {
            throw new IllegalArgumentException(
                fieldName
                    + " must be non-negative, saw "
                    + text);
        }

        return value;
    }

    private static boolean isProtocolSideValid(
            BigDecimal price,
            BigDecimal quantity,
            Metadata metadata) {

        if (quantity == null) {
            return false;
        }

        if (price == null) {
            return quantity.compareTo(
                BigDecimal.ZERO) == 0;
        }

        return quantity.compareTo(
                BigDecimal.ZERO) > 0
            && metadata.isPriceWithinBounds(price);
    }

    private record ParsedSide(
        BigDecimal price,
        BigDecimal quantity) {
    }
}
