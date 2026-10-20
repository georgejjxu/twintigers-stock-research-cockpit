-- George's watch list: ours, independent of the portfolio source (survives replacing Ghostfolio).
CREATE TABLE watchlist (
    symbol   TEXT PRIMARY KEY,   -- Yahoo form, upper case (ENB.TO, IBM)
    note     TEXT,
    added_at TEXT NOT NULL
);
