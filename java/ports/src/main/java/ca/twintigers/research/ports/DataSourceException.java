package ca.twintigers.research.ports;

/** An external source is unreachable, refused the request, or returned data that cannot be used. The message is safe to show the user (no secrets). */
public class DataSourceException extends Exception {

    public DataSourceException(String message) {
        super(message);
    }

    public DataSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
