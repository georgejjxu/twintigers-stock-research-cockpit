package ca.twintigers.research.cli;

import ca.twintigers.research.adapters.Wiring;
import ca.twintigers.research.adapters.config.Settings;
import ca.twintigers.research.application.PriceSignalService;
import ca.twintigers.research.application.Reports;
import ca.twintigers.research.domain.portfolio.Portfolio;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command line. Usage:
 * <pre>
 *   health TICKER [--years N] [--json]
 *   signals SYMBOL... | --holdings  [--days N] [--json]
 *   replay  SYMBOL... | --holdings  [--years N] [--out FILE]
 *   review  [--limit PCT] [--days N] [--json]
 *   rules
 *   alerts list | add | subscribe | remove | mute | tune-defaults | test-notify   (see AlertCommands)
 *   ledger-sync | ghostfolio-parity [--out FILE] | returns [RANGE...] [--ledger] | dividends [--ledger]  (see LedgerCommands)
 * </pre>
 */
public final class CliMain {

    private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            OUT.println("Commands: health, signals, replay, review, rules, alerts, tune-rules, write-check, ledger-sync, "
                    + "ghostfolio-parity, returns, dividends. See CliMain javadoc.");
            System.exit(2);
        }
        Args a = new Args(args);
        Wiring w = new Wiring(Settings.load());
        ObjectMapper json = new ObjectMapper().rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();
        switch (args[0]) {
            case "health" -> {
                var r = w.health.health(a.positional.getFirst(), a.intOpt("--years", 3));
                OUT.println(a.flag("--json") ? json.writeValueAsString(r.table()) : r.markdown());
            }
            case "signals" -> {
                for (String s : symbols(a, w)) {
                    var r = w.prices.signals(s, a.intOpt("--days", 30));
                    OUT.println(a.flag("--json") ? json.writeValueAsString(r) : Reports.signals(r));
                    OUT.println();
                }
            }
            case "replay" -> {
                StringBuilder md = new StringBuilder("# Rule replay\n\nEvery price rule run over each day of history "
                        + "(no look-ahead), with what happened 5 and 20 trading days later. Use it to tune thresholds.\n\n");
                for (String s : symbols(a, w)) {
                    PriceSignalService.ReplayReport r = w.prices.replay(s, a.intOpt("--years", 2));
                    md.append(Reports.replay(s, r.source(), r.replay())).append("\n\n");
                }
                emit(md.toString(), a.opt("--out"));
            }
            case "review" -> {
                var r = w.review.review(a.doubleOpt("--limit", 20), a.intOpt("--days", 30));
                OUT.println(a.flag("--json") ? json.writeValueAsString(r) : Reports.review(r));
            }
            case "rules" -> OUT.println(Reports.catalog(w.prices.engine().rules(), List.of()));
            case "alerts" -> System.exit(AlertCommands.run(w, a, OUT));
            case "tune-rules" -> System.exit(TuneRules.run(w, a, OUT));
            case "write-check" -> System.exit(WriteCheck.run(w, a, OUT));
            case "ledger-sync", "ghostfolio-parity", "returns", "dividends" -> System.exit(LedgerCommands.run(w, a, OUT));
            default -> {
                OUT.println("Unknown command " + args[0]);
                System.exit(2);
            }
        }
    }

    private static List<String> symbols(Args a, Wiring w) throws Exception {
        if (a.flag("--holdings")) {
            return w.portfolio.portfolio().positions().stream().map(Portfolio.Position::symbol).toList();
        }
        return a.positional;
    }

    private static void emit(String text, String out) throws Exception {
        if (out == null) {
            OUT.println(text);
        } else {
            Path p = Path.of(out);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, text, StandardCharsets.UTF_8);
            OUT.println("Wrote " + p.toAbsolutePath());
        }
    }

    /** The folder holding .mcp.json: RESEARCH_PROJECT_ROOT, or the nearest parent of the working directory. */
    static Path projectRoot() {
        String env = System.getenv("RESEARCH_PROJECT_ROOT");
        if (env != null) {
            return Path.of(env);
        }
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(".mcp.json"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("Cannot find the project root (.mcp.json); set RESEARCH_PROJECT_ROOT.");
        }
        return p;
    }

    static final class Args {
        final List<String> positional = new ArrayList<>();
        final List<String> raw;

        Args(String[] args) {
            raw = List.of(args);
            for (int i = 1; i < args.length; i++) {
                if (args[i].startsWith("--")) {
                    if (i + 1 < args.length && !args[i + 1].startsWith("--") && takesValue(args[i])) {
                        i++;
                    }
                } else {
                    positional.add(args[i]);
                }
            }
        }

        static boolean takesValue(String opt) {
            return List.of("--years", "--days", "--limit", "--out", "--level", "--pct", "--reference", "--atr", "--since",
                    "--note", "--min-severity", "--hours", "--min-hit", "--min-fires", "--message", "--min-is", "--min-oos", "--account").contains(opt);
        }

        boolean flag(String f) {
            return raw.contains(f);
        }

        String opt(String name) {
            int i = raw.indexOf(name);
            return i >= 0 && i + 1 < raw.size() ? raw.get(i + 1) : null;
        }

        int intOpt(String name, int def) {
            String v = opt(name);
            return v == null ? def : Integer.parseInt(v);
        }

        double doubleOpt(String name, double def) {
            String v = opt(name);
            return v == null ? def : Double.parseDouble(v);
        }
    }
}
