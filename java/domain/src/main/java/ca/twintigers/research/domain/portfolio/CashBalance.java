package ca.twintigers.research.domain.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An account's cash balance from {@code date} on (until the next entry), in the account's currency. Cash in a
 * foreign currency counts in returns through its exchange-rate moves, as in Ghostfolio; base-currency cash does not.
 */
public record CashBalance(String account, String currency, LocalDate date, BigDecimal balance) {
}
