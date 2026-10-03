package gh.edu.clet.sfl.facilities.shared.domain.error;

/**
 * The machine-readable error codes for S152 and S153, one per *Error State* named in the SRS.
 *
 * <p>The SRS states its error conditions as prose - "Duplicate Identifier - 'An active record with
 * this identifier already exists for this site.'" - which is testable only if the wire carries
 * something more stable than that sentence. Each constant below is that stable thing, and
 * {@link #defaultMessage()} carries the SRS wording verbatim so the two cannot drift apart.
 *
 * <p>Codes are the contract. A message may be reworded for a UI; a code may not change.
 */
public enum FacilitiesErrorCode {

    // SRS-SFL-S152-01 - operational records
    DUPLICATE_IDENTIFIER("An active record with this identifier already exists for this site."),
    MISSING_SITE_SCOPE("Select a valid CLET site before saving this record."),
    UNAUTHORIZED_SCOPE("You are not authorised to access this site or record."),

    // SRS-SFL-S152-02 - workflow
    CLOSURE_EVIDENCE_MISSING("Required evidence must be attached before closure."),
    UNAUTHORIZED_APPROVAL("You do not have permission to approve this workflow transition."),
    INVALID_STATE_TRANSITION("This record cannot move to the requested state from its current state."),

    // SRS-SFL-S152-03 and S153-03 - evidence and audit
    AUDIT_CHAIN_FAILURE("Audit integrity check failed. Escalate to compliance and security."),
    RETENTION_CLASS_MISSING("Select a retention class before saving this evidence."),
    EXPORT_NOT_APPROVED("Evidence export requires approval and a recorded reason."),

    // SRS-SFL-S153-02 - CMMS workflow. SLA_BREACH is not a refusal: it is the state an item is put
    // into by the scheduled evaluator, and it is here so the escalation event and any UI carry the
    // SRS's wording rather than each inventing their own.
    SLA_BREACH("This item has breached its configured SLA and has been escalated."),

    // SRS-SFL-S152-04 - integration
    DUPLICATE_MESSAGE("Duplicate integration message received and safely ignored."),
    IDEMPOTENCY_KEY_CONFLICT("This idempotency key was already used with a different request payload."),

    // SRS-SFL-S152-05 - dashboards
    DATA_STALE("Dashboard data is older than the configured freshness threshold."),
    NO_SCOPE("No site scope is assigned to your user profile."),
    RESTRICTED_DRILLDOWN("You do not have permission to view the underlying record."),

    // SRS-SFL-S159-02 - booking. BOOKING_CONFLICT is the code the exclusion constraint's violation is
    // translated into as well as the one the domain check raises, so a caller cannot tell whether they
    // lost a race or simply asked late - and does not need to.
    BOOKING_CONFLICT("This space or resource is already booked for part of the requested window."),
    SPACE_NOT_BOOKABLE("This space cannot be booked for the requested purpose."),
    RESOURCE_UNAVAILABLE("The requested resource is not available for this window."),

    // Phase 2 IFIMP (SRS 2026/002 §3.1). One block per system; wording is the SRS error state. A module
    // needing another code appends it at the END of its own block only.

    // ---- S156 BMS / IoT
    BMS_TELEMETRY_REJECTED("Telemetry failed authentication or schema validation; logged, never actioned."),
    BMS_LOCATION_UNRESOLVABLE(
            "Telemetry references a device/location not in the S152 register; quarantined pending mapping."),
    BMS_DEVICE_UNREGISTERED(
            "Telemetry from a device ID with no AVAMP record; quarantined and flagged for registration."),
    BMS_RULE_CONFLICT(
            "Overlapping active rules for the same sensor with different thresholds; the stricter rule wins and the conflict is logged for review."),
    BMS_RULE_OVERRIDE_REQUIRED(
            "A rule cannot be silently disabled without an audited override and a named accountable owner."),
    BMS_DATA_STALE("No reading within the expected interval; shown as unknown, not healthy."),
    // ---- end of block

    // ---- S157 Energy & Sustainability
    ENERGY_READING_IMPLAUSIBLE(
            "Manual entry outside the plausibility band; held for supervisor verification, not silently accepted."),
    ENERGY_TARIFF_MISSING(
            "A site/utility with consumption but no configured tariff cannot compute cost variance; flagged rather than assumed zero-cost."),
    ENERGY_PERIOD_INCOMPLETE(
            "Fewer than the configured minimum percentage of expected readings received; KPI is published with a flagged low-completeness indicator."),
    ENERGY_DEVICE_DOUBLE_REGISTERED(
            "A device must not be double-registered - one AVAMP asset identity per physical meter/device, referenced by both modules."),
    ENERGY_SELF_VERIFICATION("A held reading must be verified by someone other than the person who entered it."),
    // ---- end of block

