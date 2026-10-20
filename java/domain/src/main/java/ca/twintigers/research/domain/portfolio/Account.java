package ca.twintigers.research.domain.portfolio;

import java.math.BigDecimal;

/**
 * A brokerage account (e.g. "RRSP", "Cash CAD").
 *
 * @param cashBalance cash in the account's own currency
 * @param cashInBase  the same cash in the portfolio's base currency (null when the source cannot convert)
 */
public record Account(String name, String currency, BigDecimal cashBalance, BigDecimal cashInBase) {
}
