# Price-rule tuning: which signals are trustworthy on these stocks

Generated 2026-10-19 by `research-cli tune-rules`. History 2021-10-18 to 2026-10-16 for ENB.TO, ENB, FTS.TO, KXS.TO, BNS.TO, T.TO, IBM.

**Method (guarding against overfitting).** Each warning and watch rule with a direction was tried in a small fixed set of stricter versions (no parameter search). The best version was chosen on the **in-sample** period (2021-10-18 to 2024-03-19) only, then checked on the **out-of-sample** period (2024-03-20 to 2026-10-16) it had not seen. A rule stays a *warning* only if that version went the rule's way at least 55% of the time at both 5 and 20 trading days, with at least 15 cases in-sample and 8 out-of-sample. All other such rules are demoted to *info*: still shown in the Cockpit and by `price_signals`, but not as warnings and not pushed by the monitor.

**Base rate** (how often these stocks simply closed lower after 5 / 20 days, any day):

| Period | Days | Down after 5 d % | Down after 20 d % |
|---|---:|---:|---:|
| in-sample | 4251 | 49.5 | 50.2 |
| out-of-sample | 4389 | 44.1 | 43.3 |

A bearish rule at 50% is only slightly better than this base rate; the 55% bar asks for a clear edge.

## Before and after

Hit % = share of cases where the close 5 / 20 trading days later went the rule's way.

| Rule | Before: in-sample cases | 5 d % | 20 d % | Before: out-of-sample cases | 5 d % | 20 d % | Chosen version | After: in-sample cases | 5 d % | 20 d % | After: out-of-sample cases | 5 d % | 20 d % | Decision |
|---|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---|
| PA02_seller_controlled_drop | 78 | 50.0 | 55.1 | 71 | 39.4 | 41.4 | – | – | – | – | – | – | – | demote to info |
| PA04_gap_down_no_fill | 70 | 55.7 | 61.4 | 45 | 46.7 | 44.4 | as defined | 70 | 55.7 | 61.4 | 45 | 46.7 | 44.4 | demote to info |
| PA05_sustained_decline_on_volume | 121 | 43.8 | 41.3 | 91 | 41.8 | 50.0 | – | – | – | – | – | – | – | demote to info |
| PA06_support_breakdown | 136 | 52.9 | 51.5 | 137 | 38.0 | 37.3 | – | – | – | – | – | – | – | demote to info |
| K01_bearish_engulfing | 88 | 55.7 | 63.6 | 92 | 37.0 | 42.9 | as defined | 88 | 55.7 | 63.6 | 92 | 37.0 | 42.9 | demote to info |
| K02_shooting_star | 82 | 37.8 | 51.2 | 80 | 47.5 | 45.0 | next-day confirmation | 28 | 57.1 | 57.1 | 30 | 43.3 | 50.0 | demote to info |
| K03_evening_star | 10 | 50.0 | 70.0 | 11 | 36.4 | 36.4 | – | – | – | – | – | – | – | demote to info |
| K04_hanging_man | 72 | 48.6 | 52.8 | 101 | 46.5 | 46.5 | – | – | – | – | – | – | – | demote to info |
| K05_dark_cloud_cover | 9 | 88.9 | 55.6 | 20 | 50.0 | 52.6 | – | – | – | – | – | – | – | demote to info |
| K06_three_black_crows | 4 | 50.0 | 25.0 | 0 | – | – | – | – | – | – | – | – | – | demote to info |
| M03_macd_bearish_cross | 167 | 58.7 | 58.7 | 167 | 46.7 | 45.1 | as defined | 167 | 58.7 | 58.7 | 167 | 46.7 | 45.1 | demote to info |

## Every version tried

### PA02_seller_controlled_drop (Seller-controlled price drop, bearish)

no variant reached 55% at 5 and 20 days with 15+ cases in-sample.

| Version | In-sample cases | 5 d % | 20 d % | avg 20 d move % | Out-of-sample cases | 5 d % | 20 d % | avg 20 d move % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| as defined | 78 | 50.0 | 55.1 | -0.1 | 71 | 39.4 | 41.4 | 0.4 |
| volume ≥ 2× average | 35 | 34.3 | 48.6 | 0.7 | 30 | 33.3 | 36.7 | 2.1 |
| with the 200-day trend | 50 | 46.0 | 58.0 | 0.1 | 33 | 33.3 | 37.5 | 0.7 |
| next-day confirmation | 30 | 50.0 | 50.0 | -0.8 | 35 | 45.7 | 47.1 | 0.1 |
| 200-day trend + confirmation | 15 | 46.7 | 60.0 | -1.2 | 15 | 40.0 | 42.9 | -0.1 |
| volume ≥ 2× + 200-day trend | 25 | 24.0 | 44.0 | 1.8 | 18 | 27.8 | 27.8 | 3.4 |

