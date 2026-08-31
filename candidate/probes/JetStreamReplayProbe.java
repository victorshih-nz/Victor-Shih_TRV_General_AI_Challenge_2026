import io.nats.client.Connection;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.MessageInfo;
import io.nats.client.api.StreamInfo;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Read-only investigation of JetStream replay for sender-specific exchange MD.
 *
 * Default subject:
 *   ex.md.<TAKER_FEED>.PFPROBE1
 *
 * Optional first argument overrides the 8-char sender.
 *
 * This probe:
 *  1. discovers the JetStream stream(s) backing the exact sender subject
 *  2. prints stream configuration/state
 *  3. snapshots first/last retained message for the subject
 *  4. replays the bounded [firstSeq, snapshotLastSeq] window with getNextMessage
 *  5. repeats the same replay and verifies deterministic seq+payload equality
 *  6. measures management-call latency
 *
 * It does not publish orders, mutate consumers, purge data, or change production state.
 */
public final class JetStreamReplayProbe {

    private static final String DEFAULT_SENDER = "PFPROBE1";
    private static final int MAX_REPLAY_MESSAGES = 10_000;

    public static void main(String[] args) throws Exception {
        String natsUrl = requireEnv("NATS_URL");
        String feed = requireEnv("TAKER_FEED");

        String sender = args.length >= 1
                ? args[0].trim()
                : DEFAULT_SENDER;

        if (sender.length() != 8) {
            throw new IllegalArgumentException(
                    "probe sender must be exactly 8 characters");
        }

        String subject =
                "ex.md." + feed + "." + sender;

        Options options = new Options.Builder()
                .server(natsUrl)
                .build();

        try (Connection connection = Nats.connect(options)) {
            JetStreamManagement jsm =
                    connection.jetStreamManagement();

            System.out.println("=== JETSTREAM REPLAY PROBE ===");
            System.out.println("natsUrl=" + natsUrl);
            System.out.println("feed=" + feed);
            System.out.println("sender=" + sender);
            System.out.println("subject=" + subject);

            List<String> streams =
                    jsm.getStreamNames(subject);

            System.out.println("matchingStreams=" + streams);

            if (streams.isEmpty()) {
                throw new IllegalStateException(
                        "No JetStream stream stores " + subject);
            }

            if (streams.size() != 1) {
                throw new IllegalStateException(
                        "Expected exactly one stream for "
                                + subject
                                + ", observed "
                                + streams);
            }

            String stream = streams.get(0);

            StreamInfo streamInfo =
                    jsm.getStreamInfo(stream);

            System.out.println();
            System.out.println("=== STREAM INFO ===");
            System.out.println("stream=" + stream);
            System.out.println(
                    "config=" + streamInfo.getConfiguration());
            System.out.println(
                    "state=" + streamInfo.getStreamState());

            MessageInfo first;
            MessageInfo last;

            long firstCallStart = System.nanoTime();

            try {
                first = jsm.getFirstMessage(
                        stream,
                        subject);
            } catch (Exception e) {
                System.out.println();
                System.out.println(
                        "NO_SUBJECT_HISTORY subject="
                                + subject
                                + " error="
                                + safe(e.getMessage()));
                System.out.println(
                        "Run PartialFillOrderingProbe first, "
                                + "then rerun this read-only replay probe.");
                return;
            }

            long firstCallNs =
                    System.nanoTime() - firstCallStart;

            long lastCallStart = System.nanoTime();

            last = jsm.getLastMessage(
                    stream,
                    subject);

            long lastCallNs =
                    System.nanoTime() - lastCallStart;

            validateMessage(first, subject);
            validateMessage(last, subject);

            long snapshotFirstSeq = first.getSeq();
            long snapshotLastSeq = last.getSeq();

            System.out.println();
            System.out.println("=== RETAINED SUBJECT WINDOW ===");
            printMessageSummary("first", first);
            printMessageSummary("last", last);
            System.out.println(
                    "firstLookupMs="
                            + nanosToMillis(firstCallNs));
            System.out.println(
                    "lastLookupMs="
                            + nanosToMillis(lastCallNs));

            if (first.getTime() != null) {
                Duration oldestAge =
                        Duration.between(
                                first.getTime(),
                                ZonedDateTime.now());

                System.out.println(
                        "oldestRetainedAgeMs="
                                + oldestAge.toMillis());
            }

            System.out.println();
            System.out.println("=== REPLAY PASS 1 ===");

            ReplayResult pass1 =
                    replay(
                            jsm,
                            stream,
                            subject,
                            snapshotFirstSeq,
                            snapshotLastSeq,
                            true);

            System.out.println();
            System.out.println("=== REPLAY PASS 2 ===");

            ReplayResult pass2 =
                    replay(
                            jsm,
                            stream,
                            subject,
                            snapshotFirstSeq,
                            snapshotLastSeq,
                            false);

            boolean deterministic =
                    pass1.fingerprints.equals(
                            pass2.fingerprints);

            System.out.println();
            System.out.println("=== SUMMARY ===");
            System.out.println(
                    "snapshotFirstSeq=" + snapshotFirstSeq);
            System.out.println(
                    "snapshotLastSeq=" + snapshotLastSeq);
            System.out.println(
                    "pass1Count=" + pass1.count);
            System.out.println(
                    "pass2Count=" + pass2.count);
            System.out.println(
                    "pass1ElapsedMs="
                            + nanosToMillis(pass1.elapsedNs));
            System.out.println(
                    "pass2ElapsedMs="
                            + nanosToMillis(pass2.elapsedNs));
            System.out.println(
                    "pass1MaxLookupMs="
                            + nanosToMillis(
                                    pass1.maxLookupNs));
            System.out.println(
                    "pass2MaxLookupMs="
                            + nanosToMillis(
                                    pass2.maxLookupNs));
            System.out.println(
                    "sameSequenceAndPayload="
                            + deterministic);

            if (!deterministic) {
                throw new IllegalStateException(
                        "Replay passes did not return "
                                + "identical bounded history");
            }

            if (pass1.count == 0) {
                throw new IllegalStateException(
                        "Subject had first/last messages "
                                + "but bounded replay returned none");
            }

            System.out.println("RESULT=REPLAYABLE");
        }
    }

