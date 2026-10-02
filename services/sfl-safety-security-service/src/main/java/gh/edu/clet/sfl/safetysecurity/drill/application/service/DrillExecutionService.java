package gh.edu.clet.sfl.safetysecurity.drill.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillMusterPort;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillMusterPort.CheckIn;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillNotificationPort;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillRepository;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.BaselinePerson;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.Drill;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillExecution;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillStatus;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.GapFollowUp;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.RollCallGap;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.SourceChannel;
import gh.edu.clet.sfl.safetysecurity.drill.domain.policy.ExecutionReadinessPolicy;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.AuditPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S175-02 on the day: "Drill starts -> current occupancy snapshot taken -> roll-call executed at muster
 * point -> gaps identified in real time -> gap list carried into after-action report."
 *
 * <p>Starting a drill does three things in one transaction: takes the S160/S160a baseline, opens an S162a muster
 * session at the assembly point, and sends the S174 notification in drill mode. If S174 refuses - "Test/Real
 * Ambiguity" - nothing is recorded and the drill stays scheduled.
 */
@Service
public class DrillExecutionService {

    private static final Logger log = LoggerFactory.getLogger(DrillExecutionService.class);

    private final DrillRepository repository;
    private final DrillRecords records;
    private final DrillAccessPolicy access;
    private final DrillMusterPort muster;
    private final DrillNotificationPort notifications;
    private final DrillEvents events;
    private final AuditPort audit;
    private final Clock clock;
    private final Duration baselineFreshness;

    public DrillExecutionService(DrillRepository repository, DrillRecords records, DrillAccessPolicy access,
            DrillMusterPort muster, DrillNotificationPort notifications, DrillEvents events, AuditPort audit,
            Clock clock, @Value("${sfl.drill.baseline-freshness:PT15M}") Duration baselineFreshness) {
        this.repository = repository;
        this.records = records;
        this.access = access;
        this.muster = muster;
        this.notifications = notifications;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
        this.baselineFreshness = baselineFreshness;
    }

    @Transactional
    public DrillDetail start(UUID drillId, Long expectedVersion, ActorContext actor, SourceChannel channel) {
        Drill before = records.require(drillId);
        access.require(actor, SflPermission.DRILL_EXECUTE, before.siteCode(), "Drill", drillId.toString());
        before.metadata().requireVersion(expectedVersion);
        if (before.status() != DrillStatus.SCHEDULED) {
            throw DrillException.invalidState(before.status().name(), DrillStatus.IN_PROGRESS.name());
        }
        ExecutionReadinessPolicy.requireReady(before.plan());
        Instant now = clock.instant();

        DrillMusterPort.Baseline baseline = muster.baseline(before.siteCode(), now);
        UUID session = muster.open(before.siteCode(), before.plan().assemblyZone(), drillId, actor);
        DrillNotificationPort.Sent sent = notifications.send(before.siteCode(), before.reference(),
                before.plan().notificationTemplateId(), before.plan().audienceGroupIds(),
                before.plan().recipientZoneIds(), actor);

        repository.saveBaseline(drillId, before.siteCode(), baseline.persons());
        DrillExecution execution = repository.saveExecution(new DrillExecution(drillId, before.siteCode(), now,
                actor.actorId(), sent.activationId(), sent.activationNumber(), sent.sentAt(), session,
                baseline.takenAt(), baseline.accessDataAsOf(),
                DrillExecution.isStale(baseline.takenAt(), baseline.accessDataAsOf(), baselineFreshness),
                baseline.persons().size(), null, null, null, null, null));
        Drill after = repository.saveDrill(before.start(
                before.metadata().modifiedBy(actor.actorId(), now, channel, actor.correlationId())));
        audit.record(actor, channel.name(), after.siteCode(), "DRILL_STARTED", "Drill", drillId.toString(), before,
                execution, execution.baselineStale() ? "Stale Baseline" : null);
        events.started(after, execution, actor);
        return records.detail(after);
    }

    /** Records someone at the assembly point - through S162a's muster, the same check-in as a real evacuation. */
    @Transactional
    public CheckIn checkIn(UUID drillId, String personRef, ActorContext actor) {
        Drill drill = records.require(drillId);
        access.require(actor, SflPermission.DRILL_EXECUTE, drill.siteCode(), "Drill", drillId.toString());
        if (drill.status() != DrillStatus.IN_PROGRESS) {
            throw DrillException.invalidState(drill.status().name(), "CHECK_IN");
        }
        if (personRef == null || personRef.isBlank()) {
            throw new IllegalArgumentException("personRef is required");
        }
        return muster.checkIn(requireExecution(drillId).musterSessionId(), personRef.strip(), actor);
    }