    // ---- S158 Space Planning
    SPACE_SCENARIO_UNCOMMITTED("An operational workflow cannot treat a draft scenario as current state; refused."),
    SPACE_STANDARD_NOT_DEFINED(
            "No occupancy standard is configured for this space type; compliance is not evaluated."),
    SPACE_OVERRIDE_INCOMPLETE("A compliance override must carry a reason and an accountable approver."),
    SPACE_CHANGE_UNLINKED_RESOLUTION(
            "A request cannot be marked resolved without a linked outcome (scenario commit or S176 project reference)."),
    // Integration-unavailable handling (S158-03 "standard integration-unavailable handling"; S158-01/-04
    // hand-off to S176). Not SRS-named error states; added by the S158 build. Unmapped - fall back to 400,
    // and should map to 503 at merge.
    SPACE_CONSTRUCTION_INTAKE_UNAVAILABLE(
            "Construction Project Management (S176) is not available to receive this project; nothing was committed."),
    SPACE_UTILISATION_SOURCE_UNAVAILABLE(
            "Room and Resource Booking (S159) utilisation could not be read; existing snapshots and signals are unchanged."),
    // ---- end of block

    // ---- S169 Cleaning
    CLEANING_BOOKING_UNLINKED(
            "A task claiming to originate from a booking with no resolvable S159 reference; rejected at creation."),
    CLEANING_CHECKLIST_INCOMPLETE("Required checklist item unaddressed or missing evidence; task cannot be closed."),
    CLEANING_RESOURCING_CONFLICT(
            "Requested capacity already committed; returned with the competing commitment named."),
    CLEANING_VENDOR_NOT_FOUND("Vendor Master (S133) has no matching vendor record."),
    // ---- end of block

    // ---- S173 Event Logistics
    EVENT_REFERENCE_UNRESOLVABLE("Hand-off received for an event S078 does not recognise; rejected."),
    EVENT_RISK_ASSESSMENT_NOT_CURRENT(
            "Higher-risk event with no current linked assessment; set-up task cannot be confirmed."),
    EVENT_OWNING_SYSTEM_UNAVAILABLE(
            "A resource type whose owning system is not yet available; recorded as a manual coordination item, clearly marked as such."),
    EVENT_UNRESOLVED_RESOURCE_REQUEST(
            "A set-up task cannot be marked complete with an unresolved requested-status resource request and no escalation record."),
    // ---- end of block

    // ---- S176 Construction
    PROJECT_APPROVAL_MISSING("Attempt to start work without a recorded sign-off; refused."),
    PROJECT_PERMIT_MISSING("A high-risk work type with no linked S164 permit; refused."),
    CONTRACTOR_COMPLIANCE_LAPSED("Insurance or competency expired; access refused/suspended with reason shown."),
    VARIATION_NOT_APPROVED("A variation cannot be applied to the budget until its own approval is recorded."),
    VARIATION_ESCALATION_REQUIRED(
            "Cumulative variations exceed the configured threshold without escalated sign-off; further variations blocked until obtained."),
    PROJECT_HANDOVER_INCOMPLETE("No S152 update recorded; project cannot be marked handed over."),
    PROJECT_DEFECTS_OPEN(
            "The project cannot close until all defects-liability items are closed or explicitly deferred with reason."),
    PROJECT_CONTRACTOR_UNASSIGNED("No responsible contractor is recorded against the project; works cannot start."),
    // ---- end of block

    // ---- Phase 2 shared - vendor inbox and procurement gate
    VENDOR_MESSAGE_REJECTED(
            "Inbound vendor message failed authentication, source allowlist or schema validation; logged and not actioned."),
    RISK_ASSESSMENT_NOT_CURRENT("Linked assessment past its review date; refused pending renewal."),
    RISK_ASSESSMENT_NOT_LINKED("A current risk assessment is required for this work and none is linked; refused."),

    // S170 Hygiene & Pest-Control Audit Tracker
    HYGIENE_SELF_VERIFICATION("A finding or action must be verified by someone other than the person who completed it."),
    HYGIENE_PROVIDER_UNCONFIRMED("The pest-control provider has not confirmed this visit; it cannot be completed."),
    WASTE_CARRIER_UNAPPROVED("The carrier is not approved for this waste; the handover is blocked."),
    WASTE_DESTINATION_UNAPPROVED("The destination is not approved for this waste; the handover is blocked."),
    WASTE_CHAIN_OPEN("The chain of custody is incomplete; the collection stays open and has been escalated."),
    WASTE_SELF_VERIFICATION("Evidence must be accepted by someone other than the person who submitted it."),
    HYGIENE_CLOSURE_BLOCKED("A finding closes only with accepted evidence or an approved exception."),
    // ---- end of block

    // Cross-cutting
    RECORD_NOT_FOUND("The requested record does not exist."),
    INVALID_PARENT_REFERENCE("The parent record referenced by this request does not exist."),
    VALIDATION_FAILED("The request failed validation."),
    VERSION_CONFLICT("This record was changed by someone else. Reload and try again."),
    READINESS_BLOCKED("This space cannot be marked ready while critical blockers remain open."),
    READINESS_LOCKED("This space is locked for examination use and cannot be changed without an override."),
    OPERATING_MODE_TRANSITION_INVALID("The site is already in the requested operating mode.");

    private final String defaultMessage;

    FacilitiesErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    /** The SRS wording for this error state. */
    public String defaultMessage() {
        return defaultMessage;
    }
}
