$ErrorActionPreference = "Stop"

Set-Location D:\TRV_General_AI_Challenge_2026

$testRoot = "candidate\strategy\src\test\java\com\trv\quoter"
$orderTest = "$testRoot\QuoterOrderRequestClientTest.java"
$lifecycleTest = "$testRoot\QuoterLifecycleIntegrationTest.java"

foreach ($file in @($orderTest, $lifecycleTest)) {
    if (-not (Test-Path $file)) {
        throw "Missing expected test file: $file"
    }
}

$backupRoot = "D:\TRV_General_AI_Challenge_2026_evidence\3B3-test-backups-v2"
New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Read-Text([string]$Path) {
    return [System.IO.File]::ReadAllText((Resolve-Path $Path))
}

function Write-Text([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText((Resolve-Path $Path), $Text, $utf8NoBom)
}

function Count-PresentMethods {
    param(
        [string]$Text,
        [string[]]$Methods
    )

    $present = @()
    foreach ($method in $Methods) {
        if ($Text.Contains("void $method()")) {
            $present += $method
        }
    }
    return $present
}

$orderText = Read-Text $orderTest

$orderMethods = @(
    "tpsRollingWindowUsesMonotonicClockAndExpiresAtOneSecond",
    "safePairRequiresTwoAddsPlusOneImmediateCancelCapacity",
    "cancelCanUseEmergencyCapacityWithoutStealingReservedSecondAdd",
    "unknownCurrentOrderContributesCancelObligationToAddAdmission",
    "preTransportAddRejectionDoesNotConsumeTps",
    "transportAttemptFailureStillConsumesTps",
    "pendingCancelDoesNotDoubleReserveFutureCancelCapacity",
    "eachUnknownCancelRetryConsumesFreshTpsPermit"
)

$orderPresent = @(Count-PresentMethods -Text $orderText -Methods $orderMethods)

if ($orderPresent.Count -eq $orderMethods.Count) {
    Write-Host "ORDER_TPS_TESTS_ALREADY_COMPLETE"
}
elseif ($orderPresent.Count -ne 0) {
    $missing = @($orderMethods | Where-Object { $_ -notin $orderPresent })
    throw (
        "Partial 3B.3 TPS test patch detected in QuoterOrderRequestClientTest.java. " +
        "Present: " + ($orderPresent -join ", ") +
        " | Missing: " + ($missing -join ", ") +
        ". Refusing to create duplicate/partial methods."
    )
}
else {
    Copy-Item `
        $orderTest `
        "$backupRoot\QuoterOrderRequestClientTest.java.before-v2-repair" `
        -Force

    if (-not $orderText.Contains("import java.util.concurrent.atomic.AtomicInteger;")) {
        throw "Expected AtomicInteger import marker not found in QuoterOrderRequestClientTest.java"
    }

    if (-not $orderText.Contains("import java.util.concurrent.atomic.AtomicLong;")) {
        $orderText = $orderText.Replace(
            "import java.util.concurrent.atomic.AtomicInteger;",
            "import java.util.concurrent.atomic.AtomicInteger;`r`nimport java.util.concurrent.atomic.AtomicLong;")
    }

    if (-not $orderText.Contains("import static org.junit.jupiter.api.Assertions.assertTrue;")) {
        throw "Expected assertion import marker not found in QuoterOrderRequestClientTest.java"
    }

    if (-not $orderText.Contains("import static org.junit.jupiter.api.Assertions.assertNotNull;")) {
        $orderText = $orderText.Replace(
            "import static org.junit.jupiter.api.Assertions.assertTrue;",
            "import static org.junit.jupiter.api.Assertions.assertTrue;`r`nimport static org.junit.jupiter.api.Assertions.assertNotNull;`r`nimport static org.junit.jupiter.api.Assertions.assertNull;")
    }

    $orderMarker = "    private static void makeActiveAsk("

    if (-not $orderText.Contains($orderMarker)) {
        throw "Could not find QuoterOrderRequestClientTest insertion marker"
    }

    $orderTests = @'
    @Test
    void tpsRollingWindowUsesMonotonicClockAndExpiresAtOneSecond() {
        OrderManager manager =
            new OrderManager();

        FakeTransport transport =
            new FakeTransport(manager);

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        AtomicLong now =
            new AtomicLong(0L);

        OrderRequestClient client =
            new OrderRequestClient(
                SENDER,
                FEED,
                metadataWithMaxTps(3),
                manager,
                () -> true,
                () -> true,
                transport,
                scheduler,
                Duration.ofHours(1),
                Duration.ofHours(1),
                () -> {
                },
                now::get);

        try (client) {
            OrderRequestClient.AddReservation pair =
                client.tryReserveAddCapacity(2);

            assertNotNull(pair);

            try (pair) {
                client.requestAdd(
                    OrderManager.Side.BID,
                    "BIDTPS01",
                    1,
                    500,
                    pair);

                client.requestAdd(
                    OrderManager.Side.ASK,
                    "ASKTPS01",
                    1,
                    500,
                    pair);
            }

            assertEquals(
                2,
                client.currentTpsUsageForTest());

            manager.onExecution(
                OrderManager.Side.BID,
                "BIDTPS01",
                1);

            manager.onExecution(
                OrderManager.Side.ASK,
                "ASKTPS01",
                1);

            assertNull(
                client.tryReserveAddCapacity(1));

            now.set(999_999_999L);

            assertNull(
                client.tryReserveAddCapacity(1));

            now.set(1_000_000_000L);

            OrderRequestClient.AddReservation next =
                client.tryReserveAddCapacity(1);

            assertNotNull(next);
            next.close();
        }
    }

    @Test
    void safePairRequiresTwoAddsPlusOneImmediateCancelCapacity() {
        OrderManager manager =
            new OrderManager();

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        try (OrderRequestClient client =
                new OrderRequestClient(
                    SENDER,
                    FEED,
                    metadataWithMaxTps(2),
                    manager,
                    () -> true,
                    () -> true,
                    (subject, payload, timeout) ->
                        new CompletableFuture<>(),
                    scheduler,
                    Duration.ofHours(1),
                    Duration.ofHours(1))) {

            assertNull(
                client.tryReserveAddCapacity(2));
        }

        ScheduledExecutorService scheduler2 =
            Executors.newSingleThreadScheduledExecutor();

        try (OrderRequestClient client =
                new OrderRequestClient(
                    SENDER,
                    FEED,
                    metadataWithMaxTps(3),
                    new OrderManager(),
                    () -> true,
                    () -> true,
                    (subject, payload, timeout) ->
                        new CompletableFuture<>(),
                    scheduler2,
                    Duration.ofHours(1),
                    Duration.ofHours(1))) {

            OrderRequestClient.AddReservation pair =
                client.tryReserveAddCapacity(2);

            assertNotNull(pair);
            pair.close();
        }
    }

    @Test
    void cancelCanUseEmergencyCapacityWithoutStealingReservedSecondAdd() {
        OrderManager manager =
            new OrderManager();

        FakeTransport transport =
            new FakeTransport(manager);

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        OrderRequestClient client =
            new OrderRequestClient(
                SENDER,
                FEED,
                metadataWithMaxTps(3),
                manager,
                () -> true,
                () -> true,
                transport,
                scheduler,
                Duration.ofHours(1),
                Duration.ofHours(1));

        try (client) {
            OrderRequestClient.AddReservation pair =
                client.tryReserveAddCapacity(2);

            assertNotNull(pair);

            try (pair) {
                client.requestAdd(
                    OrderManager.Side.BID,
                    "BIDTPS02",
                    1,
                    500,
                    pair);

                manager.onResting(
                    OrderManager.Side.BID,
                    "BIDTPS02");

                assertEquals(
                    1,
                    client.outstandingAddReservationsForTest());

                client.requestCancel(
                    OrderManager.Side.BID);

                assertEquals(
                    2,
                    client.currentTpsUsageForTest());

                assertEquals(
                    1,
                    client.outstandingAddReservationsForTest());
            }

            assertEquals(
                0,
                client.outstandingAddReservationsForTest());
        }
    }

    @Test
    void unknownCurrentOrderContributesCancelObligationToAddAdmission() {
        OrderManager manager =
            new OrderManager();

        manager.beginAdd(
            OrderManager.Side.BID,
            "BIDTPS03",
            1,
            500L);

        manager.markRequestUncertain(
            OrderManager.Side.BID,
            "BIDTPS03");

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        try (OrderRequestClient client =
                new OrderRequestClient(
                    SENDER,
                    FEED,
                    metadataWithMaxTps(3),
                    manager,
                    () -> true,
                    () -> true,
                    (subject, payload, timeout) ->
                        new CompletableFuture<>(),
                    scheduler,
                    Duration.ofHours(1),
                    Duration.ofHours(1))) {

            assertNull(
                client.tryReserveAddCapacity(2));
        }
    }

    @Test
    void preTransportAddRejectionDoesNotConsumeTps() {
        OrderManager manager =
            new OrderManager();

        AtomicBoolean addReady =
            new AtomicBoolean(false);

        AtomicInteger transportCalls =
            new AtomicInteger();

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        try (OrderRequestClient client =
                new OrderRequestClient(
                    SENDER,
                    FEED,
                    metadataWithMaxTps(2),
                    manager,
                    addReady::get,
                    () -> true,
                    (subject, payload, timeout) -> {
                        transportCalls.incrementAndGet();
                        return new CompletableFuture<>();
                    },
                    scheduler,
                    Duration.ofHours(1),
                    Duration.ofHours(1))) {

            assertThrows(
                IllegalStateException.class,
                () -> client.requestAdd(
                    OrderManager.Side.BID,
                    "BIDTPS04",
                    1,
                    500));

            assertEquals(
                0,
                client.currentTpsUsageForTest());

            assertEquals(
                0,
                transportCalls.get());

            assertEquals(
                OrderManager.State.EMPTY,
                manager.state(
                    OrderManager.Side.BID));
        }
    }

    @Test
    void transportAttemptFailureStillConsumesTps() {
        OrderManager manager =
            new OrderManager();

        AtomicInteger transportCalls =
            new AtomicInteger();

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        try (OrderRequestClient client =
                new OrderRequestClient(
                    SENDER,
                    FEED,
                    metadataWithMaxTps(2),
                    manager,
                    () -> true,
                    () -> true,
                    (subject, payload, timeout) -> {
                        transportCalls.incrementAndGet();
                        throw new RuntimeException("transport failed");
                    },
                    scheduler,
                    Duration.ofHours(1),
                    Duration.ofHours(1))) {

            client.requestAdd(
                OrderManager.Side.BID,
                "BIDTPS05",
                1,
                500);

            assertEquals(
                1,
                transportCalls.get());

            assertEquals(
                1,
                client.currentTpsUsageForTest());

            assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(
                    OrderManager.Side.BID));
        }
    }

    @Test
    void pendingCancelDoesNotDoubleReserveFutureCancelCapacity() {
        OrderManager manager =
            new OrderManager();

        manager.beginAdd(
            OrderManager.Side.BID,
            "BIDTPS06",
            1,
            500L);

        manager.onResting(
            OrderManager.Side.BID,
            "BIDTPS06");

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        try (OrderRequestClient client =
                new OrderRequestClient(
                    SENDER,
                    FEED,
                    metadataWithMaxTps(3),
                    manager,
                    () -> true,
                    () -> true,
                    (subject, payload, timeout) ->
                        new CompletableFuture<>(),
                    scheduler,
                    Duration.ofHours(1),
                    Duration.ofHours(1))) {

            client.requestCancel(
                OrderManager.Side.BID);

            assertEquals(
                OrderManager.State.PENDING_CANCEL,
                manager.state(
                    OrderManager.Side.BID));

            OrderRequestClient.AddReservation next =
                client.tryReserveAddCapacity(1);

            assertNotNull(next);
            next.close();
        }
    }

    @Test
    void eachUnknownCancelRetryConsumesFreshTpsPermit() {
        OrderManager manager =
            new OrderManager();

        manager.beginAdd(
            OrderManager.Side.BID,
            "BIDTPS07",
            1,
            500L);

        manager.onResting(
            OrderManager.Side.BID,
            "BIDTPS07");

        AtomicInteger transportCalls =
            new AtomicInteger();

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        try (OrderRequestClient client =
                new OrderRequestClient(
                    SENDER,
                    FEED,
                    metadataWithMaxTps(2),
                    manager,
                    () -> true,
                    () -> true,
                    (subject, payload, timeout) -> {
                        transportCalls.incrementAndGet();
                        throw new RuntimeException("cancel transport failed");
                    },
                    scheduler,
                    Duration.ofHours(1),
                    Duration.ofHours(1))) {

            client.requestCancel(
                OrderManager.Side.BID);

            assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(
                    OrderManager.Side.BID));

            client.requestCancel(
                OrderManager.Side.BID);

            assertEquals(
                2,
                client.currentTpsUsageForTest());

            assertEquals(
                2,
                transportCalls.get());

            client.requestCancel(
                OrderManager.Side.BID);

            assertEquals(
                2,
                transportCalls.get());
        }
    }

    private static Metadata metadataWithMaxTps(
            int maxTps) {

        return Metadata.parse(
            FEED,
            "ticksize=1 ref_price=500 band=100 "
                + "min_volume=1 max_volume=100 "
                + "position_limit=12 max_tps="
                + maxTps);
    }

'@

    $orderText = $orderText.Replace(
        $orderMarker,
        $orderTests + $orderMarker)

    Write-Text $orderTest $orderText
    Write-Host "ORDER_TPS_TESTS_PATCHED"
}

$lifecycleText = Read-Text $lifecycleTest
$lifecycleMethod = "safePairRiskChangeAfterFirstAddPreventsSecondReservedAdd"

if ($lifecycleText.Contains("void $lifecycleMethod()")) {
    Write-Host "LIFECYCLE_PAIR_RACE_TEST_ALREADY_COMPLETE"
}
else {
    Copy-Item `
        $lifecycleTest `
        "$backupRoot\QuoterLifecycleIntegrationTest.java.before-v2-repair" `
        -Force

    $lifecycleMarker = "    private static QuoterIntegration.OwnLifecycleRouter router("

    if (-not $lifecycleText.Contains($lifecycleMarker)) {
        throw "Could not find QuoterLifecycleIntegrationTest insertion marker"
    }

    $lifecycleRaceTest = @'
    @Test
    void safePairRiskChangeAfterFirstAddPreventsSecondReservedAdd() {
        Metadata metadata =
            Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=105 band=100 "
                    + "min_volume=1 max_volume=100 "
                    + "position_limit=12 max_tps=3");

        OrderManager manager =
            new OrderManager();

        java.util.concurrent.atomic.AtomicLong now =
            new java.util.concurrent.atomic.AtomicLong(
                1_000_000_000L);

        RuntimeState runtimeState =
            new RuntimeState(
                "AAH6",
                metadata,
                now::get);

        runtimeState.markConnected();

        runtimeState.acceptBbo(
            Bbo.parse(
                "1 AAH6 100 10 110 10",
                metadata));

        runtimeState.acceptRisk(
            new DeskRiskMessage(
                1L,
                1L,
                "AAH6",
                0,
                4,
                5,
                HedgerState.SAFE,
                HedgeDirection.X));

        java.util.concurrent.atomic.AtomicInteger addCalls =
            new java.util.concurrent.atomic.AtomicInteger();

        java.util.concurrent.atomic.AtomicInteger recoveryCalls =
            new java.util.concurrent.atomic.AtomicInteger();

        java.util.concurrent.ScheduledExecutorService scheduler =
            java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor();

        try (OrderRequestClient client =
                new OrderRequestClient(
                    "QUOTE001",
                    "AAH6",
                    metadata,
                    manager,
                    runtimeState::isReady,
                    () -> true,
                    (subject, payload, timeout) ->
                        new java.util.concurrent.CompletableFuture<>(),
                    scheduler,
                    java.time.Duration.ofHours(1),
                    java.time.Duration.ofHours(1),
                    () -> {
                    },
                    now::get)) {

            QuoterIntegration.AutomaticQuoteEngine engine =
                new QuoterIntegration.AutomaticQuoteEngine(
                    runtimeState,
                    metadata,
                    manager,
                    () -> 0,
                    6,
                    12,
                    () -> true,
                    new Object(),
                    client::tryReserveAddCapacity,
                    (side, orderId, quantity, price, reservation) -> {
                        client.requestAdd(
                            side,
                            orderId,
                            quantity,
                            price,
                            reservation);

                        if (addCalls.incrementAndGet() == 1) {
                            runtimeState.acceptRisk(
                                new DeskRiskMessage(
                                    2L,
                                    2L,
                                    "AAH6",
                                    5,
                                    4,
                                    5,
                                    HedgerState.EMERGENCY,
                                    HedgeDirection.S));
                        }
                    },
                    client::requestCancel,
                    recoveryCalls::incrementAndGet);

            engine.evaluateOnce();

            assertEquals(
                1,
                addCalls.get());

            assertEquals(
                1,
                client.currentTpsUsageForTest());

            assertEquals(
                0,
                client.outstandingAddReservationsForTest());

            assertEquals(
                OrderManager.State.UNKNOWN,
                manager.state(
                    OrderManager.Side.BID));

            assertTrue(
                recoveryCalls.get() >= 1);
        }
    }

'@

    $lifecycleText = $lifecycleText.Replace(
        $lifecycleMarker,
        $lifecycleRaceTest + $lifecycleMarker)

    Write-Text $lifecycleTest $lifecycleText
    Write-Host "LIFECYCLE_PAIR_RACE_TEST_PATCHED"
}

$orderText = Read-Text $orderTest
$lifecycleText = Read-Text $lifecycleTest
$orderPresent = @(Count-PresentMethods -Text $orderText -Methods $orderMethods)

if ($orderPresent.Count -ne $orderMethods.Count) {
    throw "Final verification failed: not all 8 OrderRequestClient TPS tests are present."
}

if (-not $lifecycleText.Contains("void $lifecycleMethod()")) {
    throw "Final verification failed: lifecycle SAFE-pair race test is missing."
}

Write-Host ""
Write-Host "TEST_3B3_READY"
Write-Host "8 OrderRequestClient TPS tests + 1 lifecycle race test verified."
Write-Host "No production file changed by this script."
Write-Host "Backups, if any repair was needed: $backupRoot"
