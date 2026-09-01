package com.trv.quoter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * V1 Quoter pricing and permission policy.
 *
 * This class is intentionally small and stateful only for EWMA fair value.
 * It does not own order lifecycle state and does not send network requests.
 *
 * Frozen V1 policy:
 * - weighted mid = 80% bid + 20% ask
 * - top-of-book imbalance adjustment bounded to +/- 1 tick
 * - EWMA alpha = 0.2
 * - desk inventory skew bounded to +/- 1.5 ticks
 * - MIN_EDGE = 0.5 tick
 * - improve at most 1 tick, otherwise join, otherwise no quote
 * - SAFE opens a new pair only when both sides are profitable
 * - CONTROLLED permits only the risk-reducing side
 * - EMERGENCY / UNKNOWN do not add exposure
 * - quote clip = 1; max-position normalization = 5
 */
final class QuotePolicy {

    static final int QUOTE_CLIP = 1;
    static final int MAX_POSITION = 5;

    static final BigDecimal EWMA_ALPHA =
        new BigDecimal("0.2");
    static final BigDecimal BID_WEIGHT =
        new BigDecimal("0.8");
    static final BigDecimal ASK_WEIGHT =
        new BigDecimal("0.2");
    static final BigDecimal MAX_MICRO_ADJ_TICKS =
        new BigDecimal("1.0");
    static final BigDecimal INVENTORY_SKEW_FACTOR_TICKS =
        new BigDecimal("1.5");
    static final BigDecimal MIN_EDGE_TICKS =
        new BigDecimal("0.5");

    private static final int DIVISION_SCALE = 12;

    private final Metadata metadata;

    /*
     * EWMA is intentionally reset on trust loss / reconnect by the integration
     * layer. The first subsequent valid trusted BBO seeds EWMA directly from
     * RawFair rather than blending against stale pre-disconnect state.
     */
    private BigDecimal ewmaFair;

    QuotePolicy(Metadata metadata) {
        this.metadata =
            Objects.requireNonNull(
                metadata,
                "metadata is required");

        if (!metadata.isValid()) {
            throw new IllegalArgumentException(
                "metadata must be valid");
        }
    }

    synchronized void reset() {
        ewmaFair = null;
    }

