import com.trv.quoter.Metadata;
import com.trv.quoter.QuoterIntegration;
import com.trv.quoter.RuntimeState;

public class QuoterIntegrationProbe {
    public static void main(String[] args) throws Exception {
        QuoterIntegration integration = new QuoterIntegration();

        try {
            Metadata metadata = integration.getMetadata();
            RuntimeState runtimeState = integration.getRuntimeState();

            System.out.println("feed=" + metadata.getFeed());
            System.out.println("tickSize=" + metadata.getTickSize());
            System.out.println("initialReady=" + runtimeState.isReady());

            for (int i = 1; i <= 10; i++) {
                Thread.sleep(500);
                System.out.println(
                    "t+" + (i * 500) + "ms ready=" + runtimeState.isReady());
            }
        } finally {
            integration.close();
        }
    }
}