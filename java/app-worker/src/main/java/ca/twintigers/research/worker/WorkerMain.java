package ca.twintigers.research.worker;

import ca.twintigers.research.adapters.Wiring;
import ca.twintigers.research.adapters.config.Settings;
import ca.twintigers.research.application.AlertEngine;
import ca.twintigers.research.application.NotificationDispatcher;
import ca.twintigers.research.domain.alerts.Notification;
import ca.twintigers.research.ports.Outbox;
import ca.twintigers.research.ports.RunLog;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The monitoring worker. In Docker it runs {@code loop} as the "monitor" service (docker-compose.yml).
 * <pre>
 *   loop [--every 30] [--backup-dir DIR] [--keep 14]  run every 30 minutes, forever; daily database backup
 *   run [--mode auto|close|intraday] [--log FILE]   evaluate alerts and rules once, send notifications
 *   status                                          last runs, outbox, subscriptions
 *   test-notify [--message TEXT]                     send a test notification on every configured channel
 * </pre>
 * Exit code 0 on success, 1 when the run failed (Task Scheduler shows it as "Last Run Result").
 */
public final class WorkerMain {

    public static void main(String[] args) throws Exception {
        List<String> a = List.of(args);
        String log = opt(a, "--log");
        if (log != null) {
            Path p = Path.of(log);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            PrintStream ps = new PrintStream(new FileOutputStream(p.toFile(), true), true, StandardCharsets.UTF_8);
            System.setOut(ps);
            System.setErr(ps);
        }
        String cmd = a.isEmpty() ? "run" : a.getFirst();
        Wiring w = new Wiring(Settings.load());
        int exit = switch (cmd) {
            case "run" -> run(w, opt(a, "--mode"));
            case "loop" -> loop(w, Integer.parseInt(Objects.requireNonNullElse(opt(a, "--every"), "30")), opt(a, "--backup-dir"),
                    Integer.parseInt(Objects.requireNonNullElse(opt(a, "--keep"), "14")));
            case "status" -> status(w);
            case "test-notify" -> testNotify(w, opt(a, "--message"));
            default -> {
                System.out.println("Commands: loop [--every 30] [--backup-dir DIR] [--keep 14], run [--mode auto|close|intraday] [--log FILE], status, test-notify [--message TEXT]");
                yield 2;
            }
        };
        System.out.flush();
        System.exit(exit);
    }

    static int run(Wiring w, String mode) {
        AlertEngine.Mode m = AlertEngine.Mode.valueOf((mode == null ? "auto" : mode).toUpperCase(Locale.ROOT));
        try {
            AlertEngine.RunResult r = w.engine.run(m);
            System.out.println(Instant.now() + " run " + r.mode() + ": ran=" + r.ran() + ", symbols=" + r.symbols()
                    + ", queued=" + r.queued() + ", sent=" + r.dispatch().sent() + ", failed=" + r.dispatch().failed()
                    + (r.notes().isEmpty() ? "" : ", notes: " + String.join(" | ", r.notes())));
            return 0;
        } catch (RuntimeException e) {
            System.out.println(Instant.now() + " run failed: " + e);
            return 1;
        }
    }

    /**
     * The Docker monitor: an AUTO run every {@code every} minutes on the clock (:00, :30), forever. AUTO itself decides
     * whether there is anything to do (market hours, holidays, the once-a-day close run, catching up after sleep). Once
     * a day after 18:30 ET it also copies the database to {@code backupDir}, keeping the newest {@code keep} copies,
     * and, while Ghostfolio is the holdings source, mirrors it into the Java ledger and writes the parity report.
     * Sleeps in one-minute steps against the wall clock, so a PC that slept catches up at once.
     */
    static int loop(Wiring w, int every, String backupDir, int keep) throws InterruptedException {
        System.out.println(Instant.now() + " monitor loop: every " + every + " min, channels " + w.dispatcher.channels()
                + (backupDir == null ? "" : ", daily backup to " + backupDir));
        LocalDate lastBackup = null;
        while (true) {
            run(w, "auto");
            ZonedDateTime now = ZonedDateTime.now(w.clock.withZone(AlertEngine.EASTERN));
            if (backupDir != null && !now.toLocalDate().equals(lastBackup) && !now.toLocalTime().isBefore(LocalTime.of(18, 30))) {
                try {
                    backup(w, Path.of(backupDir), now.toLocalDate(), keep);
                    lastBackup = now.toLocalDate();
                } catch (RuntimeException | java.io.IOException e) {
                    System.out.println(Instant.now() + " backup failed: " + e.getMessage());
                }
                if ("ghostfolio".equalsIgnoreCase(w.portfolio.name())) {
                    syncAndCompare(w, Path.of(System.getenv().getOrDefault("RESEARCH_PROJECT_ROOT", "."), "docs", "parity",
                            "ghostfolio-parity.md"));
                }
            }
            long step = (long) every * 60;
            long next = (Instant.now().getEpochSecond() / step + 1) * step;
            while (Instant.now().getEpochSecond() < next) {
                Thread.sleep(Math.min(60_000, Math.max(1_000, (next - Instant.now().getEpochSecond()) * 1000)));
            }
        }
    }

