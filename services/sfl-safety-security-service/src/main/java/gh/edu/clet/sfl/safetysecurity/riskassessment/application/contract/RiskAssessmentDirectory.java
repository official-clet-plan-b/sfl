package gh.edu.clet.sfl.safetysecurity.riskassessment.application.contract;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency;
import java.time.Instant;
import java.util.UUID;

/**
 * What another SSEMP module may ask S165 - published here, in the provider's own {@code contract}
 * package, the way facilities' Phase 2 modules publish theirs. A consumer reaches it through its own port
 * and a single adapter, never by importing S165's services.
 *
 * <p>The consumer it is written for is S164: SRS-SFL-S164-01 "A request cannot be submitted without a
 * linked, in-date Risk Assessment Library (S165) record", and S165-02 "A lapsed assessment cannot be
 * newly linked to a permit or project". Same deployable, so this is a real in-process call, not a
 * recorded stand-in.
 */
public interface RiskAssessmentDirectory {

    /**
     * Whether the assessment may be newly linked right now, by the shared currency rule. Never throws for
     * "not current" - the verdict says why, and the caller words its own refusal.
     *
     * @param siteCode the site the linking record belongs to; an assessment at another site is
     *        {@link LinkCheck#found() not found}, because an assessment does not cover a different site
     */
    LinkCheck checkLink(UUID assessmentId, String siteCode, Instant at);

    /**
     * @param found false when no assessment with that id exists at that site
     * @param version the current version number, or null when nothing has been published
     * @param verdict the currency rule's answer; {@link RiskAssessmentCurrency.Reason#NONE_LINKED} when not found
     */
    record LinkCheck(UUID assessmentId, boolean found, String reference, Integer version, String activityType,
            String locationCode, RiskAssessmentCurrency.RiskLevel riskLevel, Instant reviewDueAt,
            RiskAssessmentCurrency.Verdict verdict, boolean linkable) {

        /** {@code linkable} is always derived, never supplied - a component so it is also on the wire. */
        public LinkCheck {
            linkable = found && verdict.current();
        }

        public LinkCheck(UUID assessmentId, boolean found, String reference, Integer version, String activityType,
                String locationCode, RiskAssessmentCurrency.RiskLevel riskLevel, Instant reviewDueAt,
                RiskAssessmentCurrency.Verdict verdict) {
            this(assessmentId, found, reference, version, activityType, locationCode, riskLevel, reviewDueAt, verdict,
                    false);
        }
    }
}
