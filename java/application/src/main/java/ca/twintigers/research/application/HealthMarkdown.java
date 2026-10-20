package ca.twintigers.research.application;

import ca.twintigers.research.domain.fundamentals.HealthTable;
import ca.twintigers.research.domain.fundamentals.HealthTable.Column;
import ca.twintigers.research.domain.provenance.Calc;
import ca.twintigers.research.domain.provenance.Cell;
import ca.twintigers.research.domain.provenance.Fact;
import ca.twintigers.research.domain.rules.Flag;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** Renders a {@link HealthTable} as markdown: table with source footnotes, flags, formulas, tags, assumptions. */
public final class HealthMarkdown {

    private HealthMarkdown() {
    }

    public static String render(HealthTable t) {
        Map<String, Integer> sources = new LinkedHashMap<>();
        List<String> sourceText = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        String unit = t.currency() != null ? t.currency() + " millions" : "millions (currency not stated)";
        lines.add("## Financial health: " + t.company() + " (CIK " + t.cik() + ")");
        lines.add("");
        lines.add("Figures in **bold rows** are reported; others are calculated (see Calculations).");
        lines.add("");
        StringBuilder head = new StringBuilder("| " + unit + " unless noted |");
        for (Column c : t.columns()) {
            head.append(' ').append(c.kind()).append(' ').append(c.period().start()).append('–').append(c.period().end()).append(" |");
        }
        lines.add(head.toString());
        lines.add("|---".repeat(t.columns().size() + 1) + "|");
        t.rows().forEach((name, row) -> {
            boolean reported = row.stream().allMatch(x -> x == null || x instanceof Fact);
            StringBuilder sb = new StringBuilder("| " + (reported ? "**" + name + "**" : name) + " |");
            for (Cell cell : row) {
                sb.append(' ').append(fmt(name, cell, sources, sourceText)).append(" |");
            }
            lines.add(sb.toString());
        });
        lines.add("");
        lines.add("### Flags (rule-based; thresholds are assumptions)");
        if (t.flags().isEmpty()) {
            lines.add("- None triggered.");
        }
        for (Flag f : t.flags()) {
            lines.add("- **" + f.severity().label() + "** (" + f.ruleId() + "): " + f.text());
        }
        lines.add("");
        lines.add("### Calculations");
        Map<String, List<String>> formulas = new LinkedHashMap<>();
        Map<String, String> notes = new LinkedHashMap<>();
        for (List<Cell> row : t.rows().values()) {
            for (Cell c : row) {
                if (c instanceof Calc calc && calc.value() != null) {
                    List<String> fs = formulas.computeIfAbsent(calc.name(), k -> new ArrayList<>());
                    if (!fs.contains(calc.formula())) {
                        fs.add(calc.formula());
                    }
                    if (calc.note() != null) {
                        notes.put(calc.name(), calc.note());
                    }
                }
            }
        }
        formulas.forEach((name, fs) -> lines.add("- " + name + " = " + String.join(" or ", fs) + "."
                + (notes.containsKey(name) ? " Note: " + notes.get(name) : "")));
        TreeSet<String> tags = new TreeSet<>();
        t.rows().values().forEach(row -> row.forEach(c -> {
            if (c instanceof Fact f) {
                tags.add(f.concept());
            }
        }));
        if (!tags.isEmpty()) {
            lines.add("");
            lines.add("### XBRL tags used");
            lines.add(String.join(", ", tags.stream().map(x -> "`" + x + "`").toList()));
        }
        lines.add("");
        lines.add("### Assumptions");
        t.notes().forEach(n -> lines.add("- " + n));
        lines.add("");
        lines.add("### Sources");
        lines.addAll(sourceText);
        return String.join("\n", lines);
    }

    private static String fmt(String name, Cell cell, Map<String, Integer> sources, List<String> sourceText) {
        if (cell == null || cell.value() == null) {
            return "–";
        }
        BigDecimal v = cell.value();
        String s;
        if (name.contains("%")) {
            s = String.format(Locale.ROOT, "%.1f%%", v.doubleValue());
        } else if (name.contains("conversion")) {
            s = String.format(Locale.ROOT, "%.2f×", v.doubleValue());
        } else if (name.toLowerCase(Locale.ROOT).contains("shares")) {
            s = String.format(Locale.ROOT, "%,.1fM", v.doubleValue() / 1e6);
        } else {
            s = String.format(Locale.ROOT, "%,.1f", v.doubleValue() / 1e6);
        }
        if (cell instanceof Fact f) {
            Integer n = sources.get(f.accession());
            if (n == null) {
                n = sources.size() + 1;
                sources.put(f.accession(), n);
                sourceText.add("[" + n + "] " + f.sourceText());
            }
            return s + " [" + n + "]";
        }
        return s;
    }
}
