package gh.edu.clet.sfl.safetysecurity.riskassessment.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.IntegrationEventPublisher;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.event.RiskAssessmentEventType;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentVersion;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlag;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RiskAssessment;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The one place S165's event payloads are built.
 *
 * <p>The first four payloads are a contract someone else already wrote against:
 * {@code docs/facilities/S173_Event_Contracts.md} fixes their fields, and facilities'
 * {@code RiskAssessmentEventsHandler} parses exactly those. {@code assessmentId} and {@code version} are
 * on every one, {@code version} as a number, instants as ISO-8601 text. Keeping all six builders here
 * means a field renamed for one consumer cannot quietly drift from the contract.
 */
@Component
public class RiskAssessmentEvents {

    private final IntegrationEventPublisher events;

    public RiskAssessmentEvents(IntegrationEventPublisher events) {
        this.events = events;
    }

    /** {@code assessmentId, version, siteCode, riskLevel, reviewDueAt, authorId, signedOffBy} - S173 contract. */
    public void published(RiskAssessment assessment, AssessmentVersion version, ActorContext actor) {
        Map<String, Object> payload = base(version);
        payload.put("siteCode", version.siteCode());
        payload.put("riskLevel", version.riskLevel().name());
        payload.put("reviewDueAt", iso(version.reviewDueAt()));
        payload.put("authorId", version.authorId());
        payload.put("signedOffBy", version.signedOffBy());
        payload.put("reference", assessment.reference());
        payload.put("activityType", assessment.activityType());
        payload.put("locationCode", assessment.locationCode());
        publish(RiskAssessmentEventType.RISK_ASSESSMENT_PUBLISHED, version, actor, payload);
    }

    /** {@code assessmentId, version} - the version being superseded. */
    public void superseded(AssessmentVersion superseded, ActorContext actor) {
        publish(RiskAssessmentEventType.RISK_ASSESSMENT_SUPERSEDED, superseded, actor, base(superseded));
    }

    /** {@code assessmentId, version, reviewDueAt} - the date that passed. */
    public void reviewLapsed(AssessmentVersion version, ActorContext actor) {
        Map<String, Object> payload = base(version);
        payload.put("reviewDueAt", iso(version.reviewDueAt()));
        publish(RiskAssessmentEventType.RISK_ASSESSMENT_REVIEW_LAPSED, version, actor, payload);
    }

    /** {@code assessmentId, version, signedOffBy, reviewDueAt} - the renewed date. */
    public void signedOff(AssessmentVersion version, ActorContext actor) {
        Map<String, Object> payload = base(version);
        payload.put("signedOffBy", version.signedOffBy());
        payload.put("reviewDueAt", iso(version.reviewDueAt()));
        publish(RiskAssessmentEventType.RISK_ASSESSMENT_SIGNED_OFF, version, actor, payload);
    }

    /** New in S165: the review reminder - {@code assessmentId, version, reference, riskLevel, reviewDueAt}. */
    public void reviewDue(RiskAssessment assessment, AssessmentVersion version, ActorContext actor) {
        Map<String, Object> payload = base(version);
        payload.put("reference", assessment.reference());
        payload.put("riskLevel", version.riskLevel().name());
        payload.put("reviewDueAt", iso(version.reviewDueAt()));
        publish(RiskAssessmentEventType.RISK_ASSESSMENT_REVIEW_DUE, version, actor, payload);
    }

    /**
     * New in S165: an out-of-cycle review was raised - {@code assessmentId, version, flagId, trigger,
     * sourceId, sourceReference}. References only: the incident's narrative stays in S163.
     */
    public void reviewFlagged(ReviewFlag flag, ActorContext actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assessmentId", flag.assessmentId().toString());
        payload.put("version", flag.versionNumber());
        payload.put("flagId", flag.id().toString());
        payload.put("trigger", flag.trigger().name());
        payload.put("sourceId", flag.sourceId());
        payload.put("sourceReference", flag.sourceReference());
        events.publish(RiskAssessmentEventType.RISK_ASSESSMENT_REVIEW_FLAGGED.eventType(),
                RiskAssessmentEventType.RISK_ASSESSMENT_REVIEW_FLAGGED.version(), RiskAssessmentEventType.AGGREGATE_TYPE,
                flag.assessmentId().toString(), flag.siteCode(), actor, payload);
    }

    private void publish(RiskAssessmentEventType type, AssessmentVersion version, ActorContext actor,
            Map<String, Object> payload) {
        events.publish(type.eventType(), type.version(), RiskAssessmentEventType.AGGREGATE_TYPE,
                version.assessmentId().toString(), version.siteCode(), actor, payload);
    }

    private static Map<String, Object> base(AssessmentVersion version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assessmentId", version.assessmentId().toString());
        payload.put("version", version.versionNumber());
        return payload;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
