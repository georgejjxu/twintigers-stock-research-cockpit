package ca.twintigers.research.web;

import ca.twintigers.research.domain.portfolio.Account;

import java.math.BigDecimal;
import java.util.List;

import static ca.twintigers.research.web.Html.e;

/**
 * Accounts and cash on the Transactions page: the balance of each account, changed in two steps like a transaction
 * (the form posts to a preview showing old and new balance; only "Confirm" writes). Re-rendered inside #cash-area.
 */
final class CashPage {

    private CashPage() {
    }

    static String card(String source, boolean enabled, List<Account> accounts, String panel, String error, String message) {
        return "<div class=\"cockpit-grid\">" + Layout.card("Accounts and cash", "Cash balances", "The cash in each account, in the "
                + "account's own currency, as recorded in the holdings source (" + source + "). Change it after a deposit, withdrawal "
                + "or interest; buys and sells do not change it by themselves.", area(source, enabled, accounts, panel, error, message),
                "table-card full") + "</div>";
    }

    static String area(String source, boolean enabled, List<Account> accounts, String panel, String error, String message) {
        StringBuilder b = new StringBuilder("<div id=\"cash-area\">");
        if (error != null) {
            b.append(Layout.notice(error));
        }
        if (message != null) {
            b.append("<p class=\"cockpit-success\" role=\"status\">").append(e(message)).append("</p>");
        }
        if (panel != null) {
            b.append(panel);
        }
        b.append("<div class=\"table-scroll\"><table><thead><tr><th></th><th>Account</th><th>Currency</th><th class=\"num\">Cash</th>"
                + "</tr></thead><tbody>");
        for (Account a : accounts) {
            b.append("<tr><td class=\"nowrap\">");
            if (enabled) {
                b.append("<button class=\"text-button\" hx-get=\"/accounts/cash-edit?account=").append(e(java.net.URLEncoder.encode(a.name(),
                        java.nio.charset.StandardCharsets.UTF_8))).append("\" hx-target=\"#cash-area\" hx-swap=\"outerHTML\">Change cash</button>");
            }
            b.append("</td><td>").append(e(a.name())).append("</td><td>").append(e(a.currency())).append("</td><td class=\"num\">")
             .append(Html.money(a.cashBalance())).append("</td></tr>");
        }
        b.append("</tbody></table></div>");
        if (accounts.isEmpty()) {
            b.append("<p class=\"muted\">No accounts.</p>");
        }
        if (enabled && panel == null) {
            b.append("<p>");
            if (accounts.size() > 1) {
                b.append("<button class=\"secondary-button\" hx-get=\"/accounts/transfer\" hx-target=\"#cash-area\" hx-swap=\"outerHTML\">"
                        + "Move cash between accounts</button> ");
            }
            b.append("<button class=\"secondary-button\" hx-get=\"/accounts/new\" hx-target=\"#cash-area\" hx-swap=\"outerHTML\">"
                    + "Add an account</button></p>");
        }
        return b.append("</div>").toString();
    }

    static String newAccountForm(String name, String currency, String cash, List<String> problems) {
        StringBuilder b = new StringBuilder("<section class=\"cockpit-tx-form\"><h3 class=\"cockpit-subhead\">Add an account</h3>");
        if (!problems.isEmpty()) {
            b.append("<ul class=\"cockpit-problems\" role=\"alert\">");
            problems.forEach(p -> b.append("<li>").append(e(p)).append("</li>"));
            b.append("</ul>");
        }
        return b.append("<form class=\"cockpit-form\" hx-post=\"/accounts/new\" hx-target=\"#cash-area\" hx-swap=\"outerHTML\">")
                .append("<label>Name <input name=\"name\" maxlength=\"60\" required placeholder=\"e.g. TFSA\" value=\"").append(e(name)).append("\"></label>")
                .append("<label>Currency <input name=\"currency\" maxlength=\"3\" required placeholder=\"CAD\" value=\"").append(e(currency)).append("\"></label>")
                .append("<label>Cash now <input type=\"number\" name=\"cash\" step=\"0.01\" min=\"0\" value=\"").append(e(cash)).append("\"></label>")
                .append("<button type=\"submit\" class=\"primary-button\">Add</button> <button type=\"button\" class=\"secondary-button\" "
                        + "hx-get=\"/accounts/cash-area\" hx-target=\"#cash-area\" hx-swap=\"outerHTML\">Cancel</button></form></section>")
                .toString();
    }

    // --- moving cash between accounts --------------------------------------------------------------------------

    /** A move: {@code amount} leaves {@code from} (its currency), {@code received} arrives in {@code to} (its currency). */
    record Transfer(Account from, Account to, BigDecimal amount, BigDecimal received, Double rate) {
        boolean converted() {
            return !from.currency().equalsIgnoreCase(to.currency());
        }
    }

