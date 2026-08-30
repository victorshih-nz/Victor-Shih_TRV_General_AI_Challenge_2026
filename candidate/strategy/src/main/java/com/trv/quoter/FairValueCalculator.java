package com.trv.quoter;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class FairValueCalculator {
    public BigDecimal calculateMidpoint(Bbo bbo) {
        if (bbo == null) {
            throw new IllegalArgumentException("bbo must not be null");
        }
        return bbo.midpoint();
    }

    public BigDecimal calculateMicropriceAdjustment(Bbo bbo, Metadata metadata, QuoterConfig config) {
        if (bbo == null || metadata == null || config == null) {
            throw new IllegalArgumentException("bbo, metadata, and config must not be null");
        }
        BigDecimal bidQty = bbo.getBidQty();
        BigDecimal askQty = bbo.getAskQty();
        BigDecimal bidPrice = bbo.getBidPrice();
        BigDecimal askPrice = bbo.getAskPrice();
        BigDecimal denom = bidQty.add(askQty);
        if (denom.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal midpoint = bidPrice.add(askPrice).divide(BigDecimal.valueOf(2), 12, RoundingMode.HALF_UP);
        BigDecimal imbalance = bidQty.subtract(askQty).divide(denom, 12, RoundingMode.HALF_UP);
        BigDecimal spread = askPrice.subtract(bidPrice);
        BigDecimal rawAdjustment = BigDecimal.valueOf(0.5).multiply(spread).multiply(imbalance);
        BigDecimal configuredBound = BigDecimal.valueOf(config.getMaxMicroPriceAdjustmentTicks())
                .multiply(metadata.getTickSize());
        BigDecimal boundedAdjustment = rawAdjustment.min(configuredBound).max(configuredBound.negate());
        return boundedAdjustment;
    }

    public BigDecimal calculateFairValue(Bbo bbo, Metadata metadata, QuoterConfig config) {
        BigDecimal midpoint = calculateMidpoint(bbo);
        BigDecimal adjustment = calculateMicropriceAdjustment(bbo, metadata, config);
        return midpoint.add(adjustment);
    }
}
