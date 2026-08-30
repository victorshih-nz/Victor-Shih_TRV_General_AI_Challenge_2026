package com.trv.quoter;

import java.math.BigDecimal;

public enum ValuationSignal {
    CHEAP(-1),
    FAIR(0),
    EXPENSIVE(1);

    private final int value;

    ValuationSignal(int value) {
        this.value = value;
    }

    public int asInt() {
        return value;
    }

    public BigDecimal valuationAdjustmentTicks(QuoterConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        return BigDecimal.valueOf(-this.value * config.getMaxValuationAdjustmentTicks());
    }
}
