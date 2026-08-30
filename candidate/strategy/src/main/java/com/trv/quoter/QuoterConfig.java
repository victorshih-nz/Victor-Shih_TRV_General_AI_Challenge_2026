package com.trv.quoter;

import java.util.Map;
import java.util.Objects;

public final class QuoterConfig {
    public static final String TAKER_FEED_ENV = "TAKER_FEED";
    public static final String MARKET_DATA_STALE_MS_ENV = "MARKET_DATA_STALE_MS";
    public static final String MAX_VALUATION_ADJUSTMENT_TICKS_ENV = "MAX_VALUATION_ADJUSTMENT_TICKS";
    public static final String MAX_MICROPRICE_ADJUSTMENT_TICKS_ENV = "MAX_MICROPRICE_ADJUSTMENT_TICKS";
    public static final String VALUE_BAND_BASE_TICKS_ENV = "VALUE_BAND_BASE_TICKS";
    public static final String MIN_VALUE_BAND_TICKS_ENV = "MIN_VALUE_BAND_TICKS";
    public static final String MAX_VALUE_BAND_TICKS_ENV = "MAX_VALUE_BAND_TICKS";
    public static final String EWMA_ALPHA_ENV = "EWMA_ALPHA";

    private final String feed;
    private final int marketDataStaleMs;
    private final int maxValuationAdjustmentTicks;
    private final int maxMicroPriceAdjustmentTicks;
    private final int valueBandBaseTicks;
    private final int minValueBandTicks;
    private final int maxValueBandTicks;
    private final double ewmaAlpha;

    public QuoterConfig(
            String feed,
            int marketDataStaleMs,
            int maxValuationAdjustmentTicks,
            int maxMicroPriceAdjustmentTicks,
            int valueBandBaseTicks,
            int minValueBandTicks,
            int maxValueBandTicks,
            double ewmaAlpha) {
        this.feed = requireNonBlank(feed, "feed");
        if (marketDataStaleMs <= 0) {
            throw new IllegalArgumentException("marketDataStaleMs must be > 0");
        }
        if (maxValuationAdjustmentTicks <= 0) {
            throw new IllegalArgumentException("maxValuationAdjustmentTicks must be > 0");
        }
        if (maxMicroPriceAdjustmentTicks <= 0) {
            throw new IllegalArgumentException("maxMicroPriceAdjustmentTicks must be > 0");
        }
        if (valueBandBaseTicks < 0) {
            throw new IllegalArgumentException("valueBandBaseTicks must be >= 0");
        }
        if (minValueBandTicks <= 0) {
            throw new IllegalArgumentException("minValueBandTicks must be > 0");
        }
        if (maxValueBandTicks < minValueBandTicks) {
            throw new IllegalArgumentException("maxValueBandTicks must be >= minValueBandTicks");
        }
        if (!Double.isFinite(ewmaAlpha) || ewmaAlpha <= 0.0 || ewmaAlpha >= 1.0) {
            throw new IllegalArgumentException("ewmaAlpha must be finite and in the range (0,1)");
        }
        this.marketDataStaleMs = marketDataStaleMs;
        this.maxValuationAdjustmentTicks = maxValuationAdjustmentTicks;
        this.maxMicroPriceAdjustmentTicks = maxMicroPriceAdjustmentTicks;
        this.valueBandBaseTicks = valueBandBaseTicks;
        this.minValueBandTicks = minValueBandTicks;
        this.maxValueBandTicks = maxValueBandTicks;
        this.ewmaAlpha = ewmaAlpha;
    }

    public String getFeed() {
        return feed;
    }

    public int getMarketDataStaleMs() {
        return marketDataStaleMs;
    }

    public int getMaxValuationAdjustmentTicks() {
        return maxValuationAdjustmentTicks;
    }

    public int getMaxMicroPriceAdjustmentTicks() {
        return maxMicroPriceAdjustmentTicks;
    }

    public int getValueBandBaseTicks() {
        return valueBandBaseTicks;
    }

    public int getMinValueBandTicks() {
        return minValueBandTicks;
    }

    public int getMaxValueBandTicks() {
        return maxValueBandTicks;
    }

    public double getEwmaAlpha() {
        return ewmaAlpha;
    }

    public static QuoterConfig fromEnvironment() {
        return fromMap(System.getenv());
    }

    public static QuoterConfig fromMap(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        String feed = readRequired(env, TAKER_FEED_ENV);
        int staleMs = parseInt(env.getOrDefault(MARKET_DATA_STALE_MS_ENV, "3000"), MARKET_DATA_STALE_MS_ENV);
        int maxValuationTicks = parseInt(env.getOrDefault(MAX_VALUATION_ADJUSTMENT_TICKS_ENV, "5"), MAX_VALUATION_ADJUSTMENT_TICKS_ENV);
        int maxMicroTicks = parseInt(env.getOrDefault(MAX_MICROPRICE_ADJUSTMENT_TICKS_ENV, "4"), MAX_MICROPRICE_ADJUSTMENT_TICKS_ENV);
        int baseBand = parseInt(env.getOrDefault(VALUE_BAND_BASE_TICKS_ENV, "2"), VALUE_BAND_BASE_TICKS_ENV);
        int minBand = parseInt(env.getOrDefault(MIN_VALUE_BAND_TICKS_ENV, "1"), MIN_VALUE_BAND_TICKS_ENV);
        int maxBand = parseInt(env.getOrDefault(MAX_VALUE_BAND_TICKS_ENV, "20"), MAX_VALUE_BAND_TICKS_ENV);
        double alpha = parseDouble(env.getOrDefault(EWMA_ALPHA_ENV, "0.25"), EWMA_ALPHA_ENV);

        return new QuoterConfig(feed, staleMs, maxValuationTicks, maxMicroTicks, baseBand, minBand, maxBand, alpha);
    }

    private static String readRequired(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required configuration: " + name);
        }
        return value.trim();
    }

    private static int parseInt(String raw, String name) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid integer for " + name + ": " + raw, e);
        }
    }

    private static double parseDouble(String raw, String name) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid double for " + name + ": " + raw, e);
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