### PA04_gap_down_no_fill (Gap down that does not fill, bearish)

best in-sample variant did not hold out-of-sample (45 cases, 46.7% / 44.4%).

| Version | In-sample cases | 5 d % | 20 d % | avg 20 d move % | Out-of-sample cases | 5 d % | 20 d % | avg 20 d move % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| as defined | 70 | 55.7 | 61.4 | -1.0 | 45 | 46.7 | 44.4 | 0.7 |
| volume ≥ 2× average | 26 | 46.2 | 53.8 | -0.5 | 18 | 55.6 | 27.8 | 2.3 |
| with the 200-day trend | 45 | 51.1 | 62.2 | -0.8 | 21 | 57.1 | 57.1 | -1.2 |
| next-day confirmation | 29 | 51.7 | 48.3 | 0.7 | 11 | 36.4 | 18.2 | 4.5 |
| 200-day trend + confirmation | 16 | 50.0 | 43.8 | 2.1 | 5 | 20.0 | 20.0 | 3.8 |
| volume ≥ 2× + 200-day trend | 17 | 29.4 | 58.8 | -0.2 | 10 | 50.0 | 30.0 | 2.8 |

### PA05_sustained_decline_on_volume (Sustained decline on rising volume, bearish)

no variant reached 55% at 5 and 20 days with 15+ cases in-sample.

| Version | In-sample cases | 5 d % | 20 d % | avg 20 d move % | Out-of-sample cases | 5 d % | 20 d % | avg 20 d move % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| as defined | 121 | 43.8 | 41.3 | 0.9 | 91 | 41.8 | 50.0 | 0.3 |
| volume ≥ 2× average | 29 | 44.8 | 44.8 | 0.6 | 24 | 58.3 | 65.2 | -1.1 |
| with the 200-day trend | 84 | 47.6 | 44.0 | 0.7 | 46 | 39.1 | 45.5 | 0.3 |
| next-day confirmation | 24 | 37.5 | 45.8 | 0.2 | 26 | 61.5 | 58.3 | -0.2 |
| 200-day trend + confirmation | 17 | 47.1 | 47.1 | 0.3 | 12 | 33.3 | 40.0 | 1.8 |
| volume ≥ 2× + 200-day trend | 23 | 52.2 | 43.5 | 0.6 | 10 | 40.0 | 44.4 | 0.5 |

### PA06_support_breakdown (Close below support, bearish)

no variant reached 55% at 5 and 20 days with 15+ cases in-sample.

| Version | In-sample cases | 5 d % | 20 d % | avg 20 d move % | Out-of-sample cases | 5 d % | 20 d % | avg 20 d move % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| as defined | 136 | 52.9 | 51.5 | 0.4 | 137 | 38.0 | 37.3 | 1.8 |
| volume ≥ 2× average | 20 | 35.0 | 45.0 | 2.2 | 23 | 43.5 | 43.5 | 2.3 |
| with the 200-day trend | 95 | 52.6 | 52.6 | 0.5 | 58 | 36.2 | 28.6 | 2.6 |
| next-day confirmation | 59 | 47.5 | 54.2 | -0.3 | 49 | 49.0 | 39.6 | 1.9 |
| 200-day trend + confirmation | 42 | 42.9 | 50.0 | 0.0 | 22 | 27.3 | 19.0 | 4.1 |
| volume ≥ 2× + 200-day trend | 12 | 25.0 | 50.0 | 2.6 | 11 | 36.4 | 18.2 | 5.1 |

### K01_bearish_engulfing (Bearish engulfing, bearish)

best in-sample variant did not hold out-of-sample (92 cases, 37.0% / 42.9%).

