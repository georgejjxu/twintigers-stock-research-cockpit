package ca.twintigers.research.domain.provenance;

import java.math.BigDecimal;

/**
 * A number in a report. It is either a {@link Fact} (as filed, with its source) or a {@link Calc}
 * (derived, with its formula and inputs). There is no third kind: a figure without a source or a
 * formula cannot be represented.
 */
public sealed interface Cell permits Fact, Calc {

    /** The value, or null when a calculation's inputs are missing. Facts always have a value. */
    BigDecimal value();

    default boolean hasValue() {
        return value() != null;
    }
}
