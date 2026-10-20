package ca.twintigers.research.domain.portfolio;

import java.math.BigDecimal;

/**
 * Return over a range, as computed by the portfolio source (it holds the cost basis and the history).
 *
 * @param range               1d, wtd, mtd, ytd, 1y, 5y, max or a year
 * @param netPerformancePct   net return in %, including currency effect, as the source defines it
 * @param method              how the source computes it, e.g. "Ghostfolio ROAI"
 */
public record Performance(String range, BigDecimal valueInBase, BigDecimal totalInvestment, BigDecimal netPerformance,
                          BigDecimal netPerformancePct, String method) {
}
