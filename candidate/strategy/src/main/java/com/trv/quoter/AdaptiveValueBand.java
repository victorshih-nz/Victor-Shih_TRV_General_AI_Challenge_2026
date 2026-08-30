package com.trv.quoter;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class AdaptiveValueBand {
    public BigDecimal calculateValueBandTicks(Bbo bbo, Metadata metadata, QuoterConfig config, BigDecimal volatilityTicks) {
        if (bbo == null || metadata == null || config == null) {
            throw new IllegalArgumentException("bbo, metadata, and config are required");
        }
        BigDecimal spreadTicks = bbo.getAskPrice().subtract(bbo.getBidPrice())
                .divide(metadata.getTickSize(), 0, RoundingMode.CEILING);
        BigDecimal base = BigDecimal.valueOf(config.getValueBandBaseTicks());
        BigDecimal spreadComponent = spreadTicks.max(BigDecimal.ZERO);
        BigDecimal volatilityComponent = volatilityTicks == null ? BigDecimal.ZERO : volatilityTicks.max(BigDecimal.ZERO);
        BigDecimal ticks = base.add(spreadComponent).add(volatilityComponent);
        BigDecimal min = BigDecimal.valueOf(config.getMinValueBandTicks());
        BigDecimal max = BigDecimal.valueOf(config.getMaxValueBandTicks());
        return ticks.max(min).min(max);
    }

    public ValueBand compute(Bbo bbo, Metadata metadata, QuoterConfig config, BigDecimal fairValue, BigDecimal volatilityTicks) {
        BigDecimal valueBandTicks = calculateValueBandTicks(bbo, metadata, config, volatilityTicks);
        BigDecimal valueBand = valueBandTicks.multiply(metadata.getTickSize());
        BigDecimal cheapValue = fairValue.subtract(valueBand);
        BigDecimal expensiveValue = fairValue.add(valueBand);
        return new ValueBand(valueBandTicks, valueBand, cheapValue, expensiveValue);
    }

    public record ValueBand(BigDecimal valueBandTicks, BigDecimal valueBand, BigDecimal cheapValue, BigDecimal expensiveValue) {
    }
}
