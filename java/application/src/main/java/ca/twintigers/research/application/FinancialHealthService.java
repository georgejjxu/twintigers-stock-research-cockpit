package ca.twintigers.research.application;

import ca.twintigers.research.domain.fundamentals.FactBook;
import ca.twintigers.research.domain.fundamentals.HealthTable;
import ca.twintigers.research.domain.fundamentals.HealthTableBuilder;
import ca.twintigers.research.domain.fundamentals.Thresholds;
import ca.twintigers.research.ports.CompanyDirectory;
import ca.twintigers.research.ports.CompanyDirectory.CompanyRef;
import ca.twintigers.research.ports.CompanyFactsSource;
import ca.twintigers.research.ports.DataSourceException;

/** Use case: the financial health audit for one SEC registrant. */
public final class FinancialHealthService {

    public record HealthReport(CompanyRef company, HealthTable table, String markdown) {
    }

    private final CompanyDirectory directory;
    private final CompanyFactsSource facts;
    private final Thresholds thresholds;

    public FinancialHealthService(CompanyDirectory directory, CompanyFactsSource facts, Thresholds thresholds) {
        this.directory = directory;
        this.facts = facts;
        this.thresholds = thresholds;
    }

    public HealthReport health(String tickerOrCik, int years) throws DataSourceException {
        CompanyRef ref = directory.lookup(tickerOrCik).orElseThrow(() -> new DataSourceException("Ticker "
                + tickerOrCik.toUpperCase(java.util.Locale.ROOT) + " not found in SEC's ticker list (US-listed SEC "
                + "registrants only; TELUS is TU there). Companies that file only in Canada (SEDAR+), such as Kinaxis (KXS), "
                + "have no SEC data."));
        FactBook book = new FactBook(facts.companyFacts(ref.cik()));
        HealthTable table;
        try {
            table = new HealthTableBuilder(book, thresholds).build(Math.max(1, Math.min(years, 5)), ref.sic());
        } catch (IllegalArgumentException e) {
            throw new DataSourceException(e.getMessage(), e);
        }
        return new HealthReport(ref, table, HealthMarkdown.render(table));
    }
}
