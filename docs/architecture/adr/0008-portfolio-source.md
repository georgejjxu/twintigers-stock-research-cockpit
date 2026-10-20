# ADR 0008: Portfolio source abstraction (replacing Ghostfolio)

Date: 2026-10-19. Status: accepted.

## Context
The user wants to be able to switch from Ghostfolio to another holdings source later (another tracker, or a broker
API such as Questrade or Interactive Brokers) without rewriting the Cockpit, the MCP tools or the monitor.

## Decision
- One port, `PortfolioSource`: `name()`, `capabilities()` (ACCOUNTS, ACTIVITIES, PERFORMANCE), `portfolio()`
  (positions with market value in the base currency, per-account shares, cash), `accounts()`, `activities()`,
  `dividends()` (default: DIVIDEND activities) and `performance(range)`. Optional parts return empty lists or
  `Optional.empty()` when the capability is not declared, so callers degrade gracefully instead of failing.
- Two implementations today: `adapters.ghostfolio.GhostfolioClient` (REST) and
  `adapters.local.LocalPortfolioSource` (accounts and cash from portfolio.toml, transactions in the SQLite `ledger`
  table (V4), Yahoo daily closes and FX pairs; holdings come from the ledger once it has buys/sells, else from
  portfolio.toml; no performance). Chosen by `PORTFOLIO_SOURCE=ghostfolio|local` (secrets.env or environment).
- Recording transactions is a second, separate port, `PortfolioWriter` (`create`, `update`, `delete` an
  `ActivityDraft`; `enabled()`; `accountNames()`). Both sources implement it. Every draft is validated in the domain
  (`ActivityDraft.problems`) before anything is sent. Ghostfolio writes are off unless `GHOSTFOLIO_WRITES=enabled`;
  the Cockpit's Transactions page shows a confirmation of the exact change before every write.
  `PortfolioWriterContract` is the shared suite (`GhostfolioWriterContractTest` against a stateful fake Ghostfolio,
  `LocalWriterContractTest`). A broker source may leave writing off (`enabled()` false): the page then says why.
- Everything that is ours stays in our own SQLite store, never in the source: watch list (`WatchlistStore`, V3),
  alerts and subscriptions (V2), bar cache, run log. A swap loses nothing.
- Only the composition root (`adapters.Wiring`) may name a source adapter: ArchUnit rules
  `ghostfolioIsReplaceable` and `portfolioSourcesAreOnlyWiredInTheCompositionRoot` fail the build otherwise.
- `PortfolioSourceContract` is one JUnit suite every source must pass (`GhostfolioSourceContractTest` against
  WireMock, `LocalSourceContractTest` against a temporary portfolio.toml); a new source is done when its subclass
  passes.

Update (ADR 0010): the local source now keeps its own accounts, cash history and instrument names, and mirrors
Ghostfolio through the `LedgerBook` port; returns are computed for any source by `PerformanceService`.

## Consequences
- The Cockpit, MCP tools (`portfolio_review`, `price_signals` for holdings), the monitor and the CLI work with any
  source; verified by running the Cockpit with `PORTFOLIO_SOURCE=local` against the user's portfolio.toml.
- Features that need cost basis (returns) appear only with sources that declare PERFORMANCE.
- The Python research-tool still reads Ghostfolio directly; it is the reference implementation being retired
  (ADR 0003), not part of this seam.
