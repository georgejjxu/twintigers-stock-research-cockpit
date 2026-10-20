-- George's threshold alerts. Soft-deleted (active = 0) so the history stays auditable.
CREATE TABLE alerts (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    kind           TEXT    NOT NULL,       -- ABOVE | BELOW | MOVE_PCT | TRAILING_ATR
    symbol         TEXT    NOT NULL,
    level          REAL,
    pct            REAL,
    reference      REAL,
    atr_multiple   REAL,
    since          TEXT,                   -- ISO date (trailing stop start)
    note           TEXT,
    created_at     TEXT    NOT NULL,
    muted_until    TEXT,
    active         INTEGER NOT NULL DEFAULT 1,
    state          TEXT    NOT NULL DEFAULT 'ARMED',  -- ARMED | TRIGGERED
    state_since    TEXT    NOT NULL,
    cooldown_until TEXT
);

-- Which rule results notify: scope (* or symbol/issuer), source (PRICE | FUNDAMENTAL), rule (id, prefix*, or *).
CREATE TABLE subscriptions (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    scope        TEXT    NOT NULL,
    source       TEXT    NOT NULL,
    rule         TEXT    NOT NULL,
    min_severity TEXT    NOT NULL,
    origin       TEXT    NOT NULL,          -- default | user
    created_at   TEXT    NOT NULL,
    muted_until  TEXT,
    active       INTEGER NOT NULL DEFAULT 1
);

-- Notification outbox: one row per event and channel; the unique key makes enqueueing idempotent.
CREATE TABLE outbox (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    dedupe_key      TEXT    NOT NULL,
    channel         TEXT    NOT NULL,
    title           TEXT    NOT NULL,
    body            TEXT    NOT NULL,
    priority        TEXT    NOT NULL,
    created_at      TEXT    NOT NULL,
    attempts        INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TEXT    NOT NULL,
    sent_at         TEXT,
    gave_up         INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,
    UNIQUE (dedupe_key, channel)
);

-- Heartbeat of the monitoring worker.
CREATE TABLE runs (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    mode          TEXT    NOT NULL,
    started_at    TEXT    NOT NULL,
    finished_at   TEXT,
    ok            INTEGER NOT NULL DEFAULT 0,
    symbols       INTEGER NOT NULL DEFAULT 0,
    notifications INTEGER NOT NULL DEFAULT 0,
    detail        TEXT
);

-- Last known holdings, for runs while Ghostfolio is down.
CREATE TABLE holdings_cache (
    symbol   TEXT PRIMARY KEY,
    saved_at TEXT NOT NULL
);
