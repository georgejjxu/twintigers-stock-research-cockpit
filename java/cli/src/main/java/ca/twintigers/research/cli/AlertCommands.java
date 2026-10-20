package ca.twintigers.research.cli;

import ca.twintigers.research.adapters.Wiring;
import ca.twintigers.research.application.PriceSignalService;
import ca.twintigers.research.application.Reports;
import ca.twintigers.research.domain.alerts.Alert;
import ca.twintigers.research.domain.alerts.Notification;
import ca.twintigers.research.domain.alerts.Subscription;
import ca.twintigers.research.domain.market.RuleEngine;
import ca.twintigers.research.domain.portfolio.Portfolio;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code alerts ...} commands:
 * <pre>
 *   alerts list
 *   alerts add above|below SYMBOL --level PRICE [--note TEXT]
 *   alerts add move_pct SYMBOL --pct P --reference PRICE [--note TEXT]
 *   alerts add trailing_atr SYMBOL --atr K [--since YYYY-MM-DD] [--note TEXT]
 *   alerts subscribe SCOPE price|fundamental RULE [--min-severity info|watch|warning]
 *   alerts remove alert|subscription ID
 *   alerts mute alert|subscription ID [--days N | --hours N]
 *   alerts tune-defaults [--years 2] [--min-hit 55] [--min-fires 10] [--out FILE]
 *   alerts test-notify [--message TEXT]
 * </pre>
 */
final class AlertCommands {

    private AlertCommands() {
    }

    static int run(Wiring w, CliMain.Args a, PrintStream out) throws Exception {
        List<String> p = a.positional;
        String sub = p.isEmpty() ? "list" : p.getFirst();
        switch (sub) {
            case "list" -> {
                out.println(list(w));
                return 0;
            }
            case "add" -> {
                Alert al = w.alerts.addThreshold(p.get(1), p.get(2), dbl(a, "--level"), dbl(a, "--pct"), dbl(a, "--reference"),
                        dbl(a, "--atr"), a.opt("--since") == null ? null : LocalDate.parse(a.opt("--since")), a.opt("--note"));
                out.println("Added alert #" + al.id() + ": " + al.describe());
                return 0;
            }
            case "subscribe" -> {
                Subscription s = w.alerts.subscribe(p.get(1), p.get(2), p.size() > 3 ? p.get(3) : "*",
                        a.opt("--min-severity"), "user");
                out.println("Added subscription #" + s.id() + ": " + s.describe());
                return 0;
            }
            case "remove" -> {
                boolean ok = w.alerts.remove(p.get(1), Long.parseLong(p.get(2)));
                out.println(ok ? "Removed " + p.get(1) + " #" + p.get(2) + "." : "No active " + p.get(1) + " #" + p.get(2) + ".");
                return ok ? 0 : 1;
            }
            case "mute" -> {
                Duration d = a.opt("--hours") != null ? Duration.ofHours(Long.parseLong(a.opt("--hours")))
                        : Duration.ofDays(a.intOpt("--days", 7));
                boolean ok = w.alerts.mute(p.get(1), Long.parseLong(p.get(2)), d);
                out.println(ok ? "Muted " + p.get(1) + " #" + p.get(2) + " for " + d.toHours() + " hours." : "No active " + p.get(1) + " #" + p.get(2) + ".");
                return ok ? 0 : 1;
            }
            case "tune-defaults" -> {
                return tuneDefaults(w, a, out);
            }
            case "test-notify" -> {
                Instant now = Instant.now();
                w.dispatcher.enqueue(new Notification("test|" + now, "Research monitor: test notification",
                        a.opt("--message") != null ? a.opt("--message") : "If you see this, alerts reach you on this channel.",
                        Notification.Priority.DEFAULT));
                var r = w.dispatcher.dispatch();
                out.println("Test notification on " + w.dispatcher.channels() + ": sent " + r.sent() + ", failed " + r.failed() + ".");
                return r.failed() == 0 && !w.dispatcher.channels().isEmpty() ? 0 : 1;
            }
            default -> {
                out.println("Unknown alerts command " + sub + ". See AlertCommands javadoc.");
                return 2;
            }
        }
    }

    static String list(Wiring w) {
        StringBuilder sb = new StringBuilder("Threshold alerts:\n");
        List<Alert> alerts = w.alerts.alerts();
        if (alerts.isEmpty()) {
            sb.append("  (none)\n");
        }
        Instant now = Instant.now();
        for (Alert a : alerts) {
            sb.append("  #").append(a.id()).append(' ').append(a.describe()).append(" — ").append(a.state().status())
              .append(a.muted(now) ? ", muted until " + a.mutedUntil() : "")
              .append(a.note() == null ? "" : " — " + a.note()).append('\n');
        }
        sb.append("Rule subscriptions:\n");
        for (Subscription s : w.alerts.subscriptions()) {
            sb.append("  #").append(s.id()).append(' ').append(s.describe())
              .append(s.mutedUntil() != null && now.isBefore(s.mutedUntil()) ? ", muted until " + s.mutedUntil() : "").append('\n');
        }
        sb.append("Channels: ").append(w.dispatcher.channels());
        return sb.toString();
    }

    static int tuneDefaults(Wiring w, CliMain.Args a, PrintStream out) throws Exception {
        int years = a.intOpt("--years", 2);
        double minHit = a.doubleOpt("--min-hit", 55);
        int minFires = a.intOpt("--min-fires", 10);
        List<String> symbols = w.portfolio.portfolio().positions().stream().map(Portfolio.Position::symbol).toList();
        List<RuleEngine.Replay> replays = new ArrayList<>();
        for (String s : symbols) {
            PriceSignalService.ReplayReport r = w.prices.replay(s, years);
            replays.add(r.replay());
        }
        List<RuleEngine.RuleStats> combined = Reports.combine(replays);
        List<String> chosen = w.alerts.tuneDefaults(combined, minHit, minFires);
        StringBuilder md = new StringBuilder("# Default alert subscriptions, from the rule replay\n\n");
        md.append("Generated ").append(LocalDate.now()).append(" by `research-cli alerts tune-defaults --years ").append(years)
          .append(" --min-hit ").append(minHit).append(" --min-fires ").append(minFires).append("`.\n\n");
        md.append("Rule: notify by default for every **warning**-severity price rule that fired at least ").append(minFires)
          .append(" times over the replay and went the rule's way at least ").append(minHit)
          .append(" % of the time 20 trading days later; plus every fundamental flag at warning severity. ")
          .append("Everything else stays available in `price_signals` and can be subscribed to by hand.\n\n");
        md.append("**Chosen price rules:** ").append(chosen.isEmpty() ? "none" : String.join(", ", chosen)).append("\n\n");
        md.append(Reports.combinedReplay(symbols, combined)).append("\n");
        String outFile = a.opt("--out");
        if (outFile != null) {
            java.nio.file.Path f = java.nio.file.Path.of(outFile);
            if (f.getParent() != null) {
                java.nio.file.Files.createDirectories(f.getParent());
            }
            java.nio.file.Files.writeString(f, md.toString());
            out.println("Wrote " + f.toAbsolutePath());
        }
        out.println("Default subscriptions set. Price rules: " + (chosen.isEmpty() ? "none" : String.join(", ", chosen))
                + "; fundamentals: all warning flags.");
        return 0;
    }

    private static Double dbl(CliMain.Args a, String name) {
        String v = a.opt(name);
        return v == null ? null : Double.valueOf(v.toLowerCase(Locale.ROOT));
    }
}
