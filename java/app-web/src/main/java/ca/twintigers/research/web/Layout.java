package ca.twintigers.research.web;

import static ca.twintigers.research.web.Html.e;

/**
 * The page shell, using the Twintiger Retirement Planner's markup and classes (app-shell, app-sidebar, sidebar-brand,
 * sidebar-nav, sidebar-link with data-icon, app-main, page-header) so its vendored CSS styles the Cockpit the same way.
 * Rendered on the server; the retirement app's navigation.js is not used, so the body carries "navigation-ready" itself.
 */
final class Layout {

    private Layout() {
    }

    /** Changes on every start, so browsers fetch the new cockpit.css/js after an update instead of an hour-old copy. */
    static final String VERSION = Long.toString(System.currentTimeMillis(), 36);

    enum Nav { PORTFOLIO, PERFORMANCE, XRAY, FIRE, WATCHLIST, TRANSACTIONS, MARKETS, STOCK, ALERTS, RULES }

    static String page(String title, String subtitle, Nav active, String source, String csrf, String body) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="csrf-token" content="%s">
                <title>%s · Stock Research Cockpit</title>
                <link rel="stylesheet" href="/static/vendor/twintiger/dashboard.css">
                <link rel="stylesheet" href="/static/vendor/twintiger/navigation.css">
                <link rel="stylesheet" href="/static/vendor/twintiger/page-header.css">
                <link rel="stylesheet" href="/static/vendor/twintiger/data-table.css">
                <link rel="stylesheet" href="/static/cockpit.css?v=@V@">
                <script defer src="/static/vendor/htmx/htmx-2.0.11.min.js"></script>
                <script defer src="/static/vendor/lightweight-charts/lightweight-charts-5.2.1.standalone.production.js"></script>
                <script defer src="/static/cockpit.js?v=@V@"></script>
                <script defer src="/static/quote-chart.js?v=@V@"></script>
                </head>
                <body class="navigation-ready" data-page="%s" hx-headers='{"X-CSRF-Token": "%s"}'>
                <a class="skip-to-content" href="#main-content">Skip to content</a>
                <div class="app-shell">
                    <aside class="app-sidebar">
                        <div class="sidebar-brand">
                            <p class="brand-name">TwinTigers Software</p>
                            <p class="sidebar-tag">STOCK RESEARCH COCKPIT
                                <span class="sidebar-tagline">Holdings, Watch List &amp; Alerts</span></p>
                        </div>
                        <nav class="sidebar-nav" aria-label="Site navigation">
                            <p class="sidebar-group-label">Portfolio</p>
                            %s
                            %s
                            %s
                            %s
                            %s
                            %s
                            <p class="sidebar-group-label">Research</p>
                            %s
                            %s
                            %s
                            <p class="sidebar-group-label">Monitor</p>
                            %s
                        </nav>
                    </aside>
                    <div class="app-main" id="main-content" tabindex="-1">
                        <header class="page-header">
                            <div class="page-title-block"><h1>%s</h1><p class="subtitle">%s</p></div>
                            <div class="page-header-utilities app-top-bar">
                                <span class="cockpit-source">Holdings source: <strong>%s</strong></span>
                            </div>
                        </header>
                        %s
                        <footer class="cockpit-footer">Read-only research aid: values from the holdings source (delayed prices),
                            fundamentals from SEC filings, signals from daily bars. Nothing here is investment advice.</footer>
                    </div>
                </div>
                </body>
                </html>
                """.replace("@V@", VERSION).formatted(e(csrf), e(title), e(active.name().toLowerCase()), e(csrf),
                link("Portfolio", "/", "dashboard", active == Nav.PORTFOLIO),
                link("Performance", "/performance", "history", active == Nav.PERFORMANCE),
                link("X-ray", "/xray", "audit", active == Nav.XRAY),
                link("FIRE", "/fire", "strategies", active == Nav.FIRE),
                link("Watch List", "/watchlist", "rankings", active == Nav.WATCHLIST),
                link("Transactions", "/transactions", "spending", active == Nav.TRANSACTIONS),
                link("Markets", "/markets", "rates", active == Nav.MARKETS),
                link("Stock Detail", "/stock/ENB.TO", "analysis", active == Nav.STOCK),
                link("Rule Catalog", "/rules", "help", active == Nav.RULES),
                link("Alerts", "/alerts", "stress", active == Nav.ALERTS),
                e(title), e(subtitle), e(source), body);
    }

    private static String link(String label, String href, String icon, boolean current) {
        return "<a class=\"sidebar-link" + (current ? " active" : "") + "\" data-icon=\"" + icon + "\" href=\"" + href + "\""
                + (current ? " aria-current=\"page\"" : "") + ">" + e(label) + "</a>";
    }

    /** A card in the retirement app's style: eyebrow, title, helper text, then content. */
    static String card(String eyebrow, String title, String helper, String content, String extraClass) {
        return "<article class=\"card " + extraClass + "\"><div class=\"card-heading\"><div><p class=\"eyebrow\">" + e(eyebrow)
                + "</p><h2>" + e(title) + "</h2></div>" + (helper == null ? "" : "<p class=\"helper\">" + e(helper) + "</p>")
                + "</div>" + content + "</article>";
    }

    static String kpi(String label, String value, String hint, String tone) {
        // same markup as the retirement app's KPI cards (historical-backtest.js): article.kpi > p.kpi-label, p.kpi-value, p.kpi-hint
        return "<article class=\"kpi" + (tone == null ? "" : " " + tone) + "\"><p class=\"kpi-label\">" + e(label)
                + "</p><p class=\"kpi-value\">" + e(value) + "</p>" + (hint == null ? "" : "<p class=\"kpi-hint\">" + e(hint) + "</p>")
                + "</article>";
    }

    static String badge(String text, String tone) {
        return "<span class=\"summary-status-badge compact " + tone + "\">" + e(text) + "</span>";
    }

    static String notice(String text) {
        return "<p class=\"cockpit-notice\" role=\"status\">" + e(text) + "</p>";
    }
}
