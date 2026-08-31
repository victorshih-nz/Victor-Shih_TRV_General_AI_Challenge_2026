package com.trv.quoter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class RuntimeStateTest {
    private RuntimeState runtimeState;
    private Metadata metadata;
    private Bbo validBbo;
    private DeskRiskMessage safeRisk;
    private DeskRiskMessage unknownRisk;
    private DeskRiskMessage wrongFeedRisk;
    private AtomicLong now;

    @BeforeEach
    public void setUp() {
        metadata = Metadata.parse("AAH6", "ticksize=0.01");
        validBbo = Bbo.parse("1633072800000 AAH6 100 1 200 2", metadata);
        safeRisk = new DeskRiskMessage(1633072800000000000L, 10, "AAH6", 0, 100, 200, HedgerState.SAFE, HedgeDirection.B);
        unknownRisk = new DeskRiskMessage(1633072800000000000L, 10, "AAH6", 0, 100, 200, HedgerState.UNKNOWN, HedgeDirection.B);
        wrongFeedRisk = new DeskRiskMessage(1633072800000000000L, 11, "AAH7", 0, 100, 200, HedgerState.SAFE, HedgeDirection.B);
        now = new AtomicLong(1_000_000_000L);
        runtimeState = new RuntimeState("AAH6", metadata, now::get);
    }

    @Test
    void testReadyWithValidBBOAndSafeRiskAndConnected() {
        runtimeState.markConnected();
        runtimeState.acceptBbo(validBbo);
        runtimeState.acceptRisk(safeRisk);
        assertTrue(runtimeState.isReady());
    }

    @Test
    void testNotReadyWithNoBBO() {
        assertFalse(runtimeState.isReady());
    }

    @Test
    void testNotReadyWithStaleBBO() {
        runtimeState.markConnected();
        runtimeState.acceptBbo(validBbo);
        runtimeState.acceptRisk(safeRisk);
        assertTrue(runtimeState.isReady());

        now.addAndGet(3_000_000_001L);
        assertFalse(runtimeState.isReady());
    }

    @Test
    void testNotReadyWithStaleRiskWithoutStaleBBO() {
        runtimeState.markConnected();
        runtimeState.acceptBbo(validBbo);
        runtimeState.acceptRisk(safeRisk);
        assertTrue(runtimeState.isReady());

        now.addAndGet(1_000_000_001L);
        assertFalse(runtimeState.isReady());
    }

    @Test
    void testNotReadyWithUnknownRisk() {
        runtimeState.markConnected();
        runtimeState.acceptBbo(validBbo);
        runtimeState.acceptRisk(unknownRisk);
        assertFalse(runtimeState.isReady());
    }

    @Test
    void testWrongFeedRiskIgnored() {
        runtimeState.markConnected();
        runtimeState.acceptBbo(validBbo);
        runtimeState.acceptRisk(safeRisk);
        assertTrue(runtimeState.isReady());

        runtimeState.acceptBbo(validBbo);
        runtimeState.acceptRisk(wrongFeedRisk);
        assertTrue(runtimeState.isReady());
    }

    @Test
    void lowerSeqIgnored() {
        runtimeState.markConnected();
        runtimeState.acceptBbo(validBbo);
        runtimeState.acceptRisk(safeRisk);
        assertTrue(runtimeState.isReady());

        DeskRiskMessage lowerSeqUnknown = new DeskRiskMessage(
            1633072800000000000L,
            9,
            "AAH6",
            0,
            100,
            200,
            HedgerState.UNKNOWN,
            HedgeDirection.B
    );

    runtimeState.acceptRisk(lowerSeqUnknown);

    assertTrue(runtimeState.isReady());
    }

    @Test
    void testStaleRiskResetsSequenceBaseline() {
        runtimeState.markConnected();
        runtimeState.acceptBbo(validBbo);
        runtimeState.acceptRisk(safeRisk);
        assertTrue(runtimeState.isReady());

        now.addAndGet(1_000_000_001L);
        assertFalse(runtimeState.isReady());

        runtimeState.acceptBbo(validBbo);
        runtimeState.acceptRisk(new DeskRiskMessage(1633072800000000000L, 1, "AAH6", 0, 100, 200, HedgerState.SAFE, HedgeDirection.B));
        assertTrue(runtimeState.isReady());
    }

    @Test
    void testResetTrust() {
        runtimeState.markConnected();
        runtimeState.acceptBbo(validBbo);
        runtimeState.acceptRisk(safeRisk);
        assertTrue(runtimeState.isReady());

        runtimeState.resetTrust();
        runtimeState.markConnected();
        assertFalse(runtimeState.isReady());
    }

    @Test
    void duplicateSeqIgnored() {
        runtimeState.markConnected();
        runtimeState.acceptBbo(validBbo);
        runtimeState.acceptRisk(safeRisk);

        DeskRiskMessage duplicateUnknown = new DeskRiskMessage(
            1633072800000000000L,
            10,
            "AAH6",
            0,
            100,
            200,
            HedgerState.UNKNOWN,
            HedgeDirection.B
    );

    runtimeState.acceptRisk(duplicateUnknown);

    assertTrue(runtimeState.isReady());
    }
}
