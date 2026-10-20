package ca.twintigers.research.domain.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Financial-independence arithmetic, as on Ghostfolio's FIRE page: what a withdrawal rate gives from today's net worth,
 * and how the net worth would grow with yearly savings at an assumed real (after-inflation) return. Assumptions,
 * not predictions; the Twintiger Retirement Planner models taxes, pensions and spending in detail.
 */
public final class Fire {

    private Fire() {
    }

    /** One projected year: the net worth at its end and what the withdrawal rate would give from it. */
    public record Year(int year, BigDecimal netWorth, BigDecimal yearlyWithdrawal) {
    }

    /**
     * @param withdrawalYear what {@code withdrawalRatePct} % of today's net worth gives per year
     * @param target         net worth at which the withdrawal rate covers {@code yearlySpending} (null when spending is 0)
     * @param yearsToTarget  first projected year reaching the target (null if not within the horizon)
     */
    public record Result(BigDecimal netWorth, BigDecimal withdrawalYear, BigDecimal withdrawalMonth, BigDecimal target,
                         Integer yearsToTarget, List<Year> projection) {
    }

    public static Result compute(double netWorth, double yearlySavings, double realReturnPct, double withdrawalRatePct,
                                 double yearlySpending, int years, int startYear) {
        double w = withdrawalRatePct / 100;
        double target = yearlySpending > 0 && w > 0 ? yearlySpending / w : Double.NaN;
        List<Year> proj = new ArrayList<>();
        double v = netWorth;
        Integer reached = !Double.isNaN(target) && netWorth >= target ? 0 : null;
        for (int y = 1; y <= years; y++) {
            v = v * (1 + realReturnPct / 100) + yearlySavings;
            proj.add(new Year(startYear + y, money(v), money(v * w)));
            if (reached == null && !Double.isNaN(target) && v >= target) {
                reached = y;
            }
        }
        return new Result(money(netWorth), money(netWorth * w), money(netWorth * w / 12), Double.isNaN(target) ? null : money(target),
                reached, List.copyOf(proj));
    }

    static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_EVEN);
    }
}
