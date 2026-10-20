package ca.twintigers.research.domain.fundamentals;

import ca.twintigers.research.domain.provenance.Fact;

import java.util.List;
import java.util.Map;

/**
 * Everything SEC has tagged for one company, already converted to {@link Fact}s.
 *
 * @param byConcept "taxonomy:Concept" (e.g. {@code us-gaap:Revenues}) to all its reported values
 */
public record CompanyFacts(long cik, String name, Map<String, List<Fact>> byConcept) {

    public CompanyFacts {
        byConcept = Map.copyOf(byConcept);
        name = name == null ? "" : name;
    }
}
