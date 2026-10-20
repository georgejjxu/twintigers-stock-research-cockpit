# Release notes

## Version 1.0.0 (2026-10-20): first public release

The Twintigers Stock Research Cockpit is a private investment research workstation that runs on your own computer. You keep
your accounts and transactions in it; it shows how your portfolio is doing, charts any stock or market, checks
companies' finances from their SEC filings, and alerts you on your phone when a price you care about is reached. It also
gives Claude a set of research tools that always show their sources.

**It is not investment advice and it never places trades.**

### Getting it running

1. Install **Docker Desktop** (Windows, macOS or Linux).
2. Download this project and open its folder.
3. **Windows:** double-click `start.cmd`. The first time, it creates a settings file called `secrets.env` and stops.
   Open that file, put your name and email after `SEC_USER_AGENT=` (the SEC asks every user to identify themselves),
   save, and double-click `start.cmd` again.
   **macOS / Linux:** `cp secrets.env.example secrets.env`, edit it, `mkdir -p backups`, then `docker compose up -d --build`.
4. The first start builds the app (about 5 minutes). Then your browser opens <http://localhost:8099>.
5. On the **Transactions** page, click **Add an account**, then record your purchases.

To stop it, double-click `stop.cmd` (or `docker compose stop`). Your data stays.

### What's in it

**Your portfolio**
- **Portfolio**: what you own, what it's worth, what you paid on average and your gain or loss on each holding, how much
  of your money sits in one company, and warning flags from the companies' financial statements.
- **Performance**: your return for today, this week, month and year, the last 12 months and since you started; a chart
  of your money over time; how you did compared with the Canadian (S&P/TSX) and US (S&P 500) markets; your best and
  worst holdings; how your money is spread across companies, currencies, accounts, sectors, regions and countries;
  dividends received, and dividends you may have forgotten to record.
- **X-ray**: simple checks of how your portfolio is built: too much in one company, one account, one currency, one
  sector or one region; an emergency fund; fees. You choose the limits.
- **FIRE**: how much your savings could pay you each year at a withdrawal rate you choose, and how long it may take to
  reach a target.
- **Transactions**: add accounts, record buys, sells and dividends, change cash balances and move cash between accounts.
  Every change shows you exactly what will happen and waits for you to confirm.

**Research**
- **Stock Detail**: a chart like the big finance websites: 1 day to all history, or your own dates; candles, a line or an
  area; 50- and 200-day averages; volume; log scale; zoom by dragging or with the mouse wheel; an overview strip below
  the chart; the price under your mouse. The 1-day and 5-day views use minute-by-minute prices (about 15 minutes behind).
  It also shows your position in that stock and the company's financial health with links to its filings.
- **Why did this signal appear?** Click any price signal to see the exact days and price levels behind it, explained in
  plain words.
- **Markets**: the Fear & Greed Index (how fearful or greedy US investors are, 0–100) with what goes into it and its
  history, a crypto version, and the main indices, interest rates, the US dollar, gold, oil and bitcoin.
- **Watch List**: stocks you follow, with your own notes.

**Alerts**
- Tell it "let me know if ENB.TO goes above 75" (on the Alerts page, or by asking Claude). It checks every 30 minutes
  during market hours and sends a notification to your phone through the free ntfy app.

**With Claude**
- Open Claude Code in the project folder and ask, for example, "Review my portfolio" or "Run a financial health audit
  on IBM". Every number comes with its source.

### Your data

- Everything stays on your computer, in a Docker storage volume. Each evening a copy is saved in the `backups` folder
  (the last 14 days are kept). Copy that folder somewhere safe from time to time.
- Prices, dividends and splits come from Yahoo Finance (free, delayed, unofficial), company financials from the SEC, the
  Fear & Greed Index from CNN and alternative.me. Downloads are stored locally so each is fetched only once a day (or
  once a minute while you watch a 1-day chart).

### Good to know

- Prices are delayed (about 15 minutes intraday; daily closes are final after 4:15 pm Eastern).
- The price signals are descriptions, not predictions: the built-in five-year test found none with a reliable edge, so
  they are shown as information only.
- The app is for one person on one computer. It does not have a login. Do not open its port to a network.
- Windows notifications are not supported (the alert monitor runs in Docker); use the ntfy phone app.

### For people who used the earlier private version with Ghostfolio

Ghostfolio is no longer needed. The Cockpit keeps its own records. To bring your data over once, put
`GHOSTFOLIO_URL` and `GHOSTFOLIO_TOKEN` in `secrets.env`, restart, and run
`docker exec research-cockpit java -jar /app/research-cli.jar ledger-sync`. Then remove those two lines.

### License

Apache License 2.0. See `LICENSE`, `NOTICE` and `THIRD-PARTY-NOTICES.md`.
