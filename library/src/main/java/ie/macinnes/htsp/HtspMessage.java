package ie.macinnes.htsp;

public class HtspMessage {
    private static final String TAG = HtspMessage.class.getName();

    public interface MessageListener {
        void onMessage(HtspMessage message);
    }
}
