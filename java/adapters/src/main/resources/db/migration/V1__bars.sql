-- Daily bars cache: one row per symbol and trading day, as delivered by the provider.
CREATE TABLE bars (
    symbol TEXT    NOT NULL,
    date   TEXT    NOT NULL,  -- ISO yyyy-mm-dd, exchange local date
    open   REAL    NOT NULL,
    high   REAL    NOT NULL,
    low    REAL    NOT NULL,
    close  REAL    NOT NULL,
    volume INTEGER NOT NULL,
    source TEXT    NOT NULL,
    PRIMARY KEY (symbol, date)
);

-- When each symbol was last fetched and which date range that fetch covered.
CREATE TABLE fetch_log (
    symbol     TEXT PRIMARY KEY,
    fetched_at TEXT NOT NULL,  -- ISO instant
    range_from TEXT NOT NULL,
    range_to   TEXT NOT NULL,
    source     TEXT NOT NULL
);
