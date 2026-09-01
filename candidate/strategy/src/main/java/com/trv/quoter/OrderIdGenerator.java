package com.trv.quoter;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Simple 8-character Base36 monotonic millisecond order-id generator.
 *
 * Within one process lifetime uniqueness is deterministic. Across process
 * restarts collision risk is intentionally accepted as very low rather than
 * adding persistence complexity for V1.
 */
final class OrderIdGenerator {

    static final long EPOCH_OFFSET_MS =
        Instant.parse(
            "2026-01-01T00:00:00Z")
            .toEpochMilli();

    static final long MAX_BASE36_8 =
        2_821_109_907_455L; // 36^8 - 1

    private final LongSupplier currentTimeMillis;
    private final AtomicLong lastGenerated =
        new AtomicLong(-1L);

    OrderIdGenerator() {
        this(System::currentTimeMillis);
    }

    OrderIdGenerator(
            LongSupplier currentTimeMillis) {

        this.currentTimeMillis =
            Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis is required");
    }

    String nextId() {
        while (true) {
            long candidate =
                currentTimeMillis.getAsLong()
                    - EPOCH_OFFSET_MS;

            if (candidate < 0L) {
                throw new IllegalStateException(
                    "system clock is before order-id epoch");
            }

            long previous =
                lastGenerated.get();

            long next =
                Math.max(
                    candidate,
                    previous + 1L);

            if (next < 0L
                    || next > MAX_BASE36_8) {

                throw new IllegalStateException(
                    "8-character Base36 order-id space exhausted");
            }

            if (lastGenerated.compareAndSet(
                    previous,
                    next)) {

                return encode(next);
            }
        }
    }

    private String encode(
            long value) {

        String encoded =
            Long.toString(
                    value,
                    36)
                .toUpperCase(
                    Locale.ROOT);

        if (encoded.length() > 8) {
            throw new IllegalStateException(
                "encoded order id exceeds 8 characters");
        }

        return "0".repeat(
                8 - encoded.length())
            + encoded;
    }
}
