package ca.twintigers.research.domain.alerts;

/**
 * A message to deliver. {@code dedupeKey} identifies the event (e.g. {@code signal|ENB.TO|PA02_seller_controlled_drop|2026-10-15}
 * or {@code alert|7|2026-10-19T14:30Z}); the outbox accepts each key once per channel, so a rerun never sends twice.
 */
public record Notification(String dedupeKey, String title, String body, Priority priority) {

    public enum Priority { LOW, DEFAULT, HIGH }
}
