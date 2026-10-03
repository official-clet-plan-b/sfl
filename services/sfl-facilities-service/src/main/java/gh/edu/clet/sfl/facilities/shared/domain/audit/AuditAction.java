package gh.edu.clet.sfl.facilities.shared.domain.audit;

/**
 * The catalogue of auditable S152 actions.
 *
 * <p>An enum rather than a free string: the audit trail is queried by action
 * ({@code GET /api/v1/facilities/audit?action=...}) and hashed by name, so a typo would both hide a
 * record from its own filter and produce a hash nobody can reproduce.
 *
 * <p>Additive only. Renaming a constant would invalidate every hash computed with the old name - the
 * chain would replay as tampered. Deprecate, never rename.
 */
public enum AuditAction {

    // Estate register - SRS-SFL-S152-01
    SITE_CREATED,
    SITE_UPDATED,
    SITE_LIFECYCLE_CHANGED,
    SITE_OPERATING_MODE_CHANGED,
    BUILDING_CREATED,
    BUILDING_UPDATED,
    FLOOR_CREATED,
    FLOOR_UPDATED,
    ROOM_CREATED,
    ROOM_UPDATED,
    ROOM_LIFECYCLE_CHANGED,
    ROOM_READINESS_CHANGED,
    BUILDING_LIFECYCLE_CHANGED,
    FLOOR_LIFECYCLE_CHANGED,
    ZONE_CREATED,
    ZONE_UPDATED,
    ZONE_LIFECYCLE_CHANGED,
    ZONE_MEMBER_ADDED,
    ZONE_MEMBER_REMOVED,
    DEVICE_REFERENCE_REGISTERED,
    DEVICE_REFERENCE_UPDATED,
    DEVICE_REFERENCE_LIFECYCLE_CHANGED,
    FACILITY_ASSET_REGISTERED,
    FACILITY_ASSET_UPDATED,
    FACILITY_ASSET_STATUS_CHANGED,
    FACILITY_ASSET_RELOCATED,
    FACILITY_ASSET_LIFECYCLE_CHANGED,

    // Readiness - SRS-SFL-S152-01, -02, -05
    READINESS_CHECKLIST_CREATED,
    READINESS_CHECKLIST_UPDATED,
    READINESS_ASSESSMENT_SUBMITTED,
    READINESS_BLOCKER_RAISED,
    READINESS_BLOCKER_RESOLVED,
    READINESS_LOCK_ENGAGED,
    READINESS_LOCK_RELEASED,

    // Maintenance - SRS-SFL-S153-01, -02, -03
    FAULT_REPORTED,
    FAULT_TRIAGED,
    FAULT_DISMISSED,
    FAULT_RESOLVED,
    FAULT_LIFECYCLE_CHANGED,
    WORK_ORDER_CREATED,
    WORK_ORDER_ASSIGNED,
    WORK_ORDER_STARTED,
    WORK_ORDER_HELD,
    WORK_ORDER_COMPLETED,
    WORK_ORDER_REOPENED,
    WORK_ORDER_CLOSED,
    WORK_ORDER_CANCELLED,
    /**
     * The scheduled evaluator moved an item up the ladder. SRS-SFL-S153-02.
     *
     * <p>Audited even though no human did it, because "who escalated this and when" is the first
     * question asked about an item that reached a director's queue, and "the system, at 03:14, on the
     * rules then in force" is the only answer that closes it.
     */
    WORK_ORDER_ESCALATED,
    FAULT_ESCALATED,
    WORK_ORDER_PART_RECORDED,
    WORK_ORDER_PART_REMOVED,
    PREVENTIVE_SCHEDULE_CREATED,
    PREVENTIVE_SCHEDULE_UPDATED,
    PREVENTIVE_SCHEDULE_LIFECYCLE_CHANGED,
    PREVENTIVE_WORK_ORDER_GENERATED,
    MAINTENANCE_VENDOR_REGISTERED,
    MAINTENANCE_VENDOR_UPDATED,
    MAINTENANCE_VENDOR_LIFECYCLE_CHANGED,
    EVIDENCE_ATTACHED,
    /**
     * Evidence left CLET. SRS-SFL-S153-03 requires export to carry an approved reason and to be
     * logged; this is that log, and it records the recipient as well as the reason.
     */
    EVIDENCE_EXPORTED,
    /** The retention period ran out and the reference was cleared. The row survives; see V13. */
    EVIDENCE_DISPOSED,
    EVIDENCE_LEGAL_HOLD_CHANGED,

