# ADR 0011: Intraday view, ready for a real-time feed

Date: 2026-10-20. Status: accepted; revised the same day for the quote-page chart.

## Context
The user wants to prepare for an intraday view. Until now everything used daily bars (`MarketData`, end of day) plus
the latest delayed quote for threshold alerts. Real-time data needs a paid feed or a broker API; free intraday bars
(Yahoo) are delayed about 15 minutes and cover the regular session only.

## Decision
- Domain: `Candle` (an intraday bar with its start instant; daily analysis keeps `Bar`), `Interval` (1m, 5m, 15m,
  30m, 1h, each with how much history the chart loads), `IntradayIndicators.vwap` (session VWAP, restarting each
  exchange day).
- Port `IntradayData`: `intraday(symbol, interval, from)` returns a `Series` that carries the exchange time zone, the
  source's name and its delay in minutes. Today `YahooChartClient` implements it (delay 15; one request per symbol and
  interval per minute at most, cached in memory). A real-time provider implements the same port with delay 0; a push
  stream (websocket) would be a separate port on top of it, feeding the same chart.
- Cockpit, Stock Detail: an Interval selector (Daily, 1 hour, 30, 15, 5, 1 min). Intraday shows candles, volume,
  session VWAP and the user's alert lines; times on the axis are the exchange's wall clock (09:30 is the Toronto open).
  The chart says where the data comes from, the delay and the last bar's time, and refreshes every minute while the
  newest bar is recent (session moving) and the tab is visible.
- Price rules, signals and the replay stay on daily bars: they were tuned (and mostly demoted) on daily data, and
  intraday versions would need their own out-of-sample check before being shown. Clicking a signal while on an
  intraday interval switches back to the daily chart.

## Revision: quote-page chart and stored bars
- Bars are stored by interval: `intraday_bars` (V7, symbol × interval × bar start) next to the daily `bars` table.
  `CachedIntradayData` answers from the store, asks the feed only for the tail since the last stored bar (re-reading the
  forming one) at most once a minute, never further back than the feed serves (1-minute: 7 days; 5- to 60-minute:
  60 days), and keeps what it stored, so history grows past the feed's window.
- One chart endpoint, `/api/chart/{symbol}?range=1D…MAX` or `?from=&to=` (plus `bars=` for intraday): 1D = 1-minute
  bars of the latest session, 5D = 5-minute bars of the last five sessions (or 15/30/60), short recent date spans =
  5-minute bars, everything else = daily bars with 50- and 200-day averages computed over 300 earlier days.
- The page (`quote-chart.js`): range buttons, date boxes, candle/line/area, averages, a volume pane, log scale,
  drag-to-zoom, wheel zoom and pan, + / − / ◀ / ▶ / Reset, an overview strip with a draggable window, and a readout
  of the bar under the cursor. The separate Interval menu of the first version is replaced by 1D/5D and Bars.

## Consequences
- Switching to a real-time source later is an adapter plus one line in `WebMain`/`Wiring`; the page and the JSON do
  not change, and the delay label goes to 0.
- Intraday alerts (e.g. a level crossed between two 30-minute monitor runs) are not part of this; the monitor still
  checks the delayed quote every 30 minutes.
