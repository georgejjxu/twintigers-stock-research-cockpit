package ca.twintigers.research.domain.portfolio;

import java.util.Map;

/**
 * What a holding is: asset class (EQUITY, FIXED_INCOME, LIQUIDITY, ...), sub-class (STOCK, ETF, ...), and its
 * sector and country weights (a stock: one sector at 1.0; an ETF: its spread). As Ghostfolio's asset profiles hold them.
 */
public record InstrumentProfile(String symbol, String assetClass, String assetSubClass, Map<String, Double> sectors,
                                Map<String, Double> countries) {

    public InstrumentProfile {
        sectors = sectors == null ? Map.of() : Map.copyOf(sectors);
        countries = countries == null ? Map.of() : Map.copyOf(countries);
    }

    /** Country code → name and region, for the countries likely here; others show their code under "Other". */
    public static final Map<String, String[]> COUNTRIES = Map.ofEntries(
            Map.entry("CA", new String[] {"Canada", "North America"}), Map.entry("US", new String[] {"United States", "North America"}),
            Map.entry("MX", new String[] {"Mexico", "Latin America"}), Map.entry("BR", new String[] {"Brazil", "Latin America"}),
            Map.entry("GB", new String[] {"United Kingdom", "Europe"}), Map.entry("DE", new String[] {"Germany", "Europe"}),
            Map.entry("FR", new String[] {"France", "Europe"}), Map.entry("NL", new String[] {"Netherlands", "Europe"}),
            Map.entry("CH", new String[] {"Switzerland", "Europe"}), Map.entry("IE", new String[] {"Ireland", "Europe"}),
            Map.entry("SE", new String[] {"Sweden", "Europe"}), Map.entry("DK", new String[] {"Denmark", "Europe"}),
            Map.entry("ES", new String[] {"Spain", "Europe"}), Map.entry("IT", new String[] {"Italy", "Europe"}),
            Map.entry("JP", new String[] {"Japan", "Asia-Pacific"}), Map.entry("CN", new String[] {"China", "Asia-Pacific (emerging)"}),
            Map.entry("HK", new String[] {"Hong Kong", "Asia-Pacific"}), Map.entry("TW", new String[] {"Taiwan", "Asia-Pacific (emerging)"}),
            Map.entry("KR", new String[] {"South Korea", "Asia-Pacific (emerging)"}), Map.entry("IN", new String[] {"India", "Asia-Pacific (emerging)"}),
            Map.entry("AU", new String[] {"Australia", "Asia-Pacific"}), Map.entry("SG", new String[] {"Singapore", "Asia-Pacific"}));

    public static String countryName(String code) {
        String[] c = COUNTRIES.get(code);
        return c == null ? code : c[0];
    }

    public static String region(String code) {
        String[] c = COUNTRIES.get(code);
        return c == null ? "Other" : c[1];
    }
}
