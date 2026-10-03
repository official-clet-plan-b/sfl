package gh.edu.clet.sfl.safetysecurity.drill.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillRepository;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillErrorCode;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.Drill;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillCorrectiveAction;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillFinding;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillReview;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillStatus;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.ExpectationOutcome;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.ModuleExpectation;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.SourceChannel;
import gh.edu.clet.sfl.safetysecurity.drill.domain.policy.ClosurePolicy;
import gh.edu.clet.sfl.safetysecurity.drill.domain.policy.ReviewCompletenessPolicy;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.AuditPort;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S175-03 and -05 after the day: the after-action review - its narrative, its findings, whether each module
 * did what the plan said - then submitting it, and closing the drill once its overdue corrective actions are closed
 * or deferred with a reason.
 *
 * <p>Timing and participation gaps are measured, not entered: they are on the execution record and the gap list.
 */
@Service
public class DrillReviewService {

    private final DrillRepository repository;
    private final DrillRecords records;
    private final DrillAccessPolicy access;
    private final DrillEvents events;
    private final AuditPort audit;
    private final Clock clock;

    public DrillReviewService(DrillRepository repository, DrillRecords records, DrillAccessPolicy access,
            DrillEvents events, AuditPort audit, Clock clock) {
        this.repository = repository;
        this.records = records;
        this.access = access;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /** Writes or rewrites the review's narrative. Allowed until the review is submitted. */
    @Transactional
    public DrillDetail recordReview(UUID drillId, String summary, String timingNotes, ActorContext actor,
            SourceChannel channel) {
        Drill drill = requireUnderReview(drillId, actor);
        DrillReview before = repository.findReview(drillId).orElse(null);
        DrillReview after = repository.saveReview(new DrillReview(drillId, drill.siteCode(), summary, timingNotes,
                actor.actorId(), clock.instant(), null, null));
        audit.record(actor, channel.name(), drill.siteCode(), "DRILL_REVIEW_RECORDED", "Drill", drillId.toString(),
                before, after, null);
        return records.detail(drill);
    }

    @Transactional
    public DrillFinding addFinding(UUID drillId, String description, ActorContext actor, SourceChannel channel) {
        Drill drill = requireUnderReview(drillId, actor);
        int next = repository.findFindings(drillId).stream().mapToInt(DrillFinding::sequenceNo).max().orElse(0) + 1;
        DrillFinding finding = repository.saveFinding(new DrillFinding(UUID.randomUUID(), drillId, drill.siteCode(),
                next, description, null, actor.actorId(), clock.instant()));
        audit.record(actor, channel.name(), drill.siteCode(), "DRILL_FINDING_RECORDED", "Drill", drillId.toString(),
                null, finding, null);
        return finding;
    }

    /**
     * Records that a finding needs no corrective action, and why - the only alternative to raising one
     * (SRS-SFL-S175-03). Refused once the finding has an action.
     */
    @Transactional
    public DrillFinding justifyNoAction(UUID drillId, UUID findingId, String justification, ActorContext actor,
            SourceChannel channel) {
        Drill drill = requireUnderReview(drillId, actor);
        DrillFinding before = requireFinding(drillId, findingId);
        if (justification == null || justification.isBlank()) {
            throw new DrillException(DrillErrorCode.DRILL_VALIDATION_FAILED,
                    Map.of("reason", "Say why this finding needs no corrective action."));
        }
        boolean actioned = repository.findCorrectiveActions(drillId).stream()
                .anyMatch(a -> a.findingId().equals(findingId));
        if (actioned) {
            throw new DrillException(DrillErrorCode.DRILL_FINDING_ACTIONED, Map.of("findingId", findingId.toString()));
        }
        DrillFinding after = repository.saveFinding(before.justified(justification));
        audit.record(actor, channel.name(), drill.siteCode(), "DRILL_FINDING_NO_ACTION", "Drill", drillId.toString(),
                before, after, after.noActionJustification());
        return after;
    }

    /**
     * One module expectation's outcome, by its position in the plan.
     */
    public record ExpectationJudgement(int index, ExpectationOutcome outcome, String notes) {
    }

    /** Records whether each module did what the plan said - SRS-SFL-S175-05's "defined answer". */
    @Transactional
    public DrillDetail judgeExpectations(UUID drillId, List<ExpectationJudgement> judgements, Long expectedVersion,
            ActorContext actor, SourceChannel channel) {
        Drill before = requireUnderReview(drillId, actor);
        before.metadata().requireVersion(expectedVersion);
        List<ModuleExpectation> judged = new ArrayList<>(before.plan().expectations());
        for (ExpectationJudgement j : judgements == null ? List.<ExpectationJudgement>of() : judgements) {
            if (j.index() < 0 || j.index() >= judged.size()) {
                throw new DrillException(DrillErrorCode.DRILL_VALIDATION_FAILED,
                        Map.of("reason", "No module expectation at position " + j.index() + "."));
            }
            if (j.outcome() == null) {
                throw new DrillException(DrillErrorCode.DRILL_VALIDATION_FAILED,
                        Map.of("reason", "Choose MET, PARTIALLY_MET or NOT_MET for position " + j.index() + "."));
            }
            judged.set(j.index(), judged.get(j.index()).judged(j.outcome(), j.notes()));
        }
        Drill after = repository.saveDrill(before.judge(judged, modified(before, actor, channel)));
        audit.record(actor, channel.name(), after.siteCode(), "DRILL_EXPECTATIONS_JUDGED", "Drill", drillId.toString(),
                before.plan().expectations(), after.plan().expectations(), null);
        return records.detail(after);
    }

    /**
     * Submits the after-action review and marks the drill reviewed - from here it counts toward frequency compliance
     * (S175-04). Refused unless {@link ReviewCompletenessPolicy} is satisfied, naming what is missing.
     */
    @Transactional
    public DrillDetail submit(UUID drillId, Long expectedVersion, ActorContext actor, SourceChannel channel) {
        Drill before = requireUnderReview(drillId, actor);
        before.metadata().requireVersion(expectedVersion);
        DrillReview review = repository.findReview(drillId).orElse(null);
        List<DrillCorrectiveAction> actions = repository.findCorrectiveActions(drillId);
        ReviewCompletenessPolicy.requireSubmittable(review, before.plan(), repository.findGaps(drillId),
                repository.findFindings(drillId), DrillRecords.findingsWithActions(actions));
        Instant now = clock.instant();
        repository.saveReview(review.submitted(actor.actorId(), now));
        Drill after = repository.saveDrill(before.markReviewed(modified(before, actor, channel)));
        audit.record(actor, channel.name(), after.siteCode(), "DRILL_REVIEW_SUBMITTED", "Drill", drillId.toString(),
                before, after, null);
        return records.detail(after);
    }

    /**
     * Closes a reviewed drill - SRS-SFL-S175-03: refused while corrective actions are open and overdue, unless the
     * closer gives an explicit deferral reason, which is kept on the drill. Open actions not yet due do not hold it
     * open; they stay tracked to closure on the HSE dashboard.
     */
    @Transactional
    public DrillDetail close(UUID drillId, String deferralReason, Long expectedVersion, ActorContext actor,
            SourceChannel channel) {
        Drill before = records.require(drillId);
        access.require(actor, SflPermission.DRILL_REVIEW, before.siteCode(), "Drill", drillId.toString());
        before.metadata().requireVersion(expectedVersion);
        if (before.status() != DrillStatus.REVIEWED) {
            throw DrillException.invalidState(before.status().name(), DrillStatus.CLOSED.name());
        }
        List<DrillCorrectiveAction> actions = repository.findCorrectiveActions(drillId);
        String reason = ClosurePolicy.requireClosable(actions, records.today(), deferralReason);
        Drill after = repository.saveDrill(before.close(reason, modified(before, actor, channel)));
        long open = actions.stream().filter(DrillCorrectiveAction::isOpen).count();
        audit.record(actor, channel.name(), after.siteCode(), "DRILL_CLOSED", "Drill", drillId.toString(), before,
                after, reason);
        events.closed(after, open, actor);
        return records.detail(after);
    }

    private Drill requireUnderReview(UUID drillId, ActorContext actor) {
        Drill drill = records.require(drillId);
        access.require(actor, SflPermission.DRILL_REVIEW, drill.siteCode(), "Drill", drillId.toString());
        if (drill.status() != DrillStatus.COMPLETED) {
            throw DrillException.invalidState(drill.status().name(), "REVIEW");
        }
        return drill;
    }

    private DrillFinding requireFinding(UUID drillId, UUID findingId) {
        return repository.findFinding(findingId).filter(f -> f.drillId().equals(drillId))
                .orElseThrow(() -> DrillException.notFound("DrillFinding", findingId));
    }

    private RecordMetadata modified(Drill drill, ActorContext actor, SourceChannel channel) {
        return drill.metadata().modifiedBy(actor.actorId(), clock.instant(), channel, actor.correlationId());
    }
}
