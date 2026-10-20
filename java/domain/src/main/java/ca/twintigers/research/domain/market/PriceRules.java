package ca.twintigers.research.domain.market;

import ca.twintigers.research.domain.market.PriceRule.Hit;
import ca.twintigers.research.domain.market.Signal.Direction;
import ca.twintigers.research.domain.rules.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The rule set: price action (PA), candlestick / K-line patterns (K) and momentum (M). Each rule's
 * definition string is the authoritative plain-English description (the rule catalog is generated from it).
 * Reversal patterns require trend context so they are not reported in sideways noise.
 */
public final class PriceRules {

    private PriceRules() {
    }

    public static List<PriceRule> all(PriceRuleConfig c) {
        List<PriceRule> r = new ArrayList<>();
        double topZone = 1 - c.controlCloseLocation();

        // --- price action -----------------------------------------------------------------------------
        r.add(new PriceRule("PA01_buyer_controlled_jump", "Buyer-controlled price jump", Severity.INFO, Direction.BULLISH,
                "Up day (close > open) whose true range is at least " + c.jumpAtrMultiple() + "× the previous day's ATR("
                        + c.atrPeriod() + "), closing in the top " + pct(c.controlCloseLocation()) + " of the day's range, on volume at least "
                        + c.controlRelVolume() + "× the " + c.volumePeriod() + "-day average: buyers pushed price up and held it into the close.",
                (x, i) -> control(x, i, true, topZone)));
        r.add(new PriceRule("PA02_seller_controlled_drop", "Seller-controlled price drop", Severity.WARNING, Direction.BEARISH,
                "Down day (close < open) whose true range is at least " + c.jumpAtrMultiple() + "× the previous day's ATR("
                        + c.atrPeriod() + "), closing in the bottom " + pct(c.controlCloseLocation()) + " of the day's range, on volume at least "
                        + c.controlRelVolume() + "× the " + c.volumePeriod() + "-day average: sellers pushed price down and kept it there.",
                (x, i) -> control(x, i, false, c.controlCloseLocation())));
        r.add(new PriceRule("PA03_gap_up_hold", "Gap up that holds", Severity.INFO, Direction.BULLISH,
                "Opens at least " + c.gapMinPct() + "% above the previous close, the whole day trades above the previous high "
                        + "(gap not filled), closes at or above the open, volume at least " + c.gapRelVolume() + "× average.",
                (x, i) -> gap(x, i, true)));
        r.add(new PriceRule("PA04_gap_down_no_fill", "Gap down that does not fill", Severity.WARNING, Direction.BEARISH,
                "Opens at least " + c.gapMinPct() + "% below the previous close, the whole day trades below the previous low "
                        + "(gap not filled), closes at or below the open, volume at least " + c.gapRelVolume() + "× average.",
                (x, i) -> gap(x, i, false)));
        r.add(new PriceRule("PA05_sustained_decline_on_volume", "Sustained decline on rising volume", Severity.WARNING, Direction.BEARISH,
                c.declineDays() + " lower closes in a row, or " + c.declineDays() + " closes below a falling 20-day average, while "
                        + "average volume over those days is at least " + c.declineVolumeRise() + "× the " + c.volumePeriod()
                        + "-day average before them. Reported on the first day the condition holds.",
                PriceRules::sustainedDecline));
        r.add(new PriceRule("PA06_support_breakdown", "Close below support", Severity.WARNING, Direction.BEARISH,
                "Close falls more than " + c.breakBufferAtr() + "× ATR below a support level (at least " + c.levelMinTouches()
                        + " swing highs/lows within " + c.levelClusterAtr() + "× ATR, from the last " + c.levelLookback()
                        + " days), after the previous " + c.levelConfirmDays() + " closes were all above that line.",
                (x, i) -> levelBreak(x, i, false)));
        r.add(new PriceRule("PA07_resistance_breakout", "Close above resistance", Severity.INFO, Direction.BULLISH,
                "Close rises more than " + c.breakBufferAtr() + "× ATR above a resistance level (same level definition as PA06), "
                        + "after the previous " + c.levelConfirmDays() + " closes were all below that line.",
                (x, i) -> levelBreak(x, i, true)));

        // --- candlestick (K-line) patterns --------------------------------------------------------------
        r.add(new PriceRule("K01_bearish_engulfing", "Bearish engulfing", Severity.WARNING, Direction.BEARISH,
                "After an up-trend, an up day is followed by a down day whose body opens at or above the prior close, "
                        + "closes at or below the prior open, and is larger than the prior body.",
                (x, i) -> engulfing(x, i, false)));
        r.add(new PriceRule("K02_shooting_star", "Shooting star", Severity.WARNING, Direction.BEARISH,
                "After an up-trend, a day with an upper shadow at least " + c.shadowBodyRatio() + "× the body and at least half "
                        + "the range, and a lower shadow at most " + pct(c.smallShadowRange()) + " of the range: buyers were rejected at the high.",
                (x, i) -> shadowCandle(x, i, true, true)));
        r.add(new PriceRule("K03_evening_star", "Evening star", Severity.WARNING, Direction.BEARISH,
                "After an up-trend: a long up day (body ≥ " + c.longBodyAtr() + "× ATR), then a small body (≤ " + c.starBodyRatio()
                        + "× the first) sitting above the first close, then a down day closing below the middle of the first body.",
                (x, i) -> star(x, i, false)));
        r.add(new PriceRule("K04_hanging_man", "Hanging man", Severity.WATCH, Direction.BEARISH,
                "After an up-trend, a day with a lower shadow at least " + c.shadowBodyRatio() + "× the body and at least half "
                        + "the range, and an upper shadow at most " + pct(c.smallShadowRange()) + " of the range: selling appeared during the day.",
                (x, i) -> shadowCandle(x, i, false, true)));
        r.add(new PriceRule("K05_dark_cloud_cover", "Dark cloud cover", Severity.WARNING, Direction.BEARISH,
                "After an up-trend, a long up day is followed by a down day that opens above the prior close and closes "
                        + "below the middle of the prior body (but above its open).",
                PriceRules::darkCloud));
        r.add(new PriceRule("K06_three_black_crows", "Three black crows", Severity.WARNING, Direction.BEARISH,
                "Three down days in a row after a close above the 20-day average, each body at least 0.5× ATR, each opening "
                        + "inside the previous body, each closing lower and in the bottom 30% of its range.",
                PriceRules::threeCrows));
        r.add(new PriceRule("K07_hammer", "Hammer", Severity.INFO, Direction.BULLISH,
                "After a down-trend, a day with a lower shadow at least " + c.shadowBodyRatio() + "× the body and at least half "
                        + "the range, and an upper shadow at most " + pct(c.smallShadowRange()) + " of the range: sellers were rejected at the low.",
                (x, i) -> shadowCandle(x, i, false, false)));
        r.add(new PriceRule("K08_bullish_engulfing", "Bullish engulfing", Severity.INFO, Direction.BULLISH,
                "After a down-trend, a down day is followed by an up day whose body opens at or below the prior close, "
                        + "closes at or above the prior open, and is larger than the prior body.",
                (x, i) -> engulfing(x, i, true)));
        r.add(new PriceRule("K09_morning_star", "Morning star", Severity.INFO, Direction.BULLISH,
                "After a down-trend: a long down day, then a small body sitting below the first close, then an up day "
                        + "closing above the middle of the first body.",
                (x, i) -> star(x, i, true)));

        // --- momentum -----------------------------------------------------------------------------------
        r.add(new PriceRule("M01_rsi_overbought", "RSI turns overbought", Severity.WATCH, Direction.NEUTRAL,
                "RSI(" + c.rsiPeriod() + ") crosses above " + c.rsiOverbought() + ".",
                (x, i) -> rsiCross(x, i, true)));
        r.add(new PriceRule("M02_rsi_oversold", "RSI turns oversold", Severity.INFO, Direction.NEUTRAL,
                "RSI(" + c.rsiPeriod() + ") crosses below " + c.rsiOversold() + ".",
                (x, i) -> rsiCross(x, i, false)));
        r.add(new PriceRule("M03_macd_bearish_cross", "MACD bearish cross", Severity.WATCH, Direction.BEARISH,
                "MACD(" + c.macdFast() + "," + c.macdSlow() + "," + c.macdSignal() + ") line crosses below its signal line.",
                (x, i) -> macdCross(x, i, false)));
        r.add(new PriceRule("M04_macd_bullish_cross", "MACD bullish cross", Severity.INFO, Direction.BULLISH,
                "MACD(" + c.macdFast() + "," + c.macdSlow() + "," + c.macdSignal() + ") line crosses above its signal line.",
                (x, i) -> macdCross(x, i, true)));
        return List.copyOf(r);
    }

