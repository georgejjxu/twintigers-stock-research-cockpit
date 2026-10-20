package ca.twintigers.research.domain.provenance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One value as reported in an SEC filing (XBRL companyfacts).
 *
 * @param concept   XBRL tag actually used, e.g. {@code us-gaap:OperatingIncomeLoss}
 * @param unit      "USD", "CAD", "shares", ...
 * @param start     null for point-in-time (balance sheet) values
 * @param accession SEC accession number of the filing, e.g. {@code 0000320193-23-000106}
 */
public record Fact(String concept, BigDecimal value, String unit, LocalDate end, LocalDate start,
                   String form, String accession, LocalDate filed, long cik) implements Cell {

    public Fact {
        Objects.requireNonNull(concept, "concept");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(accession, "accession");
        Objects.requireNonNull(filed, "filed");
        form = form == null ? "" : form;
    }

    public boolean isInstant() {
        return start == null;
    }

    /** Stable identifier: tag, period and filing. */
    public String id() {
        String period = start != null ? start + ".." + end : "@" + end;
        return concept + "|" + period + "|" + accession;
    }

    /** Link to the filing index on sec.gov. */
    public String url() {
        return "https://www.sec.gov/Archives/edgar/data/" + cik + "/" + accession.replace("-", "") + "/"
                + accession + "-index.htm";
    }

    public String sourceText() {
        return form + " filed " + filed + ", accession " + accession + ": " + url();
    }
}
