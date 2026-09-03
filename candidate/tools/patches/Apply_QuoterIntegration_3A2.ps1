$ErrorActionPreference = "Stop"

Set-Location D:\TRV_General_AI_Challenge_2026

$path = "candidate/strategy/src/main/java/com/trv/quoter/QuoterIntegration.java"

if (-not (Test-Path $path)) {
    throw "Missing $path"
}

$text = [System.IO.File]::ReadAllText((Resolve-Path $path))

function Replace-Once {
    param(
        [string]$Text,
        [string]$Old,
        [string]$New,
        [string]$Label
    )

    $count = [regex]::Matches(
        $Text,
        [regex]::Escape($Old)
    ).Count

    if ($count -ne 1) {
        throw "Replacement '$Label' expected exactly 1 match; found $count"
    }

    return $Text.Replace($Old, $New)
}

Write-Host "=== VERIFY 3A.1 BASE ==="

foreach ($marker in @(
    "private final LinkedHashMap<ExecutionKey, Boolean>",
    "private int ownPosition;",
    "synchronized int ownPosition()",
    "applyOwnInventoryExecution(",
    "String trackedSender,"
)) {
    if (-not $text.Contains($marker)) {
        throw "Current QuoterIntegration.java is not the expected 3A.1 file. Missing marker: $marker"
    }
}

Write-Host "PASS: expected 3A.1 markers found"

$backup = "$path.pre-3A2"
Copy-Item $path $backup -Force
Write-Host "Backup: $backup"

# 1) Imports.
$old = @'
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
'@

$new = @'
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
'@

$text = Replace-Once $text $old $new "Map import"

$old = @'
import java.util.function.BooleanSupplier;
'@

$new = @'
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
'@

$text = Replace-Once $text $old $new "IntSupplier import"

# 2) Local risk field.
$old = @'
    private final OrderManager orderManager;
    private final OwnLifecycleRouter ownLifecycleRouter;
'@

$new = @'
    private final OrderManager orderManager;
    private final OwnLifecycleRouter ownLifecycleRouter;
    private final LocalRiskLimits localRiskLimits;
'@

$text = Replace-Once $text $old $new "localRiskLimits field"

# 3) Read/validate company v1 local limits at startup.
$old = @'
        this.natsUrl = natsUrlEnv;
        this.feed = feedEnv;
        this.sender = senderEnv;
        this.orderManager = new OrderManager();
'@

$new = @'
        this.natsUrl = natsUrlEnv;
        this.feed = feedEnv;
        this.sender = senderEnv;
        this.localRiskLimits =
            LocalRiskLimits.fromEnvironment();
        this.orderManager = new OrderManager();
'@

$text = Replace-Once $text $old $new "startup local risk config"