    // --- helpers ------------------------------------------------------------------------------------------

    static String pct(double fraction) {
        return f0(fraction * 100) + "%";
    }

    static String f0(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static double r(double v, int places) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return v;
        }
        return java.math.BigDecimal.valueOf(v).setScale(places, java.math.RoundingMode.HALF_EVEN).doubleValue();
    }

    static Map<String, Object> barEvidence(Bar b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("open", r(b.open(), 4));
        m.put("high", r(b.high(), 4));
        m.put("low", r(b.low(), 4));
        m.put("close", r(b.close(), 4));
        m.put("volume", b.volume());
        return m;
    }

    private static boolean ok(double... values) {
        for (double v : values) {
            if (Double.isNaN(v)) {
                return false;
            }
        }
        return true;
    }

    // PA01 / PA02
    static Optional<Hit> control(MarketContext x, int i, boolean up, double closeLocLimit) {
        if (i < 1) {
            return Optional.empty();
        }
        Bar b = x.bar(i);
        Bar p = x.bar(i - 1);
        double atrPrev = x.atr[i - 1];
        double rv = x.relVolume(i);
        if (!ok(atrPrev, rv) || atrPrev == 0) {
            return Optional.empty();
        }
        double tr = b.trueRange(p);
        double loc = b.closeLocation();
        boolean dir = up ? b.bullish() : b.bearish();
        boolean zone = up ? loc >= closeLocLimit : loc <= closeLocLimit;
        if (!(dir && zone && tr >= x.cfg.jumpAtrMultiple() * atrPrev && rv >= x.cfg.controlRelVolume())) {
            return Optional.empty();
        }
        double ret = (b.close() / p.close() - 1) * 100;
        double intraday = (b.close() / b.open() - 1) * 100;
        Map<String, Object> ev = barEvidence(b);
        ev.put("prev_close", r(p.close(), 4));
        ev.put("open_to_close_pct", r(intraday, 2));
        ev.put("change_pct", r(ret, 2));
        ev.put("true_range", r(tr, 4));
        ev.put("atr_prev", r(atrPrev, 4));
        ev.put("range_atr_multiple", r(tr / atrPrev, 2));
        ev.put("close_location", r(loc, 2));
        ev.put("avg_volume", Math.round(x.avgVolume[i]));
        ev.put("rel_volume", r(rv, 2));
        String text = (up ? "Buyer-controlled jump: " : "Seller-controlled drop: ") + (intraday >= 0 ? "+" : "") + f1(intraday)
                + "% open to close (" + (ret >= 0 ? "+" : "") + f1(ret) + "% vs previous close) on " + f1(rv)
                + "× average volume, range " + f1(tr / atrPrev) + "× ATR, closing "
                + (up ? "at " + f0(loc * 100) + "% of the day's range (near the high)." : "at " + f0(loc * 100) + "% of the day's range (near the low).");
        return Optional.of(new Hit(text, ev));
    }

    // PA03 / PA04
    static Optional<Hit> gap(MarketContext x, int i, boolean up) {
        if (i < 1) {
            return Optional.empty();
        }
        Bar b = x.bar(i);
        Bar p = x.bar(i - 1);
        double rv = x.relVolume(i);
        if (!ok(rv)) {
            return Optional.empty();
        }
        double gapPct = up ? (b.open() / p.close() - 1) * 100 : (1 - b.open() / p.close()) * 100;
        boolean unfilled = up ? b.low() > p.high() : b.high() < p.low();
        boolean held = up ? b.close() >= b.open() : b.close() <= b.open();
        if (!(gapPct >= x.cfg.gapMinPct() && unfilled && held && rv >= x.cfg.gapRelVolume())) {
            return Optional.empty();
        }
        Map<String, Object> ev = barEvidence(b);
        ev.put("prev_close", r(p.close(), 4));
        ev.put(up ? "prev_high" : "prev_low", r(up ? p.high() : p.low(), 4));
        ev.put("gap_pct", r(gapPct, 2));
        ev.put("rel_volume", r(rv, 2));
        String text = (up ? "Gap up of " : "Gap down of ") + f1(gapPct) + "% that " + (up ? "held above the previous high" : "stayed below the previous low")
                + " all day, on " + f1(rv) + "× average volume.";
        return Optional.of(new Hit(text, ev));
    }

    // PA05
    static boolean declineCondition(MarketContext x, int i) {
        int n = x.cfg.declineDays();
        int start = i - n + 1;
        if (start - 1 < 0 || start < x.cfg.volumePeriod() || i - n < 0) {
            return false;
        }
        boolean lowerCloses = true;
        boolean belowFalling = !Double.isNaN(x.sma20[i]) && !Double.isNaN(x.sma20[i - n]) && x.sma20[i] < x.sma20[i - n];
        for (int m = start; m <= i; m++) {
            lowerCloses &= x.close[m] < x.close[m - 1];
            belowFalling &= !Double.isNaN(x.sma20[m]) && x.close[m] < x.sma20[m];
        }
        double windowVol = 0;
        for (int m = start; m <= i; m++) {
            windowVol += x.bar(m).volume();
        }
        windowVol /= n;
        double before = x.avgVolume[start];
        return (lowerCloses || belowFalling) && !Double.isNaN(before) && before > 0
                && windowVol >= x.cfg.declineVolumeRise() * before;
    }

    static Optional<Hit> sustainedDecline(MarketContext x, int i) {
        if (!declineCondition(x, i) || (i > 0 && declineCondition(x, i - 1))) {
            return Optional.empty();
        }
        int n = x.cfg.declineDays();
        int start = i - n + 1;
        double windowVol = 0;
        boolean lowerCloses = true;
        for (int m = start; m <= i; m++) {
            windowVol += x.bar(m).volume();
            lowerCloses &= x.close[m] < x.close[m - 1];
        }
        windowVol /= n;
        double before = x.avgVolume[start];
        double change = (x.close[i] / x.close[i - n] - 1) * 100;
        Map<String, Object> ev = barEvidence(x.bar(i));
        ev.put("days", n);
        ev.put("pattern", lowerCloses ? "lower closes in a row" : "closes below a falling 20-day average");
        ev.put("change_pct_over_days", r(change, 2));
        ev.put("close_" + n + "_days_earlier", r(x.close[i - n], 4));
        ev.put("sma20", r(x.sma20[i], 4));
        ev.put("sma20_" + n + "_days_earlier", r(x.sma20[i - n], 4));
        ev.put("avg_volume_window", Math.round(windowVol));
        ev.put("avg_volume_before", Math.round(before));
        ev.put("volume_ratio", r(windowVol / before, 2));
        String text = "Sustained decline: " + n + " " + ev.get("pattern") + ", " + f1(change) + "% over " + n
                + " days, on volume " + f1(windowVol / before) + "× the prior average.";
        return Optional.of(new Hit(text, ev));
    }

    // PA06 / PA07
    static Optional<Hit> levelBreak(MarketContext x, int i, boolean up) {
        if (i < 1 || Double.isNaN(x.atr[i])) {
            return Optional.empty();
        }
        double buffer = x.cfg.breakBufferAtr() * x.atr[i];
        Levels.Level best = null;
        int confirm = x.cfg.levelConfirmDays();
        if (i < confirm) {
            return Optional.empty();
        }
        for (Levels.Level l : Levels.asOf(x, i)) {
            // the previous `confirm` closes were all on the other side, so whipsaws around a line do not repeat
            boolean before = true;
            for (int m = i - confirm; m < i; m++) {
                before &= up ? x.close[m] <= l.price() + buffer : x.close[m] >= l.price() - buffer;
            }
            boolean crossed = before && (up ? x.close[i] > l.price() + buffer : x.close[i] < l.price() - buffer);
            if (crossed && (best == null || (up ? l.price() < best.price() : l.price() > best.price()))) {
                best = l;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        Map<String, Object> ev = barEvidence(x.bar(i));
        ev.put("prev_close", r(x.close[i - 1], 4));
        ev.put("level", r(best.price(), 4));
        ev.put("level_touches", best.touches());
        ev.put("level_swing_dates", best.dates().stream().map(Object::toString).toList());
        ev.put("atr", r(x.atr[i], 4));
        ev.put("buffer", r(buffer, 4));
        String text = (up ? "Closed above resistance at " : "Closed below support at ") + f2(best.price()) + " ("
                + best.touches() + " swing points), close " + f2(x.close[i]) + ", beyond a buffer of " + f2(buffer)
                + " (" + x.cfg.breakBufferAtr() + "× ATR).";
        return Optional.of(new Hit(text, ev));
    }

    private static boolean bigEnough(MarketContext x, int i) {
        return !Double.isNaN(x.atr[i]) && x.bar(i).range() >= x.cfg.minRangeAtr() * x.atr[i];
    }

    // K01 / K08
    static Optional<Hit> engulfing(MarketContext x, int i, boolean bullish) {
        if (i < 1) {
            return Optional.empty();
        }
        Bar p = x.bar(i - 1);
        Bar b = x.bar(i);
        boolean shape = bullish
                ? p.bearish() && b.bullish() && b.open() <= p.close() && b.close() >= p.open() && b.body() > p.body()
                : p.bullish() && b.bearish() && b.open() >= p.close() && b.close() <= p.open() && b.body() > p.body();
        boolean context = bullish ? x.downTrend(i - 1) : x.upTrend(i - 1);
        if (!(shape && context && bigEnough(x, i))) {
            return Optional.empty();
        }
        Map<String, Object> ev = barEvidence(b);
        ev.put("prev_open", r(p.open(), 4));
        ev.put("prev_close", r(p.close(), 4));
        ev.put("body", r(b.body(), 4));
        ev.put("prev_body", r(p.body(), 4));
        ev.put("trend_context", bullish ? "down-trend" : "up-trend");
        return Optional.of(new Hit((bullish ? "Bullish" : "Bearish") + " engulfing: body " + f2(b.body()) + " engulfs the prior body "
                + f2(p.body()) + " after a" + (bullish ? " down" : "n up") + "-trend.", ev));
    }

    // K02 shooting star, K04 hanging man, K07 hammer
    static Optional<Hit> shadowCandle(MarketContext x, int i, boolean upperShadow, boolean afterUpTrend) {
        if (i < 1 || !bigEnough(x, i)) {
            return Optional.empty();
        }
        Bar b = x.bar(i);
        double longS = upperShadow ? b.upperShadow() : b.lowerShadow();
        double shortS = upperShadow ? b.lowerShadow() : b.upperShadow();
        boolean shape = longS >= x.cfg.shadowBodyRatio() * b.body() && longS >= 0.5 * b.range()
                && shortS <= x.cfg.smallShadowRange() * b.range();
        boolean context = afterUpTrend ? x.upTrend(i - 1) : x.downTrend(i - 1);
        if (!(shape && context)) {
            return Optional.empty();
        }
        Map<String, Object> ev = barEvidence(b);
        ev.put("body", r(b.body(), 4));
        ev.put(upperShadow ? "upper_shadow" : "lower_shadow", r(longS, 4));
        ev.put(upperShadow ? "lower_shadow" : "upper_shadow", r(shortS, 4));
        ev.put("range", r(b.range(), 4));
        ev.put("atr", r(x.atr[i], 4));
        ev.put("trend_context", afterUpTrend ? "up-trend" : "down-trend");
        String name = upperShadow ? "Shooting star" : afterUpTrend ? "Hanging man" : "Hammer";
        return Optional.of(new Hit(name + ": " + (upperShadow ? "upper" : "lower") + " shadow " + f2(longS) + " vs body "
                + f2(b.body()) + " (range " + f2(b.range()) + ") after a" + (afterUpTrend ? "n up" : " down") + "-trend.", ev));
    }

    // K03 / K09
    static Optional<Hit> star(MarketContext x, int i, boolean morning) {
        if (i < 2 || Double.isNaN(x.atr[i - 2])) {
            return Optional.empty();
        }
        Bar a = x.bar(i - 2);
        Bar m = x.bar(i - 1);
        Bar b = x.bar(i);
        boolean first = (morning ? a.bearish() : a.bullish()) && a.body() >= x.cfg.longBodyAtr() * x.atr[i - 2];
        boolean middle = m.body() <= x.cfg.starBodyRatio() * a.body() && (morning ? m.bodyMid() < a.close() : m.bodyMid() > a.close());
        boolean last = morning ? b.bullish() && b.close() > a.bodyMid() : b.bearish() && b.close() < a.bodyMid();
        boolean context = morning ? x.downTrend(i - 2) : x.upTrend(i - 2);
        if (!(first && middle && last && context)) {
            return Optional.empty();
        }
        Map<String, Object> ev = barEvidence(b);
        ev.put("day1_open", r(a.open(), 4));
        ev.put("day1_close", r(a.close(), 4));
        ev.put("day2_open", r(m.open(), 4));
        ev.put("day2_close", r(m.close(), 4));
        ev.put("day1_body_mid", r(a.bodyMid(), 4));
        ev.put("atr", r(x.atr[i - 2], 4));
        return Optional.of(new Hit((morning ? "Morning star" : "Evening star") + ": long " + (morning ? "down" : "up")
                + " day, small star, then a close at " + f2(b.close()) + " " + (morning ? "above" : "below")
                + " the first body's middle " + f2(a.bodyMid()) + ".", ev));
    }

    // K05
    static Optional<Hit> darkCloud(MarketContext x, int i) {
        if (i < 1 || Double.isNaN(x.atr[i - 1])) {
            return Optional.empty();
        }
        Bar p = x.bar(i - 1);
        Bar b = x.bar(i);
        boolean shape = p.bullish() && p.body() >= x.cfg.longBodyAtr() * x.atr[i - 1] && b.bearish()
                && b.open() > p.close() && b.close() < p.bodyMid() && b.close() > p.open();
        if (!(shape && x.upTrend(i - 1))) {
            return Optional.empty();
        }
        Map<String, Object> ev = barEvidence(b);
        ev.put("prev_open", r(p.open(), 4));
        ev.put("prev_close", r(p.close(), 4));
        ev.put("prev_body_mid", r(p.bodyMid(), 4));
        return Optional.of(new Hit("Dark cloud cover: opened " + f2(b.open()) + " above the prior close " + f2(p.close())
                + ", closed " + f2(b.close()) + " below the prior body's middle " + f2(p.bodyMid()) + ".", ev));
    }

    // K06
    static Optional<Hit> threeCrows(MarketContext x, int i) {
        if (i < 3 || Double.isNaN(x.atr[i]) || Double.isNaN(x.sma20[i - 3])) {
            return Optional.empty();
        }
        double minBody = 0.5 * x.atr[i];
        for (int k = i - 2; k <= i; k++) {
            Bar b = x.bar(k);
            Bar p = x.bar(k - 1);
            if (!b.bearish() || b.body() < minBody || b.closeLocation() > 0.3 || b.close() >= p.close()) {
                return Optional.empty();
            }
            if (k > i - 2 && !(b.open() <= p.open() && b.open() >= p.close())) {
                return Optional.empty();
            }
        }
        if (!(x.close[i - 3] > x.sma20[i - 3])) {
            return Optional.empty();
        }
        Map<String, Object> ev = barEvidence(x.bar(i));
        ev.put("close_before", r(x.close[i - 3], 4));
        ev.put("sma20_before", r(x.sma20[i - 3], 4));
        ev.put("change_pct_3_days", r((x.close[i] / x.close[i - 3] - 1) * 100, 2));
        ev.put("atr", r(x.atr[i], 4));
        return Optional.of(new Hit("Three black crows: three long down days from " + f2(x.close[i - 3]) + " to "
                + f2(x.close[i]) + " (" + f1((x.close[i] / x.close[i - 3] - 1) * 100) + "%), each closing near its low.", ev));
    }

    // M01 / M02
    static Optional<Hit> rsiCross(MarketContext x, int i, boolean overbought) {
        if (i < 1 || !ok(x.rsi[i], x.rsi[i - 1])) {
            return Optional.empty();
        }
        double lvl = overbought ? x.cfg.rsiOverbought() : x.cfg.rsiOversold();
        boolean cross = overbought ? x.rsi[i - 1] <= lvl && x.rsi[i] > lvl : x.rsi[i - 1] >= lvl && x.rsi[i] < lvl;
        if (!cross) {
            return Optional.empty();
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("close", r(x.close[i], 4));
        ev.put("rsi", r(x.rsi[i], 2));
        ev.put("rsi_prev", r(x.rsi[i - 1], 2));
        ev.put("threshold", lvl);
        return Optional.of(new Hit("RSI(" + x.cfg.rsiPeriod() + ") " + (overbought ? "rose above " : "fell below ") + f0(lvl)
                + ": " + f1(x.rsi[i - 1]) + " → " + f1(x.rsi[i]) + (overbought ? " (overbought)." : " (oversold)."), ev));
    }

    // M03 / M04
    static Optional<Hit> macdCross(MarketContext x, int i, boolean bullish) {
        double[] l = x.macd.line();
        double[] s = x.macd.signal();
        if (i < 1 || !ok(l[i], l[i - 1], s[i], s[i - 1])) {
            return Optional.empty();
        }
        boolean cross = bullish ? l[i - 1] <= s[i - 1] && l[i] > s[i] : l[i - 1] >= s[i - 1] && l[i] < s[i];
        if (!cross) {
            return Optional.empty();
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("close", r(x.close[i], 4));
        ev.put("macd", r(l[i], 4));
        ev.put("signal", r(s[i], 4));
        ev.put("histogram", r(l[i] - s[i], 4));
        return Optional.of(new Hit("MACD crossed " + (bullish ? "above" : "below") + " its signal line (MACD " + f2(l[i])
                + ", signal " + f2(s[i]) + ").", ev));
    }
}