    static String transferForm(List<Account> accounts, String from, String to, String amount, String received, List<String> problems) {
        StringBuilder b = new StringBuilder("<section class=\"cockpit-tx-form\"><h3 class=\"cockpit-subhead\">Move cash between accounts</h3>");
        if (!problems.isEmpty()) {
            b.append("<ul class=\"cockpit-problems\" role=\"alert\">");
            problems.forEach(p -> b.append("<li>").append(e(p)).append("</li>"));
            b.append("</ul>");
        }
        b.append("<form class=\"cockpit-form\" hx-post=\"/accounts/transfer-preview\" hx-target=\"#cash-area\" hx-swap=\"outerHTML\">");
        b.append(select("From", "from", accounts, from)).append(select("To", "to", accounts, to))
         .append("<label>Amount (in the From account's currency) <input type=\"number\" name=\"amount\" step=\"0.01\" min=\"0.01\" required value=\"")
         .append(e(amount)).append("\"></label>")
         .append("<label>Amount received (only between currencies; blank = at the latest exchange rate) <input type=\"number\" name=\"received\" "
                 + "step=\"0.01\" min=\"0\" value=\"").append(e(received)).append("\"></label>")
         .append("<button type=\"submit\" class=\"primary-button\">Review</button> <button type=\"button\" class=\"secondary-button\" "
                 + "hx-get=\"/accounts/cash-area\" hx-target=\"#cash-area\" hx-swap=\"outerHTML\">Cancel</button></form></section>");
        return b.toString();
    }

    private static String select(String label, String name, List<Account> accounts, String chosen) {
        StringBuilder b = new StringBuilder("<label>").append(e(label)).append(" <select name=\"").append(name).append("\">");
        for (Account a : accounts) {
            b.append("<option value=\"").append(e(a.name())).append("\"").append(a.name().equals(chosen) ? " selected" : "").append(">")
             .append(e(a.name())).append(" (").append(e(a.currency())).append(", ").append(Html.money(a.cashBalance())).append(")</option>");
        }
        return b.append("</select></label>").toString();
    }

    static String transferConfirm(String source, Transfer t) {
        BigDecimal fromAfter = t.from().cashBalance().subtract(t.amount());
        BigDecimal toAfter = t.to().cashBalance().add(t.received());
        return "<section class=\"cockpit-confirm\" role=\"alertdialog\" aria-labelledby=\"transfer-title\"><h3 id=\"transfer-title\" "
                + "class=\"cockpit-subhead\">Please confirm: move " + Html.money(t.amount()) + " " + e(t.from().currency()) + " from "
                + e(t.from().name()) + " to " + e(t.to().name()) + " in " + e(source) + "</h3>"
                + (t.converted() ? "<p class=\"muted\">Arrives as " + Html.money(t.received()) + " " + e(t.to().currency())
                + (t.rate() == null ? " (as you entered)" : " at " + String.format(java.util.Locale.ROOT, "%.4f", t.rate()) + " "
                + e(t.to().currency()) + " per " + e(t.from().currency()) + ", the latest daily rate") + ".</p>" : "")
                + "<table class=\"cockpit-explain-facts\"><tbody><tr><th></th><th class=\"num\">Now</th><th class=\"num\">After</th></tr>"
                + "<tr><th>" + e(t.from().name()) + "</th><td class=\"num\">" + Html.money(t.from().cashBalance()) + " " + e(t.from().currency())
                + "</td><td class=\"num\"><strong>" + Html.money(fromAfter) + " " + e(t.from().currency()) + "</strong></td></tr><tr><th>"
                + e(t.to().name()) + "</th><td class=\"num\">" + Html.money(t.to().cashBalance()) + " " + e(t.to().currency())
                + "</td><td class=\"num\"><strong>" + Html.money(toAfter) + " " + e(t.to().currency()) + "</strong></td></tr></tbody></table>"
                + "<p class=\"muted small\">Two balance changes: first the From account, then the To account.</p>"
                + "<form class=\"cockpit-form\" hx-post=\"/accounts/transfer-save\" hx-target=\"#cash-area\" hx-swap=\"outerHTML\">"
                + "<input type=\"hidden\" name=\"from\" value=\"" + e(t.from().name()) + "\"><input type=\"hidden\" name=\"to\" value=\""
                + e(t.to().name()) + "\"><input type=\"hidden\" name=\"amount\" value=\"" + e(t.amount().toPlainString())
                + "\"><input type=\"hidden\" name=\"received\" value=\"" + e(t.received().toPlainString()) + "\">"
                + "<button type=\"submit\" class=\"primary-button\">Confirm</button> <button type=\"button\" class=\"secondary-button\" "
                + "hx-get=\"/accounts/cash-area\" hx-target=\"#cash-area\" hx-swap=\"outerHTML\">Back</button></form></section>";
    }

