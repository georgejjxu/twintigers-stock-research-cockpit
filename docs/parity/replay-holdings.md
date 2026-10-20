# Rule replay

Every price rule run over each day of history (no look-ahead), with what happened 5 and 20 trading days later. Use it to tune thresholds.

## Rule replay: ENB.TO (2024-09-18 to 2026-10-16, 522 trading days)

| Rule | Severity | Direction | Fired | Avg 5-day move % | Avg 20-day move % | Went the rule's way, 5d % | 20d % |
|---|---|---|---:|---:|---:|---:|---:|
| PA01_buyer_controlled_jump | info | bullish | 9 | 2.18 | 2.47 | 100.00 | 50.00 |
| PA02_seller_controlled_drop | warning | bearish | 7 | 0.13 | 2.19 | 50.00 | 16.70 |
| PA03_gap_up_hold | info | bullish | 3 | 1.59 | 3.05 | 100.00 | 66.70 |
| PA04_gap_down_no_fill | warning | bearish | 2 | -0.42 | 0.42 | 50.00 | 50.00 |
| PA05_sustained_decline_on_volume | warning | bearish | 6 | 1.83 | 3.80 | 33.30 | 16.70 |
| PA06_support_breakdown | warning | bearish | 8 | -1.38 | 0.44 | 62.50 | 50.00 |
| PA07_resistance_breakout | info | bullish | 8 | 0.70 | 2.58 | 75.00 | 62.50 |
| K01_bearish_engulfing | warning | bearish | 14 | 0.56 | 2.01 | 28.60 | 28.60 |
| K02_shooting_star | warning | bearish | 12 | -1.13 | -0.44 | 58.30 | 50.00 |
| K03_evening_star | warning | bearish | 1 | -1.88 | -4.66 | 100.00 | 100.00 |
| K04_hanging_man | watch | bearish | 17 | 0.29 | 1.04 | 41.20 | 41.20 |
| K05_dark_cloud_cover | warning | bearish | 4 | -2.15 | -2.48 | 100.00 | 75.00 |
| K06_three_black_crows | warning | bearish | 0 | – | – | – | – |
| K07_hammer | info | bullish | 11 | 1.20 | 1.68 | 54.50 | 70.00 |
| K08_bullish_engulfing | info | bullish | 6 | 1.06 | 2.09 | 66.70 | 60.00 |
| K09_morning_star | info | bullish | 0 | – | – | – | – |
| M01_rsi_overbought | watch | neutral | 17 | 0.01 | -0.07 | – | – |
| M02_rsi_oversold | info | neutral | 6 | 1.40 | -0.54 | – | – |
| M03_macd_bearish_cross | watch | bearish | 20 | 0.05 | 0.81 | 40.00 | 35.00 |
| M04_macd_bullish_cross | info | bullish | 20 | 0.15 | 0.26 | 55.00 | 55.00 |

Moves are close-to-close % changes after the signal day. "Went the rule's way" = fell after a bearish rule, rose after a bullish one. Source: Yahoo Finance chart API (daily, end of day), cached locally. Small counts are not statistically meaningful.

## Rule replay: ENB (2024-09-17 to 2026-10-16, 523 trading days)

