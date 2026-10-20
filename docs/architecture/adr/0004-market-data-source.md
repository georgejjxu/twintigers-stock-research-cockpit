# ADR 0004: Daily bars from Yahoo Finance, Alpha Vantage as fallback, cached in SQLite

Date: 2026-10-19. Status: accepted.

## Context
Price-action and candlestick rules need daily open, high, low, close and volume for TSX and NYSE listings.
Ghostfolio stores only closing prices. Options: Yahoo Finance chart API (free, no key, unofficial, `.TO` symbols),
Alpha Vantage (key, 25 requests/day free), paid feeds (later, for intraday).

## Decision
`MarketData` port, end-of-day only. Default adapter `YahooChartClient`; `AlphaVantageClient` (TSX symbols mapped
to `.TRT`) is used only when `ALPHAVANTAGE_API_KEY` is set and Yahoo fails (`FallbackMarketData`).
`CachedMarketData` stores bars in SQLite (`~/.cache/research-tool/market.db`, schema by Flyway) and fetches a
symbol at most once a calendar day (Toronto time). Prices are as traded (not dividend-adjusted).

## Consequences
- No cost, no key needed for the default; works for the user's TSX and NYSE holdings.
- Yahoo can change or block the endpoint without notice; the fallback and the cache limit the damage, and the
  adapter is one class.
- Unadjusted prices: a dividend or split shows as a small gap; splits would distort history (none recently in
  the holdings). Intraday rules need a separate streaming port and a paid feed.
