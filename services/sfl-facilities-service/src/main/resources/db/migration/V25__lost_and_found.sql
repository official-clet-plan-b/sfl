-- S179 Lost-and-Found Register: found items, an immutable custody chain, claims with identity verification,
-- evidence by reference, escalations and an append-only history. Site-scoped tables get the row-level policy
-- at the foot of the file. Retention periods are configuration, one row per category.

CREATE SEQUENCE facilities.lf_item_seq;
CREATE SEQUENCE facilities.lf_claim_seq;

CREATE TABLE facilities.lf_retention_policies (
    category VARCHAR(20) PRIMARY KEY,
    unclaimed_days INTEGER NOT NULL CHECK (unclaimed_days > 0),
    personal_data_days INTEGER NOT NULL CHECK (personal_data_days > 0)
);
INSERT INTO facilities.lf_retention_policies (category, unclaimed_days, personal_data_days) VALUES
    ('DOCUMENT', 90, 30), ('ELECTRONICS', 90, 30), ('JEWELLERY', 180, 30), ('CASH_VALUABLES', 180, 30),
    ('CLOTHING', 60, 30), ('BAG', 90, 30), ('KEYS', 60, 30), ('MEDICAL', 30, 30), ('OTHER', 60, 30);

CREATE TABLE facilities.lf_storage_locations (
    id UUID PRIMARY KEY,
    site_code VARCHAR(40) NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    secure BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ux_lf_storage UNIQUE (site_code, code)
);

CREATE TABLE facilities.lf_items (
    id UUID PRIMARY KEY,
    reference VARCHAR(40) NOT NULL UNIQUE,
    -- Non-sensitive and unrelated to the item: this is what a claimant is given.
    claim_reference VARCHAR(20) NOT NULL UNIQUE,
    site_code VARCHAR(40) NOT NULL,
    category VARCHAR(20) NOT NULL,
    public_description VARCHAR(240) NOT NULL,
    private_description VARCHAR(2000),
    found_location VARCHAR(240) NOT NULL,
    found_at TIMESTAMPTZ NOT NULL,
    finder_reference VARCHAR(160) NOT NULL,
    initial_condition VARCHAR(240) NOT NULL,
    status VARCHAR(24) NOT NULL,
    unsafe BOOLEAN NOT NULL DEFAULT FALSE,
    unsafe_reason VARCHAR(1000),
    storage_location_id UUID REFERENCES facilities.lf_storage_locations (id),
    retention_until DATE NOT NULL,
    closed_at TIMESTAMPTZ,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_lf_item_category CHECK (category IN
        ('DOCUMENT', 'ELECTRONICS', 'JEWELLERY', 'CASH_VALUABLES', 'CLOTHING', 'BAG', 'KEYS', 'MEDICAL', 'OTHER')),
    CONSTRAINT ck_lf_item_status CHECK (status IN
        ('REGISTERED', 'STORED', 'ISOLATED', 'RELEASED', 'DISPOSED', 'HANDED_TO_AUTHORITIES'))
);
CREATE INDEX ix_lf_items_scope ON facilities.lf_items (site_code, status, found_at);

-- Every transfer has a sender, a receiver, a place and a time, and none can be changed or removed afterwards.
CREATE TABLE facilities.lf_custody_events (
    id UUID PRIMARY KEY,
    item_id UUID NOT NULL REFERENCES facilities.lf_items (id),
    site_code VARCHAR(40) NOT NULL,
    from_party VARCHAR(160) NOT NULL,
    to_party VARCHAR(160) NOT NULL,
    location VARCHAR(160) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(500),
    recorded_by VARCHAR(160) NOT NULL
);
CREATE INDEX ix_lf_custody_item ON facilities.lf_custody_events (item_id, occurred_at);

CREATE FUNCTION facilities.lf_custody_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'The custody chain is immutable: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER lf_custody_no_change BEFORE UPDATE OR DELETE ON facilities.lf_custody_events
    FOR EACH ROW EXECUTE FUNCTION facilities.lf_custody_immutable();

