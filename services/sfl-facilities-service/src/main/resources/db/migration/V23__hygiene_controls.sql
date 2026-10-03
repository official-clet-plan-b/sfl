-- S170 Hygiene & Pest-Control Audit Tracker: controls, findings, corrective actions, evidence,
-- escalations and an append-only state history. Everything is site-scoped and gets the same row-level
-- policy as the rest of the schema (ADR 0007), applied by the call at the foot of this file.

CREATE SEQUENCE facilities.hygiene_control_seq;
CREATE SEQUENCE facilities.hygiene_finding_seq;

CREATE TABLE facilities.hygiene_controls (
    id UUID PRIMARY KEY,
    reference VARCHAR(40) NOT NULL UNIQUE,
    site_code VARCHAR(40) NOT NULL,
    room_id UUID,
    location_label VARCHAR(160),
    control_type VARCHAR(30) NOT NULL,
    risk_category VARCHAR(30) NOT NULL,
    title VARCHAR(240) NOT NULL,
    owner_reference VARCHAR(160) NOT NULL,
    frequency VARCHAR(20) NOT NULL,
    due_on DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMPTZ,
    completed_on DATE,
    completed_by VARCHAR(160),
    provider_reference VARCHAR(160),
    provider_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    provider_confirmed_by VARCHAR(160),
    provider_confirmed_at TIMESTAMPTZ,
    previous_control_id UUID REFERENCES facilities.hygiene_controls (id),
    notes VARCHAR(2000),
    overdue_notified_at TIMESTAMPTZ,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_hygiene_control_type CHECK (control_type IN ('AUDIT', 'PEST_VISIT', 'STATUTORY_CHECK')),
    CONSTRAINT ck_hygiene_control_status CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'MISSED', 'CANCELLED')),
    CONSTRAINT ck_hygiene_control_frequency CHECK (frequency IN
        ('ONE_OFF', 'WEEKLY', 'FORTNIGHTLY', 'MONTHLY', 'QUARTERLY', 'BIANNUAL', 'ANNUAL'))
);
CREATE INDEX ix_hygiene_controls_scope ON facilities.hygiene_controls (site_code, status, due_on);

CREATE TABLE facilities.hygiene_findings (
    id UUID PRIMARY KEY,
    reference VARCHAR(40) NOT NULL UNIQUE,
    control_id UUID NOT NULL REFERENCES facilities.hygiene_controls (id),
    site_code VARCHAR(40) NOT NULL,
    room_id UUID,
    category VARCHAR(30) NOT NULL,
    title VARCHAR(240) NOT NULL,
    description VARCHAR(4000),
    severity VARCHAR(10) NOT NULL,
    status VARCHAR(30) NOT NULL,
    owner_reference VARCHAR(160),
    target_date DATE,
    requires_incident BOOLEAN NOT NULL DEFAULT FALSE,
    incident_state VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED',
    incident_reference VARCHAR(80),
    work_order_state VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED',
    work_order_id UUID,
    work_order_number VARCHAR(40),
    repeat_of_id UUID REFERENCES facilities.hygiene_findings (id),
    escalation_level VARCHAR(12) NOT NULL DEFAULT 'NONE',
    closure_mode VARCHAR(12),
    closure_reason VARCHAR(2000),
    closure_approved_by VARCHAR(160),
    closed_at TIMESTAMPTZ,
    closed_by VARCHAR(160),
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_hygiene_finding_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_hygiene_finding_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'AWAITING_VERIFICATION', 'CLOSED')),
    CONSTRAINT ck_hygiene_finding_incident CHECK (incident_state IN ('NOT_REQUIRED', 'PENDING_MANUAL', 'LINKED')),
    CONSTRAINT ck_hygiene_finding_work_order CHECK (work_order_state IN ('NOT_REQUIRED', 'RAISED', 'PENDING_MANUAL')),
    CONSTRAINT ck_hygiene_finding_escalation CHECK (escalation_level IN ('NONE', 'OWNER', 'HSE', 'LEADERSHIP')),
    CONSTRAINT ck_hygiene_finding_closure CHECK (closure_mode IS NULL OR closure_mode IN ('EVIDENCE', 'EXCEPTION'))
);
CREATE INDEX ix_hygiene_findings_scope ON facilities.hygiene_findings (site_code, status, severity);
CREATE INDEX ix_hygiene_findings_control ON facilities.hygiene_findings (control_id);

