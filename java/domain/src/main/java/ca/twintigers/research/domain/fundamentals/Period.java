package ca.twintigers.research.domain.fundamentals;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** A reporting period (fiscal year or year-to-date). */
public record Period(LocalDate start, LocalDate end, String label) {

    public long days() {
        return ChronoUnit.DAYS.between(start, end);
    }

    /** The same period one year earlier (364 days, so 52/53-week years line up within tolerance). */
    public Period shiftedBackOneYear(String newLabel) {
        return new Period(start.minusDays(364), end.minusDays(364), newLabel);
    }
}