    // Booking - SRS-SFL-S159-01, -02, -03
    BOOKING_REQUESTED,
    BOOKING_CONFIRMED,
    BOOKING_REJECTED,
    BOOKING_RESCHEDULED,
    BOOKING_STARTED,
    BOOKING_COMPLETED,
    BOOKING_CANCELLED,
    /**
     * Somebody booked into a space readiness said was unavailable. SRS-SFL-S159-02.
     *
     * <p>Its own action rather than a field on {@code BOOKING_REQUESTED}, because "show me every
     * override this term" is the question an examinations board asks after something goes wrong, and
     * it should be one filtered read rather than a scan of every booking looking for a non-null column.
     */
    BOOKING_READINESS_OVERRIDDEN,
    /** The sweep marked a booking never used. Audited because nobody did it. */
    BOOKING_NO_SHOW_RECORDED,
    BOOKING_READINESS_HOLD_PLACED,
    BOOKING_READINESS_HOLD_CLEARED,
    BOOKABLE_RESOURCE_REGISTERED,
    BOOKABLE_RESOURCE_UPDATED,
    BOOKABLE_RESOURCE_LIFECYCLE_CHANGED,
    BOOKING_RESOURCE_ALLOCATED,
    BOOKING_RESOURCE_RELEASED,
    BOOKING_SETUP_TASK_CREATED,
    BOOKING_SETUP_TASK_RESOLVED,

    // =============================================================================================
    // Phase 2 IFIMP (SRS CLET/DTI/CL9/SFL/SRS/2026/002 §3.1). One block per system. A module that
    // needs another action appends it at the END of its own block only, so parallel work never edits
    // the same lines. Additive only, like everything above: a constant's name is part of its hash.
    // =============================================================================================

    // ---- S156 Building Management System / IoT - SRS-SFL-S156-01..05. Owned by buildingsystems.
    BMS_TELEMETRY_REJECTED,
    BMS_TELEMETRY_QUARANTINED,
    BMS_QUARANTINE_RESOLVED,
    BMS_READING_FLAGGED_IMPLAUSIBLE,
    BMS_DEVICE_REGISTERED,
    BMS_DEVICE_UPDATED,
    BMS_DEVICE_RETIRED,
    BMS_DEVICE_LIFECYCLE_REMINDER_RAISED,
    BMS_RULE_CREATED,
    BMS_RULE_REVISED,
    BMS_RULE_DISABLED_BY_OVERRIDE,
    BMS_RULE_ENABLED,
    BMS_RULE_CONFLICT_DETECTED,
    BMS_ALERT_RAISED,
    BMS_ALERT_CORRELATED,
    BMS_ALERT_CLEARED,
    BMS_WORK_ORDER_RAISED,
    BMS_SENSOR_OFFLINE_DETECTED,
    BMS_SENSOR_RECOVERED,
    BMS_CRITICAL_FAULT_ESCALATED,
    /** An AVAMP asset event updated S156's local projection (S156-04). */
    BMS_AVAMP_ASSET_PROJECTED,
    /** The retention sweep deleted readings past the configured retention (S156-01). */
    BMS_TELEMETRY_PURGED,
    // ---- end of block

