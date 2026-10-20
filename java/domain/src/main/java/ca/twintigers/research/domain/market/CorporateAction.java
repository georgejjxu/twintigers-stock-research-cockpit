package ca.twintigers.research.domain.market;

import java.time.LocalDate;

/**
 * A stock split or a cash dividend, as the market data source reports it.
 *
 * @param date             the split date, or the dividend's ex-date
 * @param splitRatio       new shares per old share (2-for-1 = 2.0; 1-for-10 reverse split = 0.1); 1 for a dividend
 * @param dividendPerShare cash per share in the listing's currency; 0 for a split
 */
public record CorporateAction(String symbol, LocalDate date, Kind kind, double splitRatio, double dividendPerShare) {

    public enum Kind { SPLIT, DIVIDEND }

    public static CorporateAction split(String symbol, LocalDate date, double numerator, double denominator) {
        return new CorporateAction(symbol, date, Kind.SPLIT, numerator / denominator, 0);
    }

    public static CorporateAction dividend(String symbol, LocalDate date, double perShare) {
        return new CorporateAction(symbol, date, Kind.DIVIDEND, 1, perShare);
    }

    public boolean isSplit() {
        return kind == Kind.SPLIT;
    }
}
