package com.trv.quoter;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable typed view of Exchange Protocol v2.5 EX_META for one feed.
 *
 * Production exchange metadata is parsed through {@link #parse(String, String)}
 * and is fail-closed: all trading-critical fields required by the challenge
 * must be present and valid.
 *
 * Price arithmetic deliberately remains BigDecimal-based. No double conversion
 * is used for tick size, reference price, price band, or price-bound checks.
 */
public final class Metadata {

    private final String feed;
    private final BigDecimal tickSize;
    private final BigDecimal refPrice;
    private final BigDecimal band;
    private final int minVolume;
    private final int maxVolume;
    private final int positionLimit;
    private final int maxTps;
    private final Map<String, String> rawValues;

    /**
     * Compatibility constructor retained for existing in-memory unit fixtures.
     *
     * Runtime EX_META must use parse(). The conservative 1/1/1/1 values here
     * avoid silently widening exchange limits for legacy direct-constructor
     * fixtures while preserving their source compatibility.
     */
    public Metadata(
            String feed,
            BigDecimal tickSize,
            BigDecimal refPrice,
            BigDecimal band,
            Map<String, String> rawValues) {

        this(
            feed,
            tickSize,
            refPrice,
            band,
            1,
            1,
            1,
            1,
            rawValues);
    }

    public Metadata(
            String feed,
            BigDecimal tickSize,
            BigDecimal refPrice,
            BigDecimal band,
            int minVolume,
            int maxVolume,
            int positionLimit,
            int maxTps,
            Map<String, String> rawValues) {

        this.feed =
            requireNonBlank(
                feed,
                "feed");

        this.tickSize =
            requirePositive(
                tickSize,
                "tickSize");

        this.refPrice =
            Objects.requireNonNull(
                refPrice,
                "refPrice is required");

        this.band =
            requireNonNegative(
                band,
                "band");

        this.minVolume =
            requirePositiveInt(
                minVolume,
                "minVolume");

        this.maxVolume =
            requirePositiveInt(
                maxVolume,
                "maxVolume");

        if (minVolume > maxVolume) {
            throw new IllegalArgumentException(
                "minVolume must be <= maxVolume");
        }

        this.positionLimit =
            requirePositiveInt(
                positionLimit,
                "positionLimit");

        this.maxTps =
            requirePositiveInt(
                maxTps,
                "maxTps");

        Map<String, String> copy =
            new HashMap<>(
                Objects.requireNonNull(
                    rawValues,
                    "rawValues"));

        this.rawValues =
            Collections.unmodifiableMap(copy);
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

    public int getMinVolume() {
        return minVolume;
    }

    public int getMaxVolume() {
        return maxVolume;
    }

    public int getPositionLimit() {
        return positionLimit;
    }

    public int getMaxTps() {
        return maxTps;
    }

    public boolean isVolumeWithinBounds(
            int quantity) {

        return quantity >= minVolume
            && quantity <= maxVolume;
    }

    public Map<String, String> getRawValues() {
        return rawValues;
    }

    /**
     * Defensive validity predicate for readiness consumers.
     * Constructors and parse() already enforce these invariants.
     */
    public boolean isValid() {
        return feed != null
            && !feed.isBlank()
            && tickSize != null
            && tickSize.compareTo(
                BigDecimal.ZERO) > 0
            && refPrice != null
            && band != null
            && band.compareTo(
                BigDecimal.ZERO) >= 0
            && minVolume > 0
            && maxVolume >= minVolume
            && positionLimit > 0
            && maxTps > 0;
    }

    /**
     * True when price lies inside the Exchange metadata price band.
     *
     * No positivity assumption is added here: Protocol v2.5 defines the band
     * as ref_price +/- band but does not state that the lower bound must be > 0.
     */
    public boolean isPriceWithinBounds(
            BigDecimal price) {

        if (price == null
                || !isValid()) {

            return false;
        }

        BigDecimal lower =
            refPrice.subtract(band);

        BigDecimal upper =
            refPrice.add(band);

        return price.compareTo(lower) >= 0
            && price.compareTo(upper) <= 0;
    }

    /**
     * Exact BigDecimal tick-grid test for later order-entry enforcement.
     * This method intentionally does not round.
     */
    public boolean isPriceOnTick(
            BigDecimal price) {

        if (price == null
                || tickSize == null
                || tickSize.compareTo(
                    BigDecimal.ZERO) <= 0) {

            return false;
        }

        return price.remainder(tickSize)
            .compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * Parse the complete trading-critical EX_META contract.
     *
     * Required Protocol v2.5 / challenge fields:
     * ticksize, ref_price, band, min_volume, max_volume, position_limit,
     * max_tps.
     *
     * Unknown fields such as last_traded_price are preserved in rawValues but
     * are not promoted into strategy inputs unless the strategy actually needs
     * them.
     */
    public static Metadata parse(
            String feed,
            String payload) {

        String normalizedFeed =
            requireNonBlank(
                feed,
                "feed");

        if (payload == null
                || payload.isBlank()) {

            throw new IllegalArgumentException(
                "Metadata payload is blank");
        }

        Map<String, String> values =
            parseKeyValues(payload);

        BigDecimal tickSize =
            parseRequiredBigDecimal(
                values,
                "ticksize");

        BigDecimal refPrice =
            parseRequiredBigDecimal(
                values,
                "ref_price");

        BigDecimal band =
            parseRequiredBigDecimal(
                values,
                "band");

        int minVolume =
            parseRequiredInt(
                values,
                "min_volume");

        int maxVolume =
            parseRequiredInt(
                values,
                "max_volume");

        int positionLimit =
            parseRequiredInt(
                values,
                "position_limit");

        int maxTps =
            parseRequiredInt(
                values,
                "max_tps");

        return new Metadata(
            normalizedFeed,
            tickSize,
            refPrice,
            band,
            minVolume,
            maxVolume,
            positionLimit,
            maxTps,
            values);
    }

    private static Map<String, String> parseKeyValues(
            String payload) {

        Map<String, String> values =
            new HashMap<>();

        String normalized =
            payload
                .replace(',', ' ')
                .replace(';', ' ')
                .replace('\n', ' ')
                .replace('\r', ' ');

        String trimmed =
            normalized.trim();

        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                "Metadata payload has no fields");
        }

        for (String part :
                trimmed.split("\\s+")) {

            int eq =
                part.indexOf('=');

            if (eq <= 0
                    || eq == part.length() - 1) {

                continue;
            }

            String key =
                part.substring(
                    0,
                    eq).trim();

            String value =
                part.substring(
                    eq + 1).trim();

            if (!key.isBlank()
                    && !value.isBlank()) {

                values.put(
                    key,
                    value);
            }
        }

        return values;
    }

    private static BigDecimal parseRequiredBigDecimal(
            Map<String, String> values,
            String key) {

        String text =
            requireMetadataValue(
                values,
                key);

        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Metadata "
                    + key
                    + " must be decimal, saw "
                    + text,
                e);
        }
    }

    private static int parseRequiredInt(
            Map<String, String> values,
            String key) {

        String text =
            requireMetadataValue(
                values,
                key);

        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Metadata "
                    + key
                    + " must be an integer, saw "
                    + text,
                e);
        }
    }

    private static String requireMetadataValue(
            Map<String, String> values,
            String key) {

        String value =
            values.get(key);

        if (value == null
                || value.isBlank()) {

            throw new IllegalArgumentException(
                "Metadata is missing required "
                    + key);
        }

        return value;
    }

    private static String requireNonBlank(
            String value,
            String fieldName) {

        if (value == null
                || value.isBlank()) {

            throw new IllegalArgumentException(
                fieldName
                    + " must not be blank");
        }

        return value.trim();
    }

    private static BigDecimal requirePositive(
            BigDecimal value,
            String fieldName) {

        if (value == null
                || value.compareTo(
                    BigDecimal.ZERO) <= 0) {

            throw new IllegalArgumentException(
                fieldName
                    + " must be > 0");
        }

        return value;
    }

    private static BigDecimal requireNonNegative(
            BigDecimal value,
            String fieldName) {

        if (value == null
                || value.compareTo(
                    BigDecimal.ZERO) < 0) {

            throw new IllegalArgumentException(
                fieldName
                    + " must be >= 0");
        }

        return value;
    }

    private static int requirePositiveInt(
            int value,
            String fieldName) {

        if (value <= 0) {
            throw new IllegalArgumentException(
                fieldName
                    + " must be > 0");
        }

        return value;
    }
}
