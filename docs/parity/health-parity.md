# Health table parity: Python research-tool vs Java

Same cached SEC companyfacts for both (RESEARCH_OFFLINE=1). Numbers compared cell by cell; relative tolerance 1e-9 (ratios are rounded to 4 decimals on both sides).

| Ticker | Cells | Matching | Intended differences | Unexpected differences |
|---|---:|---:|---:|---:|
| ENB | 85 | 75 | 10 | 0 |
| TU | 51 | 51 | 0 | 0 |
| FTS | 51 | 51 | 0 | 0 |
| BNS | 85 | 85 | 1 | 0 |
| IBM | 85 | 85 | 0 | 0 |

**Result: PASS** — 347 of 357 cells identical; 11 intended differences; 0 unexpected.

Intended differences: Total debt and Net debt use one consistent tag scheme per company in the reporting currency (Python mixed tags per date, and currencies); the cash-conversion rule is not applied to banks; F08–F11 are new rules.

## ENB — ENBRIDGE INC.

Debt scheme (Java): C: long_term_debt_and_finance_leases + current portion. Bank: false.

| Row | Column | Python | Java | Verdict |
|---|---|---:|---:|---|
| Total debt | FY 2023-12-31 | 921,000,000 | 81,199,000,000 | intended (debt fix) |
| Total debt | FY 2024-12-31 | 102,348,000,000 | 101,672,000,000 | intended (debt fix) |
| Total debt | FY 2025-12-31 | 105,440,000,000 | 105,024,000,000 | intended (debt fix) |
| Total debt | YTD 2025-06-30 | 914,000,000 | 101,053,000,000 | intended (debt fix) |
| Total debt | YTD 2026-06-30 | 1,569,000,000 | 112,132,000,000 | intended (debt fix) |
| Net debt (debt − cash) | FY 2023-12-31 | -4,980,000,000 | 75,298,000,000 | intended (debt fix) |
| Net debt (debt − cash) | FY 2024-12-31 | 100,545,000,000 | 99,869,000,000 | intended (debt fix) |
| Net debt (debt − cash) | FY 2025-12-31 | 104,346,000,000 | 103,930,000,000 | intended (debt fix) |
| Net debt (debt − cash) | YTD 2025-06-30 | -289,000,000 | 99,850,000,000 | intended (debt fix) |
| Net debt (debt − cash) | YTD 2026-06-30 | -443,000,000 | 110,120,000,000 | intended (debt fix) |

Flags, Python: none

Flags, Java (all): F08_fcf_drop warning: Free cash flow fell 44% in the latest fiscal year (5,889 to 3,297 millions).; F09_ytd_fcf_drop warning: Year-to-date free cash flow is down 61% on the prior year (2,668 to 1,039 millions).; F10_fcf_declining_years watch: Free cash flow has fallen 2 years in a row (9,547 → 5,889 → 3,297 millions).


## TU — TELUS CORP

Debt scheme (Java): A: long_term_noncurrent + long_term_current. Bank: false.

| Row | Column | Python | Java | Verdict |
|---|---|---:|---:|---|

Flags, Python: watch: Diluted share count grew 2.8% (threshold 2.0%).

Flags, Java (all): F06_dilution watch: Diluted share count grew 2.8% (threshold 2.0%).


## FTS — FORTIS INC.

Debt scheme (Java): A: long_term_noncurrent + long_term_current. Bank: false.

| Row | Column | Python | Java | Verdict |
|---|---|---:|---:|---|

Flags, Python: warning: Free cash flow was negative in the latest fiscal year.

Flags, Java (all): F04_negative_fcf warning: Free cash flow was negative in the latest fiscal year.; F10_fcf_declining_years watch: Free cash flow has fallen 2 years in a row (-441 → -1,130 → -1,880 millions).


## BNS — BANK OF NOVA SCOTIA

Debt scheme (Java): varies by date (no single scheme covers every column). Bank: true.

| Row | Column | Python | Java | Verdict |
|---|---|---:|---:|---|

Flags, Python: watch: Operating cash flow was only 0.70× net income; earnings may be running ahead of cash.

Flags, Java (all): none


## IBM — INTERNATIONAL BUSINESS MACHINES CORP

Debt scheme (Java): A: long_term_noncurrent + long_term_current. Bank: false.

| Row | Column | Python | Java | Verdict |
|---|---|---:|---:|---|

Flags, Python: none

Flags, Java (all): F11_net_debt_rise watch: Net debt rose 16% in the latest fiscal year (41,026 to 47,673 millions).

