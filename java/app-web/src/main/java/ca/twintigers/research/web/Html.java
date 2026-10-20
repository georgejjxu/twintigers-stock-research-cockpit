package ca.twintigers.research.web;

import java.math.BigDecimal;
import java.util.Locale;

/** HTML escaping and number formatting. Every value that reaches a page goes through {@link #e}. */
final class Html {

    private Html() {
    }

    /** Escapes text for HTML content and attribute values. */
    static String e(Object o) {
        if (o == null) {
            return "";
        }
        String s = o.toString();
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    static String money(BigDecimal v) {
        return v == null ? "–" : String.format(Locale.ROOT, "%,.2f", v.doubleValue());
    }

    static String money(Double v) {
        return v == null || v.isNaN() ? "–" : String.format(Locale.ROOT, "%,.2f", v);
    }

    static String price(double v) {
        return Double.isNaN(v) ? "–" : String.format(Locale.ROOT, "%,.2f", v);
    }

    static String pct(Double v) {
        return v == null || v.isNaN() ? "–" : String.format(Locale.ROOT, "%.2f%%", v);
    }

    static String signedPct(Double v) {
        return v == null || v.isNaN() ? "–" : String.format(Locale.ROOT, "%+.2f%%", v);
    }

    static String pct(BigDecimal v) {
        return v == null ? "–" : String.format(Locale.ROOT, "%.2f%%", v.doubleValue());
    }

    static String shares(BigDecimal v) {
        if (v == null) {
            return "–";
        }
        return v.stripTrailingZeros().scale() <= 0 ? String.format(Locale.ROOT, "%,d", v.longValue()) : v.stripTrailingZeros().toPlainString();
    }

    /** URL path segment for a symbol (letters, digits, . = ^ - only, so no encoding surprises). */
    static String sym(String symbol) {
        return symbol.replaceAll("[^A-Za-z0-9.=^-]", "");
    }
}
