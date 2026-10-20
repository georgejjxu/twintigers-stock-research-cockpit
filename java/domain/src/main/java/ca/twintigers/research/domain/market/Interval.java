package ca.twintigers.research.domain.market;

import java.time.Duration;
import java.util.Locale;

/**
 * Intraday bar sizes, how much history the chart loads by default, and how far back the free feed serves them
 * (Yahoo: 1-minute bars for the last 7 days, 5- to 60-minute bars for the last 60 days). Bars once stored are kept,
 * so the local store can reach further back than the feed.
 */
public enum Interval {
    M1("1m", Duration.ofMinutes(1), Duration.ofDays(5), Duration.ofDays(7)),
    M5("5m", Duration.ofMinutes(5), Duration.ofDays(20), Duration.ofDays(60)),
    M15("15m", Duration.ofMinutes(15), Duration.ofDays(45), Duration.ofDays(60)),
    M30("30m", Duration.ofMinutes(30), Duration.ofDays(59), Duration.ofDays(60)),
    H1("1h", Duration.ofHours(1), Duration.ofDays(59), Duration.ofDays(60));

    private final String code;
    private final Duration size;
    private final Duration defaultLookback;
    private final Duration feedLimit;

    Interval(String code, Duration size, Duration defaultLookback, Duration feedLimit) {
        this.code = code;
        this.size = size;
        this.defaultLookback = defaultLookback;
        this.feedLimit = feedLimit;
    }

    /** How far back the free feed serves this interval (requests are clipped to it). */
    public Duration feedLimit() {
        return feedLimit;
    }

    /** "5m", "1h": the short code used in URLs and by Yahoo. */
    public String code() {
        return code;
    }

    public Duration size() {
        return size;
    }

    /** How much history the chart loads by default (calendar time, so weekends included). */
    public Duration defaultLookback() {
        return defaultLookback;
    }

    public static Interval parse(String code) {
        String c = code == null ? "" : code.strip().toLowerCase(Locale.ROOT);
        for (Interval i : values()) {
            if (i.code.equals(c)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Interval must be 1m, 5m, 15m, 30m or 1h, not " + code + ".");
    }
}
