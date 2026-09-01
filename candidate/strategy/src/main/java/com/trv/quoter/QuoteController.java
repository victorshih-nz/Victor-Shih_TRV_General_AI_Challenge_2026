package com.trv.quoter;

import java.util.Objects;

/**
 * Pure QuoteController decision layer.
 *
 * It reads QuotePolicy output plus the single authoritative OrderManager and
 * returns KEEP/CANCEL/ADD/WAIT decisions. It sends no network requests.
 */
final class QuoteController {

    enum Action {
        KEEP,
        CANCEL,
        ADD,
        WAIT
    }

    record SideDecision(
        Action action,
        Long price) {

        static SideDecision keep(
                long price) {

            return new SideDecision(
                Action.KEEP,
                price);
        }

        static SideDecision cancel(
                Long price) {

            return new SideDecision(
                Action.CANCEL,
                price);
        }

        static SideDecision add(
                long price) {

            return new SideDecision(
                Action.ADD,
                price);
        }

        static SideDecision waitDecision() {
            return new SideDecision(
                Action.WAIT,
                null);
        }
    }

    record Decision(
        SideDecision bid,
        SideDecision ask) {
    }

    private final QuotePolicy policy;
    private final OrderManager orders;

    QuoteController(
            QuotePolicy policy,
            OrderManager orders) {

        this.policy =
            Objects.requireNonNull(
                policy,
                "policy is required");

        this.orders =
            Objects.requireNonNull(
                orders,
                "orders is required");
    }

    synchronized Decision decide(
            QuotePolicy.QuotePlan plan) {

        Objects.requireNonNull(
            plan,
            "quote plan is required");

        OrderManager.State bidState =
            orders.state(
                OrderManager.Side.BID);

        OrderManager.State askState =
            orders.state(
                OrderManager.Side.ASK);

        SideDecision bid =
            decideSide(
                OrderManager.Side.BID,
                bidState,
                plan);

        SideDecision ask =
            decideSide(
                OrderManager.Side.ASK,
                askState,
                plan);

        /*
         * Do not create new exposure in the same evaluation that is already
         * trying to reduce/change exposure, or while lifecycle evidence for an
         * occupied slot is unresolved/pending.
         *
         * This also enforces strict cancel-then-authoritative-EMPTY-before-
         * replacement semantics without creating a separate replacement state.
         */
        boolean lifecycleBusy =
            isBusy(bidState)
                || isBusy(askState);

        boolean cancelPlanned =
            bid.action() == Action.CANCEL
                || ask.action() == Action.CANCEL;

        if (lifecycleBusy || cancelPlanned) {
            if (bid.action() == Action.ADD) {
                bid =
                    SideDecision.waitDecision();
            }

            if (ask.action() == Action.ADD) {
                ask =
                    SideDecision.waitDecision();
            }
        }

        return new Decision(
            bid,
            ask);
    }

    private SideDecision decideSide(
            OrderManager.Side side,
            OrderManager.State state,
            QuotePolicy.QuotePlan plan) {

        return switch (state) {
            case EMPTY ->
                decideEmpty(
                    side,
                    plan);

            case ACTIVE ->
                decideActive(
                    side,
                    plan);

            case PENDING_ADD,
                 PENDING_CANCEL,
                 UNKNOWN ->
                SideDecision.waitDecision();
        };
    }

    private SideDecision decideEmpty(
            OrderManager.Side side,
            QuotePolicy.QuotePlan plan) {

        Long addPrice =
            plan.addPrice(side);

        if (addPrice == null) {
            return SideDecision.waitDecision();
        }

        return SideDecision.add(
            addPrice);
    }

    private SideDecision decideActive(
            OrderManager.Side side,
            QuotePolicy.QuotePlan plan) {

        Long currentPrice =
            orders.price(side);

        /*
         * A production ACTIVE slot must always have the price registered at
         * beginAdd. Null is possible only through temporary legacy test/probe
         * compatibility and fails closed here.
         */
        if (currentPrice == null) {
            return SideDecision.cancel(null);
        }

        if (!plan.isAllowed(side)) {
            return SideDecision.cancel(
                currentPrice);
        }

        if (!policy.isStillProfitable(
                side,
                currentPrice,
                plan.finalFair())) {

            return SideDecision.cancel(
                currentPrice);
        }

        Long desiredPrice =
            plan.candidatePrice(side);

        if (desiredPrice == null) {
            return SideDecision.cancel(
                currentPrice);
        }

        if (policy.withinKeepTolerance(
                currentPrice,
                desiredPrice)) {

            return SideDecision.keep(
                currentPrice);
        }

        return SideDecision.cancel(
            currentPrice);
    }

    private boolean isBusy(
            OrderManager.State state) {

        return state
                == OrderManager.State.PENDING_ADD
            || state
                == OrderManager.State.PENDING_CANCEL
            || state
                == OrderManager.State.UNKNOWN;
    }
}