    /**
     * Checks a move and works out what arrives: the same amount between accounts in one currency; otherwise the amount
     * typed in "received", or the amount at {@code rate} (to-currency per from-currency) when that is blank.
     */
    static Transfer transfer(Account from, Account to, String amountText, String receivedText, Double rate, List<String> problems) {
        if (from.name().equals(to.name())) {
            problems.add("Choose two different accounts.");
        }
        BigDecimal amount = parse(amountText, problems);
        if (amount != null && amount.signum() == 0) {
            problems.add("The amount must be more than 0.");
        }
        if (amount != null && amount.compareTo(from.cashBalance()) > 0) {
            problems.add(from.name() + " has only " + Html.money(from.cashBalance()) + " " + from.currency() + ".");
        }
        BigDecimal received = amount;
        Double used = null;
        if (amount != null && !from.currency().equalsIgnoreCase(to.currency())) {
            if (receivedText != null && !receivedText.isBlank()) {
                received = parse(receivedText, problems);
            } else if (rate == null) {
                problems.add("No exchange rate for " + from.currency() + " to " + to.currency() + " right now: enter the amount received.");
            } else {
                received = amount.multiply(BigDecimal.valueOf(rate)).setScale(2, java.math.RoundingMode.HALF_EVEN);
                used = rate;
            }
        }
        return problems.isEmpty() ? new Transfer(from, to, amount, received, used) : null;
    }

    static String form(Account a, String value, List<String> problems) {
        StringBuilder b = new StringBuilder("<section class=\"cockpit-tx-form\"><h3 class=\"cockpit-subhead\">Change the cash in ")
                .append(e(a.name())).append("</h3>");
        if (!problems.isEmpty()) {
            b.append("<ul class=\"cockpit-problems\" role=\"alert\">");
            problems.forEach(p -> b.append("<li>").append(e(p)).append("</li>"));
            b.append("</ul>");
        }
        return b.append("<form class=\"cockpit-form\" hx-post=\"/accounts/cash-preview\" hx-target=\"#cash-area\" hx-swap=\"outerHTML\">")
                .append("<input type=\"hidden\" name=\"account\" value=\"").append(e(a.name())).append("\">")
                .append("<label>New cash balance (").append(e(a.currency())).append(") <input type=\"number\" name=\"cash\" step=\"0.01\" "
                        + "min=\"0\" required value=\"").append(e(value)).append("\"></label>")
                .append("<button type=\"submit\" class=\"primary-button\">Review</button> <button type=\"button\" class=\"secondary-button\" "
                        + "hx-get=\"/accounts/cash-area\" hx-target=\"#cash-area\" hx-swap=\"outerHTML\">Cancel</button></form></section>")
                .toString();
    }

    static String confirm(String source, Account a, BigDecimal cash) {
        return "<section class=\"cockpit-confirm\" role=\"alertdialog\" aria-labelledby=\"cash-confirm-title\">"
                + "<h3 id=\"cash-confirm-title\" class=\"cockpit-subhead\">Please confirm: change the cash in " + e(a.name()) + " in "
                + e(source) + "</h3><p><span class=\"muted\">Now:</span> " + Html.money(a.cashBalance()) + " " + e(a.currency())
                + "</p><p><span class=\"muted\">After:</span> <strong>" + Html.money(cash) + " " + e(a.currency()) + "</strong></p>"
                + "<form class=\"cockpit-form\" hx-post=\"/accounts/cash-save\" hx-target=\"#cash-area\" hx-swap=\"outerHTML\">"
                + "<input type=\"hidden\" name=\"account\" value=\"" + e(a.name()) + "\"><input type=\"hidden\" name=\"cash\" value=\""
                + e(cash.toPlainString()) + "\"><button type=\"submit\" class=\"primary-button\">Confirm</button> "
                + "<button type=\"button\" class=\"secondary-button\" hx-get=\"/accounts/cash-area\" hx-target=\"#cash-area\" "
                + "hx-swap=\"outerHTML\">Back</button></form></section>";
    }

    /** The amount typed, or null with the reason added to problems. */
    static BigDecimal parse(String raw, List<String> problems) {
        try {
            BigDecimal v = new BigDecimal(raw == null ? "" : raw.strip().replace(",", ""));
            if (v.signum() < 0) {
                problems.add("Cash must be 0 or more.");
            } else if (v.stripTrailingZeros().scale() > 2) {
                problems.add("Cash has at most 2 decimals.");
            }
            return v;
        } catch (NumberFormatException e) {
            problems.add("Cash must be a number, e.g. 1500.25.");
            return null;
        }
    }
}
