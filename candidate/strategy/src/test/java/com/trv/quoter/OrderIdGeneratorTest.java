package com.trv.quoter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OrderIdGeneratorTest {

    @Test
    void generatedIdIsExactlyEightUppercaseBase36Characters() {
        long now =
            OrderIdGenerator.EPOCH_OFFSET_MS
                + 1_234_567L;

        OrderIdGenerator generator =
            new OrderIdGenerator(
                () -> now);

        String id =
            generator.nextId();

        assertEquals(
            8,
            id.length());
        assertTrue(
            id.matches("[0-9A-Z]{8}"));
    }

    @Test
    void multipleIdsInSameMillisecondAreStrictlyUnique() {
        long now =
            OrderIdGenerator.EPOCH_OFFSET_MS
                + 50_000L;

        OrderIdGenerator generator =
            new OrderIdGenerator(
                () -> now);

        String first =
            generator.nextId();
        String second =
            generator.nextId();
        String third =
            generator.nextId();

        assertNotEquals(
            first,
            second);
        assertNotEquals(
            second,
            third);
        assertNotEquals(
            first,
            third);
    }

    @Test
    void clockRollbackWithinSameProcessCannotReuseAnId() {
        AtomicLong clock =
            new AtomicLong(
                OrderIdGenerator.EPOCH_OFFSET_MS
                    + 100_000L);

        OrderIdGenerator generator =
            new OrderIdGenerator(
                clock::get);

        String first =
            generator.nextId();

        clock.addAndGet(
            -10_000L);

        String second =
            generator.nextId();

        assertNotEquals(
            first,
            second);
    }

    @Test
    void clockBeforeEpochFailsClosed() {
        OrderIdGenerator generator =
            new OrderIdGenerator(
                () ->
                    OrderIdGenerator.EPOCH_OFFSET_MS
                        - 1L);

        assertThrows(
            IllegalStateException.class,
            generator::nextId);
    }

    @Test
    void exhaustedEightCharacterSpaceFailsClosed() {
        AtomicLong clock =
            new AtomicLong(
                OrderIdGenerator.EPOCH_OFFSET_MS
                    + OrderIdGenerator.MAX_BASE36_8);

        OrderIdGenerator generator =
            new OrderIdGenerator(
                clock::get);

        assertEquals(
            "ZZZZZZZZ",
            generator.nextId());

        assertThrows(
            IllegalStateException.class,
            generator::nextId);
    }
}
