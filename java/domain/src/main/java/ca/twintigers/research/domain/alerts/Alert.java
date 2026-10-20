package ca.twintigers.research.domain.alerts;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A price threshold the user sets for one symbol. Exactly one of the kinds below; {@code level},
 * {@code pct}, {@code reference} and {@code atrMultiple} are used as the kind needs.
 *
 * <ul>
 *   <li>ABOVE: price at or above {@code level} ("sell-watch above X")</li>
 *   <li>BELOW: price at or below {@code level} ("buy-watch below Y")</li>
 *   <li>MOVE_PCT: price moved at least {@code pct} % (up if positive, down if negative) from {@code reference}</li>
 *   <li>TRAILING_ATR: close below (highest close since {@code since}) − {@code atrMultiple} × ATR(14) — a trailing stop</li>
 * </ul>
 *
 * @param mutedUntil notifications are suppressed until then (the state machine still runs)
 */
public record Alert(long id, Kind kind, String symbol, Double level, Double pct, Double reference, Double atrMultiple,
                    LocalDate since, String note, Instant createdAt, Instant mutedUntil, boolean active, AlertState state) {

    public enum Kind { ABOVE, BELOW, MOVE_PCT, TRAILING_ATR }

    public boolean muted(Instant now) {
        return mutedUntil != null && now.isBefore(mutedUntil);
    }

    public Alert withState(AlertState s) {
        return new Alert(id, kind, symbol, level, pct, reference, atrMultiple, since, note, createdAt, mutedUntil, active, s);
    }

    /** Plain-English description, e.g. "ENB.TO at or above 75.00". */
    public String describe() {
        return switch (kind) {
            case ABOVE -> symbol + " at or above " + fmt(level);
            case BELOW -> symbol + " at or below " + fmt(level);
            case MOVE_PCT -> symbol + " moves " + (pct >= 0 ? "up " : "down ") + fmt(Math.abs(pct)) + "% from " + fmt(reference);
            case TRAILING_ATR -> symbol + " closes below its trailing stop (highest close since " + since + " − "
                    + fmt(atrMultiple) + "× ATR14)";
        };
    }

    static String fmt(Double v) {
        return v == null ? "?" : String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
