package ca.twintigers.research.application;

import ca.twintigers.research.domain.alerts.Alert;
import ca.twintigers.research.domain.alerts.AlertState;
import ca.twintigers.research.domain.alerts.Subscription;
import ca.twintigers.research.domain.market.PriceRule;
import ca.twintigers.research.domain.rules.Severity;
import ca.twintigers.research.ports.AlertStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Use case: The user manages their alerts and rule subscriptions. Validates input; writes only the alert store. */
public final class AlertService {

    static final Set<String> FUNDAMENTAL_RULES = Set.of("F01_revenue_decline", "F02_ytd_revenue_decline", "F03_margin_drop",
            "F04_negative_fcf", "F05_low_cash_conversion", "F06_dilution", "F07_debt_up_cash_down", "F08_fcf_drop",
            "F09_ytd_fcf_drop", "F10_fcf_declining_years", "F11_net_debt_rise");

    private final AlertStore store;
    private final Set<String> priceRuleIds;
    private final Clock clock;

    public AlertService(AlertStore store, List<PriceRule> priceRules, Clock clock) {
        this.store = store;
        this.priceRuleIds = priceRules.stream().map(PriceRule::id).collect(Collectors.toUnmodifiableSet());
        this.clock = clock;
    }

    static String symbol(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("symbol is required (Yahoo form: ENB.TO for the TSX, ENB for NYSE).");
        }
        return s.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * @param kind above | below | move_pct | trailing_atr
     */
    public Alert addThreshold(String kind, String symbol, Double level, Double pct, Double reference, Double atrMultiple,
                              LocalDate since, String note) {
        Alert.Kind k;
        try {
            k = Alert.Kind.valueOf(kind.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("kind must be above, below, move_pct or trailing_atr.");
        }
        switch (k) {
            case ABOVE, BELOW -> require(level != null && level > 0, "level (a price > 0) is required for " + kind + ".");
            case MOVE_PCT -> {
                require(pct != null && pct != 0, "pct (e.g. 10 for +10% or -8 for −8%) is required for move_pct.");
                require(reference != null && reference > 0, "reference (the price to measure from) is required for move_pct.");
            }
            case TRAILING_ATR -> require(atrMultiple != null && atrMultiple > 0 && atrMultiple <= 10,
                    "atr_multiple between 0 and 10 (e.g. 3) is required for trailing_atr.");
        }
        Instant now = clock.instant();
        LocalDate start = k == Alert.Kind.TRAILING_ATR && since == null ? LocalDate.now(clock) : since;
        return store.addAlert(new Alert(0, k, symbol(symbol), level, pct, reference, atrMultiple, start, note, now, null, true,
                AlertState.armed(now)));
    }

    /**
     * @param source price | fundamental
     * @param rule   rule id, id prefix with *, or *
     */
    public Subscription subscribe(String scope, String source, String rule, String minSeverity, String origin) {
        Subscription.Source src;
        try {
            src = Subscription.Source.valueOf(source.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("source must be price or fundamental.");
        }
        String r = rule == null || rule.isBlank() ? "*" : rule.strip();
        if (!r.endsWith("*")) {
            Set<String> known = src == Subscription.Source.PRICE ? priceRuleIds : FUNDAMENTAL_RULES;
            require(known.contains(r), "Unknown " + src.name().toLowerCase(Locale.ROOT) + " rule " + r + ". Known: "
                    + known.stream().sorted().collect(Collectors.joining(", ")));
        }
        Severity sev = Severity.valueOf((minSeverity == null || minSeverity.isBlank() ? "info" : minSeverity).strip().toUpperCase(Locale.ROOT));
        String sc = scope == null || scope.isBlank() || scope.strip().equals("*") ? "*" : symbol(scope);
        return store.addSubscription(new Subscription(0, sc, src, r, sev, origin == null ? "user" : origin, clock.instant(), null, true));
    }

    /**
     * Replaces the "default" subscriptions from replay statistics: every warning-severity price rule that fired at least
     * {@code minFires} times and went the rule's way at least {@code minHitPct} % of the time 20 days later, plus all
     * fundamental flags at warning severity. User subscriptions are not touched.
     *
     * @return the price rules chosen
     */
    public List<String> tuneDefaults(List<ca.twintigers.research.domain.market.RuleEngine.RuleStats> combined,
                                     double minHitPct, int minFires) {
        store.removeDefaultSubscriptions();
        List<String> chosen = combined.stream()
                .filter(s -> s.severity().equals(Severity.WARNING.label()) && s.fires() >= minFires
                        && s.hitRate20d() != null && s.hitRate20d() >= minHitPct)
                .map(ca.twintigers.research.domain.market.RuleEngine.RuleStats::ruleId).toList();
        chosen.forEach(r -> subscribe("*", "price", r, "warning", "default"));
        subscribe("*", "fundamental", "*", "warning", "default");
        return chosen;
    }

    public List<Alert> alerts() {
        return store.alerts(false);
    }

    public List<Subscription> subscriptions() {
        return store.subscriptions(false);
    }

    /** @param target "alert" or "subscription" */
    public boolean remove(String target, long id) {
        return isAlert(target) ? store.removeAlert(id) : store.removeSubscription(id);
    }

    public boolean mute(String target, long id, Duration duration) {
        Instant until = clock.instant().plus(duration);
        return isAlert(target) ? store.muteAlert(id, until) : store.muteSubscription(id, until);
    }

    private static boolean isAlert(String target) {
        String t = target == null ? "alert" : target.strip().toLowerCase(Locale.ROOT);
        if (!t.equals("alert") && !t.equals("subscription")) {
            throw new IllegalArgumentException("target must be alert or subscription.");
        }
        return t.equals("alert");
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