| Rule | Severity | Direction | Fired | Avg 5-day move % | Avg 20-day move % | Went the rule's way, 5d % | 20d % |
|---|---|---|---:|---:|---:|---:|---:|
| PA01_buyer_controlled_jump | info | bullish | 4 | 1.41 | 3.94 | 66.70 | 100.00 |
| PA02_seller_controlled_drop | warning | bearish | 7 | 1.53 | 2.43 | 42.90 | 57.10 |
| PA03_gap_up_hold | info | bullish | 5 | 1.14 | 3.96 | 80.00 | 100.00 |
| PA04_gap_down_no_fill | warning | bearish | 2 | 1.83 | -2.51 | 0.00 | 100.00 |
| PA05_sustained_decline_on_volume | warning | bearish | 9 | 2.86 | 3.58 | 11.10 | 33.30 |
| PA06_support_breakdown | warning | bearish | 25 | 0.75 | 2.83 | 32.00 | 24.00 |
| PA07_resistance_breakout | info | bullish | 18 | 0.81 | 1.75 | 55.60 | 66.70 |
| K01_bearish_engulfing | warning | bearish | 8 | 0.52 | 0.29 | 12.50 | 50.00 |
| K02_shooting_star | warning | bearish | 9 | 0.17 | 0.75 | 44.40 | 44.40 |
| K03_evening_star | warning | bearish | 1 | -0.63 | -0.27 | 100.00 | 100.00 |
| K04_hanging_man | watch | bearish | 11 | 0.20 | 0.34 | 36.40 | 63.60 |
| K05_dark_cloud_cover | warning | bearish | 5 | -0.39 | -1.52 | 40.00 | 80.00 |
| K06_three_black_crows | warning | bearish | 0 | – | – | – | – |
| K07_hammer | info | bullish | 11 | 0.46 | 2.08 | 54.50 | 90.90 |
| K08_bullish_engulfing | info | bullish | 7 | 0.71 | 0.63 | 71.40 | 57.10 |
| K09_morning_star | info | bullish | 0 | – | – | – | – |
| M01_rsi_overbought | watch | neutral | 10 | -0.79 | -2.32 | – | – |
| M02_rsi_oversold | info | neutral | 5 | 0.12 | -4.22 | – | – |
| M03_macd_bearish_cross | watch | bearish | 18 | -0.34 | 0.30 | 50.00 | 41.20 |
| M04_macd_bullish_cross | info | bullish | 19 | -0.25 | -0.21 | 52.60 | 50.00 |

Moves are close-to-close % changes after the signal day. "Went the rule's way" = fell after a bearish rule, rose after a bullish one. Source: Yahoo Finance chart API (daily, end of day), cached locally. Small counts are not statistically meaningful.

## Rule replay: FTS.TO (2024-09-18 to 2026-10-16, 522 trading days)

| Rule | Severity | Direction | Fired | Avg 5-day move % | Avg 20-day move % | Went the rule's way, 5d % | 20d % |
|---|---|---|---:|---:|---:|---:|---:|
| PA01_buyer_controlled_jump | info | bullish | 3 | 0.43 | 3.33 | 66.70 | 100.00 |
| PA02_seller_controlled_drop | warning | bearish | 10 | 0.67 | 1.26 | 30.00 | 40.00 |
| PA03_gap_up_hold | info | bullish | 3 | 1.08 | 3.24 | 100.00 | 66.70 |
| PA04_gap_down_no_fill | warning | bearish | 1 | 1.23 | 4.10 | 0.00 | 0.00 |
| PA05_sustained_decline_on_volume | warning | bearish | 10 | 0.78 | 0.42 | 20.00 | 60.00 |
| PA06_support_breakdown | warning | bearish | 17 | 1.35 | 3.20 | 23.50 | 18.80 |
| PA07_resistance_breakout | info | bullish | 18 | 0.38 | 1.88 | 58.80 | 93.80 |
| K01_bearish_engulfing | warning | bearish | 9 | -1.27 | -0.26 | 77.80 | 55.60 |
| K02_shooting_star | warning | bearish | 11 | 0.44 | -0.55 | 27.30 | 54.50 |
| K03_evening_star | warning | bearish | 2 | 1.05 | 0.39 | 50.00 | 50.00 |
| K04_hanging_man | watch | bearish | 12 | -0.13 | 1.85 | 27.30 | 36.40 |
| K05_dark_cloud_cover | warning | bearish | 2 | 1.33 | 6.39 | 0.00 | 0.00 |
| K06_three_black_crows | warning | bearish | 0 | – | – | – | – |
| K07_hammer | info | bullish | 6 | 1.33 | 1.81 | 66.70 | 80.00 |
| K08_bullish_engulfing | info | bullish | 10 | 0.61 | 1.79 | 60.00 | 70.00 |
| K09_morning_star | info | bullish | 0 | – | – | – | – |
| M01_rsi_overbought | watch | neutral | 11 | -0.27 | -0.14 | – | – |
| M02_rsi_oversold | info | neutral | 5 | 1.39 | 1.72 | – | – |
| M03_macd_bearish_cross | watch | bearish | 24 | 0.15 | 1.78 | 37.50 | 37.50 |
| M04_macd_bullish_cross | info | bullish | 24 | -0.49 | 1.53 | 50.00 | 66.70 |

