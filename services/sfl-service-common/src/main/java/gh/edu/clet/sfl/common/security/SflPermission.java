package gh.edu.clet.sfl.common.security;

public enum SflPermission {
    FACILITIES_WORK_ORDER_CREATE,
    FACILITIES_WORK_ORDER_ASSIGN,
    FACILITIES_WORK_ORDER_CLOSE,
    FACILITIES_WORK_ORDER_READ,
    FACILITIES_READINESS_MANAGE,
    FACILITIES_MASTER_DATA_MANAGE,
    // The four work-order permissions above predate S152 and are kept, because they already name the
    // right things. S153 adds the rest of what SRS-SFL-S153-01..03 needs: the fault side of the
    // workflow, the transitions the old three-state model had nowhere for, preventive scheduling,
    // vendors, and evidence - which is separated into read and export because S153-03 makes export a
    // distinct authorised act with a recorded reason, not simply a stronger form of reading.
    FACILITIES_FAULT_REPORT,
    FACILITIES_FAULT_READ,
    FACILITIES_FAULT_TRIAGE,
    FACILITIES_WORK_ORDER_UPDATE,
    FACILITIES_WORK_ORDER_CANCEL,
    FACILITIES_PM_SCHEDULE_READ,
    FACILITIES_PM_SCHEDULE_MANAGE,
    FACILITIES_VENDOR_READ,
    FACILITIES_VENDOR_MANAGE,
    FACILITIES_EVIDENCE_READ,
    FACILITIES_EVIDENCE_ATTACH,
    FACILITIES_EVIDENCE_EXPORT,
    ASSET_REFERENCE_MANAGE,
    ASSET_REFERENCE_READ,
    AUDIT_READ,
    INTEGRATION_HEALTH_READ,

    // Computer-Aided Facility Management / IWMS permissions (SRS S152). Additive only.
    // The role -> permission mapping lives in the facilities service
    // (gh.edu.clet.sfl.facilities.shared.domain.policy.FacilitiesPermissionMatrix), so no IFIMP
    // business rule enters this shared library. S152 hosts S153 and S159, so these are the
    // permissions those modules will inherit rather than redeclare.
    FACILITIES_SITE_READ,
    FACILITIES_SITE_MANAGE,
    FACILITIES_SPACE_READ,
    FACILITIES_SPACE_MANAGE,
    FACILITIES_ZONE_READ,
    FACILITIES_ZONE_MANAGE,
    FACILITIES_DEVICE_REFERENCE_READ,
    FACILITIES_DEVICE_REFERENCE_REGISTER,
    FACILITIES_ASSET_READ,
    FACILITIES_ASSET_MANAGE,
    FACILITIES_READINESS_READ,
    FACILITIES_READINESS_ASSESS,
    FACILITIES_READINESS_OVERRIDE,
    FACILITIES_READINESS_CHECKLIST_MANAGE,
    FACILITIES_OPERATING_MODE_CHANGE,
    FACILITIES_DASHBOARD_READ,
    FACILITIES_DASHBOARD_DRILLDOWN,
    FACILITIES_AUDIT_READ,
    FACILITIES_AUDIT_INTEGRITY_CHECK,
    FACILITIES_CONFIG_READ,
    FACILITIES_CONFIG_MANAGE,

    // Room and Resource Booking permissions (SRS S159). Additive only, and hosted on S152 like S153.
    //
    // REQUEST and APPROVE are separate because SRS-SFL-S159-02 makes approval a distinct authorised
    // act; CANCEL is separate again because cancelling one's own booking is not the same authority as
    // cancelling somebody else's, and the per-record narrowing for the former lives in
    // BookingApplicationService rather than here.
    //
    // OVERRIDE is the interesting one: it books into a space readiness says is unavailable. Held by
    // very few roles, always recorded with a reason, and never implied by any other permission.
    FACILITIES_BOOKING_READ,
    FACILITIES_BOOKING_REQUEST,
    FACILITIES_BOOKING_APPROVE,
    FACILITIES_BOOKING_CANCEL,
    FACILITIES_BOOKING_OVERRIDE,
    FACILITIES_RESOURCE_READ,
    FACILITIES_RESOURCE_MANAGE,
    FACILITIES_SETUP_TASK_MANAGE,

