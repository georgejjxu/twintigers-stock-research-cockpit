package ca.twintigers.research.domain.fundamentals;

import ca.twintigers.research.domain.provenance.Fact;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks the right filed value for a period. Port of {@code research_core/financials.py}; same rules:
 * <ul>
 *   <li>{@code fy}/{@code fp} describe the filing, not the period, so periods match on start/end dates
 *       within {@link #TOLERANCE_DAYS} (52/53-week years shift period ends by a few days).</li>
 *   <li>The same period appears in several filings and restatements: the latest-filed value wins
 *       (ties broken by accession number).</li>
 *   <li>Companies switch tags over time, so every metric is a preference-ordered list of tags,
 *       resolved per period: the first tag with a value for that period wins.</li>
 * </ul>
 * Unlike the Python version, lookups can be restricted to one unit (the reporting currency), so a
 * value tagged in EUR in a footnote cannot be mistaken for the CAD balance-sheet figure.
 */
public final class FactBook {

    public static final int TOLERANCE_DAYS = 10;
    /** Canadian and other foreign filers report annually on 40-F/20-F and quarterly on 6-K. */
    public static final List<String> ANNUAL_FORMS = List.of("10-K", "40-F", "20-F");
    public static final List<String> INTERIM_FORMS = List.of("10-Q", "6-K");

    private static final Comparator<Fact> LATEST_FILED =
            Comparator.comparing(Fact::filed).thenComparing(Fact::accession);

    private final CompanyFacts facts;

    public FactBook(CompanyFacts facts) {
        this.facts = facts;
    }

    public long cik() {
        return facts.cik();
    }

    public String name() {
        return facts.name();
    }

    /** "Revenues" means "us-gaap:Revenues"; tags with a taxonomy prefix are used as is. */
    public static String qualify(String concept) {
        return concept.contains(":") ? concept : "us-gaap:" + concept;
    }

    public List<Fact> all(String concept) {
        return facts.byConcept().getOrDefault(qualify(concept), List.of());
    }

    public boolean has(String concept) {
        return !all(concept).isEmpty();
    }

    static boolean close(LocalDate a, LocalDate b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return Math.abs(ChronoUnit.DAYS.between(a, b)) <= TOLERANCE_DAYS;
    }

    private static boolean unitMatches(Fact f, String unit) {
        return unit == null || unit.equals(f.unit());
    }

    /** Latest-filed duration value whose start and end match the period, from the first tag that has one. */
    public Optional<Fact> duration(List<String> concepts, Period period, String unit) {
        if (period == null) {
            return Optional.empty();
        }
        for (String c : concepts) {
            Optional<Fact> hit = all(c).stream()
                    .filter(f -> f.start() != null && close(f.start(), period.start()) && close(f.end(), period.end()))
                    .filter(f -> unitMatches(f, unit))
                    .max(LATEST_FILED);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    /** Latest-filed point-in-time value at a date, from the first tag that has one. */
    public Optional<Fact> instant(List<String> concepts, LocalDate at, String unit) {
        for (String c : concepts) {
            Optional<Fact> hit = all(c).stream()
                    .filter(f -> f.start() == null && close(f.end(), at))
                    .filter(f -> unitMatches(f, unit))
                    .max(LATEST_FILED);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    private static boolean isForm(Fact f, List<String> forms) {
        return forms.stream().anyMatch(f.form()::startsWith);
    }

    /** Most recent n annual periods reported in annual filings, newest first. */
    public List<Period> fiscalYears(List<String> anchorConcepts, int n) {
        List<LocalDate[]> spans = new ArrayList<>();
        for (String c : anchorConcepts) {
            for (Fact f : all(c)) {
                if (f.start() == null || !isForm(f, ANNUAL_FORMS)) {
                    continue;
                }
                long days = ChronoUnit.DAYS.between(f.start(), f.end());
                if (days >= 350 && days <= 380 && spans.stream().noneMatch(s -> close(f.end(), s[1]))) {
                    spans.add(new LocalDate[] {f.start(), f.end()});
                }
            }
            if (!spans.isEmpty()) {
                break;
            }
        }
        spans.sort(Comparator.comparing((LocalDate[] s) -> s[1]).reversed());
        return spans.stream().limit(n).map(s -> new Period(s[0], s[1], "FY ending " + s[1])).toList();
    }

    /** Current fiscal year-to-date period from the latest 10-Q/6-K, plus the prior-year comparable. */
    public Optional<Period[]> latestYtd(List<String> anchorConcepts, Period lastFiscalYear) {
        LocalDate fyStart = lastFiscalYear.end().plusDays(1);
        Fact best = null;
        for (String c : anchorConcepts) {
            for (Fact f : all(c)) {
                if (f.start() != null && isForm(f, INTERIM_FORMS) && close(f.start(), fyStart)
                        && (best == null || f.end().isAfter(best.end()))) {
                    best = f;
                }
            }
            if (best != null) {
                break;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        Period current = new Period(best.start(), best.end(), "YTD to " + best.end());
        Period approx = current.shiftedBackOneYear("prior YTD");
        // Snap to the dates the interim report actually uses for the comparable period.
        Period prior = duration(List.of(best.concept()), approx, null)
                .map(m -> new Period(m.start(), m.end(), "YTD to " + m.end()))
                .orElse(approx);
        return Optional.of(new Period[] {current, prior});
    }
}