Moves are close-to-close % changes after the signal day. "Went the rule's way" = fell after a bearish rule, rose after a bullish one. Source: Yahoo Finance chart API (daily, end of day), cached locally. Small counts are not statistically meaningful.

## Rule replay: KXS.TO (2024-09-18 to 2026-10-16, 522 trading days)

| Rule | Severity | Direction | Fired | Avg 5-day move % | Avg 20-day move % | Went the rule's way, 5d % | 20d % |
|---|---|---|---:|---:|---:|---:|---:|
| PA01_buyer_controlled_jump | info | bullish | 10 | -1.14 | 1.09 | 30.00 | 40.00 |
| PA02_seller_controlled_drop | warning | bearish | 11 | -1.06 | -8.91 | 54.50 | 72.70 |
| PA03_gap_up_hold | info | bullish | 7 | -2.77 | 0.85 | 14.30 | 42.90 |
| PA04_gap_down_no_fill | warning | bearish | 2 | 1.87 | 0.31 | 0.00 | 50.00 |
| PA05_sustained_decline_on_volume | warning | bearish | 10 | -0.08 | -4.87 | 50.00 | 70.00 |
| PA06_support_breakdown | warning | bearish | 12 | -0.06 | -4.12 | 50.00 | 66.70 |
| PA07_resistance_breakout | info | bullish | 8 | 1.07 | 2.50 | 100.00 | 66.70 |
| K01_bearish_engulfing | warning | bearish | 15 | -0.34 | -1.86 | 46.70 | 57.10 |
| K02_shooting_star | warning | bearish | 7 | 2.06 | -0.38 | 42.90 | 71.40 |
| K03_evening_star | warning | bearish | 0 | – | – | – | – |
| K04_hanging_man | watch | bearish | 11 | -0.91 | -3.24 | 81.80 | 72.70 |
| K05_dark_cloud_cover | warning | bearish | 0 | – | – | – | – |
| K06_three_black_crows | warning | bearish | 0 | – | – | – | – |
| K07_hammer | info | bullish | 9 | 0.84 | 1.40 | 77.80 | 55.60 |
| K08_bullish_engulfing | info | bullish | 11 | 0.09 | 4.68 | 27.30 | 72.70 |
| K09_morning_star | info | bullish | 1 | 5.94 | 13.01 | 100.00 | 100.00 |
| M01_rsi_overbought | watch | neutral | 7 | 0.38 | -2.83 | – | – |
| M02_rsi_oversold | info | neutral | 7 | 0.84 | -1.00 | – | – |
| M03_macd_bearish_cross | watch | bearish | 20 | 1.74 | 1.57 | 30.00 | 42.10 |
| M04_macd_bullish_cross | info | bullish | 20 | 0.80 | -0.07 | 47.40 | 52.60 |

Moves are close-to-close % changes after the signal day. "Went the rule's way" = fell after a bearish rule, rose after a bullish one. Source: Yahoo Finance chart API (daily, end of day), cached locally. Small counts are not statistically meaningful.

## Rule replay: BNS.TO (2024-09-18 to 2026-10-16, 522 trading days)

