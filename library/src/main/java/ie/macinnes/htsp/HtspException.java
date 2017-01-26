package ie.macinnes.htsp;

public class HtspException extends Exception {
    public HtspException() {
    }

    public HtspException(String message) {
        super(message);
    }

    public HtspException(String message, Throwable cause) {
        super(message, cause);
    }

    public HtspException(Throwable cause) {
        super(cause);
    }
}
