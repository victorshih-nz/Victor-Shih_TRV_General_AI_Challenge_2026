package com.trv.quoter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * V1 Quoter pricing and permission policy.
 *
 * Stateful only for EWMA fair value. It owns no order lifecycle state and
 * sends no network requests.
 */
final class QuotePolicy {

    static final BigDecimal EWMA_ALPHA =
        new BigDecimal("0.2");
    static final BigDecimal BID_WEIGHT =
        new BigDecimal("0.5");
    static final BigDecimal ASK_WEIGHT =
        new BigDecimal("0.5");
    static final BigDecimal MAX_MICRO_ADJ_TICKS =
        new BigDecimal("1.0");
    static final BigDecimal INVENTORY_SKEW_FACTOR_TICKS =
        new BigDecimal("1.5");
    static final BigDecimal MIN_EDGE_TICKS =
        new BigDecimal("0.5");

    private static final int DIVISION_SCALE = 12;
    private static final int DEFAULT_QUOTER_SOFT_POS = 6;
    private static final int DEFAULT_QUOTER_HARD_POS = 12;

    private final Metadata metadata;
    private final int quoterSoftPosition;
    private final int quoterHardPosition;
    private BigDecimal ewmaFair;

    /**
     * Compatibility constructor for focused tests/foundation callers.
     * Production passes the validated environment limits explicitly.
     */
    QuotePolicy(Metadata metadata) {
        this(
            metadata,
            DEFAULT_QUOTER_SOFT_POS,
            DEFAULT_QUOTER_HARD_POS);
    }

    QuotePolicy(
            Metadata metadata,
            int quoterSoftPosition,
            int quoterHardPosition) {

        this.metadata =
            Objects.requireNonNull(
                metadata,
                "metadata is required");

        if (!metadata.isValid()) {
            throw new IllegalArgumentException(
                "metadata must be valid");
        }

        if (quoterSoftPosition <= 0) {
            throw new IllegalArgumentException(
                "quoterSoftPosition must be positive");
        }

        if (quoterHardPosition < quoterSoftPosition) {
            throw new IllegalArgumentException(
                "quoterHardPosition must not be below quoterSoftPosition");
        }

        this.quoterSoftPosition =
            quoterSoftPosition;
        this.quoterHardPosition =
            quoterHardPosition;
    }

    synchronized void reset() {
        ewmaFair = null;
    }

    /**
     * V1 normal quote size is the Exchange minimum legal volume.
     *
     * Dynamic sizing is deliberately out of scope. Using the minimum legal
     * clip minimizes incremental exposure while remaining portable across the
     * private grading market.
     */
    int normalQuoteQuantity() {
        return metadata.getMinVolume();
    }

    /**
     * Compatibility behavior: no explicit local inventory supplied.
     * Production always calls the own-position overload.
     */
    synchronized QuotePlan evaluate(
            Bbo bbo,
            DeskRiskMessage risk) {

        return evaluate(
            bbo,
            risk,
            0,
            true);
    }

    synchronized QuotePlan evaluate(
            Bbo bbo,
            DeskRiskMessage risk,
            boolean advanceEwma) {

        return evaluate(
            bbo,
            risk,
            0,
            advanceEwma);
    }

    synchronized QuotePlan evaluate(
            Bbo bbo,
            DeskRiskMessage risk,
            int ownPosition) {

        return evaluate(
            bbo,
            risk,
            ownPosition,
            true);
    }

