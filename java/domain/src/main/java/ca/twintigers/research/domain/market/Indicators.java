package ca.twintigers.research.domain.market;

import java.util.Arrays;
import java.util.List;

/**
 * Standard technical indicators on daily bars. Arrays are aligned with the bars; positions without enough
 * history hold {@link Double#NaN}. Definitions follow Wilder (ATR, RSI) and Appel (MACD).
 */
public final class Indicators {

    private Indicators() {
    }

    private static double[] nans(int n) {
        double[] a = new double[n];
        Arrays.fill(a, Double.NaN);
        return a;
    }

    /** Simple moving average of the last n values, including today. */
    public static double[] sma(double[] x, int n) {
        double[] out = nans(x.length);
        double sum = 0;
        for (int i = 0; i < x.length; i++) {
            sum += x[i];
            if (i >= n) {
                sum -= x[i - n];
            }
            if (i >= n - 1) {
                out[i] = sum / n;
            }
        }
        return out;
    }

    /** Exponential moving average, seeded with the SMA of the first n values; alpha = 2 / (n + 1). NaNs in x are skipped. */
    public static double[] ema(double[] x, int n) {
        double[] out = nans(x.length);
        int first = 0;
        while (first < x.length && Double.isNaN(x[first])) {
            first++;
        }
        if (x.length - first < n) {
            return out;
        }
        double seed = 0;
        for (int i = first; i < first + n; i++) {
            seed += x[i];
        }
        double alpha = 2.0 / (n + 1);
        double prev = seed / n;
        out[first + n - 1] = prev;
        for (int i = first + n; i < x.length; i++) {
            prev = alpha * x[i] + (1 - alpha) * prev;
            out[i] = prev;
        }
        return out;
    }

    public static double[] trueRange(List<Bar> bars) {
        double[] tr = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            tr[i] = bars.get(i).trueRange(i == 0 ? null : bars.get(i - 1));
        }
        return tr;
    }

    /** Wilder's average true range: first value is the mean of n true ranges, then (prev × (n − 1) + TR) / n. */
    public static double[] atr(List<Bar> bars, int n) {
        double[] tr = trueRange(bars);
        double[] out = nans(tr.length);
        if (tr.length < n) {
            return out;
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += tr[i];
        }
        out[n - 1] = sum / n;
        for (int i = n; i < tr.length; i++) {
            out[i] = (out[i - 1] * (n - 1) + tr[i]) / n;
        }
        return out;
    }

    /** Wilder's RSI on closes: 100 − 100 / (1 + average gain / average loss), smoothed like ATR. */
    public static double[] rsi(double[] close, int n) {
        double[] out = nans(close.length);
        if (close.length <= n) {
            return out;
        }
        double gain = 0;
        double loss = 0;
        for (int i = 1; i <= n; i++) {
            double ch = close[i] - close[i - 1];
            gain += Math.max(ch, 0);
            loss += Math.max(-ch, 0);
        }
        gain /= n;
        loss /= n;
        out[n] = rsiOf(gain, loss);
        for (int i = n + 1; i < close.length; i++) {
            double ch = close[i] - close[i - 1];
            gain = (gain * (n - 1) + Math.max(ch, 0)) / n;
            loss = (loss * (n - 1) + Math.max(-ch, 0)) / n;
            out[i] = rsiOf(gain, loss);
        }
        return out;
    }

    private static double rsiOf(double gain, double loss) {
        if (loss == 0) {
            return gain == 0 ? 50 : 100;
        }
        return 100 - 100 / (1 + gain / loss);
    }

    /** MACD line (EMA fast − EMA slow), signal line (EMA of MACD) and histogram. */
    public record Macd(double[] line, double[] signal, double[] histogram) {
    }

    public static Macd macd(double[] close, int fast, int slow, int signal) {
        double[] f = ema(close, fast);
        double[] s = ema(close, slow);
        double[] line = nans(close.length);
        for (int i = 0; i < close.length; i++) {
            if (!Double.isNaN(f[i]) && !Double.isNaN(s[i])) {
                line[i] = f[i] - s[i];
            }
        }
        double[] sig = ema(line, signal);
        double[] hist = nans(close.length);
        for (int i = 0; i < close.length; i++) {
            if (!Double.isNaN(line[i]) && !Double.isNaN(sig[i])) {
                hist[i] = line[i] - sig[i];
            }
        }
        return new Macd(line, sig, hist);
    }

    /** Average volume of the n days BEFORE each day (today excluded), so today's volume can be compared with it. */
    public static double[] priorAverageVolume(List<Bar> bars, int n) {
        double[] out = nans(bars.size());
        double sum = 0;
        for (int i = 0; i < bars.size(); i++) {
            if (i >= n) {
                out[i] = sum / n;
                sum -= bars.get(i - n).volume();
            }
            sum += bars.get(i).volume();
        }
        return out;
    }
}
