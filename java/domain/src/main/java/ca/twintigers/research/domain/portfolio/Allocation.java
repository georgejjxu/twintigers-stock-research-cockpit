package ca.twintigers.research.domain.portfolio;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the net worth sits: by holding (cash as one line), by currency and by account. All values in the base
 * currency; percentages of net worth (holdings + cash). A position held in several accounts is split by shares.
 */
public final class Allocation {

    private Allocation() {
    }

    public record Slice(String name, BigDecimal valueInBase, BigDecimal pct) {
    }

    public static List<Slice> byHolding(Portfolio p) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        p.positions().forEach(x -> m.merge(x.symbol(), x.valueInBase(), BigDecimal::add));
        if (p.cashInBase().signum() != 0) {
            m.put("Cash", p.cashInBase());
        }
        return slices(m, p.netWorthInBase());
    }

    /** Positions by their trading currency, cash by its account's currency (needs accounts with cashInBase). */
    public static List<Slice> byCurrency(Portfolio p, List<Account> accounts) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        p.positions().forEach(x -> m.merge(x.currency() == null ? "?" : x.currency(), x.valueInBase(), BigDecimal::add));
        accounts.forEach(a -> {
            if (a.cashInBase() != null && a.cashInBase().signum() != 0) {
                m.merge(a.currency(), a.cashInBase(), BigDecimal::add);
            }
        });
        return slices(m, p.netWorthInBase());
    }

    /** Each account's share of the positions (by shares) plus its cash. */
    public static List<Slice> byAccount(Portfolio p, List<Account> accounts) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (Portfolio.Position x : p.positions()) {
            BigDecimal total = x.sharesByAccount().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.signum() == 0) {
                m.merge("(no account)", x.valueInBase(), BigDecimal::add);
                continue;
            }
            x.sharesByAccount().forEach((acct, n) -> m.merge(acct, x.valueInBase().multiply(n).divide(total, MathContext.DECIMAL64),
                    BigDecimal::add));
        }
        accounts.forEach(a -> {
            if (a.cashInBase() != null && a.cashInBase().signum() != 0) {
                m.merge(a.name(), a.cashInBase(), BigDecimal::add);
            }
        });
        return slices(m, p.netWorthInBase());
    }

    /** Holdings by sector (an ETF split by its sector weights); cash as its own line; unknown as "Not classified". */
    public static List<Slice> bySector(Portfolio p, Map<String, InstrumentProfile> profiles) {
        return byWeights(p, profiles, InstrumentProfile::sectors, k -> k);
    }

    /** Holdings by the issuer's country (an ETF by its country weights). */
    public static List<Slice> byCountry(Portfolio p, Map<String, InstrumentProfile> profiles) {
        return byWeights(p, profiles, InstrumentProfile::countries, InstrumentProfile::countryName);
    }

    /** Holdings by region (North America, Europe, ...). */
    public static List<Slice> byRegion(Portfolio p, Map<String, InstrumentProfile> profiles) {
        return byWeights(p, profiles, InstrumentProfile::countries, InstrumentProfile::region);
    }

    /** Stocks, ETFs, bonds, ... (asset sub-class, else class) and cash. */
    public static List<Slice> byAssetClass(Portfolio p, Map<String, InstrumentProfile> profiles) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (Portfolio.Position x : p.positions()) {
            InstrumentProfile f = profiles.get(x.symbol());
            String name = f == null ? "Not classified" : label(f.assetSubClass() != null ? f.assetSubClass() : f.assetClass());
            m.merge(name, x.valueInBase(), BigDecimal::add);
        }
        if (p.cashInBase().signum() != 0) {
            m.merge("Cash", p.cashInBase(), BigDecimal::add);
        }
        return slices(m, p.netWorthInBase());
    }

    static String label(String code) {
        if (code == null) {
            return "Not classified";
        }
        return switch (code) {
            case "STOCK" -> "Stocks";
            case "ETF" -> "ETFs";
            case "MUTUALFUND" -> "Mutual funds";
            case "BOND", "FIXED_INCOME" -> "Bonds";
            case "CASH", "LIQUIDITY" -> "Cash";
            case "CRYPTOCURRENCY" -> "Crypto";
            case "EQUITY" -> "Stocks";
            default -> code.charAt(0) + code.substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        };
    }

    private static List<Slice> byWeights(Portfolio p, Map<String, InstrumentProfile> profiles,
                                         java.util.function.Function<InstrumentProfile, Map<String, Double>> weights,
                                         java.util.function.Function<String, String> name) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (Portfolio.Position x : p.positions()) {
            InstrumentProfile f = profiles.get(x.symbol());
            Map<String, Double> w = f == null ? Map.of() : weights.apply(f);
            double total = w.values().stream().mapToDouble(Double::doubleValue).sum();
            if (total <= 0) {
                m.merge("Not classified", x.valueInBase(), BigDecimal::add);
                continue;
            }
            w.forEach((k, share) -> m.merge(name.apply(k), x.valueInBase().multiply(BigDecimal.valueOf(share / total)), BigDecimal::add));
        }
        if (p.cashInBase().signum() != 0) {
            m.merge("Cash", p.cashInBase(), BigDecimal::add);
        }
        return slices(m, p.netWorthInBase());
    }

    static List<Slice> slices(Map<String, BigDecimal> m, BigDecimal netWorth) {
        List<Slice> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new Slice(k, v.setScale(2, RoundingMode.HALF_EVEN), Portfolio.pct(v, netWorth))));
        out.sort(Comparator.comparing(Slice::valueInBase).reversed());
        return out;
    }
}
