-- A physical tag identifies exactly one asset. Without this a reader that sees a tag cannot tell
-- which asset it has found, and two registrations can silently claim the same tag. Compared without
-- regard to case because tag values are typed by people and read by hardware.
CREATE UNIQUE INDEX uq_asset_references_tag
    ON asset_visibility.asset_references (lower(external_reference))
    WHERE external_reference IS NOT NULL;

-- Custody and location are a chain of responsibility. The register keeps only the current link, so
-- every change is also written here and nothing in this table is ever updated or deleted.
CREATE TABLE asset_visibility.asset_history (
    id UUID PRIMARY KEY,
    asset_id UUID NOT NULL REFERENCES asset_visibility.asset_references (id),
    change_type VARCHAR(40) NOT NULL,
    from_value VARCHAR(300),
    to_value VARCHAR(300),
    source VARCHAR(40) NOT NULL,
    source_reference VARCHAR(160),
    actor_id VARCHAR(160) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_asset_history_asset_time
    ON asset_visibility.asset_history (asset_id, occurred_at DESC);
