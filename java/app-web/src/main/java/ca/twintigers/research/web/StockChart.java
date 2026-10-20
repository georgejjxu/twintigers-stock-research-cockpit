package ca.twintigers.research.web;

import ca.twintigers.research.application.CockpitService;
import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.Candle;
import ca.twintigers.research.domain.market.Indicators;
import ca.twintigers.research.domain.market.Interval;
import ca.twintigers.research.domain.market.IntradayIndicators;
import ca.twintigers.research.domain.market.Signal;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.IntradayData;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The quote-page chart's data: a range (1D, 5D, 1M, 3M, 6M, YTD, 1Y, 2Y, 5Y, MAX) or from/to dates, resolved to
 * intraday bars (1D: 1-minute, 5D: 5-minute, or the bar size asked for; delayed Yahoo bars from the local store) or
 * daily bars (everything longer), with the 50- and 200-day averages computed over enough earlier history to exist
 * from the first day shown, volume, session VWAP (intraday), signal markers with their explanations (daily) and
 * The user's alert lines.
 */
final class StockChart {

    static final List<String> RANGES = List.of("1D", "5D", "1M", "3M", "6M", "YTD", "1Y", "2Y", "5Y", "MAX");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private StockChart() {
    }

    /** What to load: daily from a date, or intraday bars of one interval for the last N sessions / from a date. */
    record Plan(String label, boolean intraday, Interval interval, LocalDate from, LocalDate to, int sessions) {
    }

    static Plan plan(String range, String fromText, String toText, String bars, LocalDate today) {
        if (fromText != null && !fromText.isBlank()) {
            LocalDate from = LocalDate.parse(fromText.strip());
            LocalDate to = toText == null || toText.isBlank() ? today : LocalDate.parse(toText.strip());
            if (to.isBefore(from)) {
                throw new IllegalArgumentException("The 'to' date is before the 'from' date.");
            }
            if (to.isAfter(today)) {
                to = today;
            }
            boolean shortSpan = Duration.between(from.atStartOfDay(), to.atStartOfDay()).toDays() <= 7;
            boolean inFeed = !from.isBefore(today.minusDays(59));
            if (shortSpan && inFeed) {
                Interval i = bars == null || bars.isBlank() ? Interval.M5 : Interval.parse(bars);
                if (i == Interval.M1 && from.isBefore(today.minusDays(6))) {
                    i = Interval.M5;
                }
                return new Plan(from + " – " + to, true, i, from, to, 0);
            }
            return new Plan(from + " – " + to, false, null, from, to, 0);
        }
        String r = range == null || range.isBlank() ? "1Y" : range.strip().toUpperCase(Locale.ROOT);
        return switch (r) {
            case "1D" -> new Plan(r, true, bars == null || bars.isBlank() ? Interval.M1 : Interval.parse(bars), today.minusDays(6), today, 1);
            case "5D" -> new Plan(r, true, bars == null || bars.isBlank() ? Interval.M5 : Interval.parse(bars), today.minusDays(12), today, 5);
            case "1M" -> new Plan(r, false, null, today.minusMonths(1), today, 0);
            case "3M" -> new Plan(r, false, null, today.minusMonths(3), today, 0);
            case "6M" -> new Plan(r, false, null, today.minusMonths(6), today, 0);
            case "YTD" -> new Plan(r, false, null, today.withDayOfYear(1), today, 0);
            case "1Y" -> new Plan(r, false, null, today.minusYears(1), today, 0);
            case "2Y" -> new Plan(r, false, null, today.minusYears(2), today, 0);
            case "5Y" -> new Plan(r, false, null, today.minusYears(5), today, 0);
            case "MAX" -> new Plan(r, false, null, LocalDate.of(1970, 1, 2), today, 0);
            default -> throw new IllegalArgumentException("Range must be one of " + String.join(", ", RANGES) + ".");
        };
    }