    // Fleet and vehicle management permissions (SRS S166). Additive only.
    // The role -> permission mapping lives in the fleet feature package
    // (gh.edu.clet.sfl.fleetlogistics.fleet.domain.policy.FleetPermissionMatrix),
    // so no fleet business logic enters this shared library.
    FLEET_VEHICLE_READ,
    FLEET_VEHICLE_MANAGE,
    FLEET_VEHICLE_LIFECYCLE_MANAGE,
    FLEET_VEHICLE_RESTORE,
    FLEET_VEHICLE_SENSITIVE_READ,
    FLEET_VEHICLE_ODOMETER_CORRECT,
    FLEET_COMPLIANCE_MANAGE,
    FLEET_SERVICE_RECORD_MANAGE,
    FLEET_DRIVER_READ,
    FLEET_DRIVER_MANAGE,
    FLEET_DRIVER_SENSITIVE_READ,
    FLEET_TRIP_READ,
    FLEET_TRIP_MANAGE,
    FLEET_TRIP_ASSIGN,
    /**
     * Confirm or defer a trip assigned to you.
     *
     * <p>A driver's permission, and the only write in the fleet register they hold. Separate from
     * {@code FLEET_TRIP_MANAGE} because it is not a dispatcher's power scoped down - it answers for
     * one trip, the actor's own, and grants nothing over anybody else's.
     */
    FLEET_TRIP_ACKNOWLEDGE,
    /**
     * Close a trip that is assigned to you, and only that one.
     *
     * <p>The second write a driver holds in the fleet register, and scoped the same way as
     * {@link #FLEET_TRIP_ACKNOWLEDGE}: it answers for one trip, the actor's own, and grants nothing
     * over anybody else's. Deliberately not {@link #FLEET_TRIP_CLOSE}, which closes any trip at a
     * site and belongs to a dispatcher.
     *
     * <p>It exists because the driver is the only person who knows the trip is over and the only one
     * standing at the vehicle to read the odometer off it. Without this the journey stayed open until
     * somebody at a desk closed it from a message, and the end odometer - which feeds the fuel
     * consumption and odometer-jump rules - was second-hand by the time it was recorded.
     *
     * <p>Holding it is not enough on its own. {@code TripApplicationService.close} still requires the
     * actor to be the driver bound to the trip, for the same reason acknowledgement does: every
     * driver holds this permission, so a permission check alone would wave all of them through.
     */
    FLEET_TRIP_CLOSE_OWN,
    /**
     * Start a trip that is assigned to you, and only that one.
     *
     * <p>Scoped the same way as {@link #FLEET_TRIP_CLOSE_OWN}: it answers for one trip, the actor's
     * own, and grants nothing over anybody else's. Deliberately not {@link #FLEET_TRIP_MANAGE}, which
     * starts any trip at a site and belongs to a dispatcher.
     *
     * <p>There is deliberately no equivalent {@code FLEET_TRIP_CANCEL_OWN}: cancelling - as opposed to
     * declining before it starts ({@link #FLEET_TRIP_ACKNOWLEDGE}'s deferral) or finishing it
     * ({@link #FLEET_TRIP_CLOSE_OWN}) - stays a dispatcher's call, made with the fleet office's view of
     * the wider schedule a driver does not have.
     */
    FLEET_TRIP_START_OWN,
    /**
     * Report the vehicle's current position while driving a trip that is assigned to you.
     *
     * <p>Scoped the same way as {@link #FLEET_TRIP_START_OWN} and {@link #FLEET_TRIP_CLOSE_OWN}: it
     * answers for one trip, the actor's own, and grants nothing over anybody else's. Without a
     * connected telematics vendor (S167, Phase 2), this is how a live position reaches the fleet
     * office at all - the driver's own device reports it while {@code IN_PROGRESS}, through the same
     * mobile-friendly web workflow every other field role in this platform already uses.
     */
    FLEET_TRIP_LOCATION_REPORT_OWN,
    FLEET_TRIP_CANCEL,
    FLEET_TRIP_CLOSE,
    FLEET_INSPECTION_RECORD,
    FLEET_WORKFLOW_READ,
    FLEET_WORKFLOW_MANAGE,
    FLEET_WORKFLOW_ASSIGN,
    FLEET_WORKFLOW_ESCALATE,
    FLEET_WORKFLOW_APPROVE,
    FLEET_WORKFLOW_CANCEL,
    FLEET_WORKFLOW_REOPEN,
    FLEET_EVIDENCE_READ,
    FLEET_EVIDENCE_REGISTER,
    FLEET_EVIDENCE_EXPORT_REQUEST,
    FLEET_EVIDENCE_EXPORT_APPROVE,
    FLEET_EVIDENCE_LEGAL_HOLD_OVERRIDE,
    FLEET_AUDIT_READ,
    FLEET_AUDIT_INTEGRITY_CHECK,
    FLEET_INTEGRATION_INGEST,
    FLEET_INTEGRATION_HEALTH_READ,
    FLEET_INTEGRATION_REPLAY,
    FLEET_DASHBOARD_READ,
    FLEET_DASHBOARD_DRILLDOWN,
    FLEET_REPORT_EXPORT,