    synchronized QuotePlan evaluate(
            Bbo bbo,
            DeskRiskMessage risk) {

        if (!isUsableBbo(bbo)) {
            return QuotePlan.noQuote(
                HedgerState.UNKNOWN,
                null,
                null,
                null);
        }

        if (risk == null
                || !metadata.getFeed().equals(
                    risk.getFeed())) {

            return QuotePlan.noQuote(
                HedgerState.UNKNOWN,
                null,
                null,
                null);
        }

        BigDecimal weightedMid =
            bbo.getBidPrice()
                .multiply(BID_WEIGHT)
                .add(
                    bbo.getAskPrice()
                        .multiply(ASK_WEIGHT));

        BigDecimal imbalance =
            bbo.getBidQty()
                .subtract(bbo.getAskQty())
                .divide(
                    bbo.getBidQty()
                        .add(bbo.getAskQty()),
                    DIVISION_SCALE,
                    RoundingMode.HALF_UP);

        BigDecimal maxMicroAdjustment =
            metadata.getTickSize()
                .multiply(MAX_MICRO_ADJ_TICKS);

        BigDecimal microAdjustment =
            clamp(
                imbalance.multiply(
                    metadata.getTickSize()),
                maxMicroAdjustment.negate(),
                maxMicroAdjustment);

        BigDecimal rawFair =
            weightedMid.add(
                microAdjustment);

        if (ewmaFair == null) {
            ewmaFair = rawFair;
        } else {
            ewmaFair =
                rawFair.multiply(EWMA_ALPHA)
                    .add(
                        ewmaFair.multiply(
                            BigDecimal.ONE
                                .subtract(EWMA_ALPHA)));
        }

        BigDecimal inventoryRatio =
            BigDecimal.valueOf(
                    risk.getNetPosition())
                .divide(
                    BigDecimal.valueOf(
                        MAX_POSITION),
                    DIVISION_SCALE,
                    RoundingMode.HALF_UP);

        inventoryRatio =
            clamp(
                inventoryRatio,
                BigDecimal.ONE.negate(),
                BigDecimal.ONE);

        BigDecimal inventoryAdjustment =
            inventoryRatio
                .negate()
                .multiply(
                    INVENTORY_SKEW_FACTOR_TICKS)
                .multiply(
                    metadata.getTickSize());

        BigDecimal finalFair =
            ewmaFair.add(
                inventoryAdjustment);

        HedgerState effectiveRisk =
            effectiveRiskState(risk);

        Long bidCandidate =
            chooseBid(
                bbo,
                finalFair);

        Long askCandidate =
            chooseAsk(
                bbo,
                finalFair);

        Long permittedBid = null;
        Long permittedAsk = null;

        switch (effectiveRisk) {
            case SAFE:
                /*
                 * SAFE does not deliberately initiate a directional one-sided
                 * position. New exposure starts only when both sides satisfy
                 * profitability.
                 */
                if (bidCandidate != null
                        && askCandidate != null) {

                    permittedBid = bidCandidate;
                    permittedAsk = askCandidate;
                }
                break;

            case CONTROLLED:
                /*
                 * desk.risk hedge direction is the risk-reducing direction.
                 * Net-position sign is checked as a consistency guard:
                 *
                 * positive position -> selling reduces risk
                 * negative position -> buying reduces risk
                 */
                if (risk.getHedgeDirection()
                            == HedgeDirection.S
                        && risk.getNetPosition() > 0) {

                    permittedAsk = askCandidate;

                } else if (
                    risk.getHedgeDirection()
                            == HedgeDirection.B
                        && risk.getNetPosition() < 0) {

                    permittedBid = bidCandidate;
                }
                break;

            case EMERGENCY:
            case UNKNOWN:
                // No exposure-increasing Add from the Quoter.
                break;
        }

        return new QuotePlan(
            permittedBid,
            permittedAsk,
            weightedMid,
            rawFair,
            ewmaFair,
            finalFair,
            effectiveRisk);
    }

    /**
     * Current resting orders may be kept within one tick of the newly desired
     * price, but only after the controller independently confirms that the
     * current order remains safe and profitable.
     */
    boolean withinKeepTolerance(
            long currentPrice,
            long desiredPrice) {

        BigDecimal difference =
            BigDecimal.valueOf(currentPrice)
                .subtract(
                    BigDecimal.valueOf(
                        desiredPrice))
                .abs();

        return difference.compareTo(
            metadata.getTickSize()) <= 0;
    }

    boolean isStillProfitable(
            OrderManager.Side side,
            long price,
            BigDecimal finalFair) {

        Objects.requireNonNull(
            side,
            "side is required");

        if (finalFair == null) {
            return false;
        }

        BigDecimal edge =
            metadata.getTickSize()
                .multiply(MIN_EDGE_TICKS);

        BigDecimal wirePrice =
            BigDecimal.valueOf(price);

        if (side == OrderManager.Side.BID) {
            return wirePrice.compareTo(
                finalFair.subtract(edge)) <= 0;
        }

        return wirePrice.compareTo(
            finalFair.add(edge)) >= 0;
    }

    private Long chooseBid(
            Bbo bbo,
            BigDecimal finalFair) {

        BigDecimal tick =
            metadata.getTickSize();

        BigDecimal improved =
            bbo.getBidPrice()
                .add(tick);

        if (improved.compareTo(
                    bbo.getAskPrice()) < 0
                && isValidWireQuote(
                    improved)
                && isProfitableBid(
                    improved,
                    finalFair)) {

            return toWirePrice(improved);
        }

        if (isValidWireQuote(
                bbo.getBidPrice())
                && isProfitableBid(
                    bbo.getBidPrice(),
                    finalFair)) {

            return toWirePrice(
                bbo.getBidPrice());
        }

        return null;
    }