    /**
     * While Ghostfolio is the holdings source: mirror it into the Java ledger, then write the parity report. Reads
     * Ghostfolio only; a failure is logged and retried the next evening.
     */
    static void syncAndCompare(Wiring w, Path report) {
        try {
            System.out.println(Instant.now() + " ledger sync: " + w.ledgerSync.sync(w.ghostfolio, w.ledger).summary());
            var r = w.parity.compare(w.ghostfolio, w.local, w.ledgerPerformance, List.of("1d", "ytd", "1y", "max"));
            Files.createDirectories(report.getParent());
            Files.writeString(report, r.markdown(), StandardCharsets.UTF_8);
            System.out.println(Instant.now() + " ghostfolio parity: " + r.differences() + " differences, report " + report);
        } catch (Exception e) {
            System.out.println(Instant.now() + " ledger sync / parity failed: " + e.getMessage());
        }
    }

    static void backup(Wiring w, Path dir, LocalDate day, int keep) throws java.io.IOException {
        Path target = dir.resolve("research-" + day + ".db");
        w.database.backupTo(target);
        System.out.println(Instant.now() + " backup: " + target);
        try (var files = Files.list(dir)) {
            List<Path> old = files.filter(p -> p.getFileName().toString().matches("research-\\d{4}-\\d{2}-\\d{2}\\.db"))
                    .sorted(java.util.Comparator.reverseOrder()).skip(keep).toList();
            for (Path p : old) {
                Files.deleteIfExists(p);
            }
        }
    }

    static int status(Wiring w) {
        System.out.println("Channels: " + w.dispatcher.channels());
        System.out.println("Last successful evaluation: " + w.alertDb.lastSuccessfulEvaluation().map(RunLog.Run::finishedAt)
                .map(Object::toString).orElse("never"));
        System.out.println("Recent runs:");
        for (RunLog.Run r : w.alertDb.recentRuns(10)) {
            System.out.println("  #" + r.id() + " " + r.mode() + " " + r.startedAt() + " ok=" + r.ok() + " symbols=" + r.symbols()
                    + " notifications=" + r.notifications() + (r.detail() == null || r.detail().isBlank() ? "" : " | " + r.detail()));
        }
        System.out.println("Outbox: " + w.alertDb.pendingCount() + " pending. Recent:");
        for (Outbox.Entry e : w.alertDb.recentEntries(10)) {
            System.out.println("  #" + e.id() + " " + e.channel() + " " + (e.sentAt() != null ? "sent " + e.sentAt()
                    : "pending, attempts " + e.attempts() + (e.lastError() == null ? "" : ", last error: " + e.lastError())) + " | " + e.title());
        }
        System.out.println("Subscriptions: " + w.alerts.subscriptions().size() + ", threshold alerts: " + w.alerts.alerts().size());
        return 0;
    }

    static int testNotify(Wiring w, String message) {
        if (w.dispatcher.channels().isEmpty()) {
            System.out.println("No notification channel is configured (toast is Windows-only; ntfy needs NTFY_TOPIC in secrets.env).");
            return 1;
        }
        Instant now = Instant.now();
        w.dispatcher.enqueue(new Notification("test|" + now, "Research monitor: test notification",
                message != null ? message : "If you see this, alerts from the research monitor reach you on this channel. Sent "
                        + now.atZone(AlertEngine.EASTERN).toLocalDateTime().withNano(0) + " ET.", Notification.Priority.DEFAULT));
        NotificationDispatcher.Result r = w.dispatcher.dispatch();
        System.out.println("Test notification: sent " + r.sent() + ", failed " + r.failed() + " on channels " + w.dispatcher.channels());
        for (Outbox.Entry e : w.alertDb.recentEntries(w.dispatcher.channels().size())) {
            System.out.println("  " + e.channel() + ": " + (e.sentAt() != null ? "sent" : "FAILED: " + e.lastError()));
        }
        return r.failed() == 0 ? 0 : 1;
    }

    static String opt(List<String> a, String name) {
        int i = a.indexOf(name);
        return i >= 0 && i + 1 < a.size() ? a.get(i + 1) : null;
    }
}
