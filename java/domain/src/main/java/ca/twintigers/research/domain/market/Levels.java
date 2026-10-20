package ca.twintigers.research.domain.market;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Support and resistance levels from swing points.
 *
 * <p>A swing high at day j is a high that is the highest of the {@code pivotHalfWindow} days on each side
 * (a swing low likewise). Swing points (highs and lows together, since old resistance often becomes
 * support) within {@code levelClusterAtr} × ATR of each other form one level, whose price is their average.
 * Only levels with at least {@code levelMinTouches} swing points count.
 */
public final class Levels {

    public record Level(double price, int touches, List<java.time.LocalDate> dates) {
    }

    private Levels() {
    }

    /**
     * Levels known as of the close of day {@code asOf}: swing points from the {@code levelLookback} days
     * before it, confirmed (a full half-window after the point) no later than the day before asOf.
     */
    public static List<Level> asOf(MarketContext ctx, int asOf) {
        PriceRuleConfig c = ctx.cfg;
        int k = c.pivotHalfWindow();
        int from = Math.max(k, asOf - c.levelLookback());
        int to = asOf - 1 - k; // last swing point confirmed by day asOf - 1
        double atr = ctx.atr[asOf];
        if (to < from || Double.isNaN(atr)) {
            return List.of();
        }
        List<double[]> points = new ArrayList<>(); // [price, index]
        for (int j = from; j <= to; j++) {
            Bar b = ctx.bar(j);
            boolean high = true;
            boolean low = true;
            for (int m = j - k; m <= j + k && (high || low); m++) {
                if (m == j) {
                    continue;
                }
                Bar o = ctx.bar(m);
                high &= b.high() > o.high();
                low &= b.low() < o.low();
            }
            if (high) {
                points.add(new double[] {b.high(), j});
            }
            if (low) {
                points.add(new double[] {b.low(), j});
            }
        }
        points.sort(Comparator.comparingDouble(p -> p[0]));
        List<Level> levels = new ArrayList<>();
        double tol = c.levelClusterAtr() * atr;
        int s = 0;
        while (s < points.size()) {
            int e = s;
            while (e + 1 < points.size() && points.get(e + 1)[0] - points.get(s)[0] <= tol) {
                e++;
            }
            if (e - s + 1 >= c.levelMinTouches()) {
                double sum = 0;
                List<java.time.LocalDate> dates = new ArrayList<>();
                for (int m = s; m <= e; m++) {
                    sum += points.get(m)[0];
                    dates.add(ctx.bar((int) points.get(m)[1]).date());
                }
                dates.sort(null);
                levels.add(new Level(sum / (e - s + 1), e - s + 1, dates));
            }
            s = e + 1;
        }
        return levels;
    }
}
