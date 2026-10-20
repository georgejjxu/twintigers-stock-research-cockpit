package ca.twintigers.research.domain.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A transaction the user enters or edits (before it is written). {@link #problems} lists everything wrong with it, in
 * plain English, so nothing invalid reaches the holdings source.
 *
 * @param type     BUY, SELL or DIVIDEND
 * @param quantity shares (for a dividend: the shares it was paid on)
 * @param unitPrice price per share (for a dividend: the amount per share), in {@code currency}
 */
public record ActivityDraft(String type, String account, String symbol, LocalDate date, BigDecimal quantity, BigDecimal unitPrice,
                            BigDecimal fee, String currency, String comment) {

    public static final Set<String> TYPES = Set.of("BUY", "SELL", "DIVIDEND");
    public static final Set<String> CURRENCIES = Set.of("CAD", "USD");

    /** Normalized copy: upper-case type, symbol and currency; trimmed text; zero fee when blank. */
    public ActivityDraft normalized() {
        return new ActivityDraft(up(type), account == null ? null : account.strip(), up(symbol), date, quantity, unitPrice,
                fee == null ? BigDecimal.ZERO : fee, up(currency), comment == null || comment.isBlank() ? null : comment.strip());
    }

    private static String up(String s) {
        return s == null ? null : s.strip().toUpperCase(Locale.ROOT);
    }

    public List<String> problems(LocalDate today, List<String> accounts) {
        List<String> p = new ArrayList<>();
        if (type == null || !TYPES.contains(type)) {
            p.add("Type must be buy, sell or dividend.");
        }
        if (account == null || account.isBlank()) {
            p.add("Choose an account.");
        } else if (!accounts.contains(account)) {
            p.add("Unknown account \"" + account + "\". Accounts: " + String.join(", ", accounts) + ".");
        }
        if (symbol == null || !symbol.matches("[A-Z0-9.=^-]{1,20}")) {
            p.add("Symbol must be in Yahoo form, e.g. ENB.TO for the TSX or IBM.");
        }
        if (date == null) {
            p.add("Date is required.");
        } else if (date.isAfter(today)) {
            p.add("Date " + date + " is in the future.");
        } else if (date.isBefore(LocalDate.of(1971, 1, 1))) {
            p.add("Date " + date + " is too early.");
        }
        if (quantity == null || quantity.signum() <= 0) {
            p.add("Shares must be more than 0.");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            p.add("Price must be 0 or more.");
        }
        if (fee != null && fee.signum() < 0) {
            p.add("Fee must be 0 or more.");
        }
        if (currency == null || !CURRENCIES.contains(currency)) {
            p.add("Currency must be CAD or USD.");
        }
        if (comment != null && comment.length() > 500) {
            p.add("Comment is too long (500 characters at most).");
        }
        return p;
    }

    /** One-line summary for confirmations, e.g. "BUY 100 ENB.TO @ 67.50 CAD on 2026-10-14 in RRSP (fee 0)". */
    public String summary() {
        return type + " " + plain(quantity) + " " + symbol + " @ " + plain(unitPrice) + " " + currency + " on " + date + " in " + account
                + " (fee " + plain(fee) + ")" + (comment == null ? "" : " — " + comment);
    }

    static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }
}
