package gh.edu.clet.sfl.safetysecurity.drill.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillRepository;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillRepository.CapaCounts;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillErrorCode;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.Drill;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillCorrectiveAction;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillFinding;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillStatus;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.SourceChannel;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.AuditPort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S175-03: corrective actions raised from drill findings, "following the same CAPA tracking pattern as S163
 * incidents, including an owner and due date" - S163's lifecycle, in S175's own table. Raised while the drill is
 * under review; worked, verified or cancelled at any point after, including once the drill is closed.
 */
@Service
public class DrillCorrectiveActionService {

    private final DrillRepository repository;
    private final DrillRecords records;
    private final DrillAccessPolicy access;
    private final AuditPort audit;
    private final Clock clock;

    public DrillCorrectiveActionService(DrillRepository repository, DrillRecords records, DrillAccessPolicy access,
            AuditPort audit, Clock clock) {
        this.repository = repository;
        this.records = records;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public DrillCorrectiveAction open(UUID drillId, UUID findingId, String description, String ownerId,
            LocalDate dueDate, ActorContext actor, SourceChannel channel) {
        Drill drill = records.require(drillId);
        access.require(actor, SflPermission.DRILL_CAPA_MANAGE, drill.siteCode(), "Drill", drillId.toString());
        if (drill.status() != DrillStatus.COMPLETED && drill.status() != DrillStatus.REVIEWED) {
            throw DrillException.invalidState(drill.status().name(), "OPEN_CORRECTIVE_ACTION");
        }
        DrillFinding finding = repository.findFinding(findingId).filter(f -> f.drillId().equals(drillId))
                .orElseThrow(() -> DrillException.notFound("DrillFinding", findingId));
        if (finding.noActionJustification() != null) {
            throw new DrillException(DrillErrorCode.DRILL_VALIDATION_FAILED, Map.of("reason",
                    "This finding is recorded as needing no action. Record a new finding for the action instead."));
        }
        if (dueDate == null) {
            throw new DrillException(DrillErrorCode.DRILL_VALIDATION_FAILED,
                    Map.of("reason", "A corrective action needs a due date."));
        }
        DrillCorrectiveAction saved = repository.saveCorrectiveAction(DrillCorrectiveAction.open(UUID.randomUUID(),
                finding, description, ownerId, dueDate, actor.actorId(), clock.instant()));
        audit.record(actor, channel.name(), drill.siteCode(), "DRILL_CAPA_OPENED", "Drill", drillId.toString(), null,
                saved, null);
        return saved;
    }

    @Transactional
    public DrillCorrectiveAction startProgress(UUID drillId, UUID actionId, ActorContext actor,
            SourceChannel channel) {
        return transition(drillId, actionId, actor, channel, "DRILL_CAPA_STARTED", null,
                (a, at) -> a.startProgress());
    }

    /** Effectiveness verified - the notes say how. */
    @Transactional
    public DrillCorrectiveAction verify(UUID drillId, UUID actionId, String notes, ActorContext actor,
            SourceChannel channel) {
        return transition(drillId, actionId, actor, channel, "DRILL_CAPA_VERIFIED", notes,
                (a, at) -> a.verify(notes, actor.actorId(), at));
    }

    @Transactional
    public DrillCorrectiveAction cancel(UUID drillId, UUID actionId, String reason, ActorContext actor,
            SourceChannel channel) {
        return transition(drillId, actionId, actor, channel, "DRILL_CAPA_CANCELLED", reason,
                (a, at) -> a.cancel(reason, actor.actorId(), at));
    }

    /** The drill half of the HSE dashboard's shared incidents-and-drills corrective-action panel. */
    @Transactional(readOnly = true)
    public CapaCounts counts(String siteCode, ActorContext actor) {
        access.requirePermission(actor, SflPermission.DRILL_READ, "DrillCorrectiveAction");
        List<String> sites = access.sites(actor, siteCode, "DrillCorrectiveAction");
        return repository.capaCounts(sites, records.today());
    }

    private interface Change {
        DrillCorrectiveAction apply(DrillCorrectiveAction action, Instant at);
    }

    private DrillCorrectiveAction transition(UUID drillId, UUID actionId, ActorContext actor, SourceChannel channel,
            String auditAction, String reason, Change change) {
        Drill drill = records.require(drillId);
        access.require(actor, SflPermission.DRILL_CAPA_MANAGE, drill.siteCode(), "Drill", drillId.toString());
        DrillCorrectiveAction before = repository.findCorrectiveAction(actionId)
                .filter(a -> a.drillId().equals(drillId))
                .orElseThrow(() -> DrillException.notFound("DrillCorrectiveAction", actionId));
        DrillCorrectiveAction after = repository.saveCorrectiveAction(change.apply(before, clock.instant()));
        audit.record(actor, channel.name(), drill.siteCode(), auditAction, "Drill", drillId.toString(), before, after,
                reason);
        return after;
    }
}
