package ca.twintigers.research.cli;

import ca.twintigers.research.adapters.Wiring;
import ca.twintigers.research.domain.portfolio.Activity;
import ca.twintigers.research.domain.portfolio.ActivityDraft;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code write-check --account NAME}: proves the configured PortfolioWriter end to end against the real holdings source,
 * touching only the named (throwaway) account: add a 1-share IBM buy, change its price, delete it, checking each step
 * through the source's own activity list. Refuses to run on an account that already has transactions.
 */
final class WriteCheck {

    private WriteCheck() {
    }

    static int run(Wiring w, CliMain.Args a, PrintStream out) throws Exception {
        String account = a.opt("--account");
        if (account == null || account.isBlank()) {
            out.println("Usage: write-check --account \"<throwaway account name>\"");
            return 2;
        }
        List<Activity> mine = w.portfolio.activities().stream().filter(x -> account.equals(x.account())).toList();
        if (!mine.isEmpty()) {
            out.println("Refusing: account \"" + account + "\" already has " + mine.size() + " transactions. Use an empty throwaway account.");
            return 1;
        }
        LocalDate day = LocalDate.of(2026, 10, 14);
        ActivityDraft buy = new ActivityDraft("BUY", account, "IBM", day, BigDecimal.ONE, new BigDecimal("1.00"), BigDecimal.ZERO, "USD",
                "write-check (deleted again)");
        Activity created = w.writer.create(buy);
        out.println("1. created  " + created.id() + ": " + describe(created));
        Activity updated = w.writer.update(created.id(), new ActivityDraft("BUY", account, "IBM", day, BigDecimal.ONE,
                new BigDecimal("2.00"), BigDecimal.ZERO, "USD", "write-check (deleted again)"));
        out.println("2. updated  " + updated.id() + ": " + describe(updated));
        boolean ok = updated.id().equals(created.id()) && updated.unitPrice().compareTo(new BigDecimal("2.00")) == 0;
        w.writer.delete(created.id());
        boolean gone = w.portfolio.activities().stream().noneMatch(x -> created.id().equals(x.id()));
        out.println("3. deleted  " + created.id() + ": " + (gone ? "no longer listed" : "STILL LISTED"));
        var cash = w.writer.setCash(account, new BigDecimal("12.34"));
        boolean cashOk = cash.cashBalance().compareTo(new BigDecimal("12.34")) == 0
                && w.portfolio.accounts().stream().anyMatch(x -> x.name().equals(account) && x.cashBalance().compareTo(new BigDecimal("12.34")) == 0);
        out.println("4. cash set to " + cash.cashBalance().toPlainString() + " " + cash.currency() + ": " + (cashOk ? "listed" : "NOT LISTED"));
        w.writer.setCash(account, BigDecimal.ZERO);
        boolean all = ok && gone && cashOk;
        out.println(all ? "WRITE CHECK PASSED on " + w.writer.name() : "WRITE CHECK FAILED");
        return all ? 0 : 1;
    }

    static String describe(Activity a) {
        return a.type() + " " + a.quantity().stripTrailingZeros().toPlainString() + " " + a.symbol() + " @ "
                + a.unitPrice().stripTrailingZeros().toPlainString() + " " + a.currency() + " on " + a.date() + " in " + a.account();
    }
}
