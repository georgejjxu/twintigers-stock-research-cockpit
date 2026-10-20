package ca.twintigers.research.cli;

import ca.twintigers.research.adapters.Wiring;
import ca.twintigers.research.application.LedgerSync;
import ca.twintigers.research.application.SourceParity;
import ca.twintigers.research.domain.portfolio.Dividends;
import ca.twintigers.research.domain.portfolio.PerformanceCalculator;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The Java ledger that is replacing Ghostfolio:
 * <pre>
 *   ledger-sync                          mirror Ghostfolio's accounts, cash and transactions into the ledger (reads Ghostfolio only)
 *   ghostfolio-parity [--out FILE]       compare the ledger with Ghostfolio (default docs/parity/ghostfolio-parity.md)
 *   returns [RANGE...] [--ledger]        returns of the chosen source (or computed from the ledger), e.g. 1d ytd 1y max 2025
 *   dividends [--ledger]                 dividend income by month, and dividends paid but not recorded
 * </pre>
 */
final class LedgerCommands {

    private LedgerCommands() {
    }

    static int run(Wiring w, CliMain.Args a, PrintStream out) throws Exception {
        String cmd = a.raw.getFirst();
        return switch (cmd) {
            case "ledger-sync" -> {
                LedgerSync.Result r = w.ledgerSync.sync(w.ghostfolio, w.ledger);
                out.println("Ledger synced from " + w.ghostfolio.name() + ": " + r.summary());
                r.skipped().forEach(s -> out.println("  skipped: " + s));
                yield 0;
            }
            case "ghostfolio-parity" -> {
                SourceParity.Result r = w.parity.compare(w.ghostfolio, w.local, w.ledgerPerformance, List.of("1d", "ytd", "1y", "max"));
                String file = a.opt("--out") != null ? a.opt("--out") : "docs/parity/ghostfolio-parity.md";
                Path p = CliMain.projectRoot().resolve(file);
                Files.createDirectories(p.getParent());
                Files.writeString(p, r.markdown(), StandardCharsets.UTF_8);
                out.println("Ghostfolio parity: " + r.differences() + " differences. Report: " + p);
                r.lines().stream().filter(l -> l.status() == SourceParity.Status.DIFFERENT)
                        .forEach(l -> out.println("  " + l.area() + " / " + l.item() + ": " + l.reference() + " vs " + l.candidate()
                                + (l.note().isBlank() ? "" : " (" + l.note() + ")")));
                yield r.differences() == 0 ? 0 : 1;
            }
            case "returns" -> {
                List<String> ranges = a.positional.isEmpty() ? List.of("1d", "wtd", "mtd", "ytd", "1y", "max") : a.positional;
                boolean ledger = a.flag("--ledger");
                out.println("Returns, " + (ledger ? "computed from the Java ledger" : "from " + w.portfolio.name()) + " (" + w.baseCurrency + "):");
                for (String range : ranges) {
                    if (ledger || !w.portfolio.capabilities().contains(ca.twintigers.research.ports.PortfolioSource.Capability.PERFORMANCE)) {
                        PerformanceCalculator.Report r = (ledger ? w.ledgerPerformance : w.performance).report(range);
                        out.println("  " + range + " (" + r.from() + " to " + r.to() + "): price return " + r.priceReturnPct()
                                + " %, total return incl. dividends " + r.totalReturnPct() + " %; value " + r.valueStart() + " -> "
                                + r.valueEnd() + ", bought " + r.bought() + ", sold " + r.sold() + ", dividends " + r.dividends()
                                + ", gain " + r.gain() + (r.notes().isEmpty() ? "" : " | " + String.join(" ", r.notes())));
                    } else {
                        w.performance.performance(range).ifPresentOrElse(
                                p -> out.println("  " + range + ": " + p.netPerformancePct() + " %, gain " + p.netPerformance()
                                        + ", value " + p.valueInBase() + " (" + p.method() + ")"),
                                () -> out.println("  " + range + ": not available"));
                    }
                }
                yield 0;
            }
            case "dividends" -> {
                var perf = a.flag("--ledger") ? w.ledgerPerformance : w.performance;
                List<Dividends.Month> months = perf.dividendIncome();
                out.println("Dividend income by month (" + w.baseCurrency + ", net of withholding):");
                if (months.isEmpty()) {
                    out.println("  none recorded");
                }
                months.forEach(m -> out.println("  " + m.month() + ": " + m.amountInBase() + " (" + m.payments() + " payments)"));
                List<Dividends.Missing> missing = perf.missingDividends();
                out.println("Paid by the companies but not recorded (suggestions; amounts before withholding tax):");
                if (missing.isEmpty()) {
                    out.println("  none");
                }
                missing.forEach(m -> out.println("  " + m.exDate() + " " + m.symbol() + " in " + m.account() + ": " + m.shares().toPlainString()
                        + " shares x " + m.perShare().toPlainString() + " " + m.currency() + " = " + m.amount().toPlainString()));
                yield 0;
            }
            default -> 2;
        };
    }
}
