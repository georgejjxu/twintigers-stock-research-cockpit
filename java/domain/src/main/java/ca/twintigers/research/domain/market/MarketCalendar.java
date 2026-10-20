package ca.twintigers.research.domain.market;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;

/**
 * Trading days and regular hours for the TSX and NYSE (both 09:30–16:00 Eastern). Holidays are supplied as
 * data (java/config/holidays/*.txt), so the domain holds no hard-coded dates.
 */
public final class MarketCalendar {

    public enum Exchange { TSX, NYSE }

    public static final LocalTime OPEN = LocalTime.of(9, 30);
    public static final LocalTime CLOSE = LocalTime.of(16, 0);
    /** After this time (Eastern) the day's bar is treated as final. */
    public static final LocalTime FINAL_AFTER = LocalTime.of(16, 15);

    private final Map<Exchange, Set<LocalDate>> holidays;

    public MarketCalendar(Map<Exchange, Set<LocalDate>> holidays) {
        this.holidays = Map.copyOf(holidays);
    }

    /** TSX for symbols ending in .TO or .V, otherwise NYSE (also used for NASDAQ: same holidays). */
    public static Exchange exchangeOf(String symbol) {
        String s = symbol.toUpperCase(java.util.Locale.ROOT);
        return s.endsWith(".TO") || s.endsWith(".V") ? Exchange.TSX : Exchange.NYSE;
    }

    public boolean isTradingDay(Exchange x, LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY && !holidays.getOrDefault(x, Set.of()).contains(d);
    }

    public boolean isOpen(Exchange x, LocalDate d, LocalTime t) {
        return isTradingDay(x, d) && !t.isBefore(OPEN) && t.isBefore(CLOSE);
    }

    /** The trading day before d on that exchange. */
    public LocalDate previousTradingDay(Exchange x, LocalDate d) {
        LocalDate p = d.minusDays(1);
        while (!isTradingDay(x, p)) {
            p = p.minusDays(1);
        }
        return p;
    }

    /** Whether the holiday data covers this year (so a missing file is noticed rather than ignored). */
    public boolean covers(Exchange x, int year) {
        return holidays.getOrDefault(x, Set.of()).stream().anyMatch(h -> h.getYear() == year);
    }
}
