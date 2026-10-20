package ca.twintigers.research.application;

import ca.twintigers.research.domain.portfolio.Portfolio;
import ca.twintigers.research.domain.portfolio.Portfolio.IssuerWeight;
import ca.twintigers.research.domain.rules.Flag;
import ca.twintigers.research.domain.market.Signal;
import ca.twintigers.research.ports.DataSourceException;
import ca.twintigers.research.ports.PortfolioSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Use case: "review my portfolio". Holdings from the portfolio source, issuer concentration, and for each
 * issuer its fundamental flags (SEC) and each listing's recent price signals.
 */
public final class PortfolioReviewService {

    /**
     * @param healthSource "SEC EDGAR" or why fundamentals are not available
     */
    public record IssuerReview(IssuerWeight weight, String secTicker, String healthSource, List<Flag> healthFlags,
                               String healthError) {
    }

    public record SymbolSignals(String symbol, String asOf, List<Signal> signals, String error) {
    }

    public record Review(String baseCurrency, BigDecimal investedInBase, BigDecimal cashInBase, BigDecimal netWorthInBase,
                         double concentrationLimitPct, List<Portfolio.Position> positions, List<IssuerReview> issuers,
                         List<SymbolSignals> priceSignals, int signalLookbackDays) {
    }

    private final PortfolioSource portfolio;
    private final FinancialHealthService health;
    private final PriceSignalService prices;
    private final Map<String, String> secTickers;

    /**
     * @param secTickers issuer symbol to SEC ticker where they differ (TELUS: T → TU); an empty value means
     *                   "not an SEC registrant"
     */
    public PortfolioReviewService(PortfolioSource portfolio, FinancialHealthService health, PriceSignalService prices,
                                  Map<String, String> secTickers) {
        this.portfolio = portfolio;
        this.health = health;
        this.prices = prices;
        this.secTickers = Map.copyOf(secTickers);
    }

    public Review review(double limitPct, int signalDays) throws DataSourceException {
        Portfolio p = portfolio.portfolio();
        List<IssuerReview> issuers = new ArrayList<>();
        for (IssuerWeight w : p.issuerWeights(limitPct)) {
            String sec = secTickers.getOrDefault(w.issuer(), w.issuer());
            if (sec.isBlank()) {
                issuers.add(new IssuerReview(w, null, null, List.of(),
                        "Not an SEC registrant (files on SEDAR+): no SEC fundamentals."));
                continue;
            }
            try {
                var r = health.health(sec, 3);
                issuers.add(new IssuerReview(w, sec, "SEC EDGAR", r.table().flags(), null));
            } catch (DataSourceException | RuntimeException e) {
                issuers.add(new IssuerReview(w, sec, null, List.of(), e.getMessage()));
            }
        }
        List<SymbolSignals> signals = new ArrayList<>();
        for (Portfolio.Position pos : p.positions()) {
            try {
                var s = prices.signals(pos.symbol(), signalDays);
                signals.add(new SymbolSignals(pos.symbol(), s.asOf().toString(), s.signals(), null));
            } catch (DataSourceException | RuntimeException e) {
                signals.add(new SymbolSignals(pos.symbol(), null, List.of(), e.getMessage()));
            }
        }
        return new Review(p.baseCurrency(), p.investedInBase(), p.cashInBase(), p.netWorthInBase(), limitPct,
                p.positions(), issuers, signals, signalDays);
    }

    /** Default issuer → SEC ticker map for this portfolio. */
    public static Map<String, String> defaultSecTickers() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("T", "TU");   // TELUS files as TU (T is AT&T)
        m.put("KXS", "");   // Kinaxis: SEDAR+ only
        return m;
    }
}