CREATE TABLE facilities.lf_claims (
    id UUID PRIMARY KEY,
    reference VARCHAR(40) NOT NULL UNIQUE,
    item_id UUID NOT NULL REFERENCES facilities.lf_items (id),
    site_code VARCHAR(40) NOT NULL,
    claimant_name VARCHAR(160),
    claimant_contact VARCHAR(160),
    claimant_description VARCHAR(2000),
    status VARCHAR(12) NOT NULL,
    identity_verified BOOLEAN NOT NULL DEFAULT FALSE,
    verification_method VARCHAR(20),
    verification_reference VARCHAR(160),
    verified_by VARCHAR(160),
    verified_at TIMESTAMPTZ,
    decision_reason VARCHAR(2000),
    decided_by VARCHAR(160),
    decided_at TIMESTAMPTZ,
    released_by VARCHAR(160),
    released_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    personal_data_purged_at TIMESTAMPTZ,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_lf_claim_status CHECK (status IN ('RECEIVED', 'VERIFIED', 'APPROVED', 'REFUSED', 'RELEASED', 'WITHDRAWN')),
    CONSTRAINT ck_lf_claim_method CHECK (verification_method IS NULL OR verification_method IN
        ('ID_DOCUMENT', 'STAFF_ID', 'VISITOR_RECORD', 'KNOWN_TO_STAFF'))
);
CREATE INDEX ix_lf_claims_item ON facilities.lf_claims (item_id, status);
CREATE INDEX ix_lf_claims_scope ON facilities.lf_claims (site_code, status);

CREATE TABLE facilities.lf_evidence (
    id UUID PRIMARY KEY,
    item_id UUID NOT NULL REFERENCES facilities.lf_items (id),
    claim_id UUID REFERENCES facilities.lf_claims (id),
    site_code VARCHAR(40) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    reference VARCHAR(240) NOT NULL,
    file_name VARCHAR(240) NOT NULL,
    media_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    content_hash VARCHAR(128) NOT NULL,
    retention_class VARCHAR(20) NOT NULL,
    submitted_by VARCHAR(160) NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_lf_evidence_kind CHECK (kind IN
        ('PHOTO', 'VERIFICATION', 'RELEASE_RECEIPT', 'DISPOSAL_AUTHORISATION', 'AUTHORITY_RECEIPT'))
);
CREATE INDEX ix_lf_evidence_item ON facilities.lf_evidence (item_id, kind);

CREATE TABLE facilities.lf_escalations (
    id UUID PRIMARY KEY,
    site_code VARCHAR(40) NOT NULL,
    item_id UUID NOT NULL REFERENCES facilities.lf_items (id),
    claim_id UUID,
    reason VARCHAR(30) NOT NULL,
    escalated_to VARCHAR(20) NOT NULL,
    detail VARCHAR(500),
    incident_state VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED',
    incident_reference VARCHAR(80),
    raised_at TIMESTAMPTZ NOT NULL,
    acknowledged_by VARCHAR(160),
    acknowledged_at TIMESTAMPTZ,
    CONSTRAINT ck_lf_escalation_reason CHECK (reason IN ('UNSAFE_ITEM', 'COMPETING_CLAIMS', 'RETENTION_EXPIRED')),
    CONSTRAINT ck_lf_escalation_incident CHECK (incident_state IN ('NOT_REQUIRED', 'PENDING_MANUAL', 'LINKED'))
);
CREATE UNIQUE INDEX ux_lf_escalation_open ON facilities.lf_escalations (item_id, reason) WHERE acknowledged_at IS NULL;
CREATE INDEX ix_lf_escalations_scope ON facilities.lf_escalations (site_code, raised_at DESC);

CREATE TABLE facilities.lf_history (
    id UUID PRIMARY KEY,
    site_code VARCHAR(40) NOT NULL,
    subject_type VARCHAR(12) NOT NULL,
    subject_id UUID NOT NULL,
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    actor VARCHAR(160) NOT NULL,
    reason VARCHAR(2000),
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_lf_history_subject ON facilities.lf_history (subject_id, occurred_at);

SELECT facilities.apply_site_scope_policies();
