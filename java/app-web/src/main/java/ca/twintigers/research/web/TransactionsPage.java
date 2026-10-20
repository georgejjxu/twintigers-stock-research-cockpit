package ca.twintigers.research.web;

import ca.twintigers.research.domain.portfolio.Activity;
import ca.twintigers.research.domain.portfolio.ActivityDraft;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ca.twintigers.research.web.Html.e;
import static ca.twintigers.research.web.Layout.badge;
import static ca.twintigers.research.web.Layout.card;

/**
 * The Transactions page: list, add, edit, delete. Every write is two steps: the form posts to a preview that shows exactly
 * what will be written where; only "Confirm" writes. Everything is re-rendered inside #tx-area.
 */
final class TransactionsPage {

    private TransactionsPage() {
    }

    /** Parsed form, plus problems found while parsing numbers and dates. */
    record Form(String id, ActivityDraft draft, List<String> problems) {
    }

    static Form parse(Map<String, String> f) {
        List<String> problems = new ArrayList<>();
        LocalDate date = null;
        try {
            String d = f.getOrDefault("date", "").strip();
            date = d.isEmpty() ? null : LocalDate.parse(d);
        } catch (java.time.DateTimeException e) {
            problems.add("Date must look like 2026-10-14.");
        }
        BigDecimal qty = num(f, "quantity", "Shares", problems);
        BigDecimal price = num(f, "unitPrice", "Price", problems);
        BigDecimal fee = num(f, "fee", "Fee", problems);
        ActivityDraft d = new ActivityDraft(f.get("type"), f.get("account"), f.get("symbol"), date, qty, price, fee, f.get("currency"),
                f.get("comment")).normalized();
        String id = f.get("id");
        return new Form(id == null || id.isBlank() ? null : id.strip(), d, problems);
    }

    private static BigDecimal num(Map<String, String> f, String k, String label, List<String> problems) {
        String v = f.getOrDefault(k, "").strip().replace(",", "");
        if (v.isEmpty()) {
            return k.equals("fee") ? BigDecimal.ZERO : null;
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            problems.add(label + " must be a number.");
            return null;
        }
    }

    // --- rendering ---------------------------------------------------------------------------------------

    static String page(String source, boolean enabled, List<String> accounts, List<Activity> activities, String error) {
        return page(source, enabled, accounts, activities, error, null);
    }

    /** @param prefill a form to show filled in (e.g. "Record…" from the Performance page's unrecorded dividends) */
    static String page(String source, boolean enabled, List<String> accounts, List<Activity> activities, String error, ActivityDraft prefill) {
        String panel = prefill == null || !enabled ? null : form(null, prefill, accounts, List.of(),
                "DIVIDEND".equals(prefill.type()) ? "Record a dividend (check the amount on your statement)" : "Add a transaction");
        return "<div class=\"cockpit-grid\">" + card("Transactions", "Buys, sells and dividends", "Recorded in the holdings source ("
                + source + "). Every change shows a confirmation first. Rows marked placeholder still have the trial import's "
                + "made-up price: use \"Enter real price\".", area(source, enabled, accounts, activities, panel, error, null),
                "table-card full") + "</div>";
    }

    /** The whole interactive area: message, form or confirmation, table. */
    static String area(String source, boolean enabled, List<String> accounts, List<Activity> activities, String panel, String error,
                       String message) {
        StringBuilder b = new StringBuilder("<div id=\"tx-area\">");
        if (!enabled) {
            b.append(Layout.notice("Recording transactions in " + source + " is switched off. To allow it, add GHOSTFOLIO_WRITES=enabled "
                    + "to secrets.env and restart the Cockpit (scripts\\cockpit.ps1 -Stop, then scripts\\cockpit.ps1)."));
        }
        if (error != null) {
            b.append(Layout.notice(error));
        }
        if (message != null) {
            b.append("<p class=\"cockpit-success\" role=\"status\">").append(e(message)).append("</p>");
        }
        b.append(panel != null ? panel : enabled ? form(null, null, accounts, List.of(), "Add a transaction") : "");
        b.append(table(activities, enabled));
        return b.append("</div>").toString();
    }

