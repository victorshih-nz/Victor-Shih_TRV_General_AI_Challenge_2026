package com.trv.quoter;

import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class QuoterMain {

    private static final Logger logger =
        Logger.getLogger(QuoterMain.class.getName());

    private QuoterMain() {
    }

    public static void main(String[] args) throws Exception {
        QuoterIntegration quoter =
            new QuoterIntegration();

        CountDownLatch shutdownLatch =
            new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(
            new Thread(
                () -> {
                    try {
                        quoter.close();
                    } catch (Exception e) {
                        logger.log(
                            Level.SEVERE,
                            "Failed to close Quoter cleanly",
                            e);
                    } finally {
                        shutdownLatch.countDown();
                    }
                },
                "quoter-shutdown"));

        logger.info("Quoter started");

        shutdownLatch.await();
    }
}