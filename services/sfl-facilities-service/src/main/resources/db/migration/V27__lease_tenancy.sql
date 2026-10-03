-- S177 Lease & Tenancy Management: agreements with terms, obligations with due-date alerts, amendments that
-- preserve every prior version and its approval chain, documents with expiry, a business calendar, and an
-- append-only history. Site-scoped tables get the row-level policy at the foot of the file; the calendar and
-- settings are organisation-wide.

CREATE SEQUENCE facilities.lease_agreement_seq;
CREATE SEQUENCE facilities.lease_amendment_seq;

CREATE TABLE facilities.lease_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    timezone VARCHAR(60) NOT NULL,
    weekend_days VARCHAR(40) NOT NULL
);
INSERT INTO facilities.lease_settings (id, timezone, weekend_days) VALUES (1, 'Africa/Accra', 'SATURDAY,SUNDAY');

CREATE TABLE facilities.lease_holidays (
    holiday_date DATE PRIMARY KEY,
    name VARCHAR(160) NOT NULL
);

CREATE TABLE facilities.lease_agreements (
    id UUID PRIMARY KEY,
    reference VARCHAR(40) NOT NULL UNIQUE,
    site_code VARCHAR(40) NOT NULL,
    room_id UUID,
    property_reference VARCHAR(240) NOT NULL,
    agreement_kind VARCHAR(10) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    title VARCHAR(240) NOT NULL,
    counterparty_reference VARCHAR(160),
    counterparty_state VARCHAR(12) NOT NULL DEFAULT 'UNRESOLVED',
    contract_reference VARCHAR(160),
    finance_reference VARCHAR(160),
    owner_reference VARCHAR(160),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    renewal_type VARCHAR(10) NOT NULL,
    renewal_term_months INTEGER,
    notice_days INTEGER,
    notice_date DATE,
    rent_review_date DATE,
    annual_rent NUMERIC(14, 2),
    deposit_amount NUMERIC(14, 2),
    currency VARCHAR(3),
    status VARCHAR(12) NOT NULL,
    version_number INTEGER NOT NULL DEFAULT 1,
    requested_by VARCHAR(160),
    approved_by VARCHAR(160),
    approved_at TIMESTAMPTZ,
    terminated_on DATE,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_lease_kind CHECK (agreement_kind IN ('LEASE', 'TENANCY')),
    CONSTRAINT ck_lease_direction CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT ck_lease_counterparty CHECK (counterparty_state IN ('UNRESOLVED', 'VERIFIED')),
    CONSTRAINT ck_lease_renewal CHECK (renewal_type IN ('NONE', 'OPTION', 'AUTO')),
    CONSTRAINT ck_lease_status CHECK (status IN ('DRAFT', 'IN_REVIEW', 'ACTIVE', 'EXPIRED', 'TERMINATED', 'ARCHIVED')),
    CONSTRAINT ck_lease_term CHECK (end_date > start_date)
);
CREATE INDEX ix_lease_agreements_scope ON facilities.lease_agreements (site_code, status, end_date);

-- Every approved change leaves a snapshot of the terms that applied, who approved it and when.
CREATE TABLE facilities.lease_versions (
    id UUID PRIMARY KEY,
    agreement_id UUID NOT NULL REFERENCES facilities.lease_agreements (id),
    site_code VARCHAR(40) NOT NULL,
    version_number INTEGER NOT NULL,
    end_date DATE NOT NULL,
    annual_rent NUMERIC(14, 2),
    deposit_amount NUMERIC(14, 2),
    notice_days INTEGER,
    rent_review_date DATE,
    renewal_term_months INTEGER,
    owner_reference VARCHAR(160),
    counterparty_reference VARCHAR(160),
    status VARCHAR(12) NOT NULL,
    amendment_id UUID,
    approved_by VARCHAR(160),
    approved_at TIMESTAMPTZ,
    recorded_by VARCHAR(160) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ux_lease_version UNIQUE (agreement_id, version_number)
);