    // Fuel Management and Driver Logbooks permissions (SRS S168_fuel). Additive only.
    FUEL_TRANSACTION_READ,
    FUEL_TRANSACTION_CAPTURE,
    FUEL_TRANSACTION_IMPORT,
    FUEL_TRANSACTION_VOID,
    FUEL_POLICY_READ,
    /** SRS-SFL-S168fuel-04. Reading the card register is a fleet-office read, not a driver's. */
    FUEL_CARD_READ,
    /** Issue, reassign, suspend, reinstate, cancel. A payment instrument, so manager-only. */
    FUEL_CARD_MANAGE,
    FUEL_POLICY_MANAGE,
    FUEL_LOGBOOK_READ,
    FUEL_LOGBOOK_CREATE,
    FUEL_LOGBOOK_SUBMIT,
    FUEL_LOGBOOK_REVIEW,
    FUEL_LOGBOOK_REOPEN,
    FUEL_RECONCILIATION_RUN,
    FUEL_ANOMALY_READ,
    FUEL_ANOMALY_MANAGE,
    FUEL_ANOMALY_APPROVE,
    FUEL_ANOMALY_ESCALATE,
    FUEL_REPORT_READ,
    FUEL_REPORT_EXPORT,
    FUEL_INTEGRATION_INGEST,
    FUEL_INTEGRATION_REPLAY,

    // Mailroom / Courier and Dispatch Tracking permissions (SRS S171). Additive only.
    // The role -> permission mapping lives in the dispatch feature package
    // (gh.edu.clet.sfl.fleetlogistics.dispatch.domain.policy.DispatchPermissionMatrix).
    DISPATCH_ITEM_READ,
    DISPATCH_ITEM_REGISTER,
    DISPATCH_ITEM_MANAGE,
    DISPATCH_MANIFEST_READ,
    DISPATCH_MANIFEST_CREATE,
    DISPATCH_CUSTODY_RECORD,
    DISPATCH_RECEIPT_CONFIRM,
    DISPATCH_RETURN_RECONCILE,
    DISPATCH_INBOUND_REGISTER,
    DISPATCH_INBOUND_DISTRIBUTE,
    DISPATCH_EXCEPTION_READ,
    DISPATCH_EXCEPTION_MANAGE,
    DISPATCH_EXCEPTION_APPROVE,
    DISPATCH_EXCEPTION_ESCALATE,
    DISPATCH_REPORT_READ,
    DISPATCH_REPORT_EXPORT,
    DISPATCH_INTEGRATION_INGEST,
    DISPATCH_INTEGRATION_REPLAY,

    // Emergency Mass Notification permissions (SRS S174). Additive only. The role -> permission mapping
    // lives in the emergency service (gh.edu.clet.sfl.emergencynotification.domain.policy.EmergencyPermissionMatrix).
    EMERGENCY_TEMPLATE_READ,
    EMERGENCY_TEMPLATE_MANAGE,
    EMERGENCY_SCENARIO_READ,
    EMERGENCY_SCENARIO_MANAGE,
    EMERGENCY_AUDIENCE_READ,
    EMERGENCY_AUDIENCE_MANAGE,
    EMERGENCY_ACTIVATION_READ,
    EMERGENCY_ACTIVATION_CREATE,
    EMERGENCY_ACTIVATION_APPROVE,
    EMERGENCY_ACTIVATION_SEND,
    EMERGENCY_BREAK_GLASS_SEND,
    EMERGENCY_AFTER_ACTION_APPROVE,
    EMERGENCY_ALL_CLEAR_SEND,
    EMERGENCY_EVIDENCE_READ,
    EMERGENCY_EVIDENCE_EXPORT,
    EMERGENCY_REPORT_READ,
    EMERGENCY_REPORT_EXPORT,
    EMERGENCY_INTEGRATION_INGEST,
    EMERGENCY_INTEGRATION_REPLAY,

