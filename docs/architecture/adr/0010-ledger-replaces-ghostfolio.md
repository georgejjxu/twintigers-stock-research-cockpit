# ADR 0010: The Java ledger replaces Ghostfolio, after a parity period

Date: 2026-10-19. Status: accepted and completed. Switched on 2026-10-20 after a final sync (parity: 0 differences); Ghostfolio removed from the setup, its last dump kept in backups/.

## Context
The user wants Ghostfolio (TypeScript, plus Postgres and Redis) replaced by the Java engine. We replace what we use,
not the whole application: accounts and cash, transactions, holdings and values, returns, dividends. The local source
(ADR 0008) already did holdings from a ledger; returns, dividends, splits, foreign cash and its own accounts were
missing.

## Decision
Same approach as the Python retirement (ADR 0003): build next to the running system, compare daily, switch when the
numbers agree.

1. **Ledger mirror.** The local source keeps its own accounts and cash (`ledger_accounts`, V5), dated cash balances
   (`ledger_cash_history`, V6) and instrument names. `LedgerSync` (application, port `LedgerBook`) copies Ghostfolio's
   accounts, cash history and every buy, sell and dividend, keyed by Ghostfolio's id (`ledger.external_id`). It is
   repeatable: changed rows are updated, deleted ones removed; rows entered locally (no id) are never touched.
   Fees, interest and other Ghostfolio types are reported as skipped (the user has none).
2. **Returns and dividends in the domain.** `PerformanceCalculator` replays the transactions daily: time-weighted,
   buys at the start of their day at trade price incl. fee, sells at the end net of fee; price return (excluding
   dividends, comparable with Ghostfolio's net performance) and total return (including them). Splits from Yahoo's
   chart events (`CorporateActions` port) convert quantities to today's share units, since Yahoo's closes are
   split-adjusted. Like Ghostfolio, cash in the base currency is left out and cash in another currency is held like a
   position, so USD cash's exchange-rate moves count. `Dividends` gives income by month and lists dividends Yahoo
   reports on held shares with no DIVIDEND transaction near the ex-date (suggestions only).
   `PerformanceService` (application) feeds it from any source's transactions; the Cockpit's "Return YTD" uses the
   source's own figure when it has one (Ghostfolio), else the computed one.
3. **Parity.** `SourceParity` compares Ghostfolio (reference) with the ledger (candidate): accounts and cash, counts
   of buys/sells/dividends and shares per account exactly; market values within 1 % (different price snapshots and
   exchange rates); returns for 1d, ytd, 1y and max within 0.25 percentage points. The monitor runs sync + parity every
   evening after 18:30 ET while `PORTFOLIO_SOURCE=ghostfolio`, writing `docs/parity/ghostfolio-parity.md`.
   On demand: `research-cli ledger-sync`, `ghostfolio-parity`, `returns [--ledger]`, `dividends [--ledger]`.

## Consequences
- First run (2026-10-19): 0 differences. Exact items all match; values within 0.1 %; returns ytd −0.64 % (Ghostfolio)
  vs −0.61 % (Java), 1d −1.42 % vs −1.38 %; the rest is exchange-rate snapshots.
- The ledger follows Ghostfolio every evening, so entering real purchase prices in either the Cockpit or Ghostfolio
  keeps both in step until the switch.
- Step 4 (2026-10-20): the Cockpit edits account cash (`PortfolioWriter.setCash`, both sources, checked live on a
  throwaway Ghostfolio account) and has a Performance page (returns per period and year, value chart, allocation,
  dividends with unrecorded ones). Which further Ghostfolio screens the user uses is still open. The switch itself is `PORTFOLIO_SOURCE=local`; Ghostfolio then stays
  stopped but installed for a few weeks as a fallback.
- With later buys and sells, a time-weighted return and Ghostfolio's return on average investment can legitimately
  differ; the parity note says so, and the tolerance applies to the period when both are comparable.
