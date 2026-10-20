# Adding a portfolio source (e.g. a broker API)

The Cockpit, the MCP tools and the monitor read holdings only through the `PortfolioSource` port
(`java/ports/.../PortfolioSource.java`). To add a source (Questrade, Interactive Brokers, a CSV export of another
tracker, ...):

1. **Create the adapter** in its own package, e.g. `java/adapters/src/main/java/ca/twintigers/research/adapters/questrade/QuestradeSource.java`,
   implementing `PortfolioSource`:
   - `name()`: short id shown in the UI ("questrade").
   - `capabilities()`: declare only what the API really provides (ACCOUNTS, ACTIVITIES, PERFORMANCE).
   - `portfolio()`: one `Portfolio.Position` per symbol in **Yahoo form** (TSX: `ENB.TO`), quantity, last price,
     value in the base currency (CAD), and `sharesByAccount`; plus total cash in the base currency. Sort by value,
     largest first.
   - `accounts()`, `activities()` (newest first), `performance(range)`: or empty if not declared.
   - Use `adapters.http.Http` for calls (timeouts and retries); put tokens in `secrets.env`; never put a token in an
     error message.
2. **Wire it** in `adapters/Wiring.portfolioSource(...)`: add a `case "questrade" -> new QuestradeSource(...)`.
   Nothing else in the code base may refer to the new class (ArchUnit checks this; extend the rule's package list
   when you add the package).
3. **Prove it**: add `QuestradeSourceContractTest extends PortfolioSourceContract`, serving the contract's sample
   portfolio from WireMock (see `GhostfolioSourceContractTest`). The source is done when the suite passes.
4. **Switch**: set `PORTFOLIO_SOURCE=questrade` in `secrets.env`, restart the Cockpit and the monitor
   (`docker compose restart research-cockpit research-monitor`) and reconnect the MCP server. Watch list, alerts and history are kept: they live in
   the Cockpit's own database, not in the source.

Read-only by design: a source adapter must not place orders or change anything at the broker. The Cockpit's
Transactions page writes through the separate `PortfolioWriter` port (recording transactions, never orders). A broker
source should implement it with `enabled()` returning false (the page then explains that recording is switched off),
unless it is a tracker where recording transactions is the point; then add a `PortfolioWriterContract` test as well.
