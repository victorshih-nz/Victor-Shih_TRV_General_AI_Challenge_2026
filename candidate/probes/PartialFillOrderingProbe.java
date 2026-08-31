import com.trv.quoter.Metadata;
import com.trv.quoter.QuoterIntegration;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class PartialFillOrderingProbe {

    /*
     * Controlled investigation only.
     *
     * Sender identities are deliberately separate from the production Quoter.
     * Both are exactly 8 characters.
     */
    private static final String TRACKED_SENDER = "PFPROBE1";
    private static final String CONTRA_SENDER = "PFPROBE2";

    private static final int OBSERVATION_MS = 700;
    private static final int EVENT_WAIT_MS = 2000;

    private final QuoterIntegration integration;
    private final Connection connection;
    private final Metadata metadata;
    private final String feed;

    private final AtomicLong localReceiptSequence = new AtomicLong();
    private final AtomicLong requestSequence = new AtomicLong();

    private final AtomicReference<BboSnapshot> latestBbo =
            new AtomicReference<>();

    private final List<ObservedEvent> events =
            Collections.synchronizedList(new ArrayList<>());

    private final Set<OrderRef> createdOrders =
            Collections.synchronizedSet(new HashSet<>());

    private final AtomicInteger orderCounter = new AtomicInteger();
    private final String sessionTag;

    private volatile String currentCase = "STARTUP";

    private Dispatcher dispatcher;

    public static void main(String[] args) throws Exception {
        PartialFillOrderingProbe probe =
                new PartialFillOrderingProbe();

        try {
            probe.run();
        } finally {
            probe.close();
        }
    }

    private PartialFillOrderingProbe() throws Exception {
        integration = new QuoterIntegration();
        connection = integration.getConnection();
        metadata = integration.getMetadata();
        feed = metadata.getFeed();

        /*
         * Four hex characters + one prefix + three-digit counter
         * gives exactly 8 characters per order id.
         */
        sessionTag = String.format(
                "%04X",
                System.nanoTime() & 0xffff);

        startSubscriptions();
    }

    private void run() throws Exception {
        System.out.println(
                "=== PARTIAL FILL ORDERING PROBE ===");
        System.out.println("feed=" + feed);
        System.out.println(
                "trackedSender=" + TRACKED_SENDER);
        System.out.println(
                "contraSender=" + CONTRA_SENDER);
        System.out.println(
                "tickSize=" + metadata.getTickSize());

        waitForUsableBbo();

        runCase1NoFill();
        cleanupAndSettle();

        runCase2SinglePartial();
        cleanupAndSettle();

        runCase3FullFill();
        cleanupAndSettle();

        runCase4MultiPartial();
        cleanupAndSettle();

        runCase5ContraLargerThanRemaining();
        cleanupAndSettle();

        printEvidenceSummary();
    }

    /*
     * ------------------------------------------------------------
     * CASE 1
     * No fill: add an inside-spread SELL and observe A.
     * ------------------------------------------------------------
     */
    private void runCase1NoFill() throws Exception {
        startCase("CASE1_NO_FILL");

        long price = chooseInsideSpreadPrice();

        String orderId = nextOrderId("A");

        System.out.println(
                "CASE_SETUP case=" + currentCase
                        + " trackedSide=S"
                        + " qty=10"
                        + " price=" + price);

        sendAdd(
                TRACKED_SENDER,
                orderId,
                "S",
                10,
                price);

        waitForEvent(
                TRACKED_SENDER,
                publicId(TRACKED_SENDER, orderId),
                "A",
                EVENT_WAIT_MS);

        observe();
    }

    /*
     * ------------------------------------------------------------
     * CASE 2
     * Contra resting SELL 3.
     * Tracked BUY L 10 at same price.
     *
     * Expected economic shape only:
     * match 3, possible remainder 7.
     * Probe does not assume event semantics.
     * ------------------------------------------------------------
     */
    private void runCase2SinglePartial() throws Exception {
        startCase("CASE2_SINGLE_PARTIAL");

        long price = chooseInsideSpreadPrice();

        String contraId = nextOrderId("B");

        sendAdd(
                CONTRA_SENDER,
                contraId,
                "S",
                3,
                price);

        requireResting(
                CONTRA_SENDER,
                contraId);

        String trackedId = nextOrderId("C");

        System.out.println(
                "CASE_SETUP case=" + currentCase
                        + " contraResting=3"
                        + " incomingTracked=10"
                        + " price=" + price);

        sendAdd(
                TRACKED_SENDER,
                trackedId,
                "B",
                10,
                price);

        observe();
    }

    /*
     * ------------------------------------------------------------
     * CASE 3
     * Contra resting SELL 10.
     * Tracked BUY L 10.
     *
     * Observe whether tracked incoming order receives A.
     * ------------------------------------------------------------
     */
    private void runCase3FullFill() throws Exception {
        startCase("CASE3_FULL");

        long price = chooseInsideSpreadPrice();

        String contraId = nextOrderId("D");

        sendAdd(
                CONTRA_SENDER,
                contraId,
                "S",
                10,
                price);

        requireResting(
                CONTRA_SENDER,
                contraId);

        String trackedId = nextOrderId("E");

        System.out.println(
                "CASE_SETUP case=" + currentCase
                        + " contraResting=10"
                        + " incomingTracked=10"
                        + " price=" + price);

        sendAdd(
                TRACKED_SENDER,
                trackedId,
                "B",
                10,
                price);

        observe();
    }

    /*
     * ------------------------------------------------------------
     * CASE 4
     * Two contra resting orders, quantities 3 and 2.
     * Tracked incoming BUY L 10.
     *
     * Observe multiple E/T messages and A ordering.
     * ------------------------------------------------------------
     */
    private void runCase4MultiPartial() throws Exception {
        startCase("CASE4_MULTI_PARTIAL");

        long price = chooseInsideSpreadPrice();

        String contraId1 = nextOrderId("F");
        String contraId2 = nextOrderId("G");

        sendAdd(
                CONTRA_SENDER,
                contraId1,
                "S",
                3,
                price);

        requireResting(
                CONTRA_SENDER,
                contraId1);

        sendAdd(
                CONTRA_SENDER,
                contraId2,
                "S",
                2,
                price);

        requireResting(
                CONTRA_SENDER,
                contraId2);

        String trackedId = nextOrderId("H");

        System.out.println(
                "CASE_SETUP case=" + currentCase
                        + " contraOrders=3+2"
                        + " incomingTracked=10"
                        + " price=" + price);

        sendAdd(
                TRACKED_SENDER,
                trackedId,
                "B",
                10,
                price);

        observe();
    }

    /*
     * ------------------------------------------------------------
     * CASE 5
     *
     * Our tracked order rests with quantity 1.
     * Contra incoming BUY has quantity 5.
     *
     * Critical evidence:
     * E on tracked sender should reveal the actual quantity
     * matched against our resting order.
     *
     * The contra order may then have quantity remaining.
     * ------------------------------------------------------------
     */
    private void runCase5ContraLargerThanRemaining()
            throws Exception {

        startCase("CASE5_CONTRA_LARGER");

        long price = chooseInsideSpreadPrice();

        String trackedId = nextOrderId("J");

        sendAdd(
                TRACKED_SENDER,
                trackedId,
                "S",
                1,
                price);

        requireResting(
                TRACKED_SENDER,
                trackedId);

        String contraId = nextOrderId("K");

        System.out.println(
                "CASE_SETUP case=" + currentCase
                        + " trackedResting=1"
                        + " contraIncoming=5"
                        + " price=" + price);

        sendAdd(
                CONTRA_SENDER,
                contraId,
                "B",
                5,
                price);

        observe();
    }

    /*
     * ------------------------------------------------------------
     * Subscriptions
     * ------------------------------------------------------------
     */
    private void startSubscriptions() throws Exception {
        dispatcher = connection.createDispatcher();

        dispatcher.subscribe(
                "ex.md." + feed + "." + TRACKED_SENDER,
                message -> recordMarketData(
                        TRACKED_SENDER,
                        message.getData()));

        dispatcher.subscribe(
                "ex.md." + feed + "." + CONTRA_SENDER,
                message -> recordMarketData(
                        CONTRA_SENDER,
                        message.getData()));

        dispatcher.subscribe(
                "ex.bbo." + feed,
                message -> recordBbo(message.getData()));

        connection.flush(Duration.ofSeconds(5));
    }

    private void recordMarketData(
            String observedSender,
            byte[] data) {

        long receiptSeq =
                localReceiptSequence.incrementAndGet();

        long receiptNanoTime = System.nanoTime();

        String raw = new String(
                data,
                StandardCharsets.UTF_8);

        try {
            ObservedEvent event =
                    ObservedEvent.parse(
                            currentCase,
                            receiptSeq,
                            receiptNanoTime,
                            observedSender,
                            raw);

            events.add(event);

            System.out.println(event.toLogLine());

        } catch (Exception e) {
            System.out.println(
                    "RAW_EVENT"
                            + " case=" + currentCase
                            + " receiptSeq=" + receiptSeq
                            + " localNs=" + receiptNanoTime
                            + " observedSender="
                            + observedSender
                            + " parseError="
                            + safe(e.getMessage())
                            + " raw=" + safe(raw));
        }
    }

    private void recordBbo(byte[] data) {
        String raw = new String(
                data,
                StandardCharsets.UTF_8);

        try {
            latestBbo.set(BboSnapshot.parse(raw));
        } catch (Exception ignored) {
            // Probe waits for the next usable BBO.
        }
    }

    /*
     * ------------------------------------------------------------
     * Requests
     * ------------------------------------------------------------
     */
    private String sendAdd(
            String sender,
            String orderId,
            String side,
            int quantity,
            long price) throws Exception {

        String payload =
                sender
                        + " A "
                        + feed
                        + " "
                        + orderId
                        + " "
                        + side
                        + " "
                        + quantity
                        + " "
                        + price
                        + " L";

        createdOrders.add(
                new OrderRef(sender, orderId));

        return sendRequest(sender, payload);
    }

    private String sendCancel(
            String sender,
            String orderId) throws Exception {

        String payload =
                sender
                        + " C "
                        + feed
                        + " "
                        + orderId;

        return sendRequest(sender, payload);
    }

    private String sendRequest(
            String sender,
            String payload) throws Exception {

        long seq = requestSequence.incrementAndGet();
        long sendNs = System.nanoTime();

        System.out.println(
                "REQUEST"
                        + " case=" + currentCase
                        + " requestSeq=" + seq
                        + " localSendNs=" + sendNs
                        + " sender=" + sender
                        + " payload=\"" + payload + "\"");

        Message reply = connection.request(
                "ex.req." + sender,
                payload.getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(2));

        long replyNs = System.nanoTime();

        String replyText =
                reply == null
                        ? "<TIMEOUT>"
                        : new String(
                                reply.getData(),
                                StandardCharsets.UTF_8);

        System.out.println(
                "REPLY"
                        + " case=" + currentCase
                        + " requestSeq=" + seq
                        + " localSendNs=" + sendNs
                        + " localReplyNs=" + replyNs
                        + " elapsedNs=" + (replyNs - sendNs)
                        + " raw=\"" + safe(replyText) + "\"");

        return replyText;
    }

    /*
     * ------------------------------------------------------------
     * Case control / cleanup
     * ------------------------------------------------------------
     */
    private void startCase(String caseName)
            throws Exception {

        currentCase = caseName;

        /*
         * Allow any previous cleanup lifecycle events to arrive before
         * case setup. Case conclusions use order IDs, not sleep alone.
         */
        Thread.sleep(200);

        System.out.println();
        System.out.println(
                "=== " + caseName + " ===");

        BboSnapshot bbo = waitForUsableBbo();

        System.out.println(
                "CASE_BBO"
                        + " case=" + caseName
                        + " bid=" + bbo.bidPrice
                        + " ask=" + bbo.askPrice);
    }

    private void cleanupAndSettle() {
        List<OrderRef> snapshot;

        synchronized (createdOrders) {
            snapshot =
                    new ArrayList<>(createdOrders);

            createdOrders.clear();
        }

        for (OrderRef order : snapshot) {
            try {
                sendCancel(
                        order.sender,
                        order.orderId);
            } catch (Exception e) {
                System.out.println(
                        "CLEANUP"
                                + " sender=" + order.sender
                                + " orderId=" + order.orderId
                                + " result=ignored"
                                + " error="
                                + safe(e.getMessage()));
            }
        }

        try {
            connection.flush(Duration.ofSeconds(2));
            Thread.sleep(300);
        } catch (Exception ignored) {
        }
    }

    private void requireResting(
            String sender,
            String orderId) throws Exception {

        String id17 = publicId(sender, orderId);

        ObservedEvent event =
                waitForEvent(
                        sender,
                        id17,
                        "A",
                        EVENT_WAIT_MS);

        if (event == null) {
            throw new IllegalStateException(
                    "Controlled setup failed: "
                            + id17
                            + " did not produce A event");
        }
    }

    private void observe() throws InterruptedException {
        Thread.sleep(OBSERVATION_MS);
    }

    /*
     * ------------------------------------------------------------
     * Safe inside-spread price
     * ------------------------------------------------------------
     */
    private long chooseInsideSpreadPrice()
            throws Exception {

        BboSnapshot bbo = waitForUsableBbo();

        BigDecimal tickValue = metadata.getTickSize();

        long tick;

        try {
            tick = tickValue.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalStateException(
                    "Probe requires integer tick size, observed "
                            + tickValue);
        }

        if (tick <= 0) {
            throw new IllegalStateException(
                    "Invalid tick size " + tick);
        }

        long candidate = bbo.bidPrice + tick;

        if (candidate >= bbo.askPrice) {
            throw new IllegalStateException(
                    "No safe inside-spread probe price: "
                            + "bid=" + bbo.bidPrice
                            + " ask=" + bbo.askPrice
                            + " tick=" + tick
                            + ". Probe stopped rather than contaminating "
                            + "results with uncontrolled market liquidity.");
        }

        return candidate;
    }

    private BboSnapshot waitForUsableBbo()
            throws Exception {

        long deadline =
                System.nanoTime()
                        + Duration.ofSeconds(5).toNanos();

        while (System.nanoTime() < deadline) {
            BboSnapshot bbo = latestBbo.get();

            if (bbo != null
                    && bbo.bidPrice != null
                    && bbo.askPrice != null
                    && bbo.bidPrice < bbo.askPrice) {
                return bbo;
            }

            Thread.sleep(20);
        }

        throw new IllegalStateException(
                "Timed out waiting for usable two-sided BBO");
    }

    /*
     * ------------------------------------------------------------
     * Event waiting
     * ------------------------------------------------------------
     */
    private ObservedEvent waitForEvent(
            String observedSender,
            String id17,
            String eventType,
            int timeoutMs)
            throws InterruptedException {

        long deadline =
                System.nanoTime()
                        + Duration.ofMillis(timeoutMs).toNanos();

        while (System.nanoTime() < deadline) {
            synchronized (events) {
                for (int i = events.size() - 1; i >= 0; i--) {
                    ObservedEvent event = events.get(i);

                    if (event.observedSender.equals(observedSender)
                            && event.type.equals(eventType)
                            && event.references(id17)) {
                        return event;
                    }
                }
            }

            Thread.sleep(10);
        }

        return null;
    }

    /*
     * ------------------------------------------------------------
     * Evidence summary
     *
     * This deliberately reports observations rather than inferring
     * undocumented protocol guarantees.
     * ------------------------------------------------------------
     */
    private void printEvidenceSummary() {
        System.out.println();
        System.out.println(
                "=== RAW EVIDENCE SUMMARY ===");

        printCaseSummary("CASE1_NO_FILL");
        printCaseSummary("CASE2_SINGLE_PARTIAL");
        printCaseSummary("CASE3_FULL");
        printCaseSummary("CASE4_MULTI_PARTIAL");
        printCaseSummary("CASE5_CONTRA_LARGER");

        System.out.println();
        System.out.println(
                "Interpretation rule:");
        System.out.println(
                "- E/T volume is compared with actual matched quantity.");
        System.out.println(
                "- A.volume is observation only, never quantity authority.");
        System.out.println(
                "- localReceiptSequence gives callback arrival order.");
        System.out.println(
                "- exchangeTs gives exchange event timestamp order.");
    }

    private void printCaseSummary(String caseName) {
        List<ObservedEvent> caseEvents =
                new ArrayList<>();

        synchronized (events) {
            for (ObservedEvent event : events) {
                if (event.caseName.equals(caseName)) {
                    caseEvents.add(event);
                }
            }
        }

        System.out.println();
        System.out.println(
                "SUMMARY case=" + caseName
                        + " eventCount=" + caseEvents.size());

        for (ObservedEvent event : caseEvents) {
            if (event.type.equals("A")
                    || event.type.equals("E")
                    || event.type.equals("T")
                    || event.type.equals("C")) {

                System.out.println(
                        "  "
                                + "receiptSeq="
                                + event.localReceiptSequence
                                + " exchangeTs="
                                + event.exchangeTimestamp
                                + " sender="
                                + event.observedSender
                                + " type="
                                + event.type
                                + " id="
                                + nullSafe(event.orderId)
                                + " incoming="
                                + nullSafe(event.incomingOrderId)
                                + " resting="
                                + nullSafe(event.restingOrderId)
                                + " volume="
                                + nullSafe(event.volume));
            }
        }
    }

    /*
     * ------------------------------------------------------------
     * ID generation
     * ------------------------------------------------------------
     */
    private String nextOrderId(String prefix) {
        int value = orderCounter.incrementAndGet();

        if (value > 999) {
            throw new IllegalStateException(
                    "probe order id counter exhausted");
        }

        String id =
                prefix
                        + sessionTag
                        + String.format("%03d", value);

        if (id.length() != 8) {
            throw new IllegalStateException(
                    "invalid generated order id " + id);
        }

        return id;
    }

    private static String publicId(
            String sender,
            String orderId) {

        return sender + ":" + orderId;
    }

    /*
     * ------------------------------------------------------------
     * Close
     * ------------------------------------------------------------
     */
    private void close() throws Exception {
        cleanupAndSettle();

        if (dispatcher != null) {
            dispatcher.unsubscribe(
                    "ex.md."
                            + feed
                            + "."
                            + TRACKED_SENDER);

            dispatcher.unsubscribe(
                    "ex.md."
                            + feed
                            + "."
                            + CONTRA_SENDER);

            dispatcher.unsubscribe(
                    "ex.bbo." + feed);
        }

        integration.close();
    }

    /*
     * ------------------------------------------------------------
     * Models
     * ------------------------------------------------------------
     */
    private static final class OrderRef {
        private final String sender;
        private final String orderId;

        private OrderRef(
                String sender,
                String orderId) {

            this.sender = sender;
            this.orderId = orderId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (!(other instanceof OrderRef)) {
                return false;
            }

            OrderRef that = (OrderRef) other;

            return sender.equals(that.sender)
                    && orderId.equals(that.orderId);
        }

        @Override
        public int hashCode() {
            return 31 * sender.hashCode()
                    + orderId.hashCode();
        }
    }

    private static final class BboSnapshot {
        private final Long bidPrice;
        private final Long askPrice;

        private BboSnapshot(
                Long bidPrice,
                Long askPrice) {

            this.bidPrice = bidPrice;
            this.askPrice = askPrice;
        }

        private static BboSnapshot parse(String raw) {
            String[] parts =
                    raw.trim().split("\\s+");

            if (parts.length != 6) {
                throw new IllegalArgumentException(
                        "unexpected BBO: " + raw);
            }

            Long bid =
                    "-".equals(parts[2])
                            ? null
                            : Long.parseLong(parts[2]);

            Long ask =
                    "-".equals(parts[4])
                            ? null
                            : Long.parseLong(parts[4]);

            return new BboSnapshot(bid, ask);
        }
    }

    private static final class ObservedEvent {
        private final String caseName;
        private final long localReceiptSequence;
        private final long localReceiptNanoTime;
        private final String observedSender;

        private final long exchangeTimestamp;
        private final String type;

        private final String orderId;
        private final String incomingOrderId;
        private final String restingOrderId;

        private final Long volume;
        private final Long price;
        private final String matchId;
        private final String aggressorSide;

        private final String raw;

        private ObservedEvent(
                String caseName,
                long localReceiptSequence,
                long localReceiptNanoTime,
                String observedSender,
                long exchangeTimestamp,
                String type,
                String orderId,
                String incomingOrderId,
                String restingOrderId,
                Long volume,
                Long price,
                String matchId,
                String aggressorSide,
                String raw) {

            this.caseName = caseName;
            this.localReceiptSequence =
                    localReceiptSequence;
            this.localReceiptNanoTime =
                    localReceiptNanoTime;
            this.observedSender = observedSender;
            this.exchangeTimestamp = exchangeTimestamp;
            this.type = type;
            this.orderId = orderId;
            this.incomingOrderId = incomingOrderId;
            this.restingOrderId = restingOrderId;
            this.volume = volume;
            this.price = price;
            this.matchId = matchId;
            this.aggressorSide = aggressorSide;
            this.raw = raw;
        }

        private static ObservedEvent parse(
                String caseName,
                long localReceiptSequence,
                long localReceiptNanoTime,
                String observedSender,
                String raw) {

            String[] p =
                    raw.trim().split("\\s+");

            if (p.length < 2) {
                throw new IllegalArgumentException(
                        "too few fields");
            }

            long exchangeTimestamp =
                    Long.parseLong(p[0]);

            String type = p[1];

            switch (type) {
                case "A":
                    if (p.length != 6) {
                        throw new IllegalArgumentException(
                                "unexpected A field count");
                    }

                    return new ObservedEvent(
                            caseName,
                            localReceiptSequence,
                            localReceiptNanoTime,
                            observedSender,
                            exchangeTimestamp,
                            type,
                            p[2],
                            null,
                            null,
                            Long.parseLong(p[4]),
                            Long.parseLong(p[5]),
                            null,
                            p[3],
                            raw);

                case "C":
                    if (p.length != 3) {
                        throw new IllegalArgumentException(
                                "unexpected C field count");
                    }

                    return new ObservedEvent(
                            caseName,
                            localReceiptSequence,
                            localReceiptNanoTime,
                            observedSender,
                            exchangeTimestamp,
                            type,
                            p[2],
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            raw);

                case "E":
                case "T":
                    if (p.length != 8) {
                        throw new IllegalArgumentException(
                                "unexpected "
                                        + type
                                        + " field count");
                    }

                    return new ObservedEvent(
                            caseName,
                            localReceiptSequence,
                            localReceiptNanoTime,
                            observedSender,
                            exchangeTimestamp,
                            type,
                            null,
                            p[2],
                            p[3],
                            Long.parseLong(p[4]),
                            Long.parseLong(p[5]),
                            p[6],
                            p[7],
                            raw);

                default:
                    throw new IllegalArgumentException(
                            "unhandled event type " + type);
            }
        }

        private boolean references(String id17) {
            return id17.equals(orderId)
                    || id17.equals(incomingOrderId)
                    || id17.equals(restingOrderId);
        }

        private String toLogLine() {
            return "EVENT"
                    + " case=" + caseName
                    + " receiptSeq="
                    + localReceiptSequence
                    + " localReceiptNs="
                    + localReceiptNanoTime
                    + " exchangeTs="
                    + exchangeTimestamp
                    + " observedSender="
                    + observedSender
                    + " type="
                    + type
                    + " orderId="
                    + nullSafe(orderId)
                    + " incoming="
                    + nullSafe(incomingOrderId)
                    + " resting="
                    + nullSafe(restingOrderId)
                    + " volume="
                    + nullSafe(volume)
                    + " price="
                    + nullSafe(price)
                    + " matchId="
                    + nullSafe(matchId)
                    + " aggressorSide="
                    + nullSafe(aggressorSide)
                    + " raw=\""
                    + safe(raw)
                    + "\"";
        }
    }

    private static String nullSafe(Object value) {
        return value == null
                ? "-"
                : value.toString();
    }

    private static String safe(String value) {
        if (value == null) {
            return "-";
        }

        return value
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\"", "'");
    }
}