-- =============================================================================================
-- S175 Crisis & Evacuation Drill Management (Phase 2 SRS CLET/DTI/CL9/SFL/SRS/2026/002 §3.2; ADR 0010).
--
-- Lands in safety_security alongside S165. Every table is site-scoped and carries the V18 row-level-security
-- policy from this, its first migration (CORR-06) - the call is the last statement.
--
-- Foreign keys stay inside this module's own tables. The S174 drill notification, the S162a muster session and
-- the S160/S160a people in the baseline are held by value.
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- Drills: the plan and where it stands (S175-01, -05)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE safety_security.drills (
    id                       UUID PRIMARY KEY,
    site_code                VARCHAR(80)   NOT NULL,
    reference                VARCHAR(40)   NOT NULL,
    drill_type               VARCHAR(30)   NOT NULL,
    title                    VARCHAR(200)  NOT NULL,
    scenario                 VARCHAR(4000),
    expected_participants    VARCHAR(1000),
    assembly_zone            VARCHAR(80),
    scheduled_for            TIMESTAMPTZ,
    notification_template_id UUID,
    audience_group_ids       UUID[]        NOT NULL DEFAULT '{}',
    recipient_zone_ids       UUID[]        NOT NULL DEFAULT '{}',
    status                   VARCHAR(20)   NOT NULL,
    status_reason            VARCHAR(1000),
    -- Idempotency-Key on the creating POST, scoped to the caller - as on risk_assessments.
    creation_idempotency_key VARCHAR(120),
    creation_request_hash    VARCHAR(64),
    created_by               VARCHAR(160)  NOT NULL,
    created_at               TIMESTAMPTZ   NOT NULL,
    last_modified_by         VARCHAR(160)  NOT NULL,
    last_modified_at         TIMESTAMPTZ   NOT NULL,
    record_version           BIGINT        NOT NULL DEFAULT 0,
    source_channel           VARCHAR(20)   NOT NULL,
    correlation_id           VARCHAR(120),
    CONSTRAINT uq_drills_reference UNIQUE (reference),
    CONSTRAINT ck_drills_type CHECK (drill_type IN ('FIRE', 'SECURITY_LOCKDOWN', 'MEDICAL', 'COMBINED')),
    CONSTRAINT ck_drills_status CHECK (status IN ('PLANNED', 'SCHEDULED', 'POSTPONED', 'IN_PROGRESS', 'COMPLETED',
        'REVIEWED', 'CLOSED', 'CANCELLED')),
    -- S175-01: a postponed or cancelled drill keeps the reason why.
    CONSTRAINT ck_drills_reason CHECK (status NOT IN ('POSTPONED', 'CANCELLED') OR status_reason IS NOT NULL),
    -- Nothing reaches the calendar without a drill template - the service checks it is one, with S174.
    CONSTRAINT ck_drills_scheduled_ready CHECK (status IN ('PLANNED', 'POSTPONED', 'CANCELLED')
        OR (scheduled_for IS NOT NULL AND notification_template_id IS NOT NULL AND assembly_zone IS NOT NULL)),
    CONSTRAINT ck_drills_source_channel CHECK (source_channel IN ('WEB', 'API', 'SYSTEM', 'INTEGRATION'))
);

CREATE UNIQUE INDEX uq_drills_creation_key ON safety_security.drills (created_by, creation_idempotency_key)
    WHERE creation_idempotency_key IS NOT NULL;
CREATE INDEX ix_drills_site_scheduled ON safety_security.drills (site_code, scheduled_for);
CREATE INDEX ix_drills_site_type_status ON safety_security.drills (site_code, drill_type, status);