| Rule | Severity | Direction | Fired | Avg 5-day move % | Avg 20-day move % | Went the rule's way, 5d % | 20d % |
|---|---|---|---:|---:|---:|---:|---:|
| PA01_buyer_controlled_jump | info | bullish | 12 | -0.02 | 1.33 | 66.70 | 66.70 |
| PA02_seller_controlled_drop | warning | bearish | 5 | 1.22 | 3.06 | 20.00 | 20.00 |
| PA03_gap_up_hold | info | bullish | 6 | 0.70 | 4.02 | 66.70 | 100.00 |
| PA04_gap_down_no_fill | warning | bearish | 6 | 0.71 | 2.78 | 33.30 | 33.30 |
| PA05_sustained_decline_on_volume | warning | bearish | 8 | -1.23 | -1.37 | 62.50 | 71.40 |
| PA06_support_breakdown | warning | bearish | 9 | -0.18 | 0.58 | 44.40 | 62.50 |
| PA07_resistance_breakout | info | bullish | 12 | -0.03 | 3.46 | 50.00 | 90.90 |
| K01_bearish_engulfing | warning | bearish | 9 | 0.47 | 0.08 | 44.40 | 33.30 |
| K02_shooting_star | warning | bearish | 15 | 1.19 | 3.49 | 33.30 | 13.30 |
| K03_evening_star | warning | bearish | 1 | 1.09 | 7.09 | 0.00 | 0.00 |
| K04_hanging_man | watch | bearish | 8 | 1.20 | 4.27 | 37.50 | 0.00 |
| K05_dark_cloud_cover | warning | bearish | 2 | 0.21 | -0.22 | 50.00 | 100.00 |
| K06_three_black_crows | warning | bearish | 0 | – | – | – | – |
| K07_hammer | info | bullish | 6 | -2.81 | -2.39 | 16.70 | 40.00 |
| K08_bullish_engulfing | info | bullish | 5 | -0.37 | 0.17 | 20.00 | 40.00 |
| K09_morning_star | info | bullish | 0 | – | – | – | – |
| M01_rsi_overbought | watch | neutral | 20 | 0.55 | 3.70 | – | – |
| M02_rsi_oversold | info | neutral | 9 | -0.67 | 0.11 | – | – |
| M03_macd_bearish_cross | watch | bearish | 21 | 0.24 | 1.92 | 38.10 | 35.00 |
| M04_macd_bullish_cross | info | bullish | 20 | -0.32 | 1.68 | 50.00 | 65.00 |

Moves are close-to-close % changes after the signal day. "Went the rule's way" = fell after a bearish rule, rose after a bullish one. Source: Yahoo Finance chart API (daily, end of day), cached locally. Small counts are not statistically meaningful.

## Rule replay: T.TO (2024-09-18 to 2026-10-16, 522 trading days)

| Rule | Severity | Direction | Fired | Avg 5-day move % | Avg 20-day move % | Went the rule's way, 5d % | 20d % |
|---|---|---|---:|---:|---:|---:|---:|
| PA01_buyer_controlled_jump | info | bullish | 9 | -3.70 | -4.98 | 22.20 | 22.20 |
| PA02_seller_controlled_drop | warning | bearish | 14 | -0.94 | -0.79 | 50.00 | 50.00 |
| PA03_gap_up_hold | info | bullish | 4 | -2.29 | -1.48 | 25.00 | 50.00 |
| PA04_gap_down_no_fill | warning | bearish | 10 | -0.33 | -3.38 | 60.00 | 60.00 |
| PA05_sustained_decline_on_volume | warning | bearish | 21 | -1.02 | -2.13 | 61.90 | 60.00 |
| PA06_support_breakdown | warning | bearish | 11 | -0.51 | -4.53 | 36.40 | 90.90 |
| PA07_resistance_breakout | info | bullish | 13 | -2.37 | -4.54 | 30.80 | 15.40 |
| K01_bearish_engulfing | warning | bearish | 9 | 0.29 | -0.25 | 33.30 | 66.70 |
| K02_shooting_star | warning | bearish | 6 | -0.07 | -1.61 | 83.30 | 66.70 |
| K03_evening_star | warning | bearish | 2 | 0.89 | -1.44 | 0.00 | 50.00 |
| K04_hanging_man | watch | bearish | 11 | -1.77 | -2.95 | 72.70 | 81.80 |
| K05_dark_cloud_cover | warning | bearish | 0 | – | – | – | – |
| K06_three_black_crows | warning | bearish | 0 | – | – | – | – |
| K07_hammer | info | bullish | 11 | -0.07 | -5.48 | 45.50 | 9.10 |
| K08_bullish_engulfing | info | bullish | 14 | -1.27 | -4.70 | 35.70 | 28.60 |
| K09_morning_star | info | bullish | 0 | – | – | – | – |
| M01_rsi_overbought | watch | neutral | 2 | -3.32 | -11.19 | – | – |
| M02_rsi_oversold | info | neutral | 16 | -1.79 | -4.82 | – | – |
| M03_macd_bearish_cross | watch | bearish | 21 | -1.85 | -4.49 | 81.00 | 85.70 |
| M04_macd_bullish_cross | info | bullish | 21 | -1.72 | -3.53 | 30.00 | 20.00 |

