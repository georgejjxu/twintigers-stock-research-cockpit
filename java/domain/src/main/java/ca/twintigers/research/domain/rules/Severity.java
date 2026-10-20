package ca.twintigers.research.domain.rules;

/** How much attention a rule's result deserves. Lowest first. */
public enum Severity {
    /** Context, often a bullish counterpart of a warning rule. */
    INFO,
    /** Worth a look; not a problem on its own. */
    WATCH,
    /** A classic warning sign. */
    WARNING;

    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
