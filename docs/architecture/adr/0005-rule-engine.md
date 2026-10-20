# ADR 0005: Rule engine design

Date: 2026-10-19. Status: accepted.

## Context
The user asked for warning rules on price action ("buyer-controlled price jump", "seller-controlled price drop",
sustained decline on volume) and candlestick ("K-line") patterns, plus fixes to the fundamental rules. Rules must
be explainable, tunable, and checked against history before they are trusted. An LLM must never produce the numbers.

## Decision
- A rule is data: `PriceRule(id, name, severity, direction, definition, evaluator)`. The definition string is the
  documentation; the rule catalog is generated from it.
- Evaluation is a pure function of the bars up to day i (`MarketContext` precomputes indicators once). No look-ahead,
  verified by a property test, so a replay gives the same signals the rule would have given live.
- Every signal and flag carries evidence: the exact bar and indicator values (or filed figures) that triggered it.
- Edge-triggered state rules (sustained decline, RSI/MACD crosses, level breaks with 5 confirming closes) so a
  condition reports once, not every day it persists.
- Reversal patterns need trend context (close vs 20-day average and vs 5 days earlier).
- Indicators (SMA, EMA, Wilder ATR and RSI, MACD) are implemented in the domain (about 150 lines) instead of using
  ta4j: exact control over definitions and NaN handling, no dependency in the pure domain, and every value citable.
  This deviates from the original plan (ta4j).
- Thresholds live in `java/config/rules.properties`; `replay_rules` reports fires, average 5/20-day moves and how
  often the move went the rule's way, per rule and combined.

## Consequences
- The first replay (2 years, 7 holdings) shows most bearish rules near or below a 50 % hit rate on these
  low-volatility dividend stocks: useful as descriptions of what happened, not as predictions. Thresholds should
  be tuned with the replay before alerts are trusted.
- Adding a rule = one entry in `PriceRules` + a test; it appears in the catalog, the tools and the replay automatically.
