package com.trv.quoter;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class Metadata {
    private final String feed;
    private final BigDecimal tickSize;
    private final BigDecimal refPrice;
    private final BigDecimal band;
    private final Map<String, String> rawValues;

    public Metadata(String feed, BigDecimal tickSize, BigDecimal refPrice, BigDecimal band, Map<String, String> rawValues) {
        this.feed = requireNonBlank(feed, "feed");
        this.tickSize = requirePositive(tickSize, "tickSize");
        this.refPrice = refPrice == null ? null : refPrice;
        this.band = band == null ? null : requireNonNegative(band, "band");
        this.rawValues = new HashMap<>(Objects.requireNonNull(rawValues, "rawValues"));
    }

    public String getFeed() {
        return feed;
    }

    public BigDecimal getTickSize() {
        return tickSize;
    }

    public BigDecimal getRefPrice() {
        return refPrice;
    }

    public BigDecimal getBand() {
        return band;
    }

    public boolean isValid() {
        return tickSize.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isPriceWithinBounds(BigDecimal price) {
        if (price == null) {
            return false;
        }
        if (refPrice == null || band == null) {
            return true;
        }
        BigDecimal lower = refPrice.subtract(band);
        BigDecimal upper = refPrice.add(band);
        return price.compareTo(lower) >= 0 && price.compareTo(upper) <= 0;
    }

    public static Metadata parse(String feed, String payload) {
        if (feed == null || feed.isBlank()) {
            throw new IllegalArgumentException("feed must not be blank");
        }
        Map<String, String> values = new HashMap<>();
        if (payload != null && !payload.isBlank()) {
            String normalized = payload.replace(',', ' ')
                    .replace(';', ' ')
                    .replace("\n", " ")
                    .replace("\r", " ");
            String[] parts = normalized.trim().split("\\s+");
            for (String part : parts) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = part.substring(0, eq).trim();
                String value = part.substring(eq + 1).trim();
                if (!key.isBlank() && !value.isBlank()) {
                    values.put(key, value);
                }
            }
        }
        String tickSizeText = values.get("ticksize");
        if (tickSizeText == null || tickSizeText.isBlank()) {
            throw new IllegalArgumentException("Metadata is missing required ticksize");
        }

        BigDecimal tickSize = new BigDecimal(tickSizeText);
        BigDecimal refPrice = values.containsKey("ref_price") ? new BigDecimal(values.get("ref_price")) : null;
        BigDecimal band = values.containsKey("band") ? new BigDecimal(values.get("band")) : null;
        return new Metadata(feed, tickSize, refPrice, band, values);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static BigDecimal requirePositive(BigDecimal value, String fieldName) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(fieldName + " must be > 0");
        }
        return value;
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String fieldName) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(fieldName + " must be >= 0");
        }
        return value;
    }
}
