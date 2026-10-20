package ca.twintigers.research.domain.market;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Indicators that only make sense within a trading session. */
public final class IntradayIndicators {

    private IntradayIndicators() {
    }

    /**
     * Session VWAP: the volume-weighted average of each bar's typical price (high + low + close) / 3, restarting at
     * each session (exchange-time date). NaN until the session has volume.
     */
    public static double[] vwap(List<Candle> candles, ZoneId zone) {
        double[] out = new double[candles.size()];
        double pv = 0;
        double vol = 0;
        LocalDate session = null;
        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            LocalDate d = c.start().atZone(zone).toLocalDate();
            if (!d.equals(session)) {
                session = d;
                pv = 0;
                vol = 0;
            }
            pv += (c.high() + c.low() + c.close()) / 3 * c.volume();
            vol += c.volume();
            out[i] = vol > 0 ? pv / vol : Double.NaN;
        }
        return out;
    }
}
