package ca.twintigers.research.domain.portfolio;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A snapshot of holdings (from Ghostfolio) and the deterministic allocation figures built from it.
 *
 * @param cashInBase sum of account cash balances in the base currency
 */
public record Portfolio(String baseCurrency, List<Position> positions, BigDecimal cashInBase) {

    /**
     * @param symbol        listing symbol as the market data source knows it, e.g. ENB.TO
     * @param valueInBase   market value in the base currency
     * @param sharesByAccount account name to shares
     */
    public record Position(String symbol, String name, String currency, BigDecimal quantity, BigDecimal marketPrice,
                           BigDecimal valueInBase, Map<String, BigDecimal> sharesByAccount) {
    }

    /** One issuer across its listings (ENB.TO + ENB). Percentages are of net worth (positions + cash). */
    public record IssuerWeight(String issuer, String name, List<String> symbols, BigDecimal valueInBase,
                               BigDecimal pctOfNetWorth, boolean overLimit) {
    }

    public Portfolio {
        positions = List.copyOf(positions);
    }

    public BigDecimal investedInBase() {
        return positions.stream().map(Position::valueInBase).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal netWorthInBase() {
        return investedInBase().add(cashInBase);
    }

    /** ENB.TO -> ENB, BRK-B -> BRK-B: the exchange suffix is dropped so cross-listed shares group together. */
    public static String issuerOf(String symbol) {
        int dot = symbol.indexOf('.');
        return (dot > 0 ? symbol.substring(0, dot) : symbol).toUpperCase(Locale.ROOT);
    }

    static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) {
            return null;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, MathContext.DECIMAL64).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** Issuer weights, largest first, flagged when above {@code limitPct} % of net worth. */
    public List<IssuerWeight> issuerWeights(double limitPct) {
        Map<String, List<Position>> groups = new LinkedHashMap<>();
        for (Position p : positions) {
            groups.computeIfAbsent(issuerOf(p.symbol()), k -> new ArrayList<>()).add(p);
        }
        BigDecimal nw = netWorthInBase();
        List<IssuerWeight> out = new ArrayList<>();
        groups.forEach((issuer, ps) -> {
            BigDecimal v = ps.stream().map(Position::valueInBase).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal pct = pct(v, nw);
            out.add(new IssuerWeight(issuer, ps.getFirst().name(), ps.stream().map(Position::symbol).toList(),
                    v.setScale(2, RoundingMode.HALF_EVEN), pct, pct != null && pct.doubleValue() > limitPct));
        });
        out.sort(Comparator.comparing(IssuerWeight::valueInBase).reversed());
        return out;
    }
}
