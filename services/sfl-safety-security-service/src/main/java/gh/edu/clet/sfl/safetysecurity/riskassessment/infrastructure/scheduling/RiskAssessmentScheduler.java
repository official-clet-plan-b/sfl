package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.scheduling;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.ReviewFlagService;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.RiskAssessmentReviewService;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.RiskAssessmentReviewService.SweepCandidate;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.RiskAssessmentReviewService.SweepOutcome;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SRS-SFL-S165-02/04 sweeps: review reminders, recorded lapses, and deferred review flags whose date has
 * passed. Mirrors {@code LifeSafetyScheduler}'s shape - the services hold the rules, this decides when and
 * over what - with one difference: each item is its own transaction, so one bad record cannot roll back
 * every reminder in the same tick, and failures are logged per item.
 *
 * <p>Runs on a platform thread (the service's scheduler is built from {@code PlatformThreads}), so under
 * {@code sfl_app} the row-level-security scope is {@code *} rather than nothing. The actor is a service
 * account over every site for the same reason.
 */
@Component
@ConditionalOnProperty(name = "sfl.risk-assessment.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class RiskAssessmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentScheduler.class);

    private final RiskAssessmentReviewService reviews;
    private final ReviewFlagService flags;
    private final Clock clock;

    public RiskAssessmentScheduler(RiskAssessmentReviewService reviews, ReviewFlagService flags, Clock clock) {
        this.reviews = reviews;
        this.flags = flags;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${sfl.risk-assessment.scheduling.fixed-delay:PT1H}",
            initialDelayString = "${sfl.risk-assessment.scheduling.initial-delay:PT1M}")
    public void sweep() {
        ActorContext system = system();
        int lapsed = 0;
        int reminded = 0;
        for (SweepCandidate candidate : reviews.sweepCandidates()) {
            try {
                SweepOutcome outcome = reviews.sweep(candidate, system);
                lapsed += outcome == SweepOutcome.LAPSED ? 1 : 0;
                reminded += outcome == SweepOutcome.REMINDED ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("Risk-assessment review sweep could not process {} v{}", candidate.assessmentId(),
                        candidate.versionNumber(), e);
            }
        }
        int reopened = 0;
        for (UUID flagId : flags.expiredDeferrals()) {
            try {
                flags.reopenExpiredDeferral(flagId, system);
                reopened++;
            } catch (RuntimeException e) {
                log.warn("Review-flag sweep could not reopen {}", flagId, e);
            }
        }
        if (lapsed + reminded + reopened > 0) {
            log.info("S165 sweep: {} lapsed, {} reminded, {} deferred flags reopened", lapsed, reminded, reopened);
        }
    }

    private ActorContext system() {
        return new ActorContext(new SiteScopedPrincipal("risk-assessment-scheduler", "Risk Assessment Scheduler",
                Set.of(SflRole.SFL_ADMIN), Set.of("*"), true), "risk-assessment-sweep-" + clock.instant().toEpochMilli());
    }
}
