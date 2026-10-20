package ca.twintigers.research.domain.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One transaction: BUY, SELL, DIVIDEND, INTEREST, FEE, ...
 *
 * @param id        the source's identifier (Ghostfolio activity id, local ledger row id); used to edit or delete
 * @param unitPrice price per share in {@code currency} (for a dividend: the amount per share)
 */
public record Activity(String id, LocalDate date, String type, String account, String symbol, BigDecimal quantity,
                       BigDecimal unitPrice, BigDecimal fee, String currency, String comment) {

    public boolean isDividend() {
        return "DIVIDEND".equalsIgnoreCase(type);
    }

    /** The trial import marked made-up purchase prices with "placeholder" in the comment. */
    public boolean isPlaceholder() {
        return comment != null && comment.toLowerCase(java.util.Locale.ROOT).contains("placeholder");
    }
}
