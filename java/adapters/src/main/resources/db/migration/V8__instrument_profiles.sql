-- Sector, country and asset class per symbol (mirrored from Ghostfolio's asset profiles), for allocation and X-ray.
CREATE TABLE instrument_profiles (
    symbol          TEXT PRIMARY KEY,
    asset_class     TEXT,
    asset_sub_class TEXT,
    sectors         TEXT NOT NULL DEFAULT '{}',  -- JSON {name: weight}
    countries       TEXT NOT NULL DEFAULT '{}'   -- JSON {ISO code: weight}
);