    @Transactional(readOnly = true)
    public RollCallView rollCall(UUID drillId, ActorContext actor) {
        Drill drill = records.require(drillId);
        access.require(actor, SflPermission.DRILL_READ, drill.siteCode(), "Drill", drillId.toString());
        DrillExecution execution = requireExecution(drillId);
        List<BaselinePerson> baseline = repository.findBaseline(drillId);
        List<CheckIn> checkIns = muster.checkIns(execution.musterSessionId());
        Set<String> checkedIn = checkIns.stream().map(CheckIn::personRef).collect(Collectors.toSet());
        Set<String> expected = baseline.stream().map(BaselinePerson::personRef).collect(Collectors.toSet());
        return new RollCallView(execution, baseline,
                checkIns, baseline.stream().filter(p -> !checkedIn.contains(p.personRef())).toList(),
                checkIns.stream().map(CheckIn::personRef).filter(p -> !expected.contains(p)).distinct().toList(),
                notificationStatus(execution));
    }

    /**
     * Closes the roll-call and marks the drill complete - SRS-SFL-S175-02's acceptance criterion: everyone on site
     * per the baseline who did not check in is on the gap list, written in this same transaction, before the drill
     * is complete. Also closes the S162a session and the S174 drill notification.
     */
    @Transactional
    public DrillDetail closeRollCall(UUID drillId, Long expectedVersion, ActorContext actor, SourceChannel channel) {
        Drill before = records.require(drillId);
        access.require(actor, SflPermission.DRILL_EXECUTE, before.siteCode(), "Drill", drillId.toString());
        before.metadata().requireVersion(expectedVersion);
        if (before.status() != DrillStatus.IN_PROGRESS) {
            throw DrillException.invalidState(before.status().name(), DrillStatus.COMPLETED.name());
        }
        DrillExecution execution = requireExecution(drillId);
        List<CheckIn> checkIns = muster.checkIns(execution.musterSessionId());
        Set<String> checkedIn = checkIns.stream().map(CheckIn::personRef).collect(Collectors.toSet());
        List<RollCallGap> gaps = repository.findBaseline(drillId).stream()
                .filter(p -> !checkedIn.contains(p.personRef()))
                .map(p -> RollCallGap.of(drillId, before.siteCode(), p)).toList();
        repository.saveGaps(gaps);

        Instant now = clock.instant();
        Instant lastCheckIn = checkIns.stream().map(CheckIn::checkedInAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        long baselineCheckedIn = repository.findBaseline(drillId).stream()
                .filter(p -> checkedIn.contains(p.personRef())).count();
        DrillExecution closed = repository.saveExecution(execution.rollCallClosed(now, actor.actorId(),
                (int) baselineCheckedIn, gaps.size(), lastCheckIn));
        muster.close(execution.musterSessionId(), actor);
        if (execution.notificationActivationId() != null) {
            notifications.close(execution.notificationActivationId(), actor);
        }
        Drill after = repository.saveDrill(before.completeRollCall(
                before.metadata().modifiedBy(actor.actorId(), now, channel, actor.correlationId())));
        audit.record(actor, channel.name(), after.siteCode(), "DRILL_ROLL_CALL_CLOSED", "Drill", drillId.toString(),
                execution, closed, gaps.size() + " gap(s)");
        events.completed(after, closed, actor);
        return records.detail(after);
    }

    /** Records how a gap was followed up - required for every gap before the review is submitted. */
    @Transactional
    public RollCallGap followUpGap(UUID drillId, UUID gapId, GapFollowUp outcome, String notes, ActorContext actor,
            SourceChannel channel) {
        Drill drill = records.require(drillId);
        access.require(actor, SflPermission.DRILL_EXECUTE, drill.siteCode(), "Drill", drillId.toString());
        if (drill.status() != DrillStatus.COMPLETED) {
            throw DrillException.invalidState(drill.status().name(), "FOLLOW_UP_GAP");
        }
        RollCallGap before = repository.findGap(gapId).filter(g -> g.drillId().equals(drillId))
                .orElseThrow(() -> DrillException.notFound("RollCallGap", gapId));
        RollCallGap after = repository.saveGap(before.followedUp(outcome, notes, actor.actorId(), clock.instant()));
        audit.record(actor, channel.name(), drill.siteCode(), "DRILL_GAP_FOLLOWED_UP", "Drill", drillId.toString(),
                before, after, null);
        return after;
    }

    private DrillExecution requireExecution(UUID drillId) {
        return repository.findExecution(drillId).orElseThrow(() -> DrillException.notFound("DrillExecution", drillId));
    }

    private DrillNotificationPort.Sent notificationStatus(DrillExecution execution) {
        if (execution.notificationActivationId() == null) {
            return null;
        }
        try {
            return notifications.status(execution.notificationActivationId());
        } catch (RuntimeException e) {
            // The roll-call screen still works without delivery figures.
            log.warn("Could not read S174 status for drill notification {}", execution.notificationActivationId(), e);
            return null;
        }
    }
}
