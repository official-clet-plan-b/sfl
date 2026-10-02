package gh.edu.clet.sfl.facilities.eventlogistics.domain;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency;
import java.time.Instant;

/**
 * S173's local copy of one S165 risk assessment version, fed by SSEMP events - SRS-SFL-S173-03.
 *
 * <p>S165 lives in {@code sfl-safety-security-service}, a different deployable, so S173 cannot read its
 * table and must not call it synchronously. It keeps what the currency check needs, from the four
 * reserved {@code sfl.ssemp.risk-assessment-*.v1} events, which S165 publishes (ADR 0010). An
 * assessment S173 has not heard of is refused, never assumed - the alternative is confirming a major
 * event against an assessment nobody has written.
 *
 * <p>Deliberately holds nothing but currency inputs. Hazards, controls and the assessment text stay in
 * S165.
 */
public record RiskAssessmentProjection(
        String assessmentId,
        int version,
        String siteCode,
        RiskAssessmentCurrency.Status status,
        RiskAssessmentCurrency.RiskLevel riskLevel,
        Instant reviewDueAt,
        String authorId,
        String signedOffBy,
        String lastEventType,
        Instant lastEventAt) {

    /** The shape the shared currency rule takes - the same rule S164-01 will use. */
    public RiskAssessmentCurrency.Snapshot toSnapshot() {
        return new RiskAssessmentCurrency.Snapshot(assessmentId, version, status, riskLevel, reviewDueAt, authorId,
                signedOffBy);
    }

    public RiskAssessmentProjection withStatus(RiskAssessmentCurrency.Status next, String eventType, Instant at) {
        return new RiskAssessmentProjection(assessmentId, version, siteCode, next, riskLevel, reviewDueAt, authorId,
                signedOffBy, eventType, at);
    }

    public RiskAssessmentProjection withReviewDue(Instant due, String eventType, Instant at) {
        return new RiskAssessmentProjection(assessmentId, version, siteCode, status, riskLevel, due, authorId,
                signedOffBy, eventType, at);
    }

    public RiskAssessmentProjection signedOff(String reviewer, Instant renewedReviewDue, String eventType,
            Instant at) {
        return new RiskAssessmentProjection(assessmentId, version, siteCode, status, riskLevel,
                renewedReviewDue == null ? reviewDueAt : renewedReviewDue, authorId, reviewer, eventType, at);
    }
}