-- Per-module expectations, written with the plan and judged in the review (S175-05). Replaced wholesale.
CREATE TABLE safety_security.drill_expectations (
    drill_id                 UUID          NOT NULL REFERENCES safety_security.drills (id) ON DELETE CASCADE,
    sequence_no              INTEGER       NOT NULL,
    site_code                VARCHAR(80)   NOT NULL,
    module                   VARCHAR(40)   NOT NULL,
    expectation              VARCHAR(1000) NOT NULL,
    success_criterion        VARCHAR(1000),
    outcome                  VARCHAR(20),
    outcome_notes            VARCHAR(2000),
    PRIMARY KEY (drill_id, sequence_no),
    CONSTRAINT ck_drill_expectations_module CHECK (module IN ('S160_VISITOR_MANAGEMENT', 'S160A_ACCESS_CONTROL',
        'S162A_LIFE_SAFETY', 'S166_FLEET', 'S171_DISPATCH', 'S174_MASS_NOTIFICATION', 'OTHER')),
    CONSTRAINT ck_drill_expectations_outcome
        CHECK (outcome IS NULL OR outcome IN ('MET', 'PARTIALLY_MET', 'NOT_MET'))
);

-- ---------------------------------------------------------------------------------------------
-- The day: execution record, baseline and roll-call gaps (S175-02)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE safety_security.drill_executions (
    drill_id                     UUID          PRIMARY KEY REFERENCES safety_security.drills (id),
    site_code                    VARCHAR(80)   NOT NULL,
    started_at                   TIMESTAMPTZ   NOT NULL,
    started_by                   VARCHAR(160)  NOT NULL,
    notification_activation_id   UUID,
    notification_number          VARCHAR(60),
    notification_sent_at         TIMESTAMPTZ,
    muster_session_id            UUID          NOT NULL,
    baseline_taken_at            TIMESTAMPTZ   NOT NULL,
    baseline_access_data_as_of   TIMESTAMPTZ,
    baseline_stale               BOOLEAN       NOT NULL,
    baseline_count               INTEGER       NOT NULL,
    roll_call_closed_at          TIMESTAMPTZ,
    roll_call_closed_by          VARCHAR(160),
    checked_in_count             INTEGER,
    gap_count                    INTEGER,
    notification_to_muster_seconds BIGINT,
    CONSTRAINT ck_drill_executions_closed
        CHECK ((roll_call_closed_at IS NULL) = (gap_count IS NULL AND checked_in_count IS NULL))
);

CREATE INDEX ix_drill_executions_site_started ON safety_security.drill_executions (site_code, started_at);

CREATE TABLE safety_security.drill_baseline_persons (
    drill_id                 UUID          NOT NULL REFERENCES safety_security.drills (id),
    person_ref               VARCHAR(160)  NOT NULL,
    site_code                VARCHAR(80)   NOT NULL,
    display_name             VARCHAR(200),
    source                   VARCHAR(20)   NOT NULL,
    PRIMARY KEY (drill_id, person_ref)
);

CREATE TABLE safety_security.drill_roll_call_gaps (
    id                       UUID PRIMARY KEY,
    drill_id                 UUID          NOT NULL REFERENCES safety_security.drills (id),
    site_code                VARCHAR(80)   NOT NULL,
    person_ref               VARCHAR(160)  NOT NULL,
    display_name             VARCHAR(200),
    source                   VARCHAR(20)   NOT NULL,
    follow_up                VARCHAR(20),
    follow_up_notes          VARCHAR(2000),
    followed_up_by           VARCHAR(160),
    followed_up_at           TIMESTAMPTZ,
    CONSTRAINT uq_drill_roll_call_gaps_person UNIQUE (drill_id, person_ref),
    CONSTRAINT ck_drill_roll_call_gaps_follow_up
        CHECK (follow_up IS NULL OR follow_up IN ('ACCOUNTED_FOR', 'NOT_ON_SITE', 'UNACCOUNTED'))
);

-- ---------------------------------------------------------------------------------------------
-- After-action review, findings and corrective actions (S175-03)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE safety_security.drill_reviews (
    drill_id                 UUID          PRIMARY KEY REFERENCES safety_security.drills (id),
    site_code                VARCHAR(80)   NOT NULL,
    summary                  VARCHAR(4000),
    timing_notes             VARCHAR(2000),
    recorded_by              VARCHAR(160),
    recorded_at              TIMESTAMPTZ,
    submitted_by             VARCHAR(160),
    submitted_at             TIMESTAMPTZ,
    CONSTRAINT ck_drill_reviews_submitted_with_summary CHECK (submitted_at IS NULL OR summary IS NOT NULL)
);

