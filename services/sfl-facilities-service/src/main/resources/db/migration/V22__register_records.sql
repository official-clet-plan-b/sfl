CREATE TABLE facilities.register_records (
    id UUID PRIMARY KEY,
    system_code VARCHAR(10) NOT NULL,
    site_code VARCHAR(40) NOT NULL,
    record_type VARCHAR(60) NOT NULL,
    title VARCHAR(240) NOT NULL,
    status VARCHAR(40) NOT NULL,
    owner_reference VARCHAR(160),
    due_at TIMESTAMPTZ,
    severity VARCHAR(30),
    details TEXT,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX ix_register_records_scope ON facilities.register_records (system_code, site_code, status);
CREATE INDEX ix_register_records_due ON facilities.register_records (site_code, due_at);

-- Site scope is enforced in the database as well as in the service, like every other site-scoped table
-- (ADR 0007). Without this the table is readable across sites by any role that can reach it directly.
SELECT facilities.apply_site_scope_policies();