    static String form(String id, ActivityDraft d, List<String> accounts, List<String> problems, String title) {
        StringBuilder b = new StringBuilder("<section class=\"cockpit-tx-form\"><h3 class=\"cockpit-subhead\">").append(e(title)).append("</h3>");
        if (!problems.isEmpty()) {
            b.append("<ul class=\"cockpit-problems\" role=\"alert\">");
            problems.forEach(p -> b.append("<li>").append(e(p)).append("</li>"));
            b.append("</ul>");
        }
        b.append("<form class=\"cockpit-form\" hx-post=\"/transactions/preview\" hx-target=\"#tx-area\" hx-swap=\"outerHTML\">");
        if (id != null) {
            b.append("<input type=\"hidden\" name=\"id\" value=\"").append(e(id)).append("\">");
        }
        b.append("<label>Type <select name=\"type\">");
        for (String t : List.of("BUY", "SELL", "DIVIDEND")) {
            b.append("<option value=\"").append(t).append("\"").append(d != null && t.equals(d.type()) ? " selected" : "").append(">")
             .append(t.toLowerCase(java.util.Locale.ROOT)).append("</option>");
        }
        b.append("</select></label><label>Account <select name=\"account\">");
        for (String a : accounts) {
            b.append("<option").append(d != null && a.equals(d.account()) ? " selected" : "").append(">").append(e(a)).append("</option>");
        }
        b.append("</select></label>")
         .append(input("Symbol", "symbol", "text", d == null ? "" : d.symbol(), "ENB.TO", "maxlength=\"20\" required"))
         .append(input("Date", "date", "date", d == null || d.date() == null ? "" : d.date().toString(), null, "required"))
         .append(input("Shares", "quantity", "number", d == null ? "" : plain(d.quantity()), null, "step=\"any\" min=\"0\" required"))
         .append(input("Price per share", "unitPrice", "number", d == null ? "" : plain(d.unitPrice()), null, "step=\"any\" min=\"0\" required"))
         .append(input("Fee", "fee", "number", d == null ? "0" : plain(d.fee()), null, "step=\"any\" min=\"0\""))
         .append("<label>Currency <select name=\"currency\">");
        for (String c : List.of("CAD", "USD")) {
            b.append("<option").append(d != null && c.equals(d.currency()) ? " selected" : "").append(">").append(c).append("</option>");
        }
        b.append("</select></label>").append(input("Comment", "comment", "text", d == null ? "" : d.comment(), "optional", "maxlength=\"500\""))
         .append("<button type=\"submit\" class=\"primary-button\">Review</button>");
        if (id != null || !problems.isEmpty()) {
            b.append(" <button type=\"button\" class=\"secondary-button\" hx-get=\"/transactions/area\" hx-target=\"#tx-area\" "
                    + "hx-swap=\"outerHTML\">Cancel</button>");
        }
        return b.append("</form></section>").toString();
    }

    private static String input(String label, String name, String type, String value, String placeholder, String extra) {
        return "<label>" + e(label) + " <input type=\"" + type + "\" name=\"" + name + "\" value=\"" + e(value) + "\""
                + (placeholder == null ? "" : " placeholder=\"" + e(placeholder) + "\"") + " " + extra + "></label>";
    }