    static Map<String, Object> build(String symbol, Plan p, CockpitService cockpit, IntradayData intraday, Map<String, String> definitions,
                                     Clock clock) throws DataSourceException {
        String s = symbol.toUpperCase(Locale.ROOT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", s);
        out.put("range", p.label());
        out.put("mode", p.intraday() ? "intraday" : "daily");
        out.put("interval", p.intraday() ? p.interval().code() : "1d");
        out.put("alertLines", ChartData.alertLines(cockpit.alertsFor(s)));
        out.put("colors", Map.of("up", ChartData.UP, "down", ChartData.DOWN, "sma50", ChartData.BLUE, "sma200", ChartData.GOLD,
                "vwap", "#8a5cc7", "line", "#1f5f8b", "text", "#12212e", "grid", "#d9e1e4"));
        return p.intraday() ? intradayData(out, s, p, cockpit, intraday, clock) : dailyData(out, s, p, cockpit, definitions);
    }

    private static Map<String, Object> dailyData(Map<String, Object> out, String s, Plan p, CockpitService cockpit,
                                                 Map<String, String> definitions) throws DataSourceException {
        // 300 calendar days before the first day shown, so the 200-day average exists from the start
        List<Bar> all = cockpit.chartBars(s, p.from().minusDays(300));
        double[] close = all.stream().mapToDouble(Bar::close).toArray();
        double[] sma50 = Indicators.sma(close, 50);
        double[] sma200 = Indicators.sma(close, 200);
        List<Map<String, Object>> candles = new ArrayList<>();
        List<Map<String, Object>> volume = new ArrayList<>();
        List<Map<String, Object>> m50 = new ArrayList<>();
        List<Map<String, Object>> m200 = new ArrayList<>();
        Set<String> shown = new TreeSet<>();
        for (int i = 0; i < all.size(); i++) {
            Bar b = all.get(i);
            if (b.date().isBefore(p.from()) || b.date().isAfter(p.to())) {
                continue;
            }
            String t = b.date().toString();
            shown.add(t);
            candles.add(Map.of("time", t, "open", b.open(), "high", b.high(), "low", b.low(), "close", b.close()));
            volume.add(Map.of("time", t, "value", b.volume(), "color", (b.close() >= b.open() ? ChartData.UP : ChartData.DOWN) + "66"));
            if (!Double.isNaN(sma50[i])) {
                m50.add(Map.of("time", t, "value", round(sma50[i])));
            }
            if (!Double.isNaN(sma200[i])) {
                m200.add(Map.of("time", t, "value", round(sma200[i])));
            }
        }
        List<Map<String, Object>> markers = new ArrayList<>();
        CockpitService.Signals sig = cockpit.chartSignals(s);
        if (sig != null && sig.signals() != null) {
            List<Bar> window = all.stream().filter(b -> !b.date().isBefore(p.from().minusDays(30))).toList();
            for (Signal g : sig.signals()) {
                if (shown.contains(g.date().toString())) {
                    markers.add(ChartData.marker(g, window, definitions.get(g.ruleId())));
                }
            }
        }
        out.put("from", candles.isEmpty() ? null : candles.getFirst().get("time"));
        out.put("to", candles.isEmpty() ? null : candles.getLast().get("time"));
        out.put("candles", candles);
        out.put("volume", volume);
        out.put("sma50", m50);
        out.put("sma200", m200);
        out.put("vwap", List.of());
        out.put("markers", markers);
        out.put("source", cockpit.marketSource());
        out.put("delayMinutes", 0);
        out.put("lastBar", candles.isEmpty() ? null : candles.getLast().get("time"));
        out.put("live", false);
        return out;
    }

    private static Map<String, Object> intradayData(Map<String, Object> out, String s, Plan p, CockpitService cockpit,
                                                    IntradayData intraday, Clock clock) throws DataSourceException {
        ZoneId guess = s.endsWith(".TO") || s.endsWith(".V") ? ZoneId.of("America/Toronto") : ZoneId.of("America/New_York");
        Instant from = p.from().atStartOfDay(guess).toInstant();
        IntradayData.Series series = intraday.intraday(s, p.interval(), from);
        ZoneId zone = series.exchangeZone();
        List<Candle> list = series.candles().stream().filter(c -> !c.start().atZone(zone).toLocalDate().isAfter(p.to())).toList();
        if (p.sessions() > 0) {
            TreeSet<LocalDate> days = new TreeSet<>();
            list.forEach(c -> days.add(c.start().atZone(zone).toLocalDate()));
            LocalDate first = days.descendingSet().stream().limit(p.sessions()).reduce((a, b) -> b).orElse(p.from());
            list = list.stream().filter(c -> !c.start().atZone(zone).toLocalDate().isBefore(first)).toList();
        }
        double[] vw = IntradayIndicators.vwap(list, zone);
        List<Map<String, Object>> candles = new ArrayList<>();
        List<Map<String, Object>> volume = new ArrayList<>();
        List<Map<String, Object>> vwap = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Candle c = list.get(i);
            long t = c.start().getEpochSecond() + zone.getRules().getOffset(c.start()).getTotalSeconds();
            candles.add(Map.of("time", t, "open", c.open(), "high", c.high(), "low", c.low(), "close", c.close()));
            volume.add(Map.of("time", t, "value", c.volume(), "color", (c.close() >= c.open() ? ChartData.UP : ChartData.DOWN) + "66"));
            if (!Double.isNaN(vw[i])) {
                vwap.add(Map.of("time", t, "value", round(vw[i])));
            }
        }
        // previous close: the last daily close before the first session shown (the 1D baseline, as on a quote page)
        Double prevClose = null;
        if (!list.isEmpty()) {
            LocalDate firstDay = list.getFirst().start().atZone(zone).toLocalDate();
            List<Bar> daily = cockpit.chartBars(s, firstDay.minusDays(10));
            for (Bar b : daily) {
                if (b.date().isBefore(firstDay)) {
                    prevClose = b.close();
                }
            }
        }
        out.put("from", list.isEmpty() ? null : list.getFirst().start().atZone(zone).format(MINUTE));
        out.put("to", list.isEmpty() ? null : list.getLast().start().atZone(zone).format(MINUTE));
        out.put("candles", candles);
        out.put("volume", volume);
        out.put("sma50", List.of());
        out.put("sma200", List.of());
        out.put("vwap", vwap);
        out.put("markers", List.of());
        out.put("prevClose", prevClose);
        out.put("zone", zone.getId());
        out.put("source", series.source());
        out.put("delayMinutes", series.delayMinutes());
        out.put("lastBar", list.isEmpty() ? null : list.getLast().start().atZone(zone).format(MINUTE));
        out.put("live", !list.isEmpty() && list.getLast().start().isAfter(clock.instant().minus(Duration.ofMinutes(30L + series.delayMinutes()))));
        return out;
    }

    static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
