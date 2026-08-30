package com.trv.quoter;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class EwmaMovement {
    private BigDecimal previousFairValue;
    private BigDecimal previousVolatilityTicks;

    public EwmaMovement() {
        this.previousVolatilityTicks = BigDecimal.ZERO;
    }

    public BigDecimal update(BigDecimal fairValue, BigDecimal tickSize, double alpha) {
        if (fairValue == null || tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("fairValue and tickSize are required");
        }
        if (alpha <= 0.0 || alpha >= 1.0) {
            throw new IllegalArgumentException("alpha must be in (0,1)");
        }

        BigDecimal movementTicks;
        if (previousFairValue == null) {
            movementTicks = BigDecimal.ZERO;
        } else {
            BigDecimal absMove = previousFairValue.subtract(fairValue).abs();
            movementTicks = absMove.divide(tickSize, 12, RoundingMode.HALF_UP);
        }

        BigDecimal alphaValue = BigDecimal.valueOf(alpha);
        BigDecimal oneMinusAlpha = BigDecimal.ONE.subtract(alphaValue);
        BigDecimal nextVolatility = movementTicks.multiply(alphaValue)
                .add(previousVolatilityTicks.multiply(oneMinusAlpha));

        previousFairValue = fairValue;
        previousVolatilityTicks = nextVolatility;
        return nextVolatility;
    }

    public BigDecimal getPreviousVolatilityTicks() {
        return previousVolatilityTicks;
    }
}
