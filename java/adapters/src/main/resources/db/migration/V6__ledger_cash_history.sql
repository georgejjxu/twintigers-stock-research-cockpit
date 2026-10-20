-- Dated cash balances per ledger account (a balance holds from its date until the next one). Mirrored from
-- Ghostfolio's balance history; after the switch, each cash change in the ledger adds a row for that day.
CREATE TABLE ledger_cash_history (
    account TEXT NOT NULL,
    date    TEXT NOT NULL,   -- ISO date
    cash    TEXT NOT NULL,   -- decimal as text, in the account's currency
    PRIMARY KEY (account, date)
);
