# Twintigers Stock Research Cockpit

A personal investment research workstation that runs in Docker on the owner's computer (`docker-compose.yml`, ADR 0009;
`start.cmd` / `stop.cmd`, or `scripts\start.ps1`, `stop.ps1`, `status.ps1`). Claude Code opened in this folder gets one
MCP server from `.mcp.json`: **research-java** (stdio, `docker exec -i research-cockpit java -jar /app/research-mcp.jar`):
SEC health audit, price-action and candlestick (K-line) signals on daily bars, rule replay, portfolio review, alerts.
Needs the Cockpit running. Thresholds: `java\config\rules.properties`. Companies that file only in Canada (SEDAR+, e.g.
Kinaxis) have no SEC data.

Holdings, accounts, cash and transactions live in the Cockpit's own ledger (`PORTFOLIO_SOURCE=local`, ADR 0010), in the
Docker volume `twintigers_research-data`, copied nightly to `backups\`. Base currency CAD.

## Rules for answers

- **Advisory only.** Nothing here is investment advice. Present facts, calculations and assumptions separately; don't
  recommend trades. Offer considerations and questions instead.
- **Never compute a figure yourself.** Quote numbers exactly as the tools return them, with their sources and formulas.
  If a number is not available from a tool, say so.
- **No orders, no unasked writes.** No tool places orders. Change accounts, cash or transactions (Cockpit Transactions
  page) only when the owner asks for that specific change.
- State the price timestamp and source (Yahoo Finance, delayed) and the currency.
- TSX symbols carry `.TO` (ENB.TO); SEC data uses the US listing (TELUS is `TU` there, `T` is AT&T).
- Signals describe what price and volume did. The out-of-sample tuning (`docs\alerts\rule-tuning.md`) found no
  directional rule with a reliable edge on these stocks, so all are severity *info*. Never present a signal as a
  prediction or a reason to trade; cite `replay_rules` before calling one meaningful.

## Tools (research-java)

| Tool | Use |
|---|---|
| `financial_health` | 3-year + YTD SEC audit with sources and flags (F01–F11; banks exempt from cash conversion). |
| `price_signals` | Price-action, K-line and RSI/MACD signals with the bar values as evidence (default: holdings, 30 days). |
| `replay_rules` | How often each rule fired over 1–5 years and what price did 5/20 days later. |
| `portfolio_review` | Holdings, concentration, fundamental flags, recent signals. |
| `rule_catalog` | Every rule's definition and the thresholds in force. |
| `add_alert` | Price alerts (above, below, move_pct, trailing_atr) or a rule subscription. Only add what was asked, then confirm the exact alert back. |
| `list_alerts`, `remove_alert`, `mute_alert` | Manage them (ids from `list_alerts`). |
| `alert_status` | Is the monitor running? Last runs, notifications. Warn if the last evaluation is > 1 trading day old. |

The monitor is the container `research-monitor` (every 30 minutes; ntfy notifications; nightly database copy).
Guides: `docs\cockpit\README.md` (Cockpit), `docs\alerts\user-guide.md` (alerts).

## Layout

```
start.cmd, stop.cmd        one-click start / stop (Windows)
docker-compose.yml         research-cockpit (web + MCP) and research-monitor (ADR 0009)
secrets.env                SEC_USER_AGENT, ntfy topic (gitignored; template: secrets.env.example)
java\                      Java engine (Maven): domain, ports, application, adapters, app-mcp, app-web, app-worker, cli,
                           arch-tests; config\ (rules.properties, holidays); Dockerfile
docs\                      cockpit and alert guides, architecture overview, ADRs, rule catalog and tuning reports
scripts\                   start/stop/status/cockpit PowerShell scripts, docker-helpers.ps1
backups\                   nightly database copies (gitignored)
```

## Development

- Java: `cd java` then `mvn verify` (unit, jqwik property, golden-parity, WireMock, contract and ArchUnit tests). The
  domain stays pure Java (ArchUnit enforces it); only `adapters\Wiring` names adapters.
- `start.cmd` (or `docker compose up -d --build`) rebuilds the image. CLI in Docker:
  `docker exec research-cockpit java -jar /app/research-cli.jar <command>`.
- Never print or commit `secrets.env`, `portfolio.toml` or anything in `backups\`.
