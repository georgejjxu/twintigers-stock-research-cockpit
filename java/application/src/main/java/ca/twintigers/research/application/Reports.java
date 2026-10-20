package ca.twintigers.research.application;

import ca.twintigers.research.application.PortfolioReviewService.IssuerReview;
import ca.twintigers.research.application.PortfolioReviewService.Review;
import ca.twintigers.research.application.PortfolioReviewService.SymbolSignals;
import ca.twintigers.research.application.PriceSignalService.SignalReport;
import ca.twintigers.research.domain.market.PriceRule;
import ca.twintigers.research.domain.market.RuleEngine;
import ca.twintigers.research.domain.market.Signal;
import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.domain.rules.Flag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Markdown for signals, replays, the portfolio review and the rule catalog. Numbers come from the reports only. */
public final class Reports {

    private Reports() {
    }

    private static String esc(String s) {
        return s == null ? "–" : s.replace("|", "\\|");
    }

    private static String money(java.math.BigDecimal v) {
        return v == null ? "–" : String.format(Locale.ROOT, "%,.2f", v.doubleValue());
    }

    public static String signals(SignalReport r) {
        List<String> l = new ArrayList<>();
        l.add("## Price signals: " + r.symbol() + " (" + r.from() + " to " + r.asOf() + ")");
        l.add("");
        l.add("Last bar " + r.asOf() + ": open " + r.lastBar().open() + ", high " + r.lastBar().high() + ", low "
                + r.lastBar().low() + ", close " + r.lastBar().close() + ", volume " + String.format(Locale.ROOT, "%,d", r.lastBar().volume())
                + ". Source: " + r.source() + ".");
        l.add("");
        if (r.signals().isEmpty()) {
            l.add("No rule fired in this window.");
        } else {
            l.add("| Date | Severity | Rule | What happened |");
            l.add("|---|---|---|---|");
            for (Signal s : r.signals()) {
                l.add("| " + s.date() + " | " + s.severity().label() + " | " + s.ruleId() + " | " + esc(s.text()) + " |");
            }
        }
        l.add("");
        l.add("Rules are mechanical pattern checks on daily bars (definitions: rule catalog); they describe what price "
                + "and volume did, not what they will do. Not investment advice.");
        return String.join("\n", l);
    }

    public static String replay(String symbol, String source, RuleEngine.Replay rep) {
        List<String> l = new ArrayList<>();
        l.add("## Rule replay: " + symbol + " (" + rep.from() + " to " + rep.to() + ", " + rep.days() + " trading days)");
        l.add("");
        l.add("| Rule | Severity | Direction | Fired | Avg 5-day move % | Avg 20-day move % | Went the rule's way, 5d % | 20d % |");
        l.add("|---|---|---|---:|---:|---:|---:|---:|");
        for (RuleEngine.RuleStats s : rep.stats()) {
            l.add("| " + s.ruleId() + " | " + s.severity() + " | " + s.direction() + " | " + s.fires() + " | "
                    + n(s.avgReturn5d()) + " | " + n(s.avgReturn20d()) + " | " + n(s.hitRate5d()) + " | " + n(s.hitRate20d()) + " |");
        }
        l.add("");
        l.add("Moves are close-to-close % changes after the signal day. \"Went the rule's way\" = fell after a bearish rule, "
                + "rose after a bullish one. Source: " + source + ". Small counts are not statistically meaningful.");
        return String.join("\n", l);
    }

    /** All symbols' replays combined per rule: total fires, fire-weighted average moves and hit rates. */
    public static List<RuleEngine.RuleStats> combine(List<RuleEngine.Replay> replays) {
        List<RuleEngine.RuleStats> out = new ArrayList<>();
        if (replays.isEmpty()) {
            return out;
        }
        int rules = replays.getFirst().stats().size();
        for (int k = 0; k < rules; k++) {
            RuleEngine.RuleStats first = replays.getFirst().stats().get(k);
            int fires = 0;
            double[] sum = new double[4];
            int[] w = new int[4];
            for (RuleEngine.Replay r : replays) {
                RuleEngine.RuleStats s = r.stats().get(k);
                fires += s.fires();
                Double[] v = {s.avgReturn5d(), s.avgReturn20d(), s.hitRate5d(), s.hitRate20d()};
                for (int j = 0; j < 4; j++) {
                    if (v[j] != null) {
                        sum[j] += v[j] * s.fires();
                        w[j] += s.fires();
                    }
                }
            }
            Double[] avg = new Double[4];
            for (int j = 0; j < 4; j++) {
                avg[j] = w[j] == 0 ? null : Math.round(sum[j] / w[j] * 100) / 100.0;
            }
            out.add(new RuleEngine.RuleStats(first.ruleId(), first.ruleName(), first.severity(), first.direction(), fires,
                    avg[0], avg[1], avg[2], avg[3]));
        }
        return out;
    }