    /**
     * Re-evaluates quoting policy with explicit Quoter-local inventory.
     *
     * DeskRiskMessage remains advisory combined-desk coordination. It is never
     * used as Quoter inventory authority.
     *
     * advanceEwma=true:
     *   the BBO is a new trusted market observation.
     *
     * advanceEwma=false:
     *   risk/lifecycle/inventory changed while the BBO observation is unchanged.
     *   Inventory effects are recomputed without counting the same BBO twice.
     */
    synchronized QuotePlan evaluate(
            Bbo bbo,
            DeskRiskMessage risk,
            int ownPosition,
            boolean advanceEwma) {

        if (!isUsableBbo(bbo)) {
            return QuotePlan.noQuote(
                HedgerState.UNKNOWN);
        }

        if (risk == null
                || !metadata.getFeed().equals(
                    risk.getFeed())) {

            return QuotePlan.noQuote(
                HedgerState.UNKNOWN);
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
        } else if (advanceEwma) {
            ewmaFair =
                rawFair.multiply(EWMA_ALPHA)
                    .add(
                        ewmaFair.multiply(
                            BigDecimal.ONE
                                .subtract(EWMA_ALPHA)));
        }

        /*
         * Quoter inventory skew is based only on Quoter's own authoritative
         * signed inventory, never Hedger combined desk net.
         */
        BigDecimal inventoryRatio =
            BigDecimal.valueOf(
                    ownPosition)
                .divide(
                    BigDecimal.valueOf(
                        quoterHardPosition),
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

        /*
         * Quoter-local inventory permission:
         * once own inventory reaches the local soft boundary, suppress the side
         * that can increase that inventory. Desk coordination is evaluated
         * independently below; final Add permission is their intersection.
         */
        boolean localBidAllowed =
            ownPosition < quoterSoftPosition;

        boolean localAskAllowed =
            ownPosition > -quoterSoftPosition;

        HedgerState effectiveRisk =
            effectiveRiskState(risk);

        /*
         * Candidate prices describe side-level economics and are deliberately
         * kept separate from new-Add permission. QuoteController needs them to
         * decide whether an already-resting order can be kept.
         */
        Long bidCandidate =
            chooseBid(
                bbo,
                finalFair);

        Long askCandidate =
            chooseAsk(
                bbo,
                finalFair);

        boolean bidAllowed = false;
        boolean askAllowed = false;

        Long permittedBid = null;
        Long permittedAsk = null;

        switch (effectiveRisk) {
            case SAFE:
                /*
                 * SAFE is meaningful only with neutral direction X. Any
                 * contradictory directional signal fails closed.
                 */
                if (risk.getHedgeDirection()
                        != HedgeDirection.X) {
                    break;
                }

                bidAllowed =
                    localBidAllowed;
                askAllowed =
                    localAskAllowed;

                if (localBidAllowed
                        && localAskAllowed) {

                    /*
                     * Normal SAFE inventory: preserve the existing rule that
                     * does not intentionally initiate a new one-sided pair.
                     */
                    if (bidCandidate != null
                            && askCandidate != null) {

                        permittedBid =
                            bidCandidate;
                        permittedAsk =
                            askCandidate;
                    }

                } else if (localBidAllowed) {

                    /*
                     * Local short inventory at/through soft:
                     * buying is risk-reducing and one-sided quoting is allowed.
                     */
                    permittedBid =
                        bidCandidate;

                } else if (localAskAllowed) {

                    /*
                     * Local long inventory at/through soft:
                     * selling is risk-reducing and one-sided quoting is allowed.
                     */
                    permittedAsk =
                        askCandidate;
                }
                break;

            case CONTROLLED:
            case EMERGENCY:
                /*
                 * Hedger is the authoritative desk-risk classifier.
                 * Quoter does not re-derive SAFE/CONTROLLED/EMERGENCY from the
                 * desk net position.
                 *
                 * CONTROLLED/EMERGENCY direction must agree with the signed
                 * desk position. Zero-net non-SAFE messages are semantically
                 * inconsistent and therefore fail closed.
                 *
                 * Final permission is the intersection of desk-reducing
                 * direction and Quoter-local soft-limit permission.
                 */
                if (risk.getNetPosition() > 0
                        && risk.getHedgeDirection()
                            == HedgeDirection.S
                        && localAskAllowed) {

                    askAllowed = true;
                    permittedAsk =
                        askCandidate;

                } else if (risk.getNetPosition() < 0
                        && risk.getHedgeDirection()
                            == HedgeDirection.B
                        && localBidAllowed) {

                    bidAllowed = true;
                    permittedBid =
                        bidCandidate;
                }
                break;

            case UNKNOWN:
                break;
        }

        return new QuotePlan(
            permittedBid,
            permittedAsk,
            bidCandidate,
            askCandidate,
            bidAllowed,
            askAllowed,
            weightedMid,
            rawFair,
            ewmaFair,
            finalFair,
            effectiveRisk);
    }

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
                && isValidWireQuote(improved)
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
                && isValidWireQuote(improved)
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

    /*
     * Hedger is the single source of truth for combined desk-risk state.
     * Quoter must never reinterpret desk net position through sample-specific
     * local thresholds.
     */
    private HedgerState effectiveRiskState(
            DeskRiskMessage risk) {

        HedgerState reported =
            risk.getState();

        return reported == null
            ? HedgerState.UNKNOWN
            : reported;
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
        Long bidCandidatePrice,
        Long askCandidatePrice,
        boolean bidAllowed,
        boolean askAllowed,
        BigDecimal weightedMid,
        BigDecimal rawFair,
        BigDecimal ewmaFair,
        BigDecimal finalFair,
        HedgerState effectiveRisk) {

        static QuotePlan noQuote(
                HedgerState effectiveRisk) {

            return new QuotePlan(
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                null,
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

        boolean isAllowed(
                OrderManager.Side side) {

            return side == OrderManager.Side.BID
                ? bidAllowed
                : askAllowed;
        }

        Long candidatePrice(
                OrderManager.Side side) {

            return side == OrderManager.Side.BID
                ? bidCandidatePrice
                : askCandidatePrice;
        }

        Long addPrice(
                OrderManager.Side side) {

            return side == OrderManager.Side.BID
                ? bidPrice
                : askPrice;
        }
    }
}
