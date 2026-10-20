package ca.twintigers.research.application;

import ca.twintigers.research.domain.alerts.Notification;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.Notifier;
import ca.twintigers.research.ports.Outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends what is due in the outbox. Each row is one channel, so a failed phone push is retried without showing
 * the desktop toast again. Back-off: 1, 5, 15, 30, 60 minutes, then every 2 hours, giving up after 12 attempts
 * (the row stays, marked with the last error).
 */
public final class NotificationDispatcher {

    static final Duration[] BACKOFF = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofMinutes(30), Duration.ofMinutes(60)};
    static final int MAX_ATTEMPTS = 12;

    public record Result(int sent, int failed, int gaveUp) {
    }

    private final Outbox outbox;
    private final Map<String, Notifier> notifiers = new LinkedHashMap<>();
    private final Clock clock;

    public NotificationDispatcher(Outbox outbox, List<Notifier> notifiers, Clock clock) {
        this.outbox = outbox;
        notifiers.forEach(n -> this.notifiers.put(n.channel(), n));
        this.clock = clock;
    }

    public List<String> channels() {
        return List.copyOf(notifiers.keySet());
    }

    /** Queue a notification on every configured channel. Returns the number of new outbox rows. */
    public int enqueue(Notification n) {
        return outbox.enqueue(n, channels(), clock.instant());
    }

    public Result dispatch() {
        int sent = 0;
        int failed = 0;
        int gaveUp = 0;
        Instant now = clock.instant();
        for (Outbox.Entry e : outbox.due(now)) {
            Notifier n = notifiers.get(e.channel());
            if (n == null) {
                continue; // channel no longer configured: leave it queued
            }
            try {
                n.send(new Notification(e.dedupeKey(), e.title(), e.body(), e.priority()));
                outbox.markSent(e.id(), clock.instant());
                sent++;
            } catch (DataSourceException | RuntimeException ex) {
                int attempt = e.attempts() + 1;
                boolean giveUp = attempt >= MAX_ATTEMPTS;
                Duration wait = attempt <= BACKOFF.length ? BACKOFF[attempt - 1] : Duration.ofHours(2);
                outbox.markFailed(e.id(), String.valueOf(ex.getMessage()), clock.instant().plus(wait), giveUp);
                failed++;
                if (giveUp) {
                    gaveUp++;
                }
            }
        }
        return new Result(sent, failed, gaveUp);
    }
}
