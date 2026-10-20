-- Intraday bars by interval (1m, 5m, 15m, 30m, 1h), as delivered by the intraday provider (Yahoo, delayed).
-- Daily bars stay in `bars` (V1). The newest bar of a session may be replaced while it is still forming.
CREATE TABLE intraday_bars (
    symbol   TEXT    NOT NULL,
    interval TEXT    NOT NULL,  -- Interval.code()
    start_ts INTEGER NOT NULL,  -- epoch seconds, start of the bar
    open     REAL    NOT NULL,
    high     REAL    NOT NULL,
    low      REAL    NOT NULL,
    close    REAL    NOT NULL,
    volume   INTEGER NOT NULL,
    PRIMARY KEY (symbol, interval, start_ts)
);

-- When each symbol and interval was last fetched (the store answers alone within a minute of it).
CREATE TABLE intraday_fetch_log (
    symbol     TEXT NOT NULL,
    interval   TEXT NOT NULL,
    fetched_at TEXT NOT NULL,   -- ISO instant
    zone       TEXT NOT NULL,   -- exchange time zone of the listing
    source     TEXT NOT NULL,
    delay_min  INTEGER NOT NULL,
    PRIMARY KEY (symbol, interval)
);