    public static String combinedReplay(List<String> symbols, List<RuleEngine.RuleStats> stats) {
        List<String> l = new ArrayList<>();
        l.add("## Rule replay, all of " + String.join(", ", symbols) + " combined");
        l.add("");
        l.add("| Rule | Severity | Direction | Fired | Avg 5-day move % | Avg 20-day move % | Went the rule's way, 5d % | 20d % |");
        l.add("|---|---|---|---:|---:|---:|---:|---:|");
        for (RuleEngine.RuleStats s : stats) {
            l.add("| " + s.ruleId() + " | " + s.severity() + " | " + s.direction() + " | " + s.fires() + " | "
                    + n(s.avgReturn5d()) + " | " + n(s.avgReturn20d()) + " | " + n(s.hitRate5d()) + " | " + n(s.hitRate20d()) + " |");
        }
        l.add("");
        l.add("Averages are weighted by the number of fires per symbol (approximate: signals near the end of the history have "
                + "no 20-day outcome yet). A rule near 50% has no edge on these stocks; below 50% means price more often did the opposite.");
        return String.join("\n", l);
    }

    private static String n(Double d) {
        return d == null ? "–" : String.format(Locale.ROOT, "%.2f", d);
    }

    public static String review(Review r) {
        List<String> l = new ArrayList<>();
        String cur = r.baseCurrency() == null ? "" : r.baseCurrency();
        l.add("## Portfolio review (" + cur + ")");
        l.add("");
        l.add("Invested " + money(r.investedInBase()) + ", cash " + money(r.cashInBase()) + ", net worth " + money(r.netWorthInBase()) + " " + cur + ".");
        l.add("");
        l.add("| Issuer | Listings | Value | % net worth | Over " + r.concentrationLimitPct() + "% | Fundamental flags |");
        l.add("|---|---|---:|---:|---|---|");
        for (IssuerReview i : r.issuers()) {
            String flags = i.healthError() != null ? "n/a: " + i.healthError()
                    : i.healthFlags().isEmpty() ? "none" : String.join("; ", i.healthFlags().stream()
                    .map(f -> f.severity().label() + " " + f.ruleId() + ": " + f.text()).toList());
            Portfolio.IssuerWeight w = i.weight();
            l.add("| " + w.issuer() + " | " + String.join(", ", w.symbols()) + " | " + money(w.valueInBase()) + " | "
                    + (w.pctOfNetWorth() == null ? "–" : w.pctOfNetWorth() + "%") + " | " + (w.overLimit() ? "YES" : "no") + " | " + esc(flags) + " |");
        }
        l.add("");
        l.add("### Price signals, last " + r.signalLookbackDays() + " days");
        l.add("");
        l.add("| Symbol | As of | Date | Severity | Rule | What happened |");
        l.add("|---|---|---|---|---|---|");
        for (SymbolSignals s : r.priceSignals()) {
            if (s.error() != null) {
                l.add("| " + s.symbol() + " | – | – | – | – | n/a: " + esc(s.error()) + " |");
            } else if (s.signals().isEmpty()) {
                l.add("| " + s.symbol() + " | " + s.asOf() + " | – | – | – | no rule fired |");
            }
            for (Signal g : s.signals()) {
                l.add("| " + s.symbol() + " | " + s.asOf() + " | " + g.date() + " | " + g.severity().label() + " | " + g.ruleId() + " | " + esc(g.text()) + " |");
            }
        }
        l.add("");
        l.add("Values from Ghostfolio (delayed prices); fundamentals from SEC filings; signals from daily bars. Not investment advice.");
        return String.join("\n", l);
    }

    /** The rule catalog, generated from the rule definitions so documentation cannot drift from code. */
    public static String catalog(List<PriceRule> priceRules, List<String[]> fundamentalRules) {
        List<String> l = new ArrayList<>();
        l.add("| Id | Name | Severity | Direction | Definition |");
        l.add("|---|---|---|---|---|");
        for (PriceRule r : priceRules) {
            l.add("| " + r.id() + " | " + r.name() + " | " + r.severity().label() + " | " + r.direction().name().toLowerCase(Locale.ROOT)
                    + " | " + esc(r.definition()) + " |");
        }
        if (!fundamentalRules.isEmpty()) {
            l.add("");
            l.add("| Id | Severity | Definition |");
            l.add("|---|---|---|");
            fundamentalRules.forEach(f -> l.add("| " + f[0] + " | " + f[1] + " | " + esc(f[2]) + " |"));
        }
        return String.join("\n", l);
    }

    public static String flagsLine(List<Flag> flags) {
        return flags.isEmpty() ? "none" : String.join("; ", flags.stream().map(f -> f.ruleId() + ": " + f.text()).toList());
    }
}
