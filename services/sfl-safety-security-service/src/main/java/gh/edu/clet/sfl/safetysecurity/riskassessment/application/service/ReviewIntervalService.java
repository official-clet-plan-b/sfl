package gh.edu.clet.sfl.safetysecurity.riskassessment.application.service;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.AuditPort;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.RiskAssessmentRepository;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentErrorCode;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentException;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewInterval;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SourceChannel;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S165-02's review cycle, as runtime configuration: one interval and reminder lead per risk level.
 *
 * <p>"Shorter for higher risk" is enforced on every change, across the whole set: a configuration where
 * CRITICAL is reviewed less often than LOW would satisfy each row and break the requirement.
 */
@Service
public class ReviewIntervalService {

    private final RiskAssessmentRepository repository;
    private final RiskAssessmentAccessPolicy access;
    private final AuditPort audit;
    private final Clock clock;

    public ReviewIntervalService(RiskAssessmentRepository repository, RiskAssessmentAccessPolicy access,
            AuditPort audit, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ReviewInterval> list(ActorContext actor) {
        access.requirePermission(actor, SflPermission.RISK_ASSESSMENT_READ, "ReviewInterval", null);
        return all();
    }

    /** The interval a version at this level is published or signed off against. */
    @Transactional(readOnly = true)
    public ReviewInterval intervalFor(RiskLevel level) {
        return repository.findReviewInterval(level).orElseThrow(() -> new IllegalStateException(
                "No review interval is configured for " + level + "; V19 seeds one per level."));
    }

    /** The longest reminder lead - how far ahead the sweep has to look. */
    @Transactional(readOnly = true)
    public int longestReminderLeadDays() {
        return all().stream().mapToInt(ReviewInterval::reminderLeadDays).max().orElse(0);
    }

    public record IntervalChange(RiskLevel riskLevel, int intervalDays, int reminderLeadDays) {
    }

    @Transactional
    public List<ReviewInterval> update(List<IntervalChange> changes, ActorContext actor) {
        access.requirePermission(actor, SflPermission.RISK_ASSESSMENT_CONFIGURE, "ReviewInterval", null);
        Instant now = clock.instant();
        Map<RiskLevel, ReviewInterval> proposed = new EnumMap<>(RiskLevel.class);
        all().forEach(interval -> proposed.put(interval.riskLevel(), interval));
        for (IntervalChange change : changes) {
            ReviewInterval existing = proposed.get(change.riskLevel());
            RecordMetadata metadata = existing == null
                    ? RecordMetadata.createdBy(actor.actorId(), now, SourceChannel.WEB, actor.correlationId())
                    : existing.metadata().modifiedBy(actor.actorId(), now, SourceChannel.WEB, actor.correlationId());
            proposed.put(change.riskLevel(), new ReviewInterval(change.riskLevel(), change.intervalDays(),
                    change.reminderLeadDays(), metadata));
        }
        requireShorterForHigherRisk(proposed);
        for (IntervalChange change : changes) {
            ReviewInterval before = all().stream().filter(i -> i.riskLevel() == change.riskLevel()).findFirst()
                    .orElse(null);
            ReviewInterval saved = repository.saveReviewInterval(proposed.get(change.riskLevel()));
            audit.record(actor, SourceChannel.WEB.name(), null, "RISK_ASSESSMENT_REVIEW_INTERVAL_CHANGED",
                    "ReviewInterval", change.riskLevel().name(), before, saved, null);
        }
        return all();
    }

    private static void requireShorterForHigherRisk(Map<RiskLevel, ReviewInterval> intervals) {
        ReviewInterval previous = null;
        for (RiskLevel level : RiskLevel.values()) {
            ReviewInterval current = intervals.get(level);
            if (current == null) {
                continue;
            }
            if (previous != null && current.intervalDays() > previous.intervalDays()) {
                throw new RiskAssessmentException(RiskAssessmentErrorCode.RISK_ASSESSMENT_VALIDATION_FAILED,
                        Map.of("reason", level + " is reviewed every " + current.intervalDays() + " days, less often than "
                                + previous.riskLevel() + " at " + previous.intervalDays()
                                + ". A higher risk level must be reviewed at least as often (S165-02)."));
            }
            previous = current;
        }
    }

    private List<ReviewInterval> all() {
        return repository.findReviewIntervals().stream()
                .sorted(Comparator.comparing(ReviewInterval::riskLevel)).toList();
    }
}
