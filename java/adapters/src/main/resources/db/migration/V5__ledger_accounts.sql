-- The local portfolio source keeps its own accounts and instrument names (no longer only portfolio.toml), and the
-- ledger can mirror another source's transactions (Ghostfolio, until the switch): external_id is that source's id.
CREATE TABLE ledger_accounts (
    name       TEXT PRIMARY KEY,
    currency   TEXT NOT NULL,
    cash       TEXT NOT NULL,    -- decimal as text, in the account's currency
    updated_at TEXT NOT NULL
);

CREATE TABLE instruments (
    symbol     TEXT PRIMARY KEY, -- Yahoo form (ENB.TO)
    name       TEXT,
    currency   TEXT
);

ALTER TABLE ledger ADD COLUMN external_id TEXT;
CREATE UNIQUE INDEX ledger_external_id ON ledger(external_id) WHERE external_id IS NOT NULL;
