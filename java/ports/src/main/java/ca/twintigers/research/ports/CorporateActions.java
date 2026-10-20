package ca.twintigers.research.ports;

import ca.twintigers.research.domain.market.CorporateAction;

import java.time.LocalDate;
import java.util.List;

/** Stock splits and cash dividends per symbol (Yahoo form), oldest first. */
public interface CorporateActions {

    List<CorporateAction> corporateActions(String symbol, LocalDate from, LocalDate to) throws DataSourceException;
}
