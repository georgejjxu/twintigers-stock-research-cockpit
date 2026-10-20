package ca.twintigers.research.domain.alerts;

import ca.twintigers.research.domain.rules.Severity;

import java.time.Instant;

/**
 * Which rule results notify the user. A rule result (price signal or fundamental flag) notifies when at least
 * one active, unmuted subscription matches it.
 *
 * @param scope       a symbol (ENB.TO) or issuer (ENB) or {@code *} for all holdings
 * @param source      PRICE (the 20 price rules) or FUNDAMENTAL (flags F01–F11)
 * @param rule        a rule id (e.g. K05_dark_cloud_cover), an id prefix ending in {@code *} (e.g. K0*), or {@code *}
 * @param minSeverity results below this severity do not match
 * @param origin      "default" (set from the replay by tune-defaults) or "user"
 */
public record Subscription(long id, String scope, Source source, String rule, Severity minSeverity, String origin,
                           Instant createdAt, Instant mutedUntil, boolean active) {

    public enum Source { PRICE, FUNDAMENTAL }

    public boolean matches(String symbol, Source src, String ruleId, Severity severity, Instant now) {
        if (!active || src != source || severity.compareTo(minSeverity) < 0 || (mutedUntil != null && now.isBefore(mutedUntil))) {
            return false;
        }
        boolean scopeOk = scope.equals("*") || scope.equalsIgnoreCase(symbol)
                || scope.equalsIgnoreCase(ca.twintigers.research.domain.portfolio.Portfolio.issuerOf(symbol));
        boolean ruleOk = rule.equals("*") || rule.equalsIgnoreCase(ruleId)
                || (rule.endsWith("*") && ruleId.toUpperCase(java.util.Locale.ROOT)
                .startsWith(rule.substring(0, rule.length() - 1).toUpperCase(java.util.Locale.ROOT)));
        return scopeOk && ruleOk;
    }

    public String describe() {
        return source.name().toLowerCase(java.util.Locale.ROOT) + " rule " + rule + " at " + minSeverity.label() + "+ for "
                + (scope.equals("*") ? "all holdings" : scope) + " (" + origin + ")";
    }
}