    // Visitor Management permissions (SRS S160). Additive only. The role -> permission mapping lives
    // in the safety-security service (gh.edu.clet.sfl.safetysecurity.visitor.domain.policy.VisitorPermissionMatrix).
    VISITOR_VISIT_READ,
    VISITOR_VISIT_CREATE,
    VISITOR_VISIT_APPROVE,
    VISITOR_BADGE_ASSIGN,
    VISITOR_CHECKIN,
    VISITOR_CHECKOUT,
    VISITOR_CANCEL,
    VISITOR_ROLLCALL_READ,
    VISITOR_WATCHLIST_OVERRIDE,
    VISITOR_REPORT_READ,

    // HSE Incident / Near-Miss Reporting permissions (SRS S163). Additive only. The role -> permission
    // mapping lives in the safety-security service
    // (gh.edu.clet.sfl.safetysecurity.incident.domain.policy.IncidentPermissionMatrix).
    INCIDENT_REPORT_CREATE,
    INCIDENT_REPORT_READ,
    INCIDENT_TRIAGE,
    INCIDENT_INVESTIGATE,
    INCIDENT_EVIDENCE_MANAGE,
    INCIDENT_CAPA_MANAGE,
    INCIDENT_CAPA_VERIFY,
    INCIDENT_CLOSE,
    INCIDENT_REPORT_EXPORT,

    // Physical Access Control Integration permissions (SRS S160a). Additive only. The role ->
    // permission mapping lives in the safety-security service
    // (gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.policy.AccessControlPermissionMatrix).
    ACCESS_EVENT_READ,
    ACCESS_EXCEPTION_READ,
    ACCESS_EXCEPTION_ACKNOWLEDGE,
    ACCESS_PROVISIONING_READ,
    ACCESS_PROVISIONING_MANAGE,
    ACCESS_OVERRIDE_CREATE,
    ACCESS_OVERRIDE_BREAK_GLASS,
    ACCESS_OVERRIDE_READ,
    ACCESS_ZONE_MANAGE,
    ACCESS_ZONE_READ,
    ACCESS_OCCUPANCY_READ,
    ACCESS_REPORT_READ,

    // CCTV / Video Management System Integration permissions (SRS S161). Additive only. The role ->
    // permission mapping lives in the safety-security service
    // (gh.edu.clet.sfl.safetysecurity.cctv.domain.policy.CctvPermissionMatrix).
    CCTV_CAMERA_READ,
    CCTV_EVIDENCE_REQUEST_CREATE,
    CCTV_EVIDENCE_REQUEST_APPROVE,
    CCTV_EVIDENCE_REQUEST_READ,
    CCTV_EVIDENCE_ITEM_READ,
    CCTV_LIVE_VIEW_START,
    CCTV_LIVE_VIEW_READ,
    CCTV_ANALYTICS_ALERT_READ,
    CCTV_ANALYTICS_ALERT_ACKNOWLEDGE,
    CCTV_DISCLOSURE_CREATE,
    CCTV_DISCLOSURE_APPROVE,
    CCTV_DISCLOSURE_READ,
    CCTV_RETENTION_MANAGE,

    // Intrusion Detection & Alarm Monitoring permissions (SRS S162). Additive only. The role ->
    // permission mapping lives in the safety-security service
    // (gh.edu.clet.sfl.safetysecurity.intrusion.domain.policy.IntrusionPermissionMatrix).
    INTRUSION_ALARM_READ,
    INTRUSION_ALARM_ACKNOWLEDGE,
    INTRUSION_ALARM_RESOLVE,
    INTRUSION_ALARM_LINK_EVIDENCE,
    INTRUSION_ALARM_LINK_INCIDENT,
    INTRUSION_ZONE_READ,
    INTRUSION_ZONE_MANAGE,
    INTRUSION_ZONE_DISARM,
    INTRUSION_PANEL_HEALTH_READ,
    INTRUSION_DISPATCH_RECORD,
    INTRUSION_DISPATCH_READ,
    INTRUSION_REPORT_READ,

