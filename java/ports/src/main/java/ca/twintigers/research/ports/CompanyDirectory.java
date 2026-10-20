package ca.twintigers.research.ports;

import java.util.Optional;

/** Finds SEC registrants by ticker and gives their profile. */
public interface CompanyDirectory {

    /**
     * @param sic SEC Standard Industrial Classification code, null when unknown
     */
    record CompanyRef(long cik, String ticker, String name, Integer sic, String sicDescription) {
    }

    /** Ticker (US listing, e.g. TU for TELUS) or numeric CIK. Empty when SEC does not know it. */
    Optional<CompanyRef> lookup(String tickerOrCik) throws DataSourceException;
}