# 4) Production AutomaticQuoteEngine receives own inventory + local limits.
$old = @'
            new AutomaticQuoteEngine(
                runtimeState,
                metadata,
                orderManager,
                () -> reconciliationCoordinator != null
'@

$new = @'
            new AutomaticQuoteEngine(
                runtimeState,
                metadata,
                orderManager,
                ownLifecycleRouter::ownPosition,
                localRiskLimits.softPosition(),
                localRiskLimits.hardPosition(),
                () -> reconciliationCoordinator != null
'@

$text = Replace-Once $text $old $new "production engine own inventory"

# 5) Local soft/hard gate is checked after reconciliation preparation but before
#    local PENDING_ADD registration / transport dispatch. The same router monitor
#    also serializes authoritative T/E accounting, closing check-then-dispatch race.
$old = @'
            coordinator.prepareForNewExposure();

            requireOrderRequestClient()
                .requestAdd(
                    side,
                    orderId,
                    quantity,
                    price);
'@

$new = @'
            coordinator.prepareForNewExposure();

            synchronized (ownLifecycleRouter) {
                if (!ownLifecycleRouter.allowsLocalAdd(
                        side,
                        quantity,
                        localRiskLimits.softPosition(),
                        localRiskLimits.hardPosition())) {

                    return;
                }

                requireOrderRequestClient()
                    .requestAdd(
                        side,
                        orderId,
                        quantity,
                        price);
            }
'@

$text = Replace-Once $text $old $new "atomic local risk check and Add dispatch"

# 6) Add compact nested company-v1 risk config; no new production file.
$marker = @'
    /**
     * Serialized production quote orchestrator.
'@

$insert = @'
    static record LocalRiskLimits(
        int softPosition,
        int hardPosition,
        int deskHardPosition) {

        private static final int DEFAULT_QUOTER_SOFT_POS = 6;
        private static final int DEFAULT_QUOTER_HARD_POS = 12;
        private static final int DEFAULT_DESK_HARD_POS = 15;

        LocalRiskLimits {
            if (softPosition <= 0) {
                throw new IllegalArgumentException(
                    "QUOTER_SOFT_POS must be positive");
            }

            if (hardPosition <= softPosition) {
                throw new IllegalArgumentException(
                    "QUOTER_HARD_POS must exceed QUOTER_SOFT_POS");
            }

            if (deskHardPosition <= 0) {
                throw new IllegalArgumentException(
                    "DESK_HARD_POS must be positive");
            }

            /*
             * Company v1 explicitly permits equality:
             * QUOTER_HARD_POS <= DESK_HARD_POS.
             */
            if (hardPosition > deskHardPosition) {
                throw new IllegalArgumentException(
                    "QUOTER_HARD_POS must not exceed DESK_HARD_POS");
            }
        }

        static LocalRiskLimits fromEnvironment() {
            return fromMap(
                System.getenv());
        }

        static LocalRiskLimits fromMap(
                Map<String, String> env) {

            Objects.requireNonNull(
                env,
                "env is required");

            int soft =
                readPositiveInt(
                    env,
                    "QUOTER_SOFT_POS",
                    DEFAULT_QUOTER_SOFT_POS);

            int hard =
                readPositiveInt(
                    env,
                    "QUOTER_HARD_POS",
                    DEFAULT_QUOTER_HARD_POS);

            int deskHard =
                readPositiveInt(
                    env,
                    "DESK_HARD_POS",
                    DEFAULT_DESK_HARD_POS);

            return new LocalRiskLimits(
                soft,
                hard,
                deskHard);
        }

        private static int readPositiveInt(
                Map<String, String> env,
                String name,
                int defaultValue) {

            String raw =
                env.get(name);

            if (raw == null
                    || raw.isBlank()) {

                return defaultValue;
            }

            final int parsed;

            try {
                parsed =
                    Integer.parseInt(
                        raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    name + " must be an integer",
                    e);
            }

            if (parsed <= 0) {
                throw new IllegalArgumentException(
                    name + " must be positive");
            }

            return parsed;
        }
    }

    /**
     * Serialized production quote orchestrator.
'@

$text = Replace-Once $text $marker $insert "LocalRiskLimits record"

# 7) AutomaticQuoteEngine fields.
$old = @'
        private final QuoteController quoteController;
        private final OrderManager orderManager;
        private final OrderIdGenerator orderIdGenerator;
        private final BooleanSupplier reconciliationHealthy;
'@

$new = @'
        private final QuoteController quoteController;
        private final OrderManager orderManager;
        private final OrderIdGenerator orderIdGenerator;
        private final IntSupplier ownPositionSupplier;
        private final BooleanSupplier reconciliationHealthy;
'@

$text = Replace-Once $text $old $new "engine local inventory fields"

# 8) Preserve existing AutomaticQuoteEngine constructor for current tests and
#    add the production/local-risk constructor.
$old = @'
        AutomaticQuoteEngine(
                RuntimeState runtimeState,
                Metadata metadata,
                OrderManager orderManager,
                BooleanSupplier reconciliationHealthy,
                Object addRegistrationLock,
                AddAction addAction,
                CancelAction cancelAction,
                Runnable recoverySignal) {

            this.runtimeState =
                Objects.requireNonNull(
                    runtimeState,
                    "runtimeState is required");

            this.orderManager =
                Objects.requireNonNull(
                    orderManager,
                    "orderManager is required");

            this.quotePolicy =
                new QuotePolicy(
                    Objects.requireNonNull(
                        metadata,
                        "metadata is required"));

            this.quoteController =
                new QuoteController(
                    quotePolicy,
                    orderManager);

            this.orderIdGenerator =
                new OrderIdGenerator();

            this.reconciliationHealthy =
                Objects.requireNonNull(
                    reconciliationHealthy,
                    "reconciliationHealthy is required");

            this.addRegistrationLock =
                Objects.requireNonNull(
                    addRegistrationLock,
                    "addRegistrationLock is required");

            this.addAction =
                Objects.requireNonNull(
                    addAction,
                    "addAction is required");

            this.cancelAction =
                Objects.requireNonNull(
                    cancelAction,
                    "cancelAction is required");

            this.recoverySignal =
                Objects.requireNonNull(
                    recoverySignal,
                    "recoverySignal is required");
        }
'@

$new = @'
        AutomaticQuoteEngine(
                RuntimeState runtimeState,
                Metadata metadata,
                OrderManager orderManager,
                BooleanSupplier reconciliationHealthy,
                Object addRegistrationLock,
                AddAction addAction,
                CancelAction cancelAction,
                Runnable recoverySignal) {

            this(
                runtimeState,
                metadata,
                orderManager,
                () -> 0,
                6,
                12,
                reconciliationHealthy,
                addRegistrationLock,
                addAction,
                cancelAction,
                recoverySignal);
        }

        AutomaticQuoteEngine(
                RuntimeState runtimeState,
                Metadata metadata,
                OrderManager orderManager,
                IntSupplier ownPositionSupplier,
                int quoterSoftPosition,
                int quoterHardPosition,
                BooleanSupplier reconciliationHealthy,
                Object addRegistrationLock,
                AddAction addAction,
                CancelAction cancelAction,
                Runnable recoverySignal) {

            this.runtimeState =
                Objects.requireNonNull(
                    runtimeState,
                    "runtimeState is required");

            this.orderManager =
                Objects.requireNonNull(
                    orderManager,
                    "orderManager is required");

            this.ownPositionSupplier =
                Objects.requireNonNull(
                    ownPositionSupplier,
                    "ownPositionSupplier is required");

            if (quoterSoftPosition <= 0
                    || quoterHardPosition <= quoterSoftPosition) {

                throw new IllegalArgumentException(
                    "invalid Quoter local position limits");
            }

            this.quotePolicy =
                new QuotePolicy(
                    Objects.requireNonNull(
                        metadata,
                        "metadata is required"),
                    quoterSoftPosition,
                    quoterHardPosition);

            this.quoteController =
                new QuoteController(
                    quotePolicy,
                    orderManager);

            this.orderIdGenerator =
                new OrderIdGenerator();

            this.reconciliationHealthy =
                Objects.requireNonNull(
                    reconciliationHealthy,
                    "reconciliationHealthy is required");

            this.addRegistrationLock =
                Objects.requireNonNull(
                    addRegistrationLock,
                    "addRegistrationLock is required");

            this.addAction =
                Objects.requireNonNull(
                    addAction,
                    "addAction is required");

            this.cancelAction =
                Objects.requireNonNull(
                    cancelAction,
                    "cancelAction is required");

            this.recoverySignal =
                Objects.requireNonNull(
                    recoverySignal,
                    "recoverySignal is required");
        }
'@

$text = Replace-Once $text $old $new "AutomaticQuoteEngine constructors"

# 9) Policy uses Quoter own inventory, not desk net, on every evaluation.
$old = @'
                plan =
                    quotePolicy.evaluate(
                        snapshot.bbo(),
                        snapshot.risk(),
                        newBboObservation);
'@

$new = @'
                int ownPosition =
                    ownPositionSupplier
                        .getAsInt();

                plan =
                    quotePolicy.evaluate(
                        snapshot.bbo(),
                        snapshot.risk(),
                        ownPosition,
                        newBboObservation);
'@

$text = Replace-Once $text $old $new "policy ownPosition input"

# 10) SAFE pair preferred first side is based on Quoter own inventory.
$old = @'
                dispatchSafePair(
                    decision,
                    latest.risk());
'@

$new = @'
                dispatchSafePair(
                    decision);
'@

$text = Replace-Once $text $old $new "safe pair call"

$old = @'
        private void dispatchSafePair(
                QuoteController.Decision decision,
                DeskRiskMessage risk) {

            OrderManager.Side first =
                preferredFirstSide(risk);
'@

$new = @'
        private void dispatchSafePair(
                QuoteController.Decision decision) {

            OrderManager.Side first =
                preferredFirstSide();
'@

$text = Replace-Once $text $old $new "safe pair signature"

$old = @'
        private OrderManager.Side preferredFirstSide(
                DeskRiskMessage risk) {

            if (risk != null
                    && risk.getNetPosition() > 0) {

                return OrderManager.Side.ASK;
            }

            if (risk != null
                    && risk.getNetPosition() < 0) {

                return OrderManager.Side.BID;
            }

            return OrderManager.Side.BID;
        }
'@

$new = @'
        private OrderManager.Side preferredFirstSide() {
            int ownPosition =
                ownPositionSupplier
                    .getAsInt();

            if (ownPosition > 0) {
                return OrderManager.Side.ASK;
            }

            if (ownPosition < 0) {
                return OrderManager.Side.BID;
            }

            return OrderManager.Side.BID;
        }
'@

$text = Replace-Once $text $old $new "own inventory pair ordering"

# 11) Add local soft/hard envelope method to the existing accounting router.
$old = @'
        synchronized int ownPosition() {
            return ownPosition;
        }

        private void parseAndRoute(String raw) {
'@

$new = @'
        synchronized int ownPosition() {
            return ownPosition;
        }

        synchronized boolean allowsLocalAdd(
                OrderManager.Side side,
                int quantity,
                int softPosition,
                int hardPosition) {

            Objects.requireNonNull(
                side,
                "side is required");

            if (quantity <= 0) {
                throw new IllegalArgumentException(
                    "quantity must be positive");
            }

            if (softPosition <= 0
                    || hardPosition <= softPosition) {

                throw new IllegalArgumentException(
                    "invalid local position limits");
            }

            /*
             * Minimum local soft-limit rule:
             * do not add the side that can increase already-material local
             * inventory at/through the configured soft boundary.
             */
            if (side == OrderManager.Side.BID
                    && ownPosition >= softPosition) {

                return false;
            }

            if (side == OrderManager.Side.ASK
                    && ownPosition <= -softPosition) {

                return false;
            }

            synchronized (orderManager) {
                long bidExposure =
                    orderManager.remainingQty(
                        OrderManager.Side.BID);

                long askExposure =
                    orderManager.remainingQty(
                        OrderManager.Side.ASK);

                if (side == OrderManager.Side.BID) {
                    bidExposure +=
                        quantity;
                } else {
                    askExposure +=
                        quantity;
                }

                long upperBound =
                    (long) ownPosition
                        + bidExposure;

                long lowerBound =
                    (long) ownPosition
                        - askExposure;

                return upperBound <= hardPosition
                    && lowerBound >= -((long) hardPosition);
            }
        }

        private void parseAndRoute(String raw) {
'@

$text = Replace-Once $text $old $new "local exposure envelope"

# 12) Write UTF-8 without BOM.
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText(
    (Resolve-Path $path),
    $text,
    $utf8NoBom
)

Write-Host ""
Write-Host "=============================================="
Write-Host "QUOTER_INTEGRATION_3A2_PATCHED"
Write-Host "3A.1 accounting preserved."
Write-Host "3A.2 local soft/hard risk added."
Write-Host "No commit or push performed."
Write-Host "=============================================="
