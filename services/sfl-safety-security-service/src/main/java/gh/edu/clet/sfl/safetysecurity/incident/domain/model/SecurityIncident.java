package gh.edu.clet.sfl.safetysecurity.incident.domain.model;

import gh.edu.clet.sfl.safetysecurity.incident.domain.exception.IncidentErrorCode;
import gh.edu.clet.sfl.safetysecurity.incident.domain.exception.IncidentException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An HSE incident or near-miss case - SRS §D.9, SRS aggregate {@code SecurityIncident} (§12.3).
 *
 * <h2>Why this is {@code SecurityIncident}, not {@code HseIncident}</h2>
 *
 * §11.6's workflow diagram opens with "Incident from report, CCTV, access, fire, intrusion, or HSE",
 * and the readiness pack repeatedly describes S160a/S161/S162/S162a events "seeding" an S163
 * incident. The SRS aggregate table lists one shared type, {@code SecurityIncident}, not a
 * per-source one. {@link #source} is the discriminator that lets those modules seed into this same
 * table once they exist - see {@link IncidentSource}.
 *
 * <h2>Deferred by design, not by omission</h2>
 *
 * SRS §D.9 step 6 (statutory notification: authority, deadline, acknowledgement tracking) and step 7
 * (a hazard/observation register with its own effectiveness-verification gate) are not built here.
 * Both depend on open questions the business has not answered (Q-163-2: which incident classes are
 * reportable in Ghana, to whom, by when; a hazard register is its own workflow, only "linked to" CAPA
 * per D.9, not CAPA itself). {@link #reportable} and {@link #reportabilityNotes} capture the plain
 * fact so the data exists to backfill the workflow once Q-163-2 is answered; no deadline/escalation
 * engine is built against a regulation nobody has specified yet.
 *
 * @param reference a human-readable case reference, generated at report time.
 * @param anonymous {@code true} for a near-miss reported without an identified reporter - SRS §D.9
 *        step 1, "anonymous or confidential near-miss reporting supported where policy allows"
 *        (Q-163-3 unresolved: this records the fact, it does not itself decide whether policy
 *        permits it - that gate belongs wherever the reporting channel is offered).
 * @param emergencyEscalated sticky once {@code true} - see {@link IncidentStatus}'s Javadoc for why
 *        this is not a status node.
 * @param reportable set manually at triage, not computed - see the class Javadoc on Q-163-2.
 * @param riskAssessmentId the S165 risk assessment the incident happened under, if known - held by value,
 *        never resolved against S165's tables from here. Phase 2 SRS S165-04 flags that assessment for
 *        out-of-cycle review when this is set.
 * @param activityType the activity the incident happened during (e.g. {@code HOT_WORK}), if known - S165-04
 *        flags every published assessment for that activity at the site, and S165-03 counts it as an
 *        activity the platform actually performs.
 */
public record SecurityIncident(
        UUID id,
        String siteCode,
        IncidentSource source,
        String reference,
        boolean anonymous,
        String reporterId,
        String reporterContact,
        String description,
        boolean nearMiss,
        IncidentStatus status,
        Severity severity,
        RiskRating riskRating,
        boolean emergencyEscalated,
        String investigatorId,
        String investigationNotes,
        boolean reportable,
        String reportabilityNotes,
        String closureNotes,
        Instant closedAt,
        UUID riskAssessmentId,
        String activityType,
        RecordMetadata metadata) {

    public SecurityIncident {
        Objects.requireNonNull(id, "id is required");
        siteCode = normalizeSite(siteCode);
        Objects.requireNonNull(source, "source is required");
        require(reference, "reference");
        reference = reference.strip();
        if (anonymous && reporterId != null && !reporterId.isBlank()) {
            throw new IllegalArgumentException("An anonymous report must not carry a reporter identity");
        }
        reporterId = blankToNull(reporterId);
        reporterContact = blankToNull(reporterContact);
        require(description, "description");
        description = description.strip();
        Objects.requireNonNull(status, "status is required");
        investigatorId = blankToNull(investigatorId);
        investigationNotes = blankToNull(investigationNotes);
        reportabilityNotes = blankToNull(reportabilityNotes);
        closureNotes = blankToNull(closureNotes);
        if ((status == IncidentStatus.CLOSED) != (closedAt != null)) {
            throw new IllegalArgumentException("closedAt must be set exactly when the status is CLOSED");
        }
        activityType = blankToNull(activityType);
        if (activityType != null && activityType.length() > MAX_ACTIVITY_TYPE_LENGTH) {
            throw new IllegalArgumentException("activityType is at most " + MAX_ACTIVITY_TYPE_LENGTH + " characters");
        }
        Objects.requireNonNull(metadata, "metadata is required");
    }

    private static final int MAX_ACTIVITY_TYPE_LENGTH = 80;

    /** A newly reported incident or near-miss. Always starts in {@link IncidentStatus#TRIAGE}. */
    public static SecurityIncident report(UUID id, String siteCode, IncidentSource source, String reference,
            boolean anonymous, String reporterId, String reporterContact, String description, boolean nearMiss,
            String actorId, Instant at, SourceChannel channel, String correlationId) {
        return report(id, siteCode, source, reference, anonymous, reporterId, reporterContact, description, nearMiss,
                null, null, actorId, at, channel, correlationId);
    }

    /** As {@link #report}, with the S165 risk context known at report time. Both may be null. */
    public static SecurityIncident report(UUID id, String siteCode, IncidentSource source, String reference,
            boolean anonymous, String reporterId, String reporterContact, String description, boolean nearMiss,
            UUID riskAssessmentId, String activityType, String actorId, Instant at, SourceChannel channel,
            String correlationId) {
        return new SecurityIncident(id, siteCode, source, reference, anonymous, anonymous ? null : reporterId,
                reporterContact, description, nearMiss, IncidentStatus.TRIAGE, null, null, false, null, null,
                false, null, null, null, riskAssessmentId, activityType,
                RecordMetadata.createdBy(actorId, at, channel, correlationId));
    }

    /**
     * Records the risk context an investigator identified after the report - the assessment the work was
     * done under, the activity, or both. Phase 2 SRS S165-04: saving it flags that assessment for review,
     * so it is refused once the case is closed, when there is no longer anything to learn from.
     */
    public SecurityIncident withRiskContext(UUID newRiskAssessmentId, String newActivityType, String actorId,
            Instant at, SourceChannel channel, String correlationId) {
        if (status == IncidentStatus.CLOSED) {
            throw new IncidentException(IncidentErrorCode.INCIDENT_INVALID_STATE_TRANSITION,
                    Map.of("reason", "A closed incident's risk context cannot be changed."));
        }
        return new SecurityIncident(id, siteCode, source, reference, anonymous, reporterId, reporterContact,
                description, nearMiss, status, severity, riskRating, emergencyEscalated, investigatorId,
                investigationNotes, reportable, reportabilityNotes, closureNotes, closedAt, newRiskAssessmentId,
                newActivityType, metadata.modifiedBy(actorId, at, channel, correlationId));
    }

    /**
     * Rates or re-rates severity and risk - SRS §D.9 step 2, "revisable during investigation". Not a
     * status transition: an incident stays in whatever stage it is already in, but a rating of
     * {@link Severity#EMERGENCY} latches {@link #emergencyEscalated} permanently, per hard rule 2.
     * Refused once closed - there is nothing left to re-rate.
     */
    public SecurityIncident triage(Severity severity, RiskRating riskRating, boolean reportable,
            String reportabilityNotes, String actorId, Instant at, SourceChannel channel, String correlationId) {
        if (status == IncidentStatus.CLOSED) {
            throw new IncidentException(IncidentErrorCode.INCIDENT_INVALID_STATE_TRANSITION,
                    Map.of("reason", "A closed incident cannot be re-triaged."));
        }
        Objects.requireNonNull(severity, "severity is required");
        boolean escalated = emergencyEscalated || severity.triggersEmergencyEscalation();
        return copy(status, severity, riskRating, escalated, investigatorId, investigationNotes, reportable,
                reportabilityNotes, closureNotes, closedAt, actorId, at, channel, correlationId);
    }

    /**
     * Opens the investigation - SRS §D.9 step 3. Requires triage to have happened first (a severity
     * on record), the same "cannot skip the step before this one" reasoning {@code
     * VisitorVisit.assignBadge} applies to confirmation before badging.
     */
    public SecurityIncident openInvestigation(String investigatorId, String investigationNotes, String actorId,
            Instant at, SourceChannel channel, String correlationId) {
        if (severity == null) {
            throw new IncidentException(IncidentErrorCode.INCIDENT_TRIAGE_REQUIRED);
        }
        require(investigatorId, "investigatorId");
        IncidentStatus next = status.transitionTo(IncidentStatus.INVESTIGATING);
        return copy(next, severity, riskRating, emergencyEscalated, investigatorId.strip(),
                blankToNull(investigationNotes), reportable, reportabilityNotes, closureNotes, closedAt, actorId,
                at, channel, correlationId);
    }

    /** Updates investigation findings without changing status - only while a case is under investigation. */
    public SecurityIncident updateInvestigationNotes(String investigationNotes, String actorId, Instant at,
            SourceChannel channel, String correlationId) {
        if (status != IncidentStatus.INVESTIGATING) {
            throw new IncidentException(IncidentErrorCode.INCIDENT_INVALID_STATE_TRANSITION,
                    Map.of("reason", "Investigation notes can only be updated while a case is under investigation."));
        }
        return copy(status, severity, riskRating, emergencyEscalated, investigatorId,
                blankToNull(investigationNotes), reportable, reportabilityNotes, closureNotes, closedAt, actorId,
                at, channel, correlationId);
    }

    /**
     * Closes the case - SRS §D.9 hard rule 1: refused while {@code openMandatoryCorrectiveActions} is
     * greater than zero. The count is supplied by the caller (a repository query over this incident's
     * {@link CorrectiveAction} rows), the same shape facilities' {@code WorkOrder.close(int
     * attachedEvidence, ...)} uses for its own evidence-count gate - the aggregate enforces the rule,
     * the count lives where the child rows do.
     */
    public SecurityIncident close(String closureNotes, long openMandatoryCorrectiveActions, String actorId,
            Instant at, SourceChannel channel, String correlationId) {
        if (openMandatoryCorrectiveActions > 0) {
            throw new IncidentException(IncidentErrorCode.INCIDENT_MANDATORY_CAPA_OPEN,
                    Map.of("openMandatoryCorrectiveActions", openMandatoryCorrectiveActions));
        }
        require(closureNotes, "closureNotes");
        IncidentStatus next = status.transitionTo(IncidentStatus.CLOSED);
        return copy(next, severity, riskRating, emergencyEscalated, investigatorId, investigationNotes, reportable,
                reportabilityNotes, closureNotes.strip(), at, actorId, at, channel, correlationId);
    }

    private SecurityIncident copy(IncidentStatus newStatus, Severity newSeverity, RiskRating newRiskRating,
            boolean newEmergencyEscalated, String newInvestigatorId, String newInvestigationNotes,
            boolean newReportable, String newReportabilityNotes, String newClosureNotes, Instant newClosedAt,
            String actorId, Instant at, SourceChannel channel, String correlationId) {
        return new SecurityIncident(id, siteCode, source, reference, anonymous, reporterId, reporterContact,
                description, nearMiss, newStatus, newSeverity, newRiskRating, newEmergencyEscalated,
                newInvestigatorId, newInvestigationNotes, newReportable, newReportabilityNotes, newClosureNotes,
                newClosedAt, riskAssessmentId, activityType, metadata.modifiedBy(actorId, at, channel, correlationId));
    }

    private static String normalizeSite(String siteCode) {
        require(siteCode, "siteCode");
        return siteCode.strip().toUpperCase(Locale.ROOT);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
