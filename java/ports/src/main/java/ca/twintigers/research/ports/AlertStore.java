package ca.twintigers.research.ports;

import ca.twintigers.research.domain.alerts.Alert;
import ca.twintigers.research.domain.alerts.AlertState;
import ca.twintigers.research.domain.alerts.Subscription;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The user's alerts and rule subscriptions. This is the only data the system writes on the user's behalf (never Ghostfolio). */
public interface AlertStore {

    Alert addAlert(Alert alert);

    List<Alert> alerts(boolean includeInactive);

    Optional<Alert> alert(long id);

    void updateState(long id, AlertState state);

    /** Deactivates (soft delete, kept for the audit trail). Returns false when there is no such active alert. */
    boolean removeAlert(long id);

    boolean muteAlert(long id, Instant until);

    Subscription addSubscription(Subscription s);

    List<Subscription> subscriptions(boolean includeInactive);

    boolean removeSubscription(long id);

    boolean muteSubscription(long id, Instant until);

    /** Removes subscriptions with origin "default" (before re-tuning them). Returns how many. */
    int removeDefaultSubscriptions();
}
