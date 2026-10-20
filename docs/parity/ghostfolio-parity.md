# Ghostfolio parity: is the Java ledger ready to replace Ghostfolio?

Generated 2026-10-20 16:40 EDT by `research-cli ghostfolio-parity` (also nightly by the monitor). Reference: **ghostfolio**; candidate: **local** (the Java ledger, mirrored from Ghostfolio by `ledger-sync`).

**Result: no differences beyond the tolerances.**

*Published copy: every amount (cash, shares, values, prices, returns, gains) is replaced by 0. The structure and the verdicts are those of the real run.*

Exact items must match exactly. Market values may differ by up to 1.0 % (different price snapshots and exchange rates); returns by up to 0.25 percentage points. Ghostfolio's return is its net performance with currency effect; the Java figure is a time-weighted price return (excluding dividends) computed from the ledger and Yahoo daily closes. For a portfolio bought at once they are the same thing; with later buys and sells they can legitimately differ. Both count cash in a foreign currency (its exchange-rate moves) and leave base-currency cash out.


## Accounts and cash

| Item | ghostfolio | local | Status | Note |
|---|---|---|---|---|
| Cash CAD | 0 CAD | 0 CAD | match |  |
| Cash USD | 0 USD | 0 USD | match |  |
| My Account | 0 USD | 0 USD | match |  |
| RRSP | 0 CAD | 0 CAD | match |  |

## Transactions

| Item | ghostfolio | local | Status | Note |
|---|---|---|---|---|
| buy count | 8 | 8 | match |  |
| sell count | 0 | 0 | match |  |
| dividend count | 0 | 0 | match |  |

## Shares per account

| Item | ghostfolio | local | Status | Note |
|---|---|---|---|---|
| BNS.TO | RRSP: 0 | RRSP: 0 | match |  |
| ENB | Cash USD: 0 | Cash USD: 0 | match |  |
| ENB.TO | Cash CAD: 0; RRSP: 0 | Cash CAD: 0; RRSP: 0 | match |  |
| FTS.TO | RRSP: 0 | RRSP: 0 | match |  |
| IBM | Cash USD: 0 | Cash USD: 0 | match |  |
| KXS.TO | Cash CAD: 0 | Cash CAD: 0 | match |  |
| T.TO | Cash CAD: 0 | Cash CAD: 0 | match |  |

## Market value (CAD)

| Item | ghostfolio | local | Status | Note |
|---|---|---|---|---|
| BNS.TO | 0.00 | 0.00 | match | price 0.00 vs 0.00 CAD |
| ENB | 0.00 | 0.00 | within tolerance | price 0.00 vs 0.00 USD |
| ENB.TO | 0.00 | 0.00 | match | price 0.00 vs 0.00 CAD |
| FTS.TO | 0.00 | 0.00 | match | price 0.00 vs 0.00 CAD |
| IBM | 0.00 | 0.00 | within tolerance | price 0.00 vs 0.00 USD |
| KXS.TO | 0.00 | 0.00 | match | price 0.00 vs 0.00 CAD |
| T.TO | 0.00 | 0.00 | match | price 0.00 vs 0.00 CAD |
| all holdings | 0.00 | 0.00 | within tolerance |  |
| cash | 0.00 | 0.00 | within tolerance | exchange rates differ slightly |

## Return (%)

| Item | ghostfolio | local | Status | Note |
|---|---|---|---|---|
| 1d | 0.00 % | 0.00 % | match | gain 0.00 vs 0.00 |
| ytd | 0.00 % | 0.00 % | within tolerance | gain 0.00 vs 0.00 |
| 1y | 0.00 % | 0.00 % | within tolerance | gain 0.00 vs 0.00 |
| max | 0.00 % | 0.00 % | within tolerance | gain 0.00 vs 0.00 |