| Version | In-sample cases | 5 d % | 20 d % | avg 20 d move % | Out-of-sample cases | 5 d % | 20 d % | avg 20 d move % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| as defined | 88 | 55.7 | 63.6 | -1.3 | 92 | 37.0 | 42.9 | 0.5 |
| volume ≥ 2× average | 7 | 28.6 | 57.1 | -0.1 | 4 | 0.0 | 50.0 | -0.6 |
| with the 200-day trend | 40 | 55.0 | 57.5 | -0.5 | 14 | 50.0 | 50.0 | -0.2 |
| next-day confirmation | 29 | 51.7 | 62.1 | -1.3 | 29 | 44.8 | 41.4 | 0.0 |
| 200-day trend + confirmation | 11 | 45.5 | 54.5 | 0.0 | 5 | 40.0 | 40.0 | -4.6 |
| volume ≥ 2× + 200-day trend | 3 | 33.3 | 66.7 | 1.3 | 0 | – | – | – |
| prior 20-day move ≥ 5% | 31 | 54.8 | 51.6 | -0.7 | 41 | 39.0 | 46.3 | 0.5 |
| prior 20-day move ≥ 5% + confirmation | 11 | 63.6 | 63.6 | -2.0 | 11 | 36.4 | 27.3 | 1.1 |

### K02_shooting_star (Shooting star, bearish)

best in-sample variant did not hold out-of-sample (30 cases, 43.3% / 50.0%).

| Version | In-sample cases | 5 d % | 20 d % | avg 20 d move % | Out-of-sample cases | 5 d % | 20 d % | avg 20 d move % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| as defined | 82 | 37.8 | 51.2 | -0.3 | 80 | 47.5 | 45.0 | 0.6 |
| volume ≥ 2× average | 6 | 66.7 | 66.7 | -1.4 | 4 | 75.0 | 75.0 | -0.6 |
| with the 200-day trend | 29 | 37.9 | 37.9 | 0.2 | 9 | 66.7 | 55.6 | 0.5 |
| next-day confirmation | 28 | 57.1 | 57.1 | -0.7 | 30 | 43.3 | 50.0 | -0.3 |
| 200-day trend + confirmation | 11 | 54.5 | 54.5 | -0.6 | 3 | 0.0 | 33.3 | 0.5 |
| volume ≥ 2× + 200-day trend | 2 | 50.0 | 50.0 | 0.8 | 0 | – | – | – |
| prior 20-day move ≥ 5% | 33 | 48.5 | 57.6 | -0.7 | 37 | 43.2 | 40.5 | 0.9 |
| prior 20-day move ≥ 5% + confirmation | 11 | 54.5 | 36.4 | 0.8 | 12 | 33.3 | 50.0 | -0.9 |

### K03_evening_star (Evening star, bearish)

no variant reached 55% at 5 and 20 days with 15+ cases in-sample.

| Version | In-sample cases | 5 d % | 20 d % | avg 20 d move % | Out-of-sample cases | 5 d % | 20 d % | avg 20 d move % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| as defined | 10 | 50.0 | 70.0 | -2.1 | 11 | 36.4 | 36.4 | 2.7 |
| volume ≥ 2× average | 1 | 100.0 | 100.0 | -0.6 | 0 | – | – | – |
| with the 200-day trend | 4 | 25.0 | 75.0 | -0.8 | 2 | 0.0 | 50.0 | 4.5 |
| next-day confirmation | 3 | 33.3 | 33.3 | 1.1 | 4 | 25.0 | 25.0 | 1.8 |
| 200-day trend + confirmation | 2 | 50.0 | 50.0 | 0.7 | 0 | – | – | – |
| volume ≥ 2× + 200-day trend | 1 | 100.0 | 100.0 | -0.6 | 0 | – | – | – |
| prior 20-day move ≥ 5% | 2 | 50.0 | 50.0 | -1.9 | 4 | 25.0 | 25.0 | 3.1 |
| prior 20-day move ≥ 5% + confirmation | 0 | – | – | – | 2 | 50.0 | 50.0 | -0.1 |

### K04_hanging_man (Hanging man, bearish)

no variant reached 55% at 5 and 20 days with 15+ cases in-sample.

| Version | In-sample cases | 5 d % | 20 d % | avg 20 d move % | Out-of-sample cases | 5 d % | 20 d % | avg 20 d move % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| as defined | 72 | 48.6 | 52.8 | -0.8 | 101 | 46.5 | 46.5 | 0.9 |
| volume ≥ 2× average | 2 | 50.0 | 50.0 | -0.4 | 5 | 60.0 | 20.0 | 4.1 |
| with the 200-day trend | 21 | 38.1 | 42.9 | 1.1 | 20 | 60.0 | 55.0 | 0.6 |
| next-day confirmation | 10 | 70.0 | 70.0 | -2.8 | 17 | 52.9 | 56.3 | 0.1 |
| 200-day trend + confirmation | 2 | 50.0 | 50.0 | -0.8 | 5 | 80.0 | 60.0 | -1.2 |
| volume ≥ 2× + 200-day trend | 2 | 50.0 | 50.0 | -0.4 | 1 | 100.0 | 0.0 | 9.5 |
| prior 20-day move ≥ 5% | 35 | 51.4 | 48.6 | -0.6 | 37 | 35.1 | 45.9 | 1.6 |
| prior 20-day move ≥ 5% + confirmation | 5 | 60.0 | 40.0 | 0.4 | 6 | 50.0 | 50.0 | 2.6 |

