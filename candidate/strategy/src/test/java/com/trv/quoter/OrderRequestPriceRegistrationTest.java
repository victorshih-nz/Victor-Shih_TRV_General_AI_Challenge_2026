package com.trv.quoter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

class OrderRequestPriceRegistrationTest {

    @Test
    void requestAddRegistersTheActualWirePriceInOrderManager() {
        OrderManager manager =
            new OrderManager();

        ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

        OrderRequestClient client =
            new OrderRequestClient(
                "QUOTE001",
                "AAH6",
                Metadata.parse(
                    "AAH6",
                    "ticksize=1 ref_price=500 band=100"),
                manager,
                () -> true,
                () -> true,
                (subject, payload, timeout) ->
                    new CompletableFuture<>(),
                scheduler,
                Duration.ofHours(1),
                Duration.ofHours(1));

        try (client) {
            client.requestAdd(
                OrderManager.Side.BID,
                "BID00001",
                1,
                500L);

            assertEquals(
                Long.valueOf(500L),
                manager.price(
                    OrderManager.Side.BID));
        }
    }
}
