-- S178 Waste Management & Recycling Tracking: configuration (streams, points, carriers, destinations,
-- units), collections with a chain of custody, evidence, and exceptions. Site-scoped tables get the
-- row-level policy at the foot of the file; carriers, destinations and units are organisation-wide.

CREATE SEQUENCE facilities.waste_collection_seq;
CREATE SEQUENCE facilities.waste_exception_seq;

-- Unit conversion is configuration, not code: a new unit is a row. The original measurement is always
-- kept on the collection beside the normalised kilograms.
CREATE TABLE facilities.waste_units (
    code VARCHAR(20) PRIMARY KEY,
    name VARCHAR(60) NOT NULL,
    kilograms_per_unit NUMERIC(18, 6) NOT NULL CHECK (kilograms_per_unit > 0)
);
INSERT INTO facilities.waste_units (code, name, kilograms_per_unit) VALUES
    ('KG', 'Kilogram', 1), ('G', 'Gram', 0.001), ('TONNE', 'Tonne', 1000), ('LB', 'Pound', 0.45359237);

CREATE TABLE facilities.waste_carriers (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    licence_reference VARCHAR(120) NOT NULL,
    licence_expires_on DATE NOT NULL,
    hazardous_approved BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(12) NOT NULL,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_waste_carrier_status CHECK (status IN ('APPROVED', 'SUSPENDED'))
);

CREATE TABLE facilities.waste_destinations (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    destination_type VARCHAR(20) NOT NULL,
    permit_reference VARCHAR(120) NOT NULL,
    permit_expires_on DATE NOT NULL,
    accepts_hazardous BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(12) NOT NULL,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_waste_destination_type CHECK (destination_type IN ('RECYCLER', 'REUSE', 'COMPOSTING', 'TREATMENT', 'LANDFILL')),
    CONSTRAINT ck_waste_destination_status CHECK (status IN ('APPROVED', 'SUSPENDED'))
);

CREATE TABLE facilities.waste_streams (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    category VARCHAR(20) NOT NULL,
    hazardous BOOLEAN NOT NULL DEFAULT FALSE,
    diverted BOOLEAN NOT NULL DEFAULT FALSE,
    handling_rules VARCHAR(2000),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_waste_stream_category CHECK (category IN
        ('GENERAL', 'RECYCLABLE', 'ORGANIC', 'ELECTRONIC', 'CHEMICAL', 'MEDICAL', 'CONSTRUCTION', 'OTHER'))
);

CREATE TABLE facilities.waste_points (
    id UUID PRIMARY KEY,
    site_code VARCHAR(40) NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    room_id UUID,
    container_description VARCHAR(240),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ux_waste_point UNIQUE (site_code, code)
);

CREATE TABLE facilities.waste_collections (
    id UUID PRIMARY KEY,
    reference VARCHAR(40) NOT NULL UNIQUE,
    site_code VARCHAR(40) NOT NULL,
    stream_id UUID NOT NULL REFERENCES facilities.waste_streams (id),
    point_id UUID NOT NULL REFERENCES facilities.waste_points (id),
    carrier_id UUID NOT NULL REFERENCES facilities.waste_carriers (id),
    destination_id UUID REFERENCES facilities.waste_destinations (id),
    hazardous BOOLEAN NOT NULL,
    scheduled_for DATE NOT NULL,
    collected_on DATE,
    quantity NUMERIC(18, 4),
    unit VARCHAR(20) REFERENCES facilities.waste_units (code),
    quantity_kg NUMERIC(18, 4),
    quantity_basis VARCHAR(10),
    manifest_reference VARCHAR(120),
    certificate_reference VARCHAR(120),
    certificate_received_on DATE,
    contaminated BOOLEAN NOT NULL DEFAULT FALSE,
    quantity_reconciled BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(24) NOT NULL,
    closed_at TIMESTAMPTZ,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_waste_collection_status CHECK (status IN
        ('SCHEDULED', 'COLLECTED', 'HANDED_OVER', 'DESTINATION_CONFIRMED', 'CLOSED', 'MISSED', 'CANCELLED')),
    CONSTRAINT ck_waste_collection_basis CHECK (quantity_basis IS NULL OR quantity_basis IN ('MEASURED', 'ESTIMATED'))
);
CREATE INDEX ix_waste_collections_scope ON facilities.waste_collections (site_code, status, scheduled_for);

