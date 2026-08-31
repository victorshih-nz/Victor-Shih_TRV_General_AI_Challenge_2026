import com.trv.quoter.QuoterIntegration;
import com.trv.quoter.RuntimeState;

import io.nats.client.Connection;
import io.nats.client.Nats;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class QuoterIntegrationReconnectProbe {

    private static final String NATS_URL =
        "nats://127.0.0.1:4222";

    private static final String FEED = "AAH6";

    private static final String RISK_SUBJECT =
        "desk.risk." + FEED;

    public static void main(String[] args) throws Exception {

        QuoterIntegration integration =
            new QuoterIntegration();

        try (Connection publisher = Nats.connect(NATS_URL)) {

            RuntimeState runtimeState =
                integration.getRuntimeState();

            System.out.println("=== STARTUP ===");
            System.out.println(
                "feed="
                    + integration.getMetadata().getFeed());

            System.out.println(
                "tickSize="
                    + integration.getMetadata().getTickSize());

            System.out.println(
                "initialReady="
                    + runtimeState.isReady());

            /*
             * Startup epoch deliberately begins at seq=10.
             *
             * Send real heartbeat-style risk messages rather
             * than one isolated message.
             */
            long startupSeq = 10;

            boolean startupReady = false;

            for (int i = 0; i < 10; i++) {

                publishRisk(publisher, startupSeq++);

                Thread.sleep(200);

                boolean ready = runtimeState.isReady();

                System.out.println(
                    "startupAttempt="
                        + (i + 1)
                        + " ready="
                        + ready);

                if (ready) {
                    startupReady = true;
                    break;
                }
            }

            System.out.println(
                "startupAfterRiskReady=" + startupReady);

            if (!startupReady) {
                throw new IllegalStateException(
                    "Startup did not become ready "
                        + "with fresh risk heartbeat");
            }

            /*
             * Regression proof:
             *
             * Keep risk fresh for >3 seconds while deliberately
             * sending no new BBO ourselves.
             *
             * Runtime must remain READY if the retained/latest BBO
             * is still trusted.
             */
            System.out.println();
            System.out.println(
                "=== UNCHANGED BBO LIVENESS ===");

            boolean remainedReady = true;

            for (int i = 1; i <= 20; i++) {

                publishRisk(publisher, startupSeq++);

                Thread.sleep(200);

                boolean ready = runtimeState.isReady();

                System.out.println(
                    "steadyAttempt="
                        + i
                        + " ready="
                        + ready);

                if (!ready) {
                    remainedReady = false;
                    break;
                }
            }

            System.out.println(
                "readyBeyondThreeSeconds="
                    + remainedReady);

            if (!remainedReady) {
                throw new IllegalStateException(
                    "Runtime lost readiness while "
                        + "risk remained fresh");
            }

            System.out.println();
            System.out.println("=== CLIENT-ONLY RECONNECT ===");

            integration.getConnection().forceReconnect();

            /*
             * Disconnect must invalidate old BBO/risk trust.
             */
            Thread.sleep(100);

            System.out.println(
                "readyImmediatelyAfterReconnect="
                    + runtimeState.isReady());

            System.out.println();
            System.out.println(
                "=== RECONNECT RECOVERY ===");

            /*
             * New sequence epoch deliberately restarts at 1.
             *
             * Keep increasing the sequence so each message is a
             * real heartbeat. Messages that arrive before
             * subscriptionsReady are intentionally ignored.
             */
            long reconnectSeq = 1;

            boolean recovered = false;

            for (int i = 1; i <= 30; i++) {

                try {
                    publishRisk(
                        publisher,
                        reconnectSeq++);
                } catch (Exception e) {

                    System.out.println(
                        "recoveryAttempt="
                            + i
                            + " publisherNotReady="
                            + e.getClass()
                                .getSimpleName());

                    Thread.sleep(200);
                    continue;
                }

                Thread.sleep(200);

                boolean ready =
                    runtimeState.isReady();

                System.out.println(
                    "recoveryAttempt="
                        + i
                        + " ready="
                        + ready);

                if (ready) {
                    recovered = true;
                    break;
                }
            }

            System.out.println(
                "reconnectRecovered=" + recovered);

            if (!recovered) {
                throw new IllegalStateException(
                    "Runtime did not recover "
                        + "after NATS reconnect");
            }

            /*
             * Stop heartbeats completely.
             * Risk must become stale after >1 second.
             */
            System.out.println();
            System.out.println(
                "=== RISK HEARTBEAT STALE ===");

            Thread.sleep(1_100);

            boolean readyAfterRiskStale =
                runtimeState.isReady();

            System.out.println(
                "readyAfterRiskStale="
                    + readyAfterRiskStale);

            if (readyAfterRiskStale) {
                throw new IllegalStateException(
                    "Runtime remained ready "
                        + "after risk heartbeat became stale");
            }

            System.out.println();
            System.out.println(
                "=== PROBE PASS ===");

        } finally {
            integration.close();
        }
    }

    private static void publishRisk(
            Connection publisher,
            long sequence) throws Exception {

        long timestampNs =
            System.currentTimeMillis()
                * 1_000_000L;

        String payload =
            timestampNs
                + " " + sequence
                + " " + FEED
                + " 0 100 200 SAFE X";

        publisher.publish(
            RISK_SUBJECT,
            payload.getBytes(
                StandardCharsets.UTF_8));

        publisher.flush(
            Duration.ofSeconds(2));
    }
}