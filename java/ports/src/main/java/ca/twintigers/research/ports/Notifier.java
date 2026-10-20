package ca.twintigers.research.ports;

import ca.twintigers.research.domain.alerts.Notification;

/** A delivery channel (Windows toast, ntfy push). */
public interface Notifier {

    /** Channel name stored in the outbox, e.g. "toast" or "ntfy". */
    String channel();

    void send(Notification n) throws DataSourceException;
}
