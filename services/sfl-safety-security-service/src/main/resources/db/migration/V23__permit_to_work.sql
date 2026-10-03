-- =============================================================================================
-- S164 Permit-to-Work / Hot-Work Authorisation (Phase 2 SRS CLET/DTI/CL9/SFL/SRS/2026/002 §3.2; ADR 0010).
--
-- Lands in safety_security on the S165 foundation: a permit cannot be submitted without a current S165 risk
-- assessment. Every site-scoped table carries the V18 row-level-security policy from this, its first migration
-- (CORR-06); the call is the last statement. Permit types are organisation-wide configuration and belong to no site.
--
-- Foreign keys stay inside this module's own tables. The S165 assessment, the S160a zone, the S152 location, the S133
-- contractor and the S153 / S176 originating work are held by value: the first two are checked in-process, the rest
-- are recorded and shown as unverified, never as confirmed.
-- =============================================================================================

CREATE SEQUENCE safety_security.permit_seq START 1;

-- ---------------------------------------------------------------------------------------------
-- Permit types: configurable, each with its own approvals and validity rules (S164-01)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE safety_security.permit_types (
    id                        UUID PRIMARY KEY,
    code                      VARCHAR(40)   NOT NULL,
    name                      VARCHAR(120)  NOT NULL,
    description               VARCHAR(1000),
    risk_level                VARCHAR(10)   NOT NULL,
    -- The S165 activity type an assessment must cover for this kind of work; blank means any.
    activity_type             VARCHAR(80),
    risk_assessment_required  BOOLEAN       NOT NULL DEFAULT TRUE,
    -- S164-02: higher-risk types need an issuing authority plus an independent safety sign-off.
    two_stage                 BOOLEAN       NOT NULL,
    requires_isolation        BOOLEAN       NOT NULL,
    max_validity_hours        INTEGER       NOT NULL,
    required_competencies     TEXT[]        NOT NULL DEFAULT '{}',
    active                    BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by                VARCHAR(160)  NOT NULL,
    created_at                TIMESTAMPTZ   NOT NULL,
    updated_at                TIMESTAMPTZ   NOT NULL,
    record_version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_permit_types_code UNIQUE (code),
    CONSTRAINT ck_permit_types_risk CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_permit_types_validity CHECK (max_validity_hours BETWEEN 1 AND 720),
    -- A higher-risk type cannot be configured to skip the independent sign-off.
    CONSTRAINT ck_permit_types_two_stage CHECK (risk_level IN ('LOW', 'MEDIUM') OR two_stage)
);

INSERT INTO safety_security.permit_types (id, code, name, description, risk_level, activity_type, risk_assessment_required,
    two_stage, requires_isolation, max_validity_hours, required_competencies, created_by, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'HOT_WORK', 'Hot work', 'Welding, cutting, grinding, brazing or any work producing sparks or flame.',
        'HIGH', 'HOT_WORK', TRUE, TRUE, TRUE, 8, ARRAY['HOT_WORK_OPERATIVE', 'FIRE_WATCH'], 'system', now(), now()),
    (gen_random_uuid(), 'WORKING_AT_HEIGHT', 'Working at height', 'Work where a person could fall far enough to be injured.',
        'HIGH', 'WORKING_AT_HEIGHT', TRUE, TRUE, FALSE, 12, ARRAY['WORKING_AT_HEIGHT'], 'system', now(), now()),
    (gen_random_uuid(), 'CONFINED_SPACE', 'Confined space entry', 'Entry into a space with restricted access and a risk of a hazardous atmosphere.',
        'CRITICAL', 'CONFINED_SPACE', TRUE, TRUE, TRUE, 8, ARRAY['CONFINED_SPACE_ENTRANT', 'STANDBY_PERSON'], 'system', now(), now());

