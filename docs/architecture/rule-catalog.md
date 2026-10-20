# Rule catalog

Every rule is mechanical: it either fires or it doesn't, from filed numbers or daily bars, with the inputs that
triggered it attached as evidence. Thresholds are assumptions, not standards; all of them are in
`java/config/rules.properties`. Rules describe what happened. They do not predict, and nothing here is investment advice.

Severity: **warning** = classic warning sign, **watch** = worth a look, **info** = context (often the bullish
counterpart). Direction: what the pattern traditionally suggests, which the replay checks against history.

## Fundamental rules (financial_health)

Tested on the latest fiscal year against the one before (and on year-to-date where noted). F01–F07 are ported
from the Python research-tool with identical wording (parity-tested); F08–F11 are new.

| Id | Severity | Fires when | Default |
|---|---|---|---|
| F01_revenue_decline | warning | Revenue growth in the latest fiscal year is negative. | – |
| F02_ytd_revenue_decline | warning | Year-to-date revenue is below the same period last year. | – |
| F03_margin_drop | warning | Operating margin fell by more than the threshold (points). | 3.0 pts |
| F04_negative_fcf | warning | Free cash flow (operating cash flow − capex) is negative. | – |
| F05_low_cash_conversion | watch | Operating cash flow / net income is below the threshold while net income is positive. **Not applied to banks** (SEC SIC 6000–6199): deposits and loans swing a bank's operating cash flow. | 0.8× |
| F06_dilution | watch | Diluted weighted-average share count grew more than the threshold. | 2.0 % |
| F07_debt_up_cash_down | warning | Total debt rose more than the threshold while cash fell. | 20 % |
| F08_fcf_drop | warning | Free cash flow fell more than the threshold vs the prior year (prior year positive). | 30 % |
| F09_ytd_fcf_drop | warning | Year-to-date free cash flow fell more than the threshold vs the same period last year. | 30 % |
| F10_fcf_declining_years | watch | Free cash flow fell N years in a row, each year by at least the minimum. | 2 years, ≥5 % each |
| F11_net_debt_rise | watch | Net debt (total debt − cash) rose more than the threshold (prior year positive). | 10 % |

Total debt: one tag scheme per company, used for every column (see `DebtResolver`): (A) long-term debt
non-current + current, (B) total long-term debt, (C) long-term debt and finance leases + current portion; a scheme
is rejected at a date where it is under 25 % of the largest long-term debt tagged there (footnote fragments).
Short-term borrowings and commercial paper are added, never reported alone. Reporting currency only.

## Price rules (price_signals, replay_rules, portfolio_review)

Tested on each day's bar using only data up to that day (property-tested: no look-ahead). ATR is Wilder's
14-day average true range; relative volume is the day's volume over the average of the 20 days before it.
Reversal patterns need trend context: up-trend = close above its 20-day average and above the close 5 days
earlier (down-trend mirrored). The table below is generated from the code (`research-cli rules`).

| Id | Name | Severity | Direction | Definition |
|---|---|---|---|---|
| PA01_buyer_controlled_jump | Buyer-controlled price jump | info | bullish | Up day (close > open) whose true range is at least 1.5× the previous day's ATR(14), closing in the top 25% of the day's range, on volume at least 1.5× the 20-day average: buyers pushed price up and held it into the close. |
| PA02_seller_controlled_drop | Seller-controlled price drop | warning | bearish | Down day (close < open) whose true range is at least 1.5× the previous day's ATR(14), closing in the bottom 25% of the day's range, on volume at least 1.5× the 20-day average: sellers pushed price down and kept it there. |
| PA03_gap_up_hold | Gap up that holds | info | bullish | Opens at least 0.5% above the previous close, the whole day trades above the previous high (gap not filled), closes at or above the open, volume at least 1.2× average. |
| PA04_gap_down_no_fill | Gap down that does not fill | warning | bearish | Opens at least 0.5% below the previous close, the whole day trades below the previous low (gap not filled), closes at or below the open, volume at least 1.2× average. |
| PA05_sustained_decline_on_volume | Sustained decline on rising volume | warning | bearish | 5 lower closes in a row, or 5 closes below a falling 20-day average, while average volume over those days is at least 1.2× the 20-day average before them. Reported on the first day the condition holds. |
| PA06_support_breakdown | Close below support | warning | bearish | Close falls more than 0.25× ATR below a support level (at least 2 swing highs/lows within 0.5× ATR, from the last 120 days), after the previous 5 closes were all above that line. |
| PA07_resistance_breakout | Close above resistance | info | bullish | Close rises more than 0.25× ATR above a resistance level (same level definition as PA06), after the previous 5 closes were all below that line. |
| K01_bearish_engulfing | Bearish engulfing | warning | bearish | After an up-trend, an up day is followed by a down day whose body opens at or above the prior close, closes at or below the prior open, and is larger than the prior body. |
| K02_shooting_star | Shooting star | warning | bearish | After an up-trend, a day with an upper shadow at least 2.0× the body and at least half the range, and a lower shadow at most 15% of the range: buyers were rejected at the high. |
| K03_evening_star | Evening star | warning | bearish | After an up-trend: a long up day (body ≥ 0.6× ATR), then a small body (≤ 0.3× the first) sitting above the first close, then a down day closing below the middle of the first body. |
| K04_hanging_man | Hanging man | watch | bearish | After an up-trend, a day with a lower shadow at least 2.0× the body and at least half the range, and an upper shadow at most 15% of the range: selling appeared during the day. |
| K05_dark_cloud_cover | Dark cloud cover | warning | bearish | After an up-trend, a long up day is followed by a down day that opens above the prior close and closes below the middle of the prior body (but above its open). |
| K06_three_black_crows | Three black crows | warning | bearish | Three down days in a row after a close above the 20-day average, each body at least 0.5× ATR, each opening inside the previous body, each closing lower and in the bottom 30% of its range. |
| K07_hammer | Hammer | info | bullish | After a down-trend, a day with a lower shadow at least 2.0× the body and at least half the range, and an upper shadow at most 15% of the range: sellers were rejected at the low. |
| K08_bullish_engulfing | Bullish engulfing | info | bullish | After a down-trend, a down day is followed by an up day whose body opens at or below the prior close, closes at or above the prior open, and is larger than the prior body. |
| K09_morning_star | Morning star | info | bullish | After a down-trend: a long down day, then a small body sitting below the first close, then an up day closing above the middle of the first body. |
| M01_rsi_overbought | RSI turns overbought | watch | neutral | RSI(14) crosses above 70.0. |
| M02_rsi_oversold | RSI turns oversold | info | neutral | RSI(14) crosses below 30.0. |
| M03_macd_bearish_cross | MACD bearish cross | watch | bearish | MACD(12,26,9) line crosses below its signal line. |
| M04_macd_bullish_cross | MACD bullish cross | info | bullish | MACD(12,26,9) line crosses above its signal line. |

## Evidence

Each signal carries the numbers behind it, for example PA01: open, high, low, close, volume, previous close,
change %, true range, previous ATR, range as a multiple of ATR, close location (0 = low, 1 = high), average volume,
relative volume. Support/resistance breaks add the level, its number of swing points and their dates, and the ATR
buffer. Nothing in a signal's text is a number that is not also in its evidence.

## How well do they work here?

`replay_rules` (or `research-cli replay --holdings`) runs every rule over two years of history and reports how
often it fired and how price moved 5 and 20 trading days later. See `docs/parity/replay-holdings.md` for the first
run on the current holdings. Treat a rule as informative only if it fires often enough and its hit rate is clearly
away from 50 %.
