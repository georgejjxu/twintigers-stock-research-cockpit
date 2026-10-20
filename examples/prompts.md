# Example prompts for Claude

Open Claude Code in this folder (the Cockpit must be running: `start.cmd`). The tool Claude should reach for is in
brackets; you don't have to name it. Everything the tools return is advisory: sourced facts and formulas, not
recommendations.

## Portfolio

- "Review my portfolio: concentration, fundamentals and recent signals." *(research-java `portfolio_review`)*
- "Any warning signals on my holdings in the last 30 days?" *(`price_signals`)*
- "Replay all the rules over two years on my holdings: which ones actually worked?" *(`replay_rules`)*

## One company

- "Run a financial health audit on IBM and explain the free-cash-flow flags and the debt figure." *(`financial_health`)*
- "Run a financial health audit on TELUS." *(SEC files TELUS as **TU**, not T)*
- "Compare Enbridge's and Fortis's financial health over three years." *(`financial_health` ENB, FTS)*
- "Show seller-controlled drops and support breakdowns on ENB.TO over the last 6 months, with the bar values."
  *(`price_signals` symbols=ENB.TO lookback_days=183)*
- "List the rules and their thresholds." *(`rule_catalog`)*

## Alerts

- "Alert me if ENB.TO goes above 75." *(`add_alert`)*
- "Which alerts do I have, and is the monitor running?" *(`list_alerts`, `alert_status`)*