CREATE TABLE facilities.hygiene_actions (
    id UUID PRIMARY KEY,
    finding_id UUID NOT NULL REFERENCES facilities.hygiene_findings (id),
    site_code VARCHAR(40) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    owner_reference VARCHAR(160) NOT NULL,
    due_on DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    completed_at TIMESTAMPTZ,
    completed_by VARCHAR(160),
    verified_at TIMESTAMPTZ,
    verified_by VARCHAR(160),
    rejection_reason VARCHAR(2000),
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_hygiene_action_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'COMPLETED', 'VERIFIED', 'REJECTED'))
);
CREATE INDEX ix_hygiene_actions_finding ON facilities.hygiene_actions (finding_id);
CREATE INDEX ix_hygiene_actions_scope ON facilities.hygiene_actions (site_code, status, due_on);

-- Evidence is held by reference: the bytes live in the records store, this row proves what was filed,
-- by whom, with what hash and for how long it must be kept (the same shape as S153 evidence).
CREATE TABLE facilities.hygiene_evidence (
    id UUID PRIMARY KEY,
    finding_id UUID NOT NULL REFERENCES facilities.hygiene_findings (id),
    action_id UUID REFERENCES facilities.hygiene_actions (id),
    site_code VARCHAR(40) NOT NULL,
    reference VARCHAR(240) NOT NULL,
    file_name VARCHAR(240) NOT NULL,
    media_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    content_hash VARCHAR(128) NOT NULL,
    retention_class VARCHAR(20) NOT NULL,
    notes VARCHAR(2000),
    status VARCHAR(12) NOT NULL,
    submitted_by VARCHAR(160) NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    reviewed_by VARCHAR(160),
    reviewed_at TIMESTAMPTZ,
    review_reason VARCHAR(2000),
    CONSTRAINT ck_hygiene_evidence_status CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_hygiene_evidence_retention CHECK (retention_class IN
        ('OPERATIONAL', 'COMPLIANCE', 'SAFETY_CRITICAL', 'EXAMINATION', 'LEGAL'))
);
CREATE INDEX ix_hygiene_evidence_finding ON facilities.hygiene_evidence (finding_id, status);

CREATE TABLE facilities.hygiene_escalations (
    id UUID PRIMARY KEY,
    site_code VARCHAR(40) NOT NULL,
    subject_type VARCHAR(12) NOT NULL,
    subject_id UUID NOT NULL,
    subject_reference VARCHAR(40) NOT NULL,
    level VARCHAR(12) NOT NULL,
    reason VARCHAR(30) NOT NULL,
    detail VARCHAR(500),
    raised_at TIMESTAMPTZ NOT NULL,
    acknowledged_by VARCHAR(160),
    acknowledged_at TIMESTAMPTZ,
    CONSTRAINT ck_hygiene_escalation_subject CHECK (subject_type IN ('CONTROL', 'FINDING', 'ACTION')),
    CONSTRAINT ck_hygiene_escalation_level CHECK (level IN ('OWNER', 'HSE', 'LEADERSHIP'))
);
CREATE INDEX ix_hygiene_escalations_scope ON facilities.hygiene_escalations (site_code, raised_at DESC);
CREATE UNIQUE INDEX ux_hygiene_escalation_once ON facilities.hygiene_escalations (subject_id, level, reason);

-- Append-only: nothing updates or deletes a row, so "who moved this, from what, and why" survives any
-- later edit of the record itself.
CREATE TABLE facilities.hygiene_history (
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
CREATE INDEX ix_hygiene_history_subject ON facilities.hygiene_history (subject_id, occurred_at);

SELECT facilities.apply_site_scope_policies();
