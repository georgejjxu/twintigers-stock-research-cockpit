package ca.twintigers.research.ports;

import java.util.List;

/** Finds listings by symbol or company name, for pickers. */
public interface SymbolSearch {

    /**
     * @param symbol   Yahoo form (RY.TO, RY)
     * @param exchange the exchange's display name (Toronto, NYSE, ...)
     */
    record Match(String symbol, String name, String exchange, String type, String sector) {
        public Match(String symbol, String name, String exchange, String type) {
            this(symbol, name, exchange, type, null);
        }
    }

    /** Best matches first; stocks and ETFs only. */
    List<Match> search(String query, int max) throws DataSourceException;
}