-- ---------------------------------------------------------------------------------------------
-- Permits (S164-01..04)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE safety_security.permits (
    id                       UUID PRIMARY KEY,
    site_code                VARCHAR(80)   NOT NULL,
    reference                VARCHAR(40)   NOT NULL,
    permit_type_id           UUID          NOT NULL REFERENCES safety_security.permit_types (id),
    work_type                VARCHAR(40)   NOT NULL,
    title                    VARCHAR(200)  NOT NULL,
    work_description         VARCHAR(4000) NOT NULL,
    -- S152 location reference, held by value: recorded, not checked from here.
    location_code            VARCHAR(120)  NOT NULL,
    -- S160a zone, checked in-process when it is named.
    zone_id                  UUID,
    zone_code                VARCHAR(80),
    starts_at                TIMESTAMPTZ   NOT NULL,
    ends_at                  TIMESTAMPTZ   NOT NULL,
    status                   VARCHAR(24)   NOT NULL,
    status_reason            VARCHAR(2000),
    -- The S165 assessment as it stood when the permit was last checked against it.
    risk_assessment_id       UUID,
    risk_assessment_reference VARCHAR(40),
    risk_assessment_version  INTEGER,
    risk_level               VARCHAR(10),
    risk_review_due_at       TIMESTAMPTZ,
    contractor_reference     VARCHAR(160),
    supervisor_reference     VARCHAR(160)  NOT NULL,
    supervisor_contact       VARCHAR(200),
    origin_system            VARCHAR(10)   NOT NULL,
    origin_reference         VARCHAR(160),
    -- 1 for the issue; each resumption opens the next round of approvals.
    approval_round           INTEGER       NOT NULL DEFAULT 1,
    requested_by             VARCHAR(160)  NOT NULL,
    submitted_at             TIMESTAMPTZ,
    issued_at                TIMESTAMPTZ,
    completion_statement     VARCHAR(4000),
    work_completed_at        TIMESTAMPTZ,
    work_completed_by        VARCHAR(160),
    closed_at                TIMESTAMPTZ,
    closed_by                VARCHAR(160),
    created_by               VARCHAR(160)  NOT NULL,
    created_at               TIMESTAMPTZ   NOT NULL,
    updated_at               TIMESTAMPTZ   NOT NULL,
    record_version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_permits_reference UNIQUE (reference),
    CONSTRAINT ck_permits_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_permits_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'ISOLATION_VERIFIED', 'STAGE1_APPROVED', 'ACTIVE',
        'SUSPENDED', 'RESUMPTION_PENDING', 'WORK_COMPLETE', 'CLOSED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_permits_origin CHECK (origin_system IN ('S153', 'S176', 'NONE')),
    -- S164-04: a closed permit says who closed it and when.
    CONSTRAINT ck_permits_closed CHECK (status <> 'CLOSED' OR (closed_at IS NOT NULL AND closed_by IS NOT NULL
        AND completion_statement IS NOT NULL))
);
CREATE INDEX ix_permits_site_status ON safety_security.permits (site_code, status, ends_at);
CREATE INDEX ix_permits_site_type ON safety_security.permits (site_code, permit_type_id, starts_at);
CREATE INDEX ix_permits_zone ON safety_security.permits (site_code, zone_id) WHERE zone_id IS NOT NULL;
CREATE INDEX ix_permits_assessment ON safety_security.permits (risk_assessment_id) WHERE risk_assessment_id IS NOT NULL;
CREATE INDEX ix_permits_contractor ON safety_security.permits (site_code, contractor_reference);

CREATE TABLE safety_security.permit_workers (
    seq BIGINT GENERATED ALWAYS AS IDENTITY,
    id                UUID PRIMARY KEY,
    permit_id         UUID          NOT NULL REFERENCES safety_security.permits (id) ON DELETE CASCADE,
    site_code         VARCHAR(80)   NOT NULL,
    person_reference  VARCHAR(160)  NOT NULL,
    display_name      VARCHAR(200)  NOT NULL,
    work_role         VARCHAR(30)   NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_permit_workers UNIQUE (permit_id, person_reference),
    CONSTRAINT ck_permit_workers_role CHECK (work_role IN ('SUPERVISOR', 'OPERATIVE', 'FIRE_WATCH', 'STANDBY'))
);

-- Competence is checked by the verifier and recorded, not assumed: the contractor-competency-exception figure on the
-- dashboard is every worker without a current, competent check for a competence the type requires.
CREATE TABLE safety_security.permit_competency_checks (
    id                 UUID PRIMARY KEY,
    permit_id          UUID          NOT NULL REFERENCES safety_security.permits (id) ON DELETE CASCADE,
    site_code          VARCHAR(80)   NOT NULL,
    worker_id          UUID          NOT NULL REFERENCES safety_security.permit_workers (id) ON DELETE CASCADE,
    competency_code    VARCHAR(80)   NOT NULL,
    competent          BOOLEAN       NOT NULL,
    evidence_reference VARCHAR(240),
    valid_until        DATE,
    checked_by         VARCHAR(160)  NOT NULL,
    checked_at         TIMESTAMPTZ   NOT NULL,
    note               VARCHAR(1000)
);
CREATE INDEX ix_permit_competency_permit ON safety_security.permit_competency_checks (permit_id, worker_id);