    // ---- S157 Energy & Sustainability Monitoring - SRS-SFL-S157-01..04. Owned by energy.
    ENERGY_METER_REGISTERED,
    ENERGY_METER_UPDATED,
    ENERGY_METER_RETIRED,
    ENERGY_TELEMETRY_REJECTED,
    ENERGY_READING_ENTERED,
    ENERGY_READING_HELD,
    ENERGY_READING_VERIFIED,
    ENERGY_READING_REJECTED,
    ENERGY_BUDGET_VERSION_CREATED,
    ENERGY_TARIFF_VERSION_CREATED,
    ENERGY_EMISSION_FACTOR_VERSION_CREATED,
    ENERGY_VARIANCE_ALERT_RAISED,
    ENERGY_ANOMALY_FLAGGED,
    ENERGY_TARIFF_MISSING_FLAGGED,
    SUSTAINABILITY_KPI_PUBLISHED,
    ENERGY_READING_INGESTED,
    ENERGY_PERIOD_CLOSED,
    ENERGY_READING_RETENTION_PURGED,
    // ---- end of block

    // ---- S158 Space Planning & Move Management - SRS-SFL-S158-01..04. Owned by spaceplanning.
    SPACE_SCENARIO_CREATED,
    SPACE_SCENARIO_UPDATED,
    SPACE_SCENARIO_ALLOCATION_CHANGED,
    SPACE_SCENARIO_COMMITTED,
    SPACE_SCENARIO_DISCARDED,
    SPACE_SCENARIO_HANDOVER_CONFIRMED,
    SPACE_SCENARIO_USE_AS_CURRENT_REFUSED,
    SPACE_ALLOCATION_APPLIED,
    OCCUPANCY_STANDARD_VERSION_CREATED,
    OCCUPANCY_OVERRIDE_RECORDED,
    UTILISATION_SNAPSHOT_RECORDED,
    UTILISATION_SIGNAL_RAISED,
    UTILISATION_SIGNAL_CLEARED,
    SPACE_CHANGE_REQUEST_SUBMITTED,
    SPACE_CHANGE_REQUEST_DECIDED,
    SPACE_CHANGE_REQUEST_LINKED,
    SPACE_CHANGE_REQUEST_RESOLVED,
    // Added by the S158 build: the first half of the two-person override (S158-02), and its withdrawal
    // when the room's allocation changes under it.
    OCCUPANCY_OVERRIDE_REQUESTED,
    OCCUPANCY_OVERRIDE_WITHDRAWN,
    // ---- end of block

    // ---- S169 Cleaning & Janitorial Schedule Management - SRS-SFL-S169-01..04. Owned by cleaning.
    CLEANING_SCHEDULE_CREATED,
    CLEANING_SCHEDULE_UPDATED,
    CLEANING_CHECKLIST_TEMPLATE_CREATED,
    CLEANING_TASK_CREATED,
    CLEANING_BOOKING_TASK_REJECTED,
    CLEANING_TASK_ASSIGNED,
    CLEANING_TASK_STARTED,
    CLEANING_CHECKLIST_ITEM_RECORDED,
    CLEANING_TASK_COMPLETED,
    CLEANING_TASK_COMPLETION_REFUSED,
    CLEANING_TASK_CANCELLED,
    CLEANING_FEEDBACK_SUBMITTED,
    CLEANING_LOW_RATING_FLAGGED,
    CLEANING_VENDOR_REGISTERED,
    CLEANING_VENDOR_SLA_VERSION_CREATED,
    CLEANING_SLA_BREACH_RECORDED,
    CLEANING_CAPACITY_RESERVED,
    CLEANING_CAPACITY_CONFLICT_RETURNED,
    CLEANING_CAPACITY_RELEASED,
    CLEANING_TASK_RESCHEDULED,
    CLEANING_COMPLETION_DISCREPANCY_RECORDED,
    CLEANING_LOW_RATING_REVIEWED,
    CLEANING_VENDOR_MASTER_REFERENCE_RECORDED,
    CLEANING_VENDOR_STATUS_CHANGED,
    // ---- end of block

