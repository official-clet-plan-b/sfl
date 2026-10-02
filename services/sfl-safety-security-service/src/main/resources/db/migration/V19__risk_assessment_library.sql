-- =============================================================================================
-- S165 Risk Assessment Library (Phase 2 SRS CLET/DTI/CL9/SFL/SRS/2026/002 §3.2; ADR 0010).
--
-- Lands in safety_security alongside S160-S163 and S162a. Every site-scoped table carries the V18
-- row-level-security policy from this, its first migration (CORR-06) - the call is the last statement.
-- The template library and the review cycle belong to no site and carry no policy; they are granted to
-- sfl_app explicitly instead.
--
-- Foreign keys stay inside this module's own tables. Everything from elsewhere - the S152 location, the
-- S163 incident that raised a review flag, the system that used an activity type - is held by value.
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- Assessments: identity, scope, and an index of the current version (S165-01)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE safety_security.risk_assessments (
    id                      UUID PRIMARY KEY,
    site_code               VARCHAR(80)  NOT NULL,
    reference               VARCHAR(40)  NOT NULL,
    activity_type           VARCHAR(80),
    location_code           VARCHAR(80),
    template_id             UUID,
    title                   VARCHAR(200) NOT NULL,
    current_version         INTEGER,
    draft_version           INTEGER,
    latest_version          INTEGER      NOT NULL,
    current_risk_level      VARCHAR(20),
    current_review_due_at   TIMESTAMPTZ,
    current_author_id       VARCHAR(160),
    current_signed_off_by   VARCHAR(160),
    -- Idempotency-Key on the creating POST: a retry with the same key and body returns this assessment;
    -- the same key with a different body is refused. Scoped to the caller, so two users cannot collide.
    creation_idempotency_key VARCHAR(120),
    creation_request_hash   VARCHAR(64),
    created_by              VARCHAR(160) NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL,
    last_modified_by        VARCHAR(160) NOT NULL,
    last_modified_at        TIMESTAMPTZ  NOT NULL,
    record_version          BIGINT       NOT NULL DEFAULT 0,
    source_channel          VARCHAR(20)  NOT NULL,
    correlation_id          VARCHAR(120),
    CONSTRAINT uq_risk_assessments_reference UNIQUE (reference),
    CONSTRAINT ck_risk_assessments_scope CHECK (activity_type IS NOT NULL OR location_code IS NOT NULL),
    CONSTRAINT ck_risk_assessments_risk_level
        CHECK (current_risk_level IS NULL OR current_risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_risk_assessments_source_channel
        CHECK (source_channel IN ('WEB', 'API', 'SYSTEM', 'INTEGRATION'))
);

CREATE UNIQUE INDEX uq_risk_assessments_creation_key
    ON safety_security.risk_assessments (created_by, creation_idempotency_key)
    WHERE creation_idempotency_key IS NOT NULL;
CREATE INDEX ix_risk_assessments_site_activity ON safety_security.risk_assessments (site_code, activity_type);
CREATE INDEX ix_risk_assessments_site_due ON safety_security.risk_assessments (site_code, current_review_due_at);

-- ---------------------------------------------------------------------------------------------
-- Versions: DRAFT -> PUBLISHED -> SUPERSEDED, never deleted (S165-01, -02)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE safety_security.risk_assessment_versions (
    id                      UUID PRIMARY KEY,
    assessment_id           UUID         NOT NULL REFERENCES safety_security.risk_assessments (id),
    site_code               VARCHAR(80)  NOT NULL,
    version_number          INTEGER      NOT NULL,
    status                  VARCHAR(20)  NOT NULL,
    title                   VARCHAR(200) NOT NULL,
    summary                 VARCHAR(4000),
    risk_level              VARCHAR(20),
    residual_score          INTEGER      NOT NULL DEFAULT 0,
    author_id               VARCHAR(160) NOT NULL,
    author_name             VARCHAR(200),
    review_interval_days    INTEGER,
    review_due_at           TIMESTAMPTZ,
    review_reminder_sent_at TIMESTAMPTZ,
    review_lapsed_at        TIMESTAMPTZ,
    published_at            TIMESTAMPTZ,
    published_by            VARCHAR(160),
    signed_off_by           VARCHAR(160),
    signed_off_by_name      VARCHAR(200),
    signed_off_at           TIMESTAMPTZ,
    superseded_at           TIMESTAMPTZ,
    created_by              VARCHAR(160) NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL,
    last_modified_by        VARCHAR(160) NOT NULL,
    last_modified_at        TIMESTAMPTZ  NOT NULL,
    record_version          BIGINT       NOT NULL DEFAULT 0,
    source_channel          VARCHAR(20)  NOT NULL,
    correlation_id          VARCHAR(120),
    CONSTRAINT uq_risk_assessment_versions_number UNIQUE (assessment_id, version_number),
    CONSTRAINT ck_risk_assessment_versions_number CHECK (version_number >= 1),
    CONSTRAINT ck_risk_assessment_versions_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    CONSTRAINT ck_risk_assessment_versions_risk_level
        CHECK (risk_level IS NULL OR risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    -- A published or superseded version always carried a review date; a draft never does.
    CONSTRAINT ck_risk_assessment_versions_review_date
        CHECK ((status = 'DRAFT') = (review_due_at IS NULL)),
    CONSTRAINT ck_risk_assessment_versions_source_channel
        CHECK (source_channel IN ('WEB', 'API', 'SYSTEM', 'INTEGRATION'))
);

-- At most one draft and one current version per assessment, enforced where a race cannot get past it.
CREATE UNIQUE INDEX uq_risk_assessment_versions_one_draft
    ON safety_security.risk_assessment_versions (assessment_id) WHERE status = 'DRAFT';
CREATE UNIQUE INDEX uq_risk_assessment_versions_one_published
    ON safety_security.risk_assessment_versions (assessment_id) WHERE status = 'PUBLISHED';
CREATE INDEX ix_risk_assessment_versions_review_due
    ON safety_security.risk_assessment_versions (review_due_at) WHERE status = 'PUBLISHED';

-- ---------------------------------------------------------------------------------------------
-- Hazards and control measures, per version (S165-01)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE safety_security.risk_assessment_hazards (
    id                      UUID PRIMARY KEY,
    version_id              UUID          NOT NULL REFERENCES safety_security.risk_assessment_versions (id),
    site_code               VARCHAR(80)   NOT NULL,
    sequence_no             INTEGER       NOT NULL,
    hazard_type             VARCHAR(40)   NOT NULL,
    description             VARCHAR(1000) NOT NULL,
    who_at_risk             VARCHAR(300),
    inherent_likelihood     VARCHAR(20)   NOT NULL,
    inherent_severity       VARCHAR(20)   NOT NULL,
    residual_likelihood     VARCHAR(20)   NOT NULL,
    residual_severity       VARCHAR(20)   NOT NULL,
    created_by              VARCHAR(160)  NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL,
    last_modified_by        VARCHAR(160)  NOT NULL,
    last_modified_at        TIMESTAMPTZ   NOT NULL,
    record_version          BIGINT        NOT NULL DEFAULT 0,
    source_channel          VARCHAR(20)   NOT NULL,
    correlation_id          VARCHAR(120),
    CONSTRAINT uq_risk_assessment_hazards_sequence UNIQUE (version_id, sequence_no),
    CONSTRAINT ck_risk_assessment_hazards_type CHECK (hazard_type IN ('WORKING_AT_HEIGHT', 'HOT_WORK_FIRE',
        'ELECTRICAL', 'CONFINED_SPACE', 'MANUAL_HANDLING', 'SLIPS_TRIPS_FALLS', 'CROWD_OCCUPANCY',
        'HAZARDOUS_SUBSTANCES', 'MACHINERY_EQUIPMENT', 'STRUCTURAL', 'VEHICLE_TRAFFIC', 'NOISE_VIBRATION',
        'BIOLOGICAL', 'ENVIRONMENTAL', 'SECURITY_VIOLENCE', 'OTHER')),
    CONSTRAINT ck_risk_assessment_hazards_likelihood
        CHECK (inherent_likelihood IN ('RARE', 'UNLIKELY', 'POSSIBLE', 'LIKELY', 'ALMOST_CERTAIN')
           AND residual_likelihood IN ('RARE', 'UNLIKELY', 'POSSIBLE', 'LIKELY', 'ALMOST_CERTAIN')),
    CONSTRAINT ck_risk_assessment_hazards_severity
        CHECK (inherent_severity IN ('NEGLIGIBLE', 'MINOR', 'MODERATE', 'MAJOR', 'CATASTROPHIC')
           AND residual_severity IN ('NEGLIGIBLE', 'MINOR', 'MODERATE', 'MAJOR', 'CATASTROPHIC')),
    CONSTRAINT ck_risk_assessment_hazards_source_channel
        CHECK (source_channel IN ('WEB', 'API', 'SYSTEM', 'INTEGRATION'))
);

CREATE TABLE safety_security.risk_assessment_controls (
    id                      UUID PRIMARY KEY,
    hazard_id               UUID          NOT NULL
        REFERENCES safety_security.risk_assessment_hazards (id) ON DELETE CASCADE,
    site_code               VARCHAR(80)   NOT NULL,
    sequence_no             INTEGER       NOT NULL,
    control_type            VARCHAR(20)   NOT NULL,
    description             VARCHAR(1000) NOT NULL,
    created_by              VARCHAR(160)  NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL,
    last_modified_by        VARCHAR(160)  NOT NULL,
    last_modified_at        TIMESTAMPTZ   NOT NULL,
    record_version          BIGINT        NOT NULL DEFAULT 0,
    source_channel          VARCHAR(20)   NOT NULL,
    correlation_id          VARCHAR(120),
    CONSTRAINT uq_risk_assessment_controls_sequence UNIQUE (hazard_id, sequence_no),
    CONSTRAINT ck_risk_assessment_controls_type
        CHECK (control_type IN ('ELIMINATION', 'SUBSTITUTION', 'ENGINEERING', 'ADMINISTRATIVE', 'PPE')),
    CONSTRAINT ck_risk_assessment_controls_source_channel
        CHECK (source_channel IN ('WEB', 'API', 'SYSTEM', 'INTEGRATION'))
);

CREATE INDEX ix_risk_assessment_hazards_version ON safety_security.risk_assessment_hazards (version_id);
CREATE INDEX ix_risk_assessment_controls_hazard ON safety_security.risk_assessment_controls (hazard_id);

-- ---------------------------------------------------------------------------------------------
-- Sign-off history (S165-02)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE safety_security.risk_assessment_sign_offs (
    id                      UUID PRIMARY KEY,
    assessment_id           UUID         NOT NULL REFERENCES safety_security.risk_assessments (id),
    version_id              UUID         NOT NULL REFERENCES safety_security.risk_assessment_versions (id),
    version_number          INTEGER      NOT NULL,
    site_code               VARCHAR(80)  NOT NULL,
    reviewer_id             VARCHAR(160) NOT NULL,
    reviewer_name           VARCHAR(200),
    signed_off_at           TIMESTAMPTZ  NOT NULL,
    notes                   VARCHAR(2000),
    previous_review_due_at  TIMESTAMPTZ,
    review_due_at           TIMESTAMPTZ  NOT NULL,
    independent             BOOLEAN      NOT NULL,
    created_by              VARCHAR(160) NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL,
    last_modified_by        VARCHAR(160) NOT NULL,
    last_modified_at        TIMESTAMPTZ  NOT NULL,
    record_version          BIGINT       NOT NULL DEFAULT 0,
    source_channel          VARCHAR(20)  NOT NULL,
    correlation_id          VARCHAR(120),
    CONSTRAINT ck_risk_assessment_sign_offs_source_channel
        CHECK (source_channel IN ('WEB', 'API', 'SYSTEM', 'INTEGRATION'))
);

CREATE INDEX ix_risk_assessment_sign_offs_assessment
    ON safety_security.risk_assessment_sign_offs (assessment_id, signed_off_at DESC);

-- ---------------------------------------------------------------------------------------------
-- Out-of-cycle review flags (S165-04). There is no DISMISSED status, and a CLEARED flag always carries
-- findings - the SRS's "not silently cleared", held by the database as well as the domain.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE safety_security.risk_assessment_review_flags (
    id                      UUID PRIMARY KEY,
    assessment_id           UUID          NOT NULL REFERENCES safety_security.risk_assessments (id),
    assessment_reference    VARCHAR(40)   NOT NULL,
    version_number          INTEGER       NOT NULL,
    site_code               VARCHAR(80)   NOT NULL,
    trigger_type            VARCHAR(20)   NOT NULL,
    source_id               VARCHAR(120)  NOT NULL,
    source_reference        VARCHAR(80),
    reason                  VARCHAR(500),
    status                  VARCHAR(20)   NOT NULL,
    raised_at               TIMESTAMPTZ   NOT NULL,
    deferred_until          TIMESTAMPTZ,
    deferral_reason         VARCHAR(1000),
    deferred_by             VARCHAR(160),
    deferral_count          INTEGER       NOT NULL DEFAULT 0,
    cleared_at              TIMESTAMPTZ,
    cleared_by              VARCHAR(160),
    findings                VARCHAR(4000),
    created_by              VARCHAR(160)  NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL,
    last_modified_by        VARCHAR(160)  NOT NULL,
    last_modified_at        TIMESTAMPTZ   NOT NULL,
    record_version          BIGINT        NOT NULL DEFAULT 0,
    source_channel          VARCHAR(20)   NOT NULL,
    correlation_id          VARCHAR(120),
    -- Idempotent raising: the same incident saved twice flags the same assessment once.
    CONSTRAINT uq_risk_assessment_review_flags_source UNIQUE (assessment_id, trigger_type, source_id),
    CONSTRAINT ck_risk_assessment_review_flags_trigger CHECK (trigger_type IN ('INCIDENT')),
    CONSTRAINT ck_risk_assessment_review_flags_status CHECK (status IN ('OPEN', 'DEFERRED', 'CLEARED')),
    CONSTRAINT ck_risk_assessment_review_flags_cleared_with_findings
        CHECK (status <> 'CLEARED' OR (findings IS NOT NULL AND cleared_at IS NOT NULL)),
    CONSTRAINT ck_risk_assessment_review_flags_deferred_with_reason
        CHECK (status <> 'DEFERRED' OR (deferral_reason IS NOT NULL AND deferred_until IS NOT NULL)),
    CONSTRAINT ck_risk_assessment_review_flags_source_channel
        CHECK (source_channel IN ('WEB', 'API', 'SYSTEM', 'INTEGRATION'))
);

CREATE INDEX ix_risk_assessment_review_flags_site_status
    ON safety_security.risk_assessment_review_flags (site_code, status, raised_at);

-- ---------------------------------------------------------------------------------------------
-- Activity types the platform has actually used (S165-03: "not a static hard-coded list")
-- ---------------------------------------------------------------------------------------------
CREATE TABLE safety_security.risk_observed_activity_types (
    id                      UUID PRIMARY KEY,
    site_code               VARCHAR(80)  NOT NULL,
    activity_type           VARCHAR(80)  NOT NULL,
    source_system           VARCHAR(20)  NOT NULL,
    first_seen_at           TIMESTAMPTZ  NOT NULL,
    last_seen_at            TIMESTAMPTZ  NOT NULL,
    occurrences             BIGINT       NOT NULL,
    last_source_reference   VARCHAR(120),
    created_by              VARCHAR(160) NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL,
    last_modified_by        VARCHAR(160) NOT NULL,
    last_modified_at        TIMESTAMPTZ  NOT NULL,
    record_version          BIGINT       NOT NULL DEFAULT 0,
    source_channel          VARCHAR(20)  NOT NULL,
    correlation_id          VARCHAR(120),
    CONSTRAINT uq_risk_observed_activity_types UNIQUE (site_code, activity_type, source_system),
    CONSTRAINT ck_risk_observed_activity_types_occurrences CHECK (occurrences >= 1),
    CONSTRAINT ck_risk_observed_activity_types_source_channel
        CHECK (source_channel IN ('WEB', 'API', 'SYSTEM', 'INTEGRATION'))
);

-- ---------------------------------------------------------------------------------------------
-- The template library (global - no site, no policy)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE safety_security.risk_assessment_templates (
    id                      UUID PRIMARY KEY,
    name                    VARCHAR(200)  NOT NULL,
    activity_type           VARCHAR(80),
    description             VARCHAR(2000),
    active                  BOOLEAN       NOT NULL,
    created_by              VARCHAR(160)  NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL,
    last_modified_by        VARCHAR(160)  NOT NULL,
    last_modified_at        TIMESTAMPTZ   NOT NULL,
    record_version          BIGINT        NOT NULL DEFAULT 0,
    source_channel          VARCHAR(20)   NOT NULL,
    correlation_id          VARCHAR(120),
    CONSTRAINT ck_risk_assessment_templates_source_channel
        CHECK (source_channel IN ('WEB', 'API', 'SYSTEM', 'INTEGRATION'))
);

CREATE TABLE safety_security.risk_assessment_template_hazards (
    id                      UUID PRIMARY KEY,
    template_id             UUID          NOT NULL
        REFERENCES safety_security.risk_assessment_templates (id) ON DELETE CASCADE,
    sequence_no             INTEGER       NOT NULL,
    hazard_type             VARCHAR(40)   NOT NULL,
    description             VARCHAR(1000) NOT NULL,
    who_at_risk             VARCHAR(300),
    inherent_likelihood     VARCHAR(20)   NOT NULL,
    inherent_severity       VARCHAR(20)   NOT NULL,
    residual_likelihood     VARCHAR(20)   NOT NULL,
    residual_severity       VARCHAR(20)   NOT NULL,
    CONSTRAINT uq_risk_assessment_template_hazards_sequence UNIQUE (template_id, sequence_no)
);

CREATE TABLE safety_security.risk_assessment_template_controls (
    id                      UUID PRIMARY KEY,
    template_hazard_id      UUID          NOT NULL
        REFERENCES safety_security.risk_assessment_template_hazards (id) ON DELETE CASCADE,
    sequence_no             INTEGER       NOT NULL,
    control_type            VARCHAR(20)   NOT NULL,
    description             VARCHAR(1000) NOT NULL,
    CONSTRAINT uq_risk_assessment_template_controls_sequence UNIQUE (template_hazard_id, sequence_no),
    CONSTRAINT ck_risk_assessment_template_controls_type
        CHECK (control_type IN ('ELIMINATION', 'SUBSTITUTION', 'ENGINEERING', 'ADMINISTRATIVE', 'PPE'))
);

-- ---------------------------------------------------------------------------------------------
-- The review cycle per risk level (S165-02), as runtime configuration (PLAT-05)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE safety_security.risk_assessment_review_intervals (
    risk_level              VARCHAR(20)  PRIMARY KEY,
    interval_days           INTEGER      NOT NULL,
    reminder_lead_days      INTEGER      NOT NULL,
    created_by              VARCHAR(160) NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL,
    last_modified_by        VARCHAR(160) NOT NULL,
    last_modified_at        TIMESTAMPTZ  NOT NULL,
    record_version          BIGINT       NOT NULL DEFAULT 0,
    source_channel          VARCHAR(20)  NOT NULL,
    correlation_id          VARCHAR(120),
    CONSTRAINT ck_risk_assessment_review_intervals_level
        CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_risk_assessment_review_intervals_days CHECK (interval_days >= 1),
    CONSTRAINT ck_risk_assessment_review_intervals_lead
        CHECK (reminder_lead_days >= 0 AND reminder_lead_days < interval_days),
    CONSTRAINT ck_risk_assessment_review_intervals_source_channel
        CHECK (source_channel IN ('WEB', 'API', 'SYSTEM', 'INTEGRATION'))
);

-- Provisional defaults, shorter for higher risk as S165-02 requires. CLET's HSE function sets the real
-- cycle through the configuration endpoint; these exist so the first publish has an interval to use.
INSERT INTO safety_security.risk_assessment_review_intervals
    (risk_level, interval_days, reminder_lead_days, created_by, created_at, last_modified_by, last_modified_at,
     source_channel)
VALUES ('LOW', 365, 30, 'V19', now(), 'V19', now(), 'SYSTEM'),
       ('MEDIUM', 180, 21, 'V19', now(), 'V19', now(), 'SYSTEM'),
       ('HIGH', 90, 14, 'V19', now(), 'V19', now(), 'SYSTEM'),
       ('CRITICAL', 30, 7, 'V19', now(), 'V19', now(), 'SYSTEM');

-- ---------------------------------------------------------------------------------------------
-- S163 incidents gain the link S165-04 reacts to. Nullable and additive: every existing incident, and
-- every existing caller that does not send them, is unaffected. Held by value - no foreign key from an
-- incident into this module's tables.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE safety_security.security_incidents
    ADD COLUMN risk_assessment_id UUID,
    ADD COLUMN activity_type VARCHAR(80);

-- ---------------------------------------------------------------------------------------------
-- Row-level security and grants - last, for every table above.
-- ---------------------------------------------------------------------------------------------
SELECT safety_security.apply_site_scope_policies(ARRAY[
    'risk_assessments',
    'risk_assessment_versions',
    'risk_assessment_hazards',
    'risk_assessment_controls',
    'risk_assessment_sign_offs',
    'risk_assessment_review_flags',
    'risk_observed_activity_types'
]);

GRANT SELECT, INSERT, UPDATE, DELETE ON safety_security.risk_assessment_templates TO sfl_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON safety_security.risk_assessment_template_hazards TO sfl_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON safety_security.risk_assessment_template_controls TO sfl_app;
GRANT SELECT, INSERT, UPDATE ON safety_security.risk_assessment_review_intervals TO sfl_app;
