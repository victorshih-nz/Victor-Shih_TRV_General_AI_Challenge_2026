package com.trv.quoter;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DeskRiskMessageTest {
    @Test
    public void testValidMessage() {
        String message = "123456789 123 feed 100 1000 2000 SAFE B";
        DeskRiskMessage parsedMessage = DeskRiskMessage.parse(message);
        assertEquals(123456789L, parsedMessage.getTimestampNs());
        assertEquals(123L, parsedMessage.getSequence());
        assertEquals("feed", parsedMessage.getFeed());
        assertEquals(100, parsedMessage.getNetPosition());
        assertEquals(1000, parsedMessage.getSoftLimit());
        assertEquals(2000, parsedMessage.getHardLimit());
        assertEquals(HedgerState.SAFE, parsedMessage.getState());
        assertEquals(HedgeDirection.B, parsedMessage.getHedgeDirection());
    }

    @Test
    public void testAllValidStates() {
        for (HedgerState state : HedgerState.values()) {
            String message = "123456789 123 feed 100 1000 2000 " + state + " B";
            assertDoesNotThrow(() -> DeskRiskMessage.parse(message));
        }
    }

    @Test
    public void testAllValidDirections() {
        for (HedgeDirection direction : HedgeDirection.values()) {
            String message = "123456789 123 feed 100 1000 2000 SAFE " + direction;
            assertDoesNotThrow(() -> DeskRiskMessage.parse(message));
        }
    }

    @Test
    public void testPositiveAndNegativeNetPosition() {
        assertDoesNotThrow(() -> DeskRiskMessage.parse("123456789 123 feed 100 1000 2000 SAFE B"));
        assertDoesNotThrow(() -> DeskRiskMessage.parse("123456789 123 feed -100 1000 2000 SAFE B"));
    }

    @Test
    public void testSequenceZero() {
        assertDoesNotThrow(() -> DeskRiskMessage.parse("123456789 0 feed 100 1000 2000 SAFE B"));
    }

    @Test
    public void testLeadingTrailingWhitespace() {
        String message = " 123456789 123 feed 100 1000 2000 SAFE B ";
        DeskRiskMessage parsedMessage = DeskRiskMessage.parse(message);
        assertEquals(123456789L, parsedMessage.getTimestampNs());
        assertEquals(123L, parsedMessage.getSequence());
        assertEquals("feed", parsedMessage.getFeed());
        assertEquals(100, parsedMessage.getNetPosition());
        assertEquals(1000, parsedMessage.getSoftLimit());
        assertEquals(2000, parsedMessage.getHardLimit());
        assertEquals(HedgerState.SAFE, parsedMessage.getState());
        assertEquals(HedgeDirection.B, parsedMessage.getHedgeDirection());
    }

    @Test
    public void testNullInput() {
        assertThrows(IllegalArgumentException.class, () -> DeskRiskMessage.parse(null));
    }

    @Test
    public void testBlankInput() {
        assertThrows(IllegalArgumentException.class, () -> DeskRiskMessage.parse(""));
        assertThrows(IllegalArgumentException.class, () -> DeskRiskMessage.parse(" "));
    }

    @Test
    public void testWrongFieldCount() {
        assertThrows(IllegalArgumentException.class, () -> DeskRiskMessage.parse("123456789 123 feed 100 1000 2000 SAFE B X"));
        assertThrows(IllegalArgumentException.class, () -> DeskRiskMessage.parse("123456789 123 feed 100 1000 2000 SAFE"));
    }

    @Test
    public void testMalformedLongInt() {
        assertThrows(NumberFormatException.class, () -> DeskRiskMessage.parse("abc 123 feed 100 1000 2000 SAFE B"));
        assertThrows(NumberFormatException.class, () -> DeskRiskMessage.parse("123 abc feed 100 1000 2000 SAFE B"));
    }

    @Test
    public void testNegativeSequence() {
        assertThrows(IllegalArgumentException.class, () -> DeskRiskMessage.parse("123456789 -123 feed 100 1000 2000 SAFE B"));
    }

    @Test
    public void testZeroNegativeLimits() {
        assertThrows(IllegalArgumentException.class, () -> DeskRiskMessage.parse("123456789 123 feed 100 0 2000 SAFE B"));
        assertThrows(IllegalArgumentException.class, () -> DeskRiskMessage.parse("123456789 123 feed 100 1000 0 SAFE B"));
    }

    @Test
    public void testEqualLimits() {
        assertThrows(IllegalArgumentException.class, () -> DeskRiskMessage.parse("123456789 123 feed 100 1000 1000 SAFE B"));
    }

    @Test
    public void testSoftLimitGreaterThanHardLimit() {
        assertThrows(IllegalArgumentException.class, () -> DeskRiskMessage.parse("123456789 123 feed 100 2000 1000 SAFE B"));
    }

    @Test
    public void testInvalidState() {
        assertThrows(IllegalArgumentException.class, () -> DeskRiskMessage.parse("123456789 123 feed 100 1000 2000 INVALID B"));
    }

    @Test
    public void testInvalidDirection() {
        assertThrows(IllegalArgumentException.class, () -> DeskRiskMessage.parse("123456789 123 feed 100 1000 2000 SAFE INVALID"));
    }

    @Test
    public void testDirectConstructorInvariantFailures() {
        assertThrows(IllegalArgumentException.class, () -> new DeskRiskMessage(123456789, -1, "feed", 100, 1000, 2000, HedgerState.SAFE, HedgeDirection.B));
        assertThrows(IllegalArgumentException.class, () -> new DeskRiskMessage(123456789, 1, "", 100, 1000, 2000, HedgerState.SAFE, HedgeDirection.B));
        assertThrows(IllegalArgumentException.class, () -> new DeskRiskMessage(123456789, 1, "feed", 100, 0, 2000, HedgerState.SAFE, HedgeDirection.B));
        assertThrows(IllegalArgumentException.class, () -> new DeskRiskMessage(123456789, 1, "feed", 100, 1000, 0, HedgerState.SAFE, HedgeDirection.B));
        assertThrows(IllegalArgumentException.class, () -> new DeskRiskMessage(123456789, 1, "feed", 100, 1000, 1000, HedgerState.SAFE, HedgeDirection.B));
        assertThrows(IllegalArgumentException.class, () -> new DeskRiskMessage(123456789, 1, "feed", 100, 2000, 1000, HedgerState.SAFE, HedgeDirection.B));
        assertThrows(IllegalArgumentException.class, () -> new DeskRiskMessage(123456789, 1, "feed", 100, 1000, 2000, null, HedgeDirection.B));
        assertThrows(IllegalArgumentException.class, () -> new DeskRiskMessage(123456789, 1, "feed", 100, 1000, 2000, HedgerState.SAFE, null));
    }
}