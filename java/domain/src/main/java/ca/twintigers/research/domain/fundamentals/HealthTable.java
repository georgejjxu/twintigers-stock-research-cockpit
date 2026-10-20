package ca.twintigers.research.domain.fundamentals;

import ca.twintigers.research.domain.provenance.Cell;
import ca.twintigers.research.domain.rules.Flag;

import java.util.List;
import java.util.Map;

/**
 * The financial health audit: columns (fiscal years oldest first, then the prior-year and current
 * year-to-date), rows of cells (sourced facts or calculations), rule-based flags, and the notes that
 * state the assumptions.
 *
 * @param currency   reporting currency (unit of the revenue / operating cash flow facts)
 * @param debtScheme how total debt was assembled for this company (which tags), see {@link DebtResolver}
 */
public record HealthTable(String company, long cik, String currency, List<Column> columns,
                          Map<String, List<Cell>> rows, List<Flag> flags, List<String> notes,
                          String debtScheme, boolean bank) {

    /** One column of the table. {@code prior} is the period one year earlier, for growth rates. */
    public record Column(Period period, Kind kind, Period prior) {
    }

    public enum Kind { FY, YTD }

    public List<Cell> row(String name) {
        return rows.get(name);
    }
}