CREATE TABLE safety_security.drill_findings (
    id                       UUID PRIMARY KEY,
    drill_id                 UUID          NOT NULL REFERENCES safety_security.drills (id),
    site_code                VARCHAR(80)   NOT NULL,
    sequence_no              INTEGER       NOT NULL,
    description              VARCHAR(2000) NOT NULL,
    no_action_justification  VARCHAR(2000),
    recorded_by              VARCHAR(160)  NOT NULL,
    recorded_at              TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_drill_findings_sequence UNIQUE (drill_id, sequence_no)
);

-- S163's corrective_actions shape and lifecycle, parented by a drill finding. S163's own table is untouched.
CREATE TABLE safety_security.drill_corrective_actions (
    id                       UUID PRIMARY KEY,
    drill_id                 UUID          NOT NULL REFERENCES safety_security.drills (id),
    finding_id               UUID          NOT NULL REFERENCES safety_security.drill_findings (id),
    site_code                VARCHAR(80)   NOT NULL,
    description              VARCHAR(2000) NOT NULL,
    owner_id                 VARCHAR(160)  NOT NULL,
    due_date                 DATE          NOT NULL,
    status                   VARCHAR(20)   NOT NULL,
    verification_notes       VARCHAR(2000),
    created_by               VARCHAR(160)  NOT NULL,
    created_at               TIMESTAMPTZ   NOT NULL,
    resolved_by              VARCHAR(160),
    resolved_at              TIMESTAMPTZ,
    CONSTRAINT ck_drill_corrective_actions_status
        CHECK (status IN ('OPEN', 'IN_PROGRESS', 'VERIFIED', 'CANCELLED')),
    CONSTRAINT ck_drill_corrective_actions_resolved
        CHECK ((status IN ('VERIFIED', 'CANCELLED')) = (resolved_at IS NOT NULL))
);

CREATE INDEX ix_drill_corrective_actions_drill ON safety_security.drill_corrective_actions (drill_id);
CREATE INDEX ix_drill_corrective_actions_site_status
    ON safety_security.drill_corrective_actions (site_code, status, due_date);

-- ---------------------------------------------------------------------------------------------
-- Frequency requirements per site and drill type (S175-04) - runtime configuration, no defaults:
-- the statutory and insurance figures are CLET's to set.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE safety_security.drill_frequency_requirements (
    id                       UUID PRIMARY KEY,
    site_code                VARCHAR(80)   NOT NULL,
    drill_type               VARCHAR(30)   NOT NULL,
    interval_days            INTEGER       NOT NULL,
    warning_days             INTEGER       NOT NULL,
    effective_from           TIMESTAMPTZ   NOT NULL,
    gap_flagged_for_due_at   TIMESTAMPTZ,
    created_by               VARCHAR(160)  NOT NULL,
    created_at               TIMESTAMPTZ   NOT NULL,
    last_modified_by         VARCHAR(160)  NOT NULL,
    last_modified_at         TIMESTAMPTZ   NOT NULL,
    record_version           BIGINT        NOT NULL DEFAULT 0,
    source_channel           VARCHAR(20)   NOT NULL,
    correlation_id           VARCHAR(120),
    CONSTRAINT uq_drill_frequency_requirements UNIQUE (site_code, drill_type),
    CONSTRAINT ck_drill_frequency_requirements_type
        CHECK (drill_type IN ('FIRE', 'SECURITY_LOCKDOWN', 'MEDICAL', 'COMBINED')),
    CONSTRAINT ck_drill_frequency_requirements_days
        CHECK (interval_days >= 1 AND warning_days >= 0 AND warning_days < interval_days),
    CONSTRAINT ck_drill_frequency_requirements_source_channel
        CHECK (source_channel IN ('WEB', 'API', 'SYSTEM', 'INTEGRATION'))
);

-- ---------------------------------------------------------------------------------------------
-- Row-level security and grants - last, for every table above.
-- ---------------------------------------------------------------------------------------------
SELECT safety_security.apply_site_scope_policies(ARRAY[
    'drills',
    'drill_expectations',
    'drill_executions',
    'drill_baseline_persons',
    'drill_roll_call_gaps',
    'drill_reviews',
    'drill_findings',
    'drill_corrective_actions',
    'drill_frequency_requirements'
]);
