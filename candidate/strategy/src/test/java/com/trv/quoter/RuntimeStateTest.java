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
        metadata = Metadata.parse("AAH6", "ticksize=0.01 ref_price=150 band=100 min_volume=1 max_volume=100 position_limit=200 max_tps=100");
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
    void testBboDoesNotExpireWhenConnectionRemainsTrusted() {
        runtimeState.markConnected();
        runtimeState.acceptBbo(validBbo);
        runtimeState.acceptRisk(safeRisk);

        assertTrue(runtimeState.isReady());

        now.addAndGet(10_000_000_000L);

        runtimeState.acceptRisk(
            new DeskRiskMessage(
                1633072810000000000L,
                11,
                "AAH6",
                0,
                100,
                200,
                HedgerState.SAFE,
                HedgeDirection.B));

        assertTrue(runtimeState.isReady());
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

    @Test
    void testStaleRiskAndBboBothStaleSequenceResetRegression() {
    runtimeState.markConnected();
    runtimeState.acceptBbo(validBbo);
    runtimeState.acceptRisk(safeRisk);
    assertTrue(runtimeState.isReady());

    now.addAndGet(3_000_000_001L);
    assertFalse(runtimeState.isReady());

    runtimeState.acceptBbo(validBbo);
    runtimeState.acceptRisk(new DeskRiskMessage(1633072800000000000L, 1, "AAH6", 0, 100, 200, HedgerState.SAFE, HedgeDirection.B));
    assertTrue(runtimeState.isReady());
    }

    @Test
    void testInvalidateBboClearsPreviouslyTrustedBbo() {
        runtimeState.markConnected();
        runtimeState.acceptBbo(validBbo);
        runtimeState.acceptRisk(safeRisk);

        assertTrue(runtimeState.isReady());

        runtimeState.invalidateBbo();

        assertFalse(runtimeState.isReady());
    }

    @Test
    void snapshotReturnsConsistentTrustedBboRiskAndReadyState() {
        AtomicLong now =
            new AtomicLong(
                1_000_000_000L);

        Metadata metadata =
            Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=105 band=100 min_volume=1 max_volume=100 position_limit=200 max_tps=100");

        RuntimeState state =
            new RuntimeState(
                "AAH6",
                metadata,
                now::get);

        Bbo bbo =
            Bbo.parse(
                "1 AAH6 100 10 110 10",
                metadata);

        DeskRiskMessage risk =
            risk(
                1L,
                HedgerState.SAFE,
                HedgeDirection.X);

        state.markConnected();
        state.acceptBbo(bbo);
        state.acceptRisk(risk);

        RuntimeState.Snapshot snapshot =
            state.snapshot();

        assertTrue(snapshot.ready());
        assertSame(bbo, snapshot.bbo());
        assertSame(risk, snapshot.risk());
    }

    @Test
    void bboVersionChangesForAcceptedBboButNotForRiskOnlyUpdate() {
        AtomicLong now =
            new AtomicLong(
                1_000_000_000L);

        Metadata metadata =
            Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=105 band=100 min_volume=1 max_volume=100 position_limit=200 max_tps=100");

        RuntimeState state =
            new RuntimeState(
                "AAH6",
                metadata,
                now::get);

        long initial =
            state.snapshot()
                .bboVersion();

        state.acceptBbo(
            Bbo.parse(
                "1 AAH6 100 10 110 10",
                metadata));

        long afterBbo =
            state.snapshot()
                .bboVersion();

        state.acceptRisk(
            risk(
                1L,
                HedgerState.SAFE,
                HedgeDirection.X));

        long afterRisk =
            state.snapshot()
                .bboVersion();

        assertTrue(afterBbo > initial);
        assertEquals(
            afterBbo,
            afterRisk);
    }

    @Test
    void snapshotExpiresStaleRiskWithoutDiscardingTrustedBbo() {
        AtomicLong now =
            new AtomicLong(
                1_000_000_000L);

        Metadata metadata =
            Metadata.parse(
                "AAH6",
                "ticksize=1 ref_price=105 band=100 min_volume=1 max_volume=100 position_limit=200 max_tps=100");

        RuntimeState state =
            new RuntimeState(
                "AAH6",
                metadata,
                now::get);

        Bbo bbo =
            Bbo.parse(
                "1 AAH6 100 10 110 10",
                metadata);

        state.markConnected();
        state.acceptBbo(bbo);
        state.acceptRisk(
            risk(
                10L,
                HedgerState.SAFE,
                HedgeDirection.X));

        long riskVersionBefore =
            state.snapshot()
                .riskVersion();

        now.addAndGet(
            1_000_000_001L);

        RuntimeState.Snapshot stale =
            state.snapshot();

        assertFalse(stale.ready());
        assertSame(bbo, stale.bbo());
        assertNull(stale.risk());
        assertTrue(
            stale.riskVersion()
                > riskVersionBefore);
    }

    private DeskRiskMessage risk(
            long sequence,
            HedgerState state,
            HedgeDirection direction) {

        return new DeskRiskMessage(
            1L,
            sequence,
            "AAH6",
            0,
            4,
            5,
            state,
            direction);
    }
}