-- Append-only chain of custody: generation to final treatment. Nothing updates or deletes a row.
CREATE TABLE facilities.waste_custody_events (
    id UUID PRIMARY KEY,
    collection_id UUID NOT NULL REFERENCES facilities.waste_collections (id),
    site_code VARCHAR(40) NOT NULL,
    step VARCHAR(30) NOT NULL,
    from_party VARCHAR(160) NOT NULL,
    to_party VARCHAR(160),
    location VARCHAR(160),
    evidence_reference VARCHAR(240),
    note VARCHAR(1000),
    recorded_by VARCHAR(160) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_waste_custody_collection ON facilities.waste_custody_events (collection_id, occurred_at);

CREATE TABLE facilities.waste_evidence (
    id UUID PRIMARY KEY,
    collection_id UUID NOT NULL REFERENCES facilities.waste_collections (id),
    site_code VARCHAR(40) NOT NULL,
    kind VARCHAR(12) NOT NULL,
    reference VARCHAR(240) NOT NULL,
    file_name VARCHAR(240) NOT NULL,
    media_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    content_hash VARCHAR(128) NOT NULL,
    retention_class VARCHAR(20) NOT NULL,
    status VARCHAR(12) NOT NULL,
    submitted_by VARCHAR(160) NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    reviewed_by VARCHAR(160),
    reviewed_at TIMESTAMPTZ,
    review_reason VARCHAR(2000),
    CONSTRAINT ck_waste_evidence_kind CHECK (kind IN ('MANIFEST', 'RECEIVING', 'CERTIFICATE', 'PHOTO')),
    CONSTRAINT ck_waste_evidence_status CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REJECTED'))
);
CREATE INDEX ix_waste_evidence_collection ON facilities.waste_evidence (collection_id, kind, status);

CREATE TABLE facilities.waste_exceptions (
    id UUID PRIMARY KEY,
    reference VARCHAR(40) NOT NULL UNIQUE,
    site_code VARCHAR(40) NOT NULL,
    collection_id UUID REFERENCES facilities.waste_collections (id),
    exception_type VARCHAR(30) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    owner_reference VARCHAR(160),
    due_on DATE NOT NULL,
    status VARCHAR(12) NOT NULL,
    escalated_to VARCHAR(20) NOT NULL DEFAULT 'FACILITIES_OWNER',
    incident_state VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED',
    incident_reference VARCHAR(80),
    work_order_state VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED',
    work_order_id UUID,
    work_order_number VARCHAR(40),
    resolution VARCHAR(2000),
    resolved_by VARCHAR(160),
    resolved_at TIMESTAMPTZ,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_waste_exception_type CHECK (exception_type IN
        ('MISSED_COLLECTION', 'CONTAMINATION', 'MISSING_CERTIFICATE', 'MISSING_RECEIVING_EVIDENCE', 'SPILL',
         'UNAPPROVED_CARRIER', 'UNAPPROVED_DESTINATION')),
    CONSTRAINT ck_waste_exception_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED')),
    CONSTRAINT ck_waste_exception_work_order CHECK (work_order_state IN ('NOT_REQUIRED', 'PENDING_MANUAL', 'RAISED')),
    CONSTRAINT ck_waste_exception_incident CHECK (incident_state IN ('NOT_REQUIRED', 'PENDING_MANUAL', 'LINKED'))
);
CREATE INDEX ix_waste_exceptions_scope ON facilities.waste_exceptions (site_code, status, due_on);
-- One open exception per collection and type: a sweep or a repeated closure attempt cannot pile them up.
CREATE UNIQUE INDEX ux_waste_exception_open ON facilities.waste_exceptions (collection_id, exception_type)
    WHERE status <> 'RESOLVED' AND collection_id IS NOT NULL;

CREATE TABLE facilities.waste_history (
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
CREATE INDEX ix_waste_history_subject ON facilities.waste_history (subject_id, occurred_at);

SELECT facilities.apply_site_scope_policies();