CREATE TABLE facilities.lease_amendments (
    id UUID PRIMARY KEY,
    reference VARCHAR(40) NOT NULL UNIQUE,
    agreement_id UUID NOT NULL REFERENCES facilities.lease_agreements (id),
    site_code VARCHAR(40) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    material BOOLEAN NOT NULL,
    status VARCHAR(14) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    new_end_date DATE,
    new_annual_rent NUMERIC(14, 2),
    new_deposit_amount NUMERIC(14, 2),
    new_notice_days INTEGER,
    new_rent_review_date DATE,
    new_renewal_term_months INTEGER,
    effective_on DATE,
    prior_version INTEGER NOT NULL,
    proposed_by VARCHAR(160) NOT NULL,
    proposed_at TIMESTAMPTZ NOT NULL,
    decided_by VARCHAR(160),
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(2000),
    legal_review_note VARCHAR(2000),
    legal_cleared_by VARCHAR(160),
    legal_cleared_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_lease_amendment_kind CHECK (kind IN
        ('RENT_CHANGE', 'TERM_CHANGE', 'RENEWAL', 'TERMINATION', 'ADMINISTRATIVE')),
    CONSTRAINT ck_lease_amendment_status CHECK (status IN
        ('PROPOSED', 'LEGAL_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN'))
);
CREATE INDEX ix_lease_amendments_agreement ON facilities.lease_amendments (agreement_id, status);

CREATE TABLE facilities.lease_obligations (
    id UUID PRIMARY KEY,
    agreement_id UUID NOT NULL REFERENCES facilities.lease_agreements (id),
    site_code VARCHAR(40) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    title VARCHAR(240) NOT NULL,
    due_on DATE NOT NULL,
    owner_reference VARCHAR(160),
    status VARCHAR(8) NOT NULL,
    completed_on DATE,
    completed_by VARCHAR(160),
    completion_note VARCHAR(1000),
    generated BOOLEAN NOT NULL DEFAULT FALSE,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_lease_obligation_kind CHECK (kind IN
        ('RENEWAL', 'NOTICE', 'RENT_REVIEW', 'INSURANCE', 'COMPLIANCE', 'DOCUMENT_EXPIRY', 'PAYMENT', 'OTHER')),
    CONSTRAINT ck_lease_obligation_status CHECK (status IN ('OPEN', 'DONE', 'WAIVED'))
);
CREATE INDEX ix_lease_obligations_scope ON facilities.lease_obligations (site_code, status, due_on);
CREATE INDEX ix_lease_obligations_agreement ON facilities.lease_obligations (agreement_id, status);

CREATE TABLE facilities.lease_documents (
    id UUID PRIMARY KEY,
    agreement_id UUID NOT NULL REFERENCES facilities.lease_agreements (id),
    site_code VARCHAR(40) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    reference VARCHAR(240) NOT NULL,
    file_name VARCHAR(240) NOT NULL,
    media_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    content_hash VARCHAR(128) NOT NULL,
    retention_class VARCHAR(20) NOT NULL,
    expires_on DATE,
    submitted_by VARCHAR(160) NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_lease_document_kind CHECK (kind IN
        ('SIGNED_AGREEMENT', 'APPROVAL_EVIDENCE', 'INSURANCE_CERTIFICATE', 'COMPLIANCE_CERTIFICATE',
         'NOTICE', 'TERMINATION_NOTICE', 'OTHER'))
);
CREATE INDEX ix_lease_documents_agreement ON facilities.lease_documents (agreement_id, kind);

CREATE TABLE facilities.lease_alerts (
    id UUID PRIMARY KEY,
    site_code VARCHAR(40) NOT NULL,
    agreement_id UUID NOT NULL REFERENCES facilities.lease_agreements (id),
    obligation_id UUID REFERENCES facilities.lease_obligations (id),
    level VARCHAR(10) NOT NULL,
    reason VARCHAR(24) NOT NULL,
    detail VARCHAR(500),
    raised_at TIMESTAMPTZ NOT NULL,
    acknowledged_by VARCHAR(160),
    acknowledged_at TIMESTAMPTZ,
    CONSTRAINT ck_lease_alert_level CHECK (level IN ('OWNER', 'MANAGER', 'DIRECTOR', 'LEGAL')),
    CONSTRAINT ck_lease_alert_reason CHECK (reason IN ('DUE_SOON', 'OVERDUE', 'EXPIRED', 'DOCUMENT_EXPIRED', 'CONFLICTING_AMENDMENT'))
);
-- One alert per obligation, level and reason: a daily control that runs every day cannot notify the same person every day.
CREATE UNIQUE INDEX ux_lease_alert_once ON facilities.lease_alerts (agreement_id, COALESCE(obligation_id, agreement_id), level, reason);
CREATE INDEX ix_lease_alerts_scope ON facilities.lease_alerts (site_code, raised_at DESC);

CREATE TABLE facilities.lease_history (
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
CREATE INDEX ix_lease_history_subject ON facilities.lease_history (subject_id, occurred_at);

SELECT facilities.apply_site_scope_policies();