    private static ReplayResult replay(
            JetStreamManagement jsm,
            String stream,
            String subject,
            long startSeq,
            long snapshotLastSeq,
            boolean printMessages) throws Exception {

        long probeStart = System.nanoTime();
        long maxLookupNs = 0L;
        long cursor = startSeq;

        int count = 0;
        List<String> fingerprints =
                new ArrayList<>();

        while (cursor <= snapshotLastSeq) {
            if (count >= MAX_REPLAY_MESSAGES) {
                throw new IllegalStateException(
                        "Replay exceeded safety limit "
                                + MAX_REPLAY_MESSAGES);
            }

            long lookupStart = System.nanoTime();

            MessageInfo message =
                    jsm.getNextMessage(
                            stream,
                            cursor,
                            subject);

            long lookupNs =
                    System.nanoTime() - lookupStart;

            maxLookupNs =
                    Math.max(
                            maxLookupNs,
                            lookupNs);

            validateMessage(
                    message,
                    subject);

            long seq = message.getSeq();

            if (seq < cursor) {
                throw new IllegalStateException(
                        "Non-monotonic replay: requested >= "
                                + cursor
                                + " but received "
                                + seq);
            }

            if (seq > snapshotLastSeq) {
                break;
            }

            byte[] data =
                    message.getData();

            String payload =
                    new String(
                            data,
                            StandardCharsets.UTF_8);

            fingerprints.add(
                    seq
                            + "|"
                            + subject
                            + "|"
                            + payload);

            count++;

            if (printMessages) {
                System.out.println(
                        "REPLAY"
                                + " seq=" + seq
                                + " storedAt="
                                + message.getTime()
                                + " lookupMs="
                                + nanosToMillis(
                                        lookupNs)
                                + " payload=\""
                                + safe(payload)
                                + "\"");
            }

            if (seq == Long.MAX_VALUE) {
                break;
            }

            cursor = seq + 1;
        }

        long elapsedNs =
                System.nanoTime() - probeStart;

        return new ReplayResult(
                count,
                elapsedNs,
                maxLookupNs,
                fingerprints);
    }

    private static void validateMessage(
            MessageInfo message,
            String expectedSubject) {

        Objects.requireNonNull(
                message,
                "JetStream returned null MessageInfo");

        if (message.getSeq() <= 0) {
            throw new IllegalStateException(
                    "Invalid stream sequence "
                            + message.getSeq());
        }

        if (!expectedSubject.equals(
                message.getSubject())) {

            throw new IllegalStateException(
                    "Subject mismatch: expected "
                            + expectedSubject
                            + " observed "
                            + message.getSubject());
        }

        if (message.getData() == null) {
            throw new IllegalStateException(
                    "Message data is null at seq "
                            + message.getSeq());
        }
    }

    private static void printMessageSummary(
            String label,
            MessageInfo message) {

        String payload =
                new String(
                        message.getData(),
                        StandardCharsets.UTF_8);

        System.out.println(
                label
                        + "Seq="
                        + message.getSeq()
                        + " "
                        + label
                        + "StoredAt="
                        + message.getTime()
                        + " "
                        + label
                        + "Payload=\""
                        + safe(payload)
                        + "\"");
    }

    private static String requireEnv(
            String name) {

        String value =
                System.getenv(name);

        if (value == null
                || value.isBlank()) {

            throw new IllegalArgumentException(
                    name
                            + " environment variable "
                            + "is required");
        }

        return value;
    }

    private static double nanosToMillis(
            long nanos) {

        return nanos / 1_000_000.0;
    }

    private static String safe(
            String value) {

        if (value == null) {
            return "<null>";
        }

        return value
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\"", "\\\"");
    }

    private record ReplayResult(
            int count,
            long elapsedNs,
            long maxLookupNs,
            List<String> fingerprints) {
    }
}
