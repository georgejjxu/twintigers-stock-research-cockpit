-- Transactions recorded by the local portfolio source (PORTFOLIO_SOURCE=local), entered in the Cockpit.
CREATE TABLE ledger (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    date       TEXT    NOT NULL,  -- ISO date
    type       TEXT    NOT NULL,  -- BUY | SELL | DIVIDEND
    account    TEXT    NOT NULL,
    symbol     TEXT    NOT NULL,  -- Yahoo form (ENB.TO)
    quantity   TEXT    NOT NULL,  -- decimal as text (exact)
    unit_price TEXT    NOT NULL,
    fee        TEXT    NOT NULL,
    currency   TEXT    NOT NULL,
    comment    TEXT,
    created_at TEXT    NOT NULL,
    updated_at TEXT
);
