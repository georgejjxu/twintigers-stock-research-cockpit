package ca.twintigers.research.domain.fundamentals;

import ca.twintigers.research.domain.provenance.Calc;
import ca.twintigers.research.domain.provenance.Fact;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Total debt at a balance-sheet date, assembled from one consistent set of tags per company.
 *
 * <p>Why: companies tag debt in different ways, and sometimes a tag appears only in a footnote for one
 * year. The Python version picked the first tag found per date, so Enbridge's total debt jumped between
 * about C$1B (short-term borrowings alone, or a C$460M footnote value) and C$105B. Here:
 * <ol>
 *   <li>Candidate schemes, in order: (A) {@code LongTermDebtNoncurrent + LongTermDebtCurrent} (or IFRS
 *       borrowings), (B) {@code LongTermDebt} (total incl. current), (C)
 *       {@code LongTermDebtAndCapitalLeaseObligations + ...Current} (includes finance leases).</li>
 *   <li>A scheme is plausible at a date only if its long-term part is at least
 *       {@link #MIN_SHARE_OF_LARGEST} of the largest long-term debt value tagged at that date, which rejects
 *       footnote fragments.</li>
 *   <li>The first scheme that is available and plausible at every column date is used for all columns,
 *       so the row is comparable across years. If none covers every date, each date falls back through
 *       the schemes in order, and the formula says which one was used.</li>
 *   <li>Short-term borrowings and commercial paper are added to whichever scheme is used, but are never
 *       reported alone as total debt.</li>
 *   <li>Only facts in the reporting currency are used.</li>
 * </ol>
 * Lease liabilities are excluded except where scheme C bundles finance leases (stated in the note).
 */
public final class DebtResolver {

    public static final double MIN_SHARE_OF_LARGEST = 0.25;

    enum Scheme {
        A("long_term_noncurrent + long_term_current", Tags.DEBT_NONCURRENT, Tags.DEBT_CURRENT),
        B("long_term_incl_current", Tags.DEBT_TOTAL_LT, List.of()),
        C("long_term_debt_and_finance_leases + current portion", Tags.DEBT_AND_LEASE_NONCURRENT,
                Tags.DEBT_AND_LEASE_CURRENT);

        final String description;
        final List<String> main;
        final List<String> current;

        Scheme(String description, List<String> main, List<String> current) {
            this.description = description;
            this.main = main;
            this.current = current;
        }
    }

    private final FactBook book;
    private final String unit;
    private final Scheme companyScheme;

    public DebtResolver(FactBook book, String unit, List<LocalDate> dates) {
        this.book = book;
        this.unit = unit;
        this.companyScheme = chooseScheme(dates);
    }

    /** Which scheme is used for every column, or a note that it varies by date. */
    public String schemeDescription() {
        return companyScheme != null ? companyScheme.name() + ": " + companyScheme.description
                : "varies by date (no single scheme covers every column)";
    }

    private Scheme chooseScheme(List<LocalDate> dates) {
        for (Scheme s : Scheme.values()) {
            if (!dates.isEmpty() && dates.stream().allMatch(d -> plausible(s, d))) {
                return s;
            }
        }
        return null;
    }

    private Optional<Fact> main(Scheme s, LocalDate at) {
        return book.instant(s.main, at, unit);
    }

    private BigDecimal largestLongTerm(LocalDate at) {
        BigDecimal largest = BigDecimal.ZERO;
        for (Scheme s : Scheme.values()) {
            Optional<Fact> m = main(s, at);
            if (m.isPresent()) {
                BigDecimal v = m.get().value();
                if (!s.current.isEmpty()) {
                    v = v.add(book.instant(s.current, at, unit).map(Fact::value).orElse(BigDecimal.ZERO));
                }
                largest = largest.max(v);
            }
        }
        return largest;
    }

    boolean plausible(Scheme s, LocalDate at) {
        Optional<Fact> m = main(s, at);
        if (m.isEmpty()) {
            return false;
        }
        BigDecimal v = m.get().value();
        if (!s.current.isEmpty()) {
            v = v.add(book.instant(s.current, at, unit).map(Fact::value).orElse(BigDecimal.ZERO));
        }
        BigDecimal largest = largestLongTerm(at);
        return largest.signum() == 0
                || v.doubleValue() >= MIN_SHARE_OF_LARGEST * largest.doubleValue();
    }

    public Calc totalDebt(LocalDate at) {
        Scheme scheme = companyScheme;
        if (scheme == null) {
            for (Scheme s : Scheme.values()) {
                if (plausible(s, at)) {
                    scheme = s;
                    break;
                }
            }
        }
        Map<String, Fact> parts = new LinkedHashMap<>();
        if (scheme != null) {
            switch (scheme) {
                case A -> {
                    parts.put("long_term_noncurrent", main(scheme, at).orElseThrow());
                    book.instant(scheme.current, at, unit).ifPresent(f -> parts.put("long_term_current", f));
                }
                case B -> parts.put("long_term_incl_current", main(scheme, at).orElseThrow());
                case C -> {
                    parts.put("long_term_debt_and_finance_leases", main(scheme, at).orElseThrow());
                    book.instant(scheme.current, at, unit).ifPresent(f -> parts.put("current_debt_and_finance_leases", f));
                }
            }
        }
        if (parts.isEmpty()) {
            List<String> shortTerm = new ArrayList<>();
            book.instant(Tags.SHORT_TERM, at, unit).ifPresent(f -> shortTerm.add("short-term borrowings " + f.value()));
            return Calc.missing("Total debt", "no long-term debt tags found", Map.of(),
                    "No long-term debt tag at this date" + (shortTerm.isEmpty() ? "" : " (" + shortTerm.getFirst()
                            + " found, not reported alone as total debt)")
                            + "; check the balance sheet (the company may have no debt, or label it differently).");
        }
        book.instant(Tags.SHORT_TERM, at, unit).ifPresent(f -> parts.put("short_term_borrowings", f));
        book.instant(Tags.COMMERCIAL_PAPER, at, unit).ifPresent(f -> parts.put("commercial_paper", f));
        BigDecimal sum = parts.values().stream().map(Fact::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> inputs = new LinkedHashMap<>();
        parts.forEach((k, f) -> inputs.put(k, f.id()));
        String note = scheme == Scheme.C
                ? "Includes finance lease obligations (the company tags debt and finance leases together); excludes operating leases."
                : "Excludes lease liabilities. Verify short-term borrowings and commercial paper are not double counted for this company.";
        return new Calc("Total debt", sum, String.join(" + ", parts.keySet()), inputs, note);
    }
}
