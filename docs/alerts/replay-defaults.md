# Default alert subscriptions, from the rule replay

Generated 2026-10-19 by `research-cli alerts tune-defaults --years 2 --min-hit 55.0 --min-fires 10`.

Rule: notify by default for every **warning**-severity price rule that fired at least 10 times over the replay and went the rule's way at least 55.0 % of the time 20 trading days later; plus every fundamental flag at warning severity. Everything else stays available in `price_signals` and can be subscribed to by hand.

**Chosen price rules:** none

## Rule replay, all of ENB.TO, ENB, FTS.TO, KXS.TO, BNS.TO, T.TO, IBM combined

| Rule | Severity | Direction | Fired | Avg 5-day move % | Avg 20-day move % | Went the rule's way, 5d % | 20d % |
|---|---|---|---:|---:|---:|---:|---:|
| PA01_buyer_controlled_jump | info | bullish | 58 | -0.31 | 0.04 | 54.61 | 49.66 |
| PA02_seller_controlled_drop | info | bearish | 61 | 0.18 | -0.46 | 41.80 | 45.07 |
| PA03_gap_up_hold | info | bullish | 32 | 0.22 | 0.98 | 56.26 | 62.52 |
| PA04_gap_down_no_fill | info | bearish | 33 | 0.26 | -0.23 | 48.48 | 51.51 |
| PA05_sustained_decline_on_volume | info | bearish | 76 | 0.19 | -0.29 | 44.73 | 54.17 |
| PA06_support_breakdown | info | bearish | 100 | 0.31 | 1.03 | 38.99 | 43.18 |
| PA07_resistance_breakout | info | bullish | 99 | 0.44 | 0.63 | 54.58 | 63.24 |
| K01_bearish_engulfing | info | bearish | 71 | 0.25 | 0.17 | 38.27 | 46.34 |
| K02_shooting_star | info | bearish | 66 | 0.26 | 0.72 | 46.96 | 45.43 |
| K03_evening_star | info | bearish | 9 | 1.16 | 0.68 | 33.33 | 50.00 |
| K04_hanging_man | watch | bearish | 85 | 0.03 | 0.47 | 47.39 | 51.01 |
| K05_dark_cloud_cover | info | bearish | 15 | -2.31 | -2.21 | 60.00 | 73.33 |
| K06_three_black_crows | info | bearish | 0 | – | – | – | – |
| K07_hammer | info | bullish | 62 | 0.53 | 0.19 | 56.45 | 56.30 |
| K08_bullish_engulfing | info | bullish | 60 | 0.18 | 0.93 | 48.33 | 54.33 |
| K09_morning_star | info | bullish | 2 | 3.49 | 10.04 | 100.00 | 100.00 |
| M01_rsi_overbought | watch | neutral | 80 | 0.05 | -0.25 | – | – |
| M02_rsi_oversold | info | neutral | 55 | 0.34 | -0.84 | – | – |
| M03_macd_bearish_cross | watch | bearish | 140 | -0.18 | 0.25 | 47.87 | 46.56 |
| M04_macd_bullish_cross | info | bullish | 140 | -0.16 | -0.07 | 48.07 | 51.21 |

Averages are weighted by the number of fires per symbol (approximate: signals near the end of the history have no 20-day outcome yet). A rule near 50% has no edge on these stocks; below 50% means price more often did the opposite.
