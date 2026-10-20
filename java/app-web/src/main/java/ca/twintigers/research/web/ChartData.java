package ca.twintigers.research.web;

import ca.twintigers.research.application.CockpitService;
import ca.twintigers.research.domain.alerts.Alert;
import ca.twintigers.research.domain.market.Bar;
import ca.twintigers.research.domain.market.Signal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON for the K-line chart (TradingView Lightweight Charts): candles, volume, 20-day average, signal markers and
 * alert price lines. Colours are the Twintiger chart tokens (teal up, coral down).
 */
final class ChartData {

    static final String UP = "#167b73";
    static final String DOWN = "#da6655";
    static final String GOLD = "#e0a43b";
    static final String BLUE = "#3268a8";
    static final String NEUTRAL = "#7b878a";

    private ChartData() {
    }

    static Map<String, Object> of(CockpitService.StockView v) {
        return of(v, Map.of());
    }

    /** @param definitions rule id to definition, for the "why it fired" panel */
    static Map<String, Object> of(CockpitService.StockView v, Map<String, String> definitions) {
        List<Map<String, Object>> candles = new ArrayList<>();
        List<Map<String, Object>> volume = new ArrayList<>();
        List<Map<String, Object>> sma = new ArrayList<>();
        for (int i = 0; i < v.bars().size(); i++) {
            Bar b = v.bars().get(i);
            String t = b.date().toString();
            candles.add(Map.of("time", t, "open", b.open(), "high", b.high(), "low", b.low(), "close", b.close()));
            volume.add(Map.of("time", t, "value", b.volume(), "color", (b.close() >= b.open() ? UP : DOWN) + "66"));
            if (i < v.sma20().length && !Double.isNaN(v.sma20()[i])) {
                sma.add(Map.of("time", t, "value", Math.round(v.sma20()[i] * 10000) / 10000.0));
            }
        }
        List<Map<String, Object>> markers = new ArrayList<>();
        if (v.signals() != null) {
            for (Signal g : v.signals().signals()) {
                markers.add(marker(g, v.bars(), definitions.get(g.ruleId())));
            }
        }
        List<Map<String, Object>> lines = alertLines(v.alerts());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", v.symbol());
        out.put("candles", candles);
        out.put("volume", volume);
        out.put("sma20", sma);
        out.put("markers", markers);
        out.put("alertLines", lines);
        out.put("colors", Map.of("up", UP, "down", DOWN, "sma", BLUE, "text", "#12212e", "grid", "#d9e1e4"));
        return out;
    }

    /** A signal's chart marker, with its explanation (the bars it used, its levels and numbers). */
    static Map<String, Object> marker(Signal g, List<Bar> bars, String definition) {
        boolean bear = g.direction() == Signal.Direction.BEARISH;
        boolean bull = g.direction() == Signal.Direction.BULLISH;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", g.date().toString());
        m.put("position", bear ? "aboveBar" : bull ? "belowBar" : "inBar");
        m.put("color", bear ? DOWN : bull ? UP : GOLD);
        m.put("shape", bear ? "arrowDown" : bull ? "arrowUp" : "circle");
        m.put("text", g.ruleId().substring(0, g.ruleId().indexOf('_')));
        m.put("severity", g.severity().label());
        m.put("rule", g.ruleName());
        m.put("detail", g.text());
        m.put("id", g.ruleId());
        m.put("explain", SignalExplainer.explain(g, bars, definition));
        return m;
    }

    /** Dashed price lines for the user's above/below and % move alerts. */
    static List<Map<String, Object>> alertLines(List<Alert> alerts) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Alert a : alerts) {
            Double price = switch (a.kind()) {
                case ABOVE, BELOW -> a.level();
                case MOVE_PCT -> a.reference() == null || a.pct() == null ? null : a.reference() * (1 + a.pct() / 100);
                case TRAILING_ATR -> null;
            };
            if (price != null) {
                Map<String, Object> line = new LinkedHashMap<>(); // ordered, so the JSON is stable
                line.put("price", price);
                line.put("color", a.kind() == Alert.Kind.BELOW || (a.pct() != null && a.pct() < 0) ? BLUE : GOLD);
                line.put("title", "#" + a.id() + " " + a.kind().name().toLowerCase(java.util.Locale.ROOT));
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Intraday bars for the chart. Times are shifted to the exchange's wall clock (seconds since 1970 as if that clock
     * were UTC), because the chart library shows times in UTC; the axis then reads 09:30 for the open in Toronto.
     */
    static Map<String, Object> intraday(ca.twintigers.research.ports.IntradayData.Series s, List<Alert> alerts) {
        List<Map<String, Object>> candles = new ArrayList<>();
        List<Map<String, Object>> volume = new ArrayList<>();
        List<Map<String, Object>> vwap = new ArrayList<>();
        double[] vw = ca.twintigers.research.domain.market.IntradayIndicators.vwap(s.candles(), s.exchangeZone());
        for (int i = 0; i < s.candles().size(); i++) {
            var c = s.candles().get(i);
            long t = c.start().getEpochSecond() + s.exchangeZone().getRules().getOffset(c.start()).getTotalSeconds();
            candles.add(Map.of("time", t, "open", c.open(), "high", c.high(), "low", c.low(), "close", c.close()));
            volume.add(Map.of("time", t, "value", c.volume(), "color", (c.close() >= c.open() ? UP : DOWN) + "66"));
            if (!Double.isNaN(vw[i])) {
                vwap.add(Map.of("time", t, "value", Math.round(vw[i] * 10000) / 10000.0));
            }
        }
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", s.symbol());
        out.put("interval", s.interval().code());
        out.put("candles", candles);
        out.put("volume", volume);
        out.put("vwap", vwap);
        out.put("alertLines", alertLines(alerts));
        out.put("source", s.source());
        out.put("delayMinutes", s.delayMinutes());
        out.put("zone", s.exchangeZone().getId());
        out.put("lastBar", s.candles().isEmpty() ? null : s.candles().getLast().start().atZone(s.exchangeZone()).format(f));
        out.put("asOf", s.asOf().atZone(s.exchangeZone()).format(f));
        // still moving: the newest bar started within the last 30 minutes (plus the feed's delay)
        out.put("live", !s.candles().isEmpty() && s.candles().getLast().start()
                .isAfter(s.asOf().minus(java.time.Duration.ofMinutes(30L + s.delayMinutes()))));
        out.put("colors", Map.of("up", UP, "down", DOWN, "vwap", GOLD, "text", "#12212e", "grid", "#d9e1e4"));
        return out;
    }
}
