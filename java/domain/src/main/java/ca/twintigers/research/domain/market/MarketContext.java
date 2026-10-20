package ca.twintigers.research.domain.market;

import java.util.List;

/** Bars for one symbol plus every indicator the rules need, computed once and aligned by index. */
public final class MarketContext {

    public final String symbol;
    public final List<Bar> bars;
    public final PriceRuleConfig cfg;
    public final double[] close;
    public final double[] atr;
    public final double[] sma20;
    public final double[] sma50;
    public final double[] sma200;
    public final double[] rsi;
    public final Indicators.Macd macd;
    public final double[] avgVolume;

    public MarketContext(String symbol, List<Bar> bars, PriceRuleConfig cfg) {
        this.symbol = symbol;
        this.bars = List.copyOf(bars);
        this.cfg = cfg;
        this.close = this.bars.stream().mapToDouble(Bar::close).toArray();
        this.atr = Indicators.atr(this.bars, cfg.atrPeriod());
        this.sma20 = Indicators.sma(close, 20);
        this.sma50 = Indicators.sma(close, 50);
        this.sma200 = Indicators.sma(close, 200);
        this.rsi = Indicators.rsi(close, cfg.rsiPeriod());
        this.macd = Indicators.macd(close, cfg.macdFast(), cfg.macdSlow(), cfg.macdSignal());
        this.avgVolume = Indicators.priorAverageVolume(this.bars, cfg.volumePeriod());
    }

    public Bar bar(int i) {
        return bars.get(i);
    }

    public int size() {
        return bars.size();
    }

    /** Relative volume: today's volume / average of the prior days (NaN without enough history). */
    public double relVolume(int i) {
        double avg = avgVolume[i];
        return Double.isNaN(avg) || avg == 0 ? Double.NaN : bars.get(i).volume() / avg;
    }

    /** Up-trend context at day i: close above its 20-day average and above the close trendLookback days earlier. */
    public boolean upTrend(int i) {
        int j = i - cfg.trendLookback();
        return j >= 0 && !Double.isNaN(sma20[i]) && close[i] > sma20[i] && close[i] > close[j];
    }

    public boolean downTrend(int i) {
        int j = i - cfg.trendLookback();
        return j >= 0 && !Double.isNaN(sma20[i]) && close[i] < sma20[i] && close[i] < close[j];
    }
}
