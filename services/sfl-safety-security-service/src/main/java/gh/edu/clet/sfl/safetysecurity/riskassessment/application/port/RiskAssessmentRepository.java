package gh.edu.clet.sfl.safetysecurity.riskassessment.application.port;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentTemplate;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentVersion;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ObservedActivityType;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlag;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlagStatus;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewInterval;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewTrigger;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RiskAssessment;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SignOff;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Everything S165 persists, behind one port - the shape {@code LifeSafetyRepository} uses. */
public interface RiskAssessmentRepository {

    // ---- assessments ------------------------------------------------------------------------------

    RiskAssessment saveAssessment(RiskAssessment assessment);

    Optional<RiskAssessment> findAssessment(UUID id);

    RiskAssessmentPage<RiskAssessment> searchAssessments(AssessmentQuery query, Paging paging);

    /** Records the Idempotency-Key and request fingerprint an assessment was created under. */
    void recordCreationKey(UUID assessmentId, String idempotencyKey, String requestHash);

    /** The assessment a caller already created under this key, and the fingerprint of that request. */
    Optional<CreationKey> findCreationKey(String createdBy, String idempotencyKey);

    record CreationKey(UUID assessmentId, String requestHash) {
    }

    /** Every assessment at a site, drafts included - the dashboard counts them all. */
    List<RiskAssessment> findAssessments(String siteCode);

    /** Every assessment at a site that has a published version - the input to coverage and hazard analytics. */
    List<RiskAssessment> findPublishedAssessments(String siteCode);

    /** Published assessments at a site scoped to this activity type - what an incident's activity links to. */
    List<RiskAssessment> findPublishedByActivityType(String siteCode, String activityType);

    // ---- versions ---------------------------------------------------------------------------------

    AssessmentVersion saveVersion(AssessmentVersion version);

    Optional<AssessmentVersion> findVersion(UUID assessmentId, int versionNumber);

    /** Newest first. */
    List<AssessmentVersion> findVersions(UUID assessmentId);

    /** Every published version at the given sites (or all sites when null), for hazard analytics. */
    List<AssessmentVersion> findPublishedVersions(String siteCode);

    /** Published versions due for review on or before the cutoff, across every site - for the sweep. */
    List<AssessmentVersion> findPublishedVersionsDueBy(Instant cutoff);

    // ---- sign-offs --------------------------------------------------------------------------------

    SignOff saveSignOff(SignOff signOff);

    /** Newest first. */
    List<SignOff> findSignOffs(UUID assessmentId);

    // ---- review flags -----------------------------------------------------------------------------

    ReviewFlag saveFlag(ReviewFlag flag);

    Optional<ReviewFlag> findFlag(UUID id);

    Optional<ReviewFlag> findFlag(UUID assessmentId, ReviewTrigger trigger, String sourceId);

    List<ReviewFlag> findFlags(UUID assessmentId);

    RiskAssessmentPage<ReviewFlag> searchFlags(String siteCode, ReviewFlagStatus status, Paging paging);

    long countFlags(String siteCode, ReviewFlagStatus status);

    /** Deferred flags whose date has passed, across every site - for the sweep. */
    List<ReviewFlag> findExpiredDeferrals(Instant now);

    // ---- observed activity types (S165-03) --------------------------------------------------------

    ObservedActivityType saveObservation(ObservedActivityType observation);

    Optional<ObservedActivityType> findObservation(String siteCode, String activityType, String sourceSystem);

    List<ObservedActivityType> findObservations(String siteCode);

    // ---- templates (global) -----------------------------------------------------------------------

    AssessmentTemplate saveTemplate(AssessmentTemplate template);

    Optional<AssessmentTemplate> findTemplate(UUID id);

    List<AssessmentTemplate> findTemplates(boolean activeOnly);

    // ---- review intervals (global) ----------------------------------------------------------------

    List<ReviewInterval> findReviewIntervals();

    Optional<ReviewInterval> findReviewInterval(RiskLevel riskLevel);

    ReviewInterval saveReviewInterval(ReviewInterval interval);

    /**
     * Register filters. {@code standing} is a coarse SQL pre-filter only; the exact standing is the
     * currency rule's, computed per row by the service.
     */
    record AssessmentQuery(String siteCode, String activityType, RiskLevel riskLevel, StandingFilter standing,
            String text, Instant now) {
    }

    /** What the register can filter on in SQL. */
    enum StandingFilter {
        DRAFT_ONLY,
        PUBLISHED,
        LAPSED,
        AWAITING_INDEPENDENT_SIGN_OFF
    }
}
