package ca.twintigers.research.ports;

import ca.twintigers.research.domain.alerts.Notification;

import java.time.Instant;
import java.util.List;

/**
 * Durable queue of notifications, one row per (dedupe key, channel). Enqueuing an existing key is a no-op, so the
 * same event is never sent twice on a channel; a failed send stays queued and is retried with back-off.
 */
public interface Outbox {

    record Entry(long id, String dedupeKey, String channel, String title, String body, Notification.Priority priority,
                 Instant createdAt, int attempts, Instant nextAttemptAt, Instant sentAt, String lastError) {
    }

    /** @return how many rows were newly created (0 when every channel already had this key) */
    int enqueue(Notification n, List<String> channels, Instant now);

    List<Entry> due(Instant now);

    void markSent(long id, Instant at);

    void markFailed(long id, String error, Instant nextAttemptAt, boolean giveUp);

    List<Entry> recentEntries(int limit);

    long pendingCount();
}
