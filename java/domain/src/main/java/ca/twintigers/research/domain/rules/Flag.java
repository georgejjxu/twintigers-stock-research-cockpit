package ca.twintigers.research.domain.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A fundamental rule that fired.
 *
 * @param ruleId   stable id from the rule catalog, e.g. {@code F04_negative_fcf}
 * @param text     one sentence; every number in it also appears in {@code evidence}
 * @param evidence the inputs that triggered the rule (name to number), so nothing has to be retyped
 */
public record Flag(String ruleId, Severity severity, String text, Map<String, Object> evidence) {

    public Flag {
        evidence = evidence == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }
}