    private Long chooseAsk(
            Bbo bbo,
            BigDecimal finalFair) {

        BigDecimal tick =
            metadata.getTickSize();

        BigDecimal improved =
            bbo.getAskPrice()
                .subtract(tick);

        if (improved.compareTo(
                    bbo.getBidPrice()) > 0
                && isValidWireQuote(
                    improved)
                && isProfitableAsk(
                    improved,
                    finalFair)) {

            return toWirePrice(improved);
        }

        if (isValidWireQuote(
                bbo.getAskPrice())
                && isProfitableAsk(
                    bbo.getAskPrice(),
                    finalFair)) {

            return toWirePrice(
                bbo.getAskPrice());
        }

        return null;
    }

    private boolean isProfitableBid(
            BigDecimal price,
            BigDecimal finalFair) {

        BigDecimal edge =
            metadata.getTickSize()
                .multiply(MIN_EDGE_TICKS);

        return price.compareTo(
            finalFair.subtract(edge)) <= 0;
    }

    private boolean isProfitableAsk(
            BigDecimal price,
            BigDecimal finalFair) {

        BigDecimal edge =
            metadata.getTickSize()
                .multiply(MIN_EDGE_TICKS);

        return price.compareTo(
            finalFair.add(edge)) >= 0;
    }

    private HedgerState effectiveRiskState(
            DeskRiskMessage risk) {

        HedgerState reported =
            risk.getState();

        if (reported == HedgerState.UNKNOWN) {
            return HedgerState.UNKNOWN;
        }

        int absPosition =
            Math.abs(
                risk.getNetPosition());

        HedgerState positionDerived;

        if (absPosition >= 5) {
            positionDerived =
                HedgerState.EMERGENCY;
        } else if (absPosition >= 3) {
            positionDerived =
                HedgerState.CONTROLLED;
        } else {
            positionDerived =
                HedgerState.SAFE;
        }

        /*
         * Fail closed on disagreement: never downgrade a more severe state
         * reported by desk.risk, and never ignore a more severe position band.
         */
        return severity(reported)
                    >= severity(positionDerived)
            ? reported
            : positionDerived;
    }

    private int severity(
            HedgerState state) {

        return switch (state) {
            case SAFE -> 0;
            case CONTROLLED -> 1;
            case EMERGENCY -> 2;
            case UNKNOWN -> 3;
        };
    }

    private boolean isUsableBbo(
            Bbo bbo) {

        if (bbo == null
                || !bbo.isValid(metadata)) {

            return false;
        }

        return isTickAligned(
                bbo.getBidPrice())
            && isTickAligned(
                bbo.getAskPrice());
    }

    private boolean isValidWireQuote(
            BigDecimal price) {

        if (price == null
                || price.compareTo(
                    BigDecimal.ZERO) <= 0) {

            return false;
        }

        return metadata
                .isPriceWithinBounds(price)
            && isTickAligned(price)
            && toWirePrice(price) != null;
    }

    private boolean isTickAligned(
            BigDecimal price) {

        return price
            .remainder(
                metadata.getTickSize())
            .compareTo(
                BigDecimal.ZERO) == 0;
    }

    private Long toWirePrice(
            BigDecimal value) {

        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            /*
             * Protocol prices are integer wire values. A metadata/tick setup
             * that produces a non-integer candidate is therefore not sent.
             */
            return null;
        }
    }

    private BigDecimal clamp(
            BigDecimal value,
            BigDecimal minimum,
            BigDecimal maximum) {

        if (value.compareTo(minimum) < 0) {
            return minimum;
        }

        if (value.compareTo(maximum) > 0) {
            return maximum;
        }

        return value;
    }

    record QuotePlan(
        Long bidPrice,
        Long askPrice,
        BigDecimal weightedMid,
        BigDecimal rawFair,
        BigDecimal ewmaFair,
        BigDecimal finalFair,
        HedgerState effectiveRisk) {

        static QuotePlan noQuote(
                HedgerState effectiveRisk,
                BigDecimal weightedMid,
                BigDecimal rawFair,
                BigDecimal finalFair) {

            return new QuotePlan(
                null,
                null,
                weightedMid,
                rawFair,
                rawFair,
                finalFair,
                effectiveRisk);
        }

        boolean hasBid() {
            return bidPrice != null;
        }

        boolean hasAsk() {
            return askPrice != null;
        }

        boolean isTwoSided() {
            return bidPrice != null
                && askPrice != null;
        }
    }
}
