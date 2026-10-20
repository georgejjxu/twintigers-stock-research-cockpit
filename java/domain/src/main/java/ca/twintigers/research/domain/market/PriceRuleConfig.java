package ca.twintigers.research.domain.market;

import java.util.Map;

/**
 * Thresholds for the price-action, candlestick and momentum rules. Defaults are common textbook values;
 * every field can be overridden in the rules configuration file with the key {@code price.<field>}
 * (e.g. {@code price.jumpAtrMultiple=2.0}). See docs/architecture/rule-catalog.md for each rule.
 *
 * @param atrPeriod              ATR length (Wilder)
 * @param volumePeriod           days in the average volume that relative volume is measured against
 * @param jumpAtrMultiple        PA01/PA02: true range must be at least this × the prior day's ATR
 * @param controlCloseLocation   PA01: close in the top (1 − this) of the range; PA02: in the bottom (this)
 * @param controlRelVolume       PA01/PA02: volume at least this × the average volume
 * @param gapMinPct              PA03/PA04: open at least this % beyond the previous close
 * @param gapRelVolume           PA03/PA04: volume at least this × average
 * @param declineDays            PA05: this many lower closes in a row, or closes below a falling SMA20 for this many days
 * @param declineVolumeRise      PA05: average volume over those days at least this × the average before them
 * @param pivotHalfWindow        PA06/PA07: a swing high/low is the extreme of this many days on each side
 * @param levelLookback          PA06/PA07: days of history searched for swing points
 * @param levelClusterAtr        PA06/PA07: swing points within this × ATR form one level
 * @param levelMinTouches        PA06/PA07: a level needs at least this many swing points
 * @param breakBufferAtr         PA06/PA07: close must clear the level by this × ATR
 * @param levelConfirmDays       PA06/PA07: the closes of this many days before the break were all on the other side of the level
 * @param trendLookback          candlestick context: close vs the close this many days earlier
 * @param longBodyAtr            a "long" candle body is at least this × ATR
 * @param shadowBodyRatio        hammer / shooting star family: long shadow at least this × body
 * @param smallShadowRange       ... and the other shadow at most this × range
 * @param minRangeAtr            single-candle patterns need a range of at least this × ATR (filters tiny days)
 * @param starBodyRatio          star patterns: middle body at most this × the first body
 * @param rsiPeriod              RSI length (Wilder)
 * @param rsiOverbought          M01: RSI crosses above this
 * @param rsiOversold            M02: RSI crosses below this
 * @param macdFast               MACD fast EMA
 * @param macdSlow               MACD slow EMA
 * @param macdSignal             MACD signal EMA
 */
public record PriceRuleConfig(int atrPeriod, int volumePeriod, double jumpAtrMultiple, double controlCloseLocation,
                              double controlRelVolume, double gapMinPct, double gapRelVolume, int declineDays,
                              double declineVolumeRise, int pivotHalfWindow, int levelLookback, double levelClusterAtr,
                              int levelMinTouches, double breakBufferAtr, int levelConfirmDays, int trendLookback, double longBodyAtr,
                              double shadowBodyRatio, double smallShadowRange, double minRangeAtr,
                              double starBodyRatio, int rsiPeriod, double rsiOverbought, double rsiOversold,
                              int macdFast, int macdSlow, int macdSignal) {

    public static PriceRuleConfig defaults() {
        return new PriceRuleConfig(14, 20, 1.5, 0.25, 1.5, 0.5, 1.2, 5, 1.2, 5, 120, 0.5, 2, 0.25, 5, 5, 0.6,
                2.0, 0.15, 0.5, 0.3, 14, 70, 30, 12, 26, 9);
    }

    /** Defaults overridden by {@code price.<field>} entries of the map; unknown keys are ignored. */
    public static PriceRuleConfig fromMap(Map<String, String> m) {
        PriceRuleConfig d = defaults();
        return new PriceRuleConfig(
                i(m, "atrPeriod", d.atrPeriod), i(m, "volumePeriod", d.volumePeriod),
                n(m, "jumpAtrMultiple", d.jumpAtrMultiple), n(m, "controlCloseLocation", d.controlCloseLocation),
                n(m, "controlRelVolume", d.controlRelVolume), n(m, "gapMinPct", d.gapMinPct),
                n(m, "gapRelVolume", d.gapRelVolume), i(m, "declineDays", d.declineDays),
                n(m, "declineVolumeRise", d.declineVolumeRise), i(m, "pivotHalfWindow", d.pivotHalfWindow),
                i(m, "levelLookback", d.levelLookback), n(m, "levelClusterAtr", d.levelClusterAtr),
                i(m, "levelMinTouches", d.levelMinTouches), n(m, "breakBufferAtr", d.breakBufferAtr),
                i(m, "levelConfirmDays", d.levelConfirmDays),
                i(m, "trendLookback", d.trendLookback), n(m, "longBodyAtr", d.longBodyAtr),
                n(m, "shadowBodyRatio", d.shadowBodyRatio), n(m, "smallShadowRange", d.smallShadowRange),
                n(m, "minRangeAtr", d.minRangeAtr), n(m, "starBodyRatio", d.starBodyRatio),
                i(m, "rsiPeriod", d.rsiPeriod), n(m, "rsiOverbought", d.rsiOverbought),
                n(m, "rsiOversold", d.rsiOversold), i(m, "macdFast", d.macdFast), i(m, "macdSlow", d.macdSlow),
                i(m, "macdSignal", d.macdSignal));
    }

    private static double n(Map<String, String> m, String k, double def) {
        String v = m.get("price." + k);
        return v == null ? def : Double.parseDouble(v.trim());
    }

    private static int i(Map<String, String> m, String k, int def) {
        String v = m.get("price." + k);
        return v == null ? def : Integer.parseInt(v.trim());
    }
}