    static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    /** Confirmation step: the exact change, and Confirm / Back. */
    static String confirm(String source, String op, String id, ActivityDraft d, Activity before) {
        StringBuilder b = new StringBuilder("<section class=\"cockpit-confirm\" role=\"alertdialog\" aria-labelledby=\"tx-confirm-title\">")
                .append("<h3 id=\"tx-confirm-title\" class=\"cockpit-subhead\">Please confirm: ")
                .append(e(switch (op) {
                    case "create" -> "add this transaction to " + source;
                    case "update" -> "change this transaction in " + source;
                    default -> "delete this transaction from " + source;
                })).append("</h3>");
        if (before != null) {
            b.append("<p><span class=\"muted\">Now:</span> ").append(e(describe(before))).append("</p>");
        }
        if (d != null) {
            b.append("<p><span class=\"muted\">").append(op.equals("create") ? "New" : "After").append(":</span> <strong>")
             .append(e(d.summary())).append("</strong></p>");
        }
        b.append("<form class=\"cockpit-form\" hx-post=\"/transactions/").append(op.equals("delete") ? e(id) + "/delete" : "save")
         .append("\" hx-target=\"#tx-area\" hx-swap=\"outerHTML\">");
        if (d != null) {
            hidden(b, "op", op);
            hidden(b, "id", id);
            hidden(b, "type", d.type());
            hidden(b, "account", d.account());
            hidden(b, "symbol", d.symbol());
            hidden(b, "date", d.date().toString());
            hidden(b, "quantity", plain(d.quantity()));
            hidden(b, "unitPrice", plain(d.unitPrice()));
            hidden(b, "fee", plain(d.fee()));
            hidden(b, "currency", d.currency());
            hidden(b, "comment", d.comment());
        }
        b.append("<button type=\"submit\" class=\"").append(op.equals("delete") ? "danger-button" : "primary-button").append("\">Confirm</button> ")
         .append("<button type=\"button\" class=\"secondary-button\" hx-get=\"/transactions/area\" hx-target=\"#tx-area\" hx-swap=\"outerHTML\">")
         .append("Back</button></form></section>");
        return b.toString();
    }

    private static void hidden(StringBuilder b, String name, String value) {
        if (value != null) {
            b.append("<input type=\"hidden\" name=\"").append(name).append("\" value=\"").append(e(value)).append("\">");
        }
    }

    static String describe(Activity a) {
        return a.type() + " " + plain(a.quantity()) + " " + a.symbol() + " @ " + plain(a.unitPrice()) + " " + a.currency() + " on " + a.date()
                + " in " + a.account() + " (fee " + plain(a.fee()) + ")" + (a.comment() == null ? "" : " — " + a.comment());
    }

    static String table(List<Activity> activities, boolean enabled) {
        if (activities.isEmpty()) {
            return "<p class=\"muted\">No transactions recorded.</p>";
        }
        StringBuilder t = new StringBuilder("<div class=\"table-scroll\"><table><thead><tr><th></th><th>Date</th><th>Type</th><th>Account</th>"
                + "<th>Symbol</th><th class=\"num\">Shares</th><th class=\"num\">Price</th><th class=\"num\">Fee</th><th>Currency</th>"
                + "<th>Comment</th></tr></thead><tbody>");
        for (Activity a : activities) {
            t.append("<tr><td class=\"nowrap\">");
            if (enabled && a.id() != null) {
                t.append("<button class=\"text-button\" hx-get=\"/transactions/").append(e(a.id())).append("/edit\" hx-target=\"#tx-area\" ")
                 .append("hx-swap=\"outerHTML\">").append(a.isPlaceholder() ? "Enter real price" : "Edit").append("</button> ")
                 .append("<button class=\"text-button danger\" hx-post=\"/transactions/").append(e(a.id()))
                 .append("/delete-preview\" hx-target=\"#tx-area\" hx-swap=\"outerHTML\">Delete</button>");
            }
            String comment = a.comment() == null ? "" : a.comment();
            String shortComment = comment.length() > 48 ? comment.substring(0, 45) + "…" : comment;
            t.append("</td><td>").append(e(a.date())).append("</td><td>").append(e(a.type().toLowerCase(java.util.Locale.ROOT))).append("</td><td>")
             .append(e(a.account())).append("</td><td>").append(Pages.symbolLink(a.symbol())).append("</td><td class=\"num\">")
             .append(Html.shares(a.quantity())).append("</td><td class=\"num\">").append(Html.money(a.unitPrice())).append("</td><td class=\"num\">")
             .append(Html.money(a.fee())).append("</td><td>").append(e(a.currency())).append("</td><td title=\"").append(e(comment)).append("\">")
             .append(a.isPlaceholder() ? badge("placeholder price", "watch") + " " : "").append(e(shortComment)).append("</td></tr>");
        }
        return t.append("</tbody></table></div>").toString();
    }
}