    // ---- S173 Event Logistics & Set-Up Workflow - SRS-SFL-S173-01..04. Owned by eventlogistics.
    EVENT_HANDOFF_ACCEPTED,
    EVENT_HANDOFF_REJECTED,
    EVENT_SETUP_TASK_CREATED,
    EVENT_RESOURCE_REQUEST_RAISED,
    EVENT_RESOURCE_REQUEST_STATUS_CHANGED,
    EVENT_MANUAL_COORDINATION_ACCEPTED,
    EVENT_RISK_ASSESSMENT_LINKED,
    EVENT_RISK_CATEGORY_CONFIGURED,
    EVENT_SETUP_TASK_CONFIRMED,
    EVENT_SETUP_CONFIRMATION_REFUSED,
    EVENT_READINESS_ESCALATED,
    EVENT_SETUP_TASK_COMPLETED,
    EVENT_RECONCILIATION_RECORDED,
    EVENT_TEMPLATE_GAP_RECORDED,
    // ---- end of block

    // ---- S176 Construction Project Management - SRS-SFL-S176-01..04. Owned by construction.
    PROJECT_REGISTERED,
    PROJECT_UPDATED,
    PROJECT_BASELINE_REVISED,
    PROJECT_MILESTONE_REVISED,
    PROJECT_APPROVED,
    PROJECT_STARTED,
    PROJECT_START_REFUSED,
    PROJECT_PERMIT_LINKED,
    CONTRACTOR_REGISTERED,
    CONTRACTOR_COMPLIANCE_UPDATED,
    CONTRACTOR_ACCESS_REQUESTED,
    CONTRACTOR_ACCESS_REFUSED,
    CONTRACTOR_ACCESS_SUSPENDED,
    VARIATION_SUBMITTED,
    VARIATION_APPROVED,
    VARIATION_REJECTED,
    VARIATION_ESCALATION_REQUIRED,
    VARIATION_ESCALATED_APPROVAL_RECORDED,
    PROJECT_PRACTICAL_COMPLETION_RECORDED,
    PROJECT_HANDOVER_RECORDED,
    PROJECT_HANDOVER_FLAGGED_INCOMPLETE,
    DEFECT_RAISED,
    DEFECT_CLOSED,
    DEFECT_DEFERRED,
    PROJECT_CLOSED,
    PROJECT_CLOSE_REFUSED,
    PROJECT_CANCELLED,
    PROJECT_MILESTONE_ACHIEVED,
    PROJECT_CONTRACTOR_ASSIGNED,
    PROJECT_PERMIT_STATUS_CHANGED,
    PROJECT_SCENARIO_CONFIRMATION_RECORDED,
    // ---- end of block

    // ---- Phase 2 shared - the signed vendor inbox (SRS NFR-SEC2) and procurement-gate reporting (CORR-07).
    VENDOR_MESSAGE_REJECTED,
    // ---- end of block

    // ---- Estate registers - S170, S172, S177, S178 and S179.
    REGISTER_RECORD_CREATED,
    REGISTER_RECORD_UPDATED,

    // ---- S170 Hygiene & Pest-Control Audit Tracker.
    HYGIENE_CONTROL_CREATED,
    HYGIENE_CONTROL_UPDATED,
    HYGIENE_FINDING_CREATED,
    HYGIENE_FINDING_UPDATED,
    HYGIENE_FINDING_CLOSED,
    HYGIENE_FINDING_REOPENED,
    HYGIENE_ACTION_CREATED,
    HYGIENE_ACTION_UPDATED,
    HYGIENE_EVIDENCE_SUBMITTED,
    HYGIENE_EVIDENCE_REVIEWED,
    HYGIENE_EVIDENCE_VIEWED,
    HYGIENE_ESCALATED,
    // ---- end of block

    // Governance - SRS-SFL-S152-03, -04, -05
    RUNTIME_CONFIGURATION_CHANGED,
    DASHBOARD_SNAPSHOT_GENERATED,
    AUDIT_INTEGRITY_VERIFIED,

    /**
     * A refused command or query.
     *
     * <p>A denial is evidence. "You are not authorised to access this site or record" is an
     * {@code SRS-SFL-S152-01} error state, and an attempt to read a site outside an actor's scope is
     * exactly the event a compliance review looks for.
     */
    AUTHORIZATION_DENIED
}