-- Isolation / lock-out records: required at request, verified before approval, removed at close-out - three separate
-- acts by named people (S164-02, -04).
CREATE TABLE safety_security.permit_isolations (
    seq BIGINT GENERATED ALWAYS AS IDENTITY,
    id                UUID PRIMARY KEY,
    permit_id         UUID          NOT NULL REFERENCES safety_security.permits (id) ON DELETE CASCADE,
    site_code         VARCHAR(80)   NOT NULL,
    kind              VARCHAR(30)   NOT NULL,
    description       VARCHAR(1000) NOT NULL,
    tag_reference     VARCHAR(160),
    status            VARCHAR(10)   NOT NULL,
    verified_by       VARCHAR(160),
    verified_at       TIMESTAMPTZ,
    verification_note VARCHAR(1000),
    removed_by        VARCHAR(160),
    removed_at        TIMESTAMPTZ,
    removal_note      VARCHAR(1000),
    created_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_permit_isolations_kind CHECK (kind IN ('ELECTRICAL', 'MECHANICAL', 'GAS_FUEL', 'PRESSURE', 'ZONE_ACCESS', 'OTHER')),
    CONSTRAINT ck_permit_isolations_status CHECK (status IN ('REQUIRED', 'VERIFIED', 'REMOVED')),
    CONSTRAINT ck_permit_isolations_verified CHECK (status = 'REQUIRED' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)),
    CONSTRAINT ck_permit_isolations_removed CHECK (status <> 'REMOVED' OR (removed_by IS NOT NULL AND removed_at IS NOT NULL))
);
CREATE INDEX ix_permit_isolations_permit ON safety_security.permit_isolations (permit_id, status);

-- Every approval, rejection and the conditions attached, one row each (S164-02: individually audited).
CREATE TABLE safety_security.permit_approvals (
    seq BIGINT GENERATED ALWAYS AS IDENTITY,
    id             UUID PRIMARY KEY,
    permit_id      UUID          NOT NULL REFERENCES safety_security.permits (id) ON DELETE CASCADE,
    site_code      VARCHAR(80)   NOT NULL,
    purpose        VARCHAR(10)   NOT NULL,
    -- The extension this approval is for; null for an issue or a resumption.
    ref_id         UUID,
    approval_round INTEGER       NOT NULL,
    stage          VARCHAR(20)   NOT NULL,
    decision       VARCHAR(10)   NOT NULL,
    decided_by     VARCHAR(160)  NOT NULL,
    decided_at     TIMESTAMPTZ   NOT NULL,
    conditions     VARCHAR(2000),
    comment        VARCHAR(2000),
    CONSTRAINT ck_permit_approvals_purpose CHECK (purpose IN ('ISSUE', 'RESUME', 'EXTEND')),
    CONSTRAINT ck_permit_approvals_stage CHECK (stage IN ('ISSUING_AUTHORITY', 'SAFETY_SIGN_OFF')),
    CONSTRAINT ck_permit_approvals_decision CHECK (decision IN ('APPROVED', 'REJECTED'))
);
CREATE UNIQUE INDEX uq_permit_approvals_stage ON safety_security.permit_approvals
    (permit_id, purpose, COALESCE(ref_id, permit_id), approval_round, stage);

-- Extension: same currency checks as a new request, not a rubber stamp (S164-03).
CREATE TABLE safety_security.permit_extensions (
    id               UUID PRIMARY KEY,
    permit_id        UUID          NOT NULL REFERENCES safety_security.permits (id) ON DELETE CASCADE,
    site_code        VARCHAR(80)   NOT NULL,
    requested_by     VARCHAR(160)  NOT NULL,
    requested_at     TIMESTAMPTZ   NOT NULL,
    previous_ends_at TIMESTAMPTZ   NOT NULL,
    new_ends_at      TIMESTAMPTZ   NOT NULL,
    reason           VARCHAR(2000) NOT NULL,
    status           VARCHAR(10)   NOT NULL,
    decided_by       VARCHAR(160),
    decided_at       TIMESTAMPTZ,
    decision_note    VARCHAR(2000),
    CONSTRAINT ck_permit_extensions_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_permit_extensions_window CHECK (new_ends_at > previous_ends_at)
);
CREATE UNIQUE INDEX uq_permit_extensions_pending ON safety_security.permit_extensions (permit_id) WHERE status = 'PENDING';

CREATE TABLE safety_security.permit_suspensions (
    id            UUID PRIMARY KEY,
    permit_id     UUID          NOT NULL REFERENCES safety_security.permits (id) ON DELETE CASCADE,
    site_code     VARCHAR(80)   NOT NULL,
    suspended_by  VARCHAR(160)  NOT NULL,
    suspended_at  TIMESTAMPTZ   NOT NULL,
    reason        VARCHAR(2000) NOT NULL,
    resumed_at    TIMESTAMPTZ
);
CREATE INDEX ix_permit_suspensions_permit ON safety_security.permit_suspensions (permit_id, suspended_at);