### K05_dark_cloud_cover (Dark cloud cover, bearish)

no variant reached 55% at 5 and 20 days with 15+ cases in-sample.

| Version | In-sample cases | 5 d % | 20 d % | avg 20 d move % | Out-of-sample cases | 5 d % | 20 d % | avg 20 d move % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| as defined | 9 | 88.9 | 55.6 | -0.6 | 20 | 50.0 | 52.6 | -0.5 |
| volume ≥ 2× average | 0 | – | – | – | 0 | – | – | – |
| with the 200-day trend | 2 | 100.0 | 50.0 | -2.0 | 0 | – | – | – |
| next-day confirmation | 7 | 42.9 | 28.6 | 0.6 | 5 | 60.0 | 50.0 | 1.1 |
| 200-day trend + confirmation | 2 | 100.0 | 50.0 | -1.9 | 0 | – | – | – |
| volume ≥ 2× + 200-day trend | 0 | – | – | – | 0 | – | – | – |
| prior 20-day move ≥ 5% | 4 | 75.0 | 100.0 | -4.2 | 9 | 77.8 | 75.0 | -3.8 |
| prior 20-day move ≥ 5% + confirmation | 2 | 100.0 | 100.0 | -5.4 | 2 | 50.0 | 0.0 | 4.6 |

### K06_three_black_crows (Three black crows, bearish)

no variant reached 55% at 5 and 20 days with 15+ cases in-sample.

| Version | In-sample cases | 5 d % | 20 d % | avg 20 d move % | Out-of-sample cases | 5 d % | 20 d % | avg 20 d move % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| as defined | 4 | 50.0 | 25.0 | -1.0 | 0 | – | – | – |
| volume ≥ 2× average | 0 | – | – | – | 0 | – | – | – |
| with the 200-day trend | 2 | 50.0 | 0.0 | 2.6 | 0 | – | – | – |
| next-day confirmation | 1 | 0.0 | 0.0 | 2.3 | 0 | – | – | – |
| 200-day trend + confirmation | 1 | 0.0 | 0.0 | 2.3 | 0 | – | – | – |
| volume ≥ 2× + 200-day trend | 0 | – | – | – | 0 | – | – | – |
| prior 20-day move ≥ 5% | 0 | – | – | – | 0 | – | – | – |
| prior 20-day move ≥ 5% + confirmation | 0 | – | – | – | 0 | – | – | – |

### M03_macd_bearish_cross (MACD bearish cross, bearish)

best in-sample variant did not hold out-of-sample (167 cases, 46.7% / 45.1%).

| Version | In-sample cases | 5 d % | 20 d % | avg 20 d move % | Out-of-sample cases | 5 d % | 20 d % | avg 20 d move % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| as defined | 167 | 58.7 | 58.7 | -0.7 | 167 | 46.7 | 45.1 | 0.4 |
| volume ≥ 2× average | 16 | 50.0 | 37.5 | 2.3 | 14 | 50.0 | 35.7 | 2.2 |
| with the 200-day trend | 88 | 55.7 | 60.2 | -0.5 | 48 | 52.1 | 40.4 | 1.1 |
| next-day confirmation | 54 | 63.0 | 57.4 | -0.8 | 46 | 52.2 | 40.0 | 1.4 |
| 200-day trend + confirmation | 30 | 56.7 | 63.3 | -0.9 | 17 | 47.1 | 37.5 | 2.7 |
| volume ≥ 2× + 200-day trend | 7 | 28.6 | 42.9 | 2.3 | 8 | 75.0 | 37.5 | 2.2 |

## Configuration written

```properties
price.severity.PA02_seller_controlled_drop=info
price.severity.PA04_gap_down_no_fill=info
price.severity.PA05_sustained_decline_on_volume=info
price.severity.PA06_support_breakdown=info
price.severity.K01_bearish_engulfing=info
price.severity.K02_shooting_star=info
price.severity.K03_evening_star=info
price.severity.K04_hanging_man=info
price.severity.K05_dark_cloud_cover=info
price.severity.K06_three_black_crows=info
price.severity.M03_macd_bearish_cross=info
```

Small samples are noisy even with this split: treat kept warnings as "worth a look", never as predictions.
