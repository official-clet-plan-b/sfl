-- S177: corrective work generated from a lease exception (SRS section 5, S153), and the work a lapsed lease sets
-- off - whoever relied on it is asked to review. One review order per lapsed agreement, however often the control runs.
CREATE TABLE facilities.lease_work_orders (
    id UUID PRIMARY KEY,
    site_code VARCHAR(40) NOT NULL,
    agreement_id UUID NOT NULL REFERENCES facilities.lease_agreements (id),
    obligation_id UUID REFERENCES facilities.lease_obligations (id),
    trigger_kind VARCHAR(16) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    state VARCHAR(16) NOT NULL,
    work_order_id UUID,
    work_order_number VARCHAR(40),
    requested_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_lease_wo_trigger CHECK (trigger_kind IN ('EXPIRED_REVIEW', 'MANUAL')),
    CONSTRAINT ck_lease_wo_state CHECK (state IN ('RAISED', 'PENDING_MANUAL'))
);
CREATE UNIQUE INDEX ux_lease_wo_expired_once ON facilities.lease_work_orders (agreement_id) WHERE trigger_kind = 'EXPIRED_REVIEW';
CREATE INDEX ix_lease_wo_agreement ON facilities.lease_work_orders (agreement_id, created_at);
CREATE INDEX ix_lease_wo_pending ON facilities.lease_work_orders (state) WHERE state = 'PENDING_MANUAL';

-- Retention is configuration, one row per system and record class (SRS 4.2). Nothing here deletes regulated
-- evidence: a class past its period is reported for authorised disposal, and only sensitive personal data
-- (catering dietary needs) is anonymised, each row audited.
CREATE TABLE facilities.record_retention_policies (
    system_code VARCHAR(10) NOT NULL,
    record_class VARCHAR(30) NOT NULL,
    retention_days INTEGER NOT NULL CHECK (retention_days > 0),
    action VARCHAR(12) NOT NULL,
    basis VARCHAR(240) NOT NULL,
    updated_by VARCHAR(160) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (system_code, record_class),
    CONSTRAINT ck_retention_action CHECK (action IN ('REVIEW', 'ANONYMISE'))
);

-- Defaults are placeholders pending the statutory confirmation SRS Appendix B asks for; a director changes them
-- here, not in code. Operational evidence is kept two years, compliance seven, safety-critical and legal ten.
INSERT INTO facilities.record_retention_policies (system_code, record_class, retention_days, action, basis, updated_by, updated_at)
SELECT s.code, c.name, c.days, 'REVIEW', 'Default pending statutory confirmation', 'system', now()
FROM (VALUES ('S170'), ('S172'), ('S177'), ('S178')) AS s (code)
CROSS JOIN (VALUES ('OPERATIONAL', 730), ('COMPLIANCE', 2555), ('SAFETY_CRITICAL', 3650), ('EXAMINATION', 1825), ('LEGAL', 3650)) AS c (name, days);
INSERT INTO facilities.record_retention_policies (system_code, record_class, retention_days, action, basis, updated_by, updated_at)
VALUES ('S172', 'DIETARY_DATA', 90, 'ANONYMISE', 'Dietary and allergy needs are sensitive: kept 90 days after the service, then anonymised', 'system', now());

ALTER TABLE facilities.cat_dietary_requests ADD COLUMN anonymised_at TIMESTAMPTZ;

SELECT facilities.apply_site_scope_policies();
