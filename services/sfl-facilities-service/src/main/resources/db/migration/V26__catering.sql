-- S172 Catering & Cafeteria Management: suppliers, venues, menus with allergen metadata, services with a
-- controlled approval workflow, dietary requests (minimum personal data), supplier and food-safety checks,
-- exceptions, variances, evidence and an append-only history. Suppliers are organisation-wide; everything
-- else is site-scoped and gets the row-level policy at the foot of the file.

CREATE SEQUENCE facilities.cat_service_seq;
CREATE SEQUENCE facilities.cat_exception_seq;

CREATE TABLE facilities.cat_suppliers (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    certificate_reference VARCHAR(120) NOT NULL,
    certificate_expires_on DATE NOT NULL,
    finance_reference VARCHAR(120),
    status VARCHAR(12) NOT NULL,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_cat_supplier_status CHECK (status IN ('APPROVED', 'SUSPENDED'))
);

CREATE TABLE facilities.cat_venues (
    id UUID PRIMARY KEY,
    site_code VARCHAR(40) NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    room_id UUID,
    capacity INTEGER NOT NULL CHECK (capacity > 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ux_cat_venue UNIQUE (site_code, code)
);

CREATE TABLE facilities.cat_menus (
    id UUID PRIMARY KEY,
    site_code VARCHAR(40) NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    status VARCHAR(12) NOT NULL,
    approved_by VARCHAR(160),
    approved_at TIMESTAMPTZ,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ux_cat_menu UNIQUE (site_code, code),
    CONSTRAINT ck_cat_menu_status CHECK (status IN ('DRAFT', 'APPROVED', 'RETIRED'))
);

-- Allergens and dietary tags are held as comma-separated codes from a closed vocabulary, validated by the service.
CREATE TABLE facilities.cat_menu_items (
    id UUID PRIMARY KEY,
    menu_id UUID NOT NULL REFERENCES facilities.cat_menus (id),
    site_code VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    allergens VARCHAR(400) NOT NULL DEFAULT '',
    allergens_declared BOOLEAN NOT NULL DEFAULT FALSE,
    dietary_tags VARCHAR(400) NOT NULL DEFAULT '',
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_cat_menu_items_menu ON facilities.cat_menu_items (menu_id);

CREATE TABLE facilities.cat_services (
    id UUID PRIMARY KEY,
    reference VARCHAR(40) NOT NULL UNIQUE,
    site_code VARCHAR(40) NOT NULL,
    venue_id UUID NOT NULL REFERENCES facilities.cat_venues (id),
    menu_id UUID NOT NULL REFERENCES facilities.cat_menus (id),
    supplier_id UUID NOT NULL REFERENCES facilities.cat_suppliers (id),
    context_type VARCHAR(12) NOT NULL,
    context_reference VARCHAR(160),
    title VARCHAR(240) NOT NULL,
    service_date DATE NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    cancellation_cutoff TIMESTAMPTZ NOT NULL,
    expected_guests INTEGER NOT NULL CHECK (expected_guests > 0),
    planned_portions INTEGER NOT NULL CHECK (planned_portions > 0),
    delivered_portions INTEGER,
    status VARCHAR(16) NOT NULL,
    requested_by VARCHAR(160),
    approved_by VARCHAR(160),
    approved_at TIMESTAMPTZ,
    capacity_exception_reason VARCHAR(1000),
    capacity_approved_by VARCHAR(160),
    supplier_exception_reason VARCHAR(1000),
    supplier_approved_by VARCHAR(160),
    purchase_reference VARCHAR(120),
    invoice_reference VARCHAR(120),
    finance_state VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED',
    delivered_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(1000),
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_cat_service_context CHECK (context_type IN ('EVENT', 'BOOKING', 'EXAMINATION', 'ROUTINE')),
    CONSTRAINT ck_cat_service_status CHECK (status IN
        ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CONFIRMED', 'DELIVERED', 'RECONCILED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_cat_service_finance CHECK (finance_state IN ('NOT_STARTED', 'PENDING_FINANCE', 'RECORDED'))
);
CREATE INDEX ix_cat_services_scope ON facilities.cat_services (site_code, status, service_date);

-- Minimum necessary personal data: an opaque reference to the person, never a name, and who authorised collecting it.
CREATE TABLE facilities.cat_dietary_requests (
    id UUID PRIMARY KEY,
    service_id UUID NOT NULL REFERENCES facilities.cat_services (id),
    site_code VARCHAR(40) NOT NULL,
    person_reference VARCHAR(80) NOT NULL,
    need_type VARCHAR(12) NOT NULL,
    need_code VARCHAR(30) NOT NULL,
    authorised_by VARCHAR(160) NOT NULL,
    status VARCHAR(12) NOT NULL,
    substitute_item_id UUID REFERENCES facilities.cat_menu_items (id),
    substitution_approved_by VARCHAR(160),
    waiver_reason VARCHAR(1000),
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_cat_need_type CHECK (need_type IN ('ALLERGY', 'DIETARY')),
    CONSTRAINT ck_cat_need_status CHECK (status IN ('OPEN', 'SUBSTITUTED', 'WAIVED'))
);
CREATE INDEX ix_cat_dietary_service ON facilities.cat_dietary_requests (service_id);

CREATE TABLE facilities.cat_checks (
    id UUID PRIMARY KEY,
    site_code VARCHAR(40) NOT NULL,
    service_id UUID REFERENCES facilities.cat_services (id),
    supplier_id UUID REFERENCES facilities.cat_suppliers (id),
    check_type VARCHAR(16) NOT NULL,
    hold_type VARCHAR(4),
    temperature_c NUMERIC(5, 1),
    result VARCHAR(4) NOT NULL,
    notes VARCHAR(1000),
    checked_by VARCHAR(160) NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_cat_check_type CHECK (check_type IN ('SUPPLIER', 'TEMPERATURE', 'FOOD_SAFETY')),
    CONSTRAINT ck_cat_check_result CHECK (result IN ('PASS', 'FAIL')),
    CONSTRAINT ck_cat_check_hold CHECK (hold_type IS NULL OR hold_type IN ('HOT', 'COLD'))
);
CREATE INDEX ix_cat_checks_service ON facilities.cat_checks (service_id, check_type);
CREATE INDEX ix_cat_checks_supplier ON facilities.cat_checks (supplier_id, check_type, checked_at DESC);

CREATE TABLE facilities.cat_exceptions (
    id UUID PRIMARY KEY,
    reference VARCHAR(40) NOT NULL UNIQUE,
    site_code VARCHAR(40) NOT NULL,
    service_id UUID REFERENCES facilities.cat_services (id),
    exception_type VARCHAR(24) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    owner_reference VARCHAR(160) NOT NULL,
    status VARCHAR(12) NOT NULL,
    incident_state VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED',
    incident_reference VARCHAR(80),
    resolution VARCHAR(2000),
    resolved_by VARCHAR(160),
    resolved_at TIMESTAMPTZ,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_cat_exception_type CHECK (exception_type IN
        ('SHORTAGE', 'SUBSTITUTION', 'FOOD_SAFETY_INCIDENT', 'SERVICE_EXCEPTION')),
    CONSTRAINT ck_cat_exception_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_cat_exception_incident CHECK (incident_state IN ('NOT_REQUIRED', 'PENDING_MANUAL', 'LINKED'))
);
CREATE INDEX ix_cat_exceptions_scope ON facilities.cat_exceptions (site_code, status, created_at);

-- Every variance has an owner and a reason; neither column may be empty.
CREATE TABLE facilities.cat_variances (
    id UUID PRIMARY KEY,
    service_id UUID NOT NULL REFERENCES facilities.cat_services (id),
    site_code VARCHAR(40) NOT NULL,
    kind VARCHAR(12) NOT NULL,
    planned NUMERIC(12, 2) NOT NULL,
    actual NUMERIC(12, 2) NOT NULL,
    difference NUMERIC(12, 2) NOT NULL,
    owner_reference VARCHAR(160) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(12) NOT NULL,
    approved_by VARCHAR(160),
    approved_at TIMESTAMPTZ,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_cat_variance_kind CHECK (kind IN ('QUANTITY', 'COST', 'SUBSTITUTION')),
    CONSTRAINT ck_cat_variance_status CHECK (status IN ('OPEN', 'APPROVED')),
    CONSTRAINT ck_cat_variance_text CHECK (length(btrim(owner_reference)) > 0 AND length(btrim(reason)) > 0)
);
CREATE INDEX ix_cat_variances_service ON facilities.cat_variances (service_id, status);

CREATE TABLE facilities.cat_evidence (
    id UUID PRIMARY KEY,
    service_id UUID NOT NULL REFERENCES facilities.cat_services (id),
    site_code VARCHAR(40) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    reference VARCHAR(240) NOT NULL,
    file_name VARCHAR(240) NOT NULL,
    media_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    content_hash VARCHAR(128) NOT NULL,
    retention_class VARCHAR(20) NOT NULL,
    submitted_by VARCHAR(160) NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_cat_evidence_kind CHECK (kind IN ('DELIVERY_NOTE', 'INVOICE', 'CERTIFICATE', 'TEMPERATURE_LOG', 'PHOTO'))
);
CREATE INDEX ix_cat_evidence_service ON facilities.cat_evidence (service_id, kind);

CREATE TABLE facilities.cat_history (
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
CREATE INDEX ix_cat_history_subject ON facilities.cat_history (subject_id, occurred_at);

SELECT facilities.apply_site_scope_policies();
