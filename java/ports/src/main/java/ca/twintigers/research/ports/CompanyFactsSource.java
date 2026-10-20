package ca.twintigers.research.ports;

import ca.twintigers.research.domain.fundamentals.CompanyFacts;

/** All XBRL facts SEC holds for a company. */
public interface CompanyFactsSource {

    CompanyFacts companyFacts(long cik) throws DataSourceException;
}