-- The notification is part of the suspension, written in the same transaction: a suspension with nobody told is not a
-- path that exists (S164-03). QUEUED means queued, not delivered - delivery is the notification channel's to report.
CREATE TABLE safety_security.permit_notifications (
    id                 UUID PRIMARY KEY,
    permit_id          UUID          NOT NULL REFERENCES safety_security.permits (id) ON DELETE CASCADE,
    site_code          VARCHAR(80)   NOT NULL,
    suspension_id      UUID          NOT NULL REFERENCES safety_security.permit_suspensions (id) ON DELETE CASCADE,
    recipient_reference VARCHAR(160) NOT NULL,
    recipient_name     VARCHAR(200),
    recipient_role     VARCHAR(30)   NOT NULL,
    state              VARCHAR(10)   NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_permit_notifications_state CHECK (state IN ('QUEUED', 'DELIVERED', 'FAILED'))
);

-- Close-out evidence, by reference: the bytes live in the records store (S003).
CREATE TABLE safety_security.permit_evidence (
    id              UUID PRIMARY KEY,
    permit_id       UUID          NOT NULL REFERENCES safety_security.permits (id) ON DELETE CASCADE,
    site_code       VARCHAR(80)   NOT NULL,
    kind            VARCHAR(12)   NOT NULL,
    reference       VARCHAR(240)  NOT NULL,
    file_name       VARCHAR(240)  NOT NULL,
    media_type      VARCHAR(120)  NOT NULL,
    size_bytes      BIGINT        NOT NULL CHECK (size_bytes > 0),
    content_hash    VARCHAR(128)  NOT NULL,
    retention_class VARCHAR(20)   NOT NULL,
    submitted_by    VARCHAR(160)  NOT NULL,
    submitted_at    TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_permit_evidence_kind CHECK (kind IN ('PHOTO', 'CHECKLIST', 'OTHER'))
);
CREATE INDEX ix_permit_evidence_permit ON safety_security.permit_evidence (permit_id);

-- A permit linked to an incident, or caught in a zone emergency, is flagged for review (S164-03, -05).
CREATE TABLE safety_security.permit_flags (
    id           UUID PRIMARY KEY,
    permit_id    UUID          NOT NULL REFERENCES safety_security.permits (id) ON DELETE CASCADE,
    site_code    VARCHAR(80)   NOT NULL,
    flag_type    VARCHAR(20)   NOT NULL,
    reference    VARCHAR(80)   NOT NULL,
    detail       VARCHAR(1000),
    status       VARCHAR(10)   NOT NULL,
    raised_at    TIMESTAMPTZ   NOT NULL,
    reviewed_by  VARCHAR(160),
    reviewed_at  TIMESTAMPTZ,
    review_note  VARCHAR(2000),
    CONSTRAINT ck_permit_flags_type CHECK (flag_type IN ('INCIDENT', 'EMERGENCY_ZONE')),
    CONSTRAINT ck_permit_flags_status CHECK (status IN ('OPEN', 'REVIEWED'))
);
CREATE UNIQUE INDEX uq_permit_flags_once ON safety_security.permit_flags (permit_id, flag_type, reference);
CREATE INDEX ix_permit_flags_open ON safety_security.permit_flags (site_code, status);

-- Expiry escalation to the authoriser, once per level per permit (S164-04).
CREATE TABLE safety_security.permit_escalations (
    id         UUID PRIMARY KEY,
    permit_id  UUID          NOT NULL REFERENCES safety_security.permits (id) ON DELETE CASCADE,
    site_code  VARCHAR(80)   NOT NULL,
    level      VARCHAR(20)   NOT NULL,
    raised_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_permit_escalations_level CHECK (level IN ('NEARING_EXPIRY', 'OVERDUE'))
);
CREATE UNIQUE INDEX uq_permit_escalations_once ON safety_security.permit_escalations (permit_id, level);

-- Append-only: what happened to a permit, in order, and who did it.
CREATE TABLE safety_security.permit_history (
    id           UUID PRIMARY KEY,
    -- Insertion order: two things can happen in the same instant, and the record must still say which came first.
    seq          BIGINT        GENERATED ALWAYS AS IDENTITY,
    permit_id    UUID          NOT NULL,
    site_code    VARCHAR(80)   NOT NULL,
    from_status  VARCHAR(24),
    to_status    VARCHAR(24)   NOT NULL,
    action       VARCHAR(80)   NOT NULL,
    actor        VARCHAR(160)  NOT NULL,
    reason       VARCHAR(2000),
    occurred_at  TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_permit_history_permit ON safety_security.permit_history (permit_id, occurred_at, seq);

-- ---------------------------------------------------------------------------------------------
-- Permit types belong to no site, so they carry no policy; the application role still needs to read and configure them
-- (the same grant S165 gives its global tables).
GRANT SELECT, INSERT, UPDATE ON safety_security.permit_types TO sfl_app;

SELECT safety_security.apply_site_scope_policies(ARRAY[
    'permits',
    'permit_workers',
    'permit_competency_checks',
    'permit_isolations',
    'permit_approvals',
    'permit_extensions',
    'permit_suspensions',
    'permit_notifications',
    'permit_evidence',
    'permit_flags',
    'permit_escalations',
    'permit_history'
]);