Moves are close-to-close % changes after the signal day. "Went the rule's way" = fell after a bearish rule, rose after a bullish one. Source: Yahoo Finance chart API (daily, end of day), cached locally. Small counts are not statistically meaningful.

## Rule replay: IBM (2024-09-17 to 2026-10-16, 523 trading days)

| Rule | Severity | Direction | Fired | Avg 5-day move % | Avg 20-day move % | Went the rule's way, 5d % | 20d % |
|---|---|---|---:|---:|---:|---:|---:|
| PA01_buyer_controlled_jump | info | bullish | 11 | 0.03 | -2.52 | 45.50 | 30.00 |
| PA02_seller_controlled_drop | warning | bearish | 7 | 1.62 | 3.00 | 28.60 | 33.30 |
| PA03_gap_up_hold | info | bullish | 4 | 4.41 | -7.88 | 50.00 | 0.00 |
| PA04_gap_down_no_fill | warning | bearish | 10 | -0.02 | 0.90 | 70.00 | 50.00 |
| PA05_sustained_decline_on_volume | warning | bearish | 12 | 0.16 | 2.16 | 50.00 | 45.50 |
| PA06_support_breakdown | warning | bearish | 18 | 0.45 | 3.79 | 44.40 | 35.30 |
| PA07_resistance_breakout | info | bullish | 22 | 1.59 | -1.18 | 42.90 | 47.60 |
| K01_bearish_engulfing | warning | bearish | 7 | 2.22 | 1.93 | 16.70 | 33.30 |
| K02_shooting_star | warning | bearish | 6 | -1.30 | 2.04 | 66.70 | 50.00 |
| K03_evening_star | warning | bearish | 1 | 7.09 | 7.47 | 0.00 | 0.00 |
| K04_hanging_man | watch | bearish | 15 | 1.10 | 2.02 | 40.00 | 53.30 |
| K05_dark_cloud_cover | warning | bearish | 2 | -13.61 | -13.99 | 100.00 | 100.00 |
| K06_three_black_crows | warning | bearish | 0 | – | – | – | – |
| K07_hammer | info | bullish | 8 | 2.05 | 2.66 | 75.00 | 50.00 |
| K08_bullish_engulfing | info | bullish | 7 | 1.68 | 4.96 | 71.40 | 57.10 |
| K09_morning_star | info | bullish | 1 | 1.03 | 7.06 | 100.00 | 100.00 |
| M01_rsi_overbought | watch | neutral | 13 | 0.62 | -2.01 | – | – |
| M02_rsi_oversold | info | neutral | 7 | 3.40 | 7.51 | – | – |
| M03_macd_bearish_cross | watch | bearish | 16 | -1.52 | -0.44 | 62.50 | 50.00 |
| M04_macd_bullish_cross | info | bullish | 16 | 0.90 | -0.37 | 50.00 | 46.70 |

Moves are close-to-close % changes after the signal day. "Went the rule's way" = fell after a bearish rule, rose after a bullish one. Source: Yahoo Finance chart API (daily, end of day), cached locally. Small counts are not statistically meaningful.