    // Fire-Safety & Life-Safety Monitoring permissions (SRS S162a). Additive only. The role ->
    // permission mapping lives in the safety-security service
    // (gh.edu.clet.sfl.safetysecurity.lifesafety.domain.policy.LifeSafetyPermissionMatrix). S162a is
    // observe-only by design - there is no "actuate"/"arm"/"disarm" permission here, unlike S160a/S162.
    LIFESAFETY_EVENT_READ,
    LIFESAFETY_FASTLANE_READ,
    LIFESAFETY_INSPECTION_MANAGE,
    LIFESAFETY_INSPECTION_READ,
    LIFESAFETY_COMPLIANCE_EXCEPTION_READ,
    LIFESAFETY_COMPLIANCE_EXCEPTION_RESOLVE,
    LIFESAFETY_COVERAGE_MANAGE,
    LIFESAFETY_COVERAGE_READ,
    LIFESAFETY_MUSTER_READ,
    LIFESAFETY_MUSTER_CHECKIN,

    // Phase 2 IFIMP permissions (SRS CLET/DTI/CL9/SFL/SRS/2026/002 §3.1). Additive only. All carry the
    // FACILITIES_ prefix because they are granted by the facilities service's own matrix
    // (gh.edu.clet.sfl.facilities.shared.domain.policy.FacilitiesPermissionMatrix) - which is also
    // what gives SFL_ADMIN every one of them without a second list to keep in step.
    //
    // Split by authority, not by screen: where the SRS makes a decision a distinct authorised act -
    // disabling a BMS rule, verifying a held meter reading, approving an occupancy override, an
    // escalated variation approval - it has its own permission and is never implied by another.

    // S156 Building Management System / IoT
    FACILITIES_BMS_READ,
    FACILITIES_BMS_TELEMETRY_INGEST,
    FACILITIES_BMS_RULE_MANAGE,
    FACILITIES_BMS_RULE_OVERRIDE,
    FACILITIES_BMS_DEVICE_MANAGE,
    FACILITIES_BMS_QUARANTINE_RESOLVE,

    // S157 Energy & Sustainability Monitoring
    FACILITIES_ENERGY_READ,
    FACILITIES_ENERGY_READING_INGEST,
    FACILITIES_ENERGY_METER_MANAGE,
    FACILITIES_ENERGY_READING_ENTER,
    FACILITIES_ENERGY_READING_VERIFY,
    FACILITIES_ENERGY_BUDGET_MANAGE,

    // S158 Space Planning & Move Management
    FACILITIES_SPACE_PLAN_READ,
    FACILITIES_SPACE_PLAN_MANAGE,
    FACILITIES_SPACE_PLAN_COMMIT,
    FACILITIES_OCCUPANCY_STANDARD_MANAGE,
    FACILITIES_OCCUPANCY_OVERRIDE_APPROVE,
    FACILITIES_SPACE_CHANGE_REQUEST,
    FACILITIES_SPACE_CHANGE_DECIDE,

    // S169 Cleaning & Janitorial Schedule Management
    FACILITIES_CLEANING_READ,
    FACILITIES_CLEANING_SCHEDULE_MANAGE,
    FACILITIES_CLEANING_REQUEST,
    FACILITIES_CLEANING_TASK_EXECUTE,
    FACILITIES_CLEANING_TASK_SUPERVISE,
    FACILITIES_CLEANING_FEEDBACK_SUBMIT,
    FACILITIES_CLEANING_VENDOR_MANAGE,

    // S173 Event Logistics & Set-Up Workflow
    FACILITIES_EVENT_READ,
    FACILITIES_EVENT_COORDINATE,
    FACILITIES_EVENT_HANDOFF_INGEST,
    FACILITIES_EVENT_RISK_CATEGORY_MANAGE,

    // S176 Construction Project Management
    FACILITIES_PROJECT_READ,
    FACILITIES_PROJECT_MANAGE,
    FACILITIES_PROJECT_APPROVE,
    FACILITIES_PROJECT_HANDOVER,
    FACILITIES_PROJECT_CLOSE,
    FACILITIES_VARIATION_APPROVE,
    FACILITIES_VARIATION_ESCALATED_APPROVE,
    FACILITIES_CONTRACTOR_MANAGE,

    // Phase 2 vendor-integration governance (SRS CORR-07, §5.2)
    FACILITIES_VENDOR_INTEGRATION_READ
}
