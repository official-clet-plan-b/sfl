package gh.edu.clet.sfl.safetysecurity.drill.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillNotificationPort;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillPage;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillRepository;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.Paging;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillErrorCode;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.Drill;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillPlan;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillStatus;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillType;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.SourceChannel;
import gh.edu.clet.sfl.safetysecurity.drill.domain.policy.ExecutionReadinessPolicy;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.AuditPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S175-01 and -05: plan a drill, schedule it once it is ready, postpone or cancel it with a reason - the
 * plan is kept either way, for rescheduling. The calendar is a search over the same register.
 */
@Service
public class DrillPlanningService {

    private final DrillRepository repository;
    private final DrillRecords records;
    private final DrillAccessPolicy access;
    private final DrillNotificationPort notifications;
    private final DrillEvents events;
    private final AuditPort audit;
    private final Clock clock;

    public DrillPlanningService(DrillRepository repository, DrillRecords records, DrillAccessPolicy access,
            DrillNotificationPort notifications, DrillEvents events, AuditPort audit, Clock clock) {
        this.repository = repository;
        this.records = records;
        this.access = access;
        this.notifications = notifications;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    public record SearchDrills(String siteCode, DrillType drillType, DrillStatus status, Instant from, Instant to,
            String search, Paging paging, ActorContext actor) {
    }

    @Transactional(readOnly = true)
    public DrillPage<Drill> search(SearchDrills query) {
        access.requirePermission(query.actor(), SflPermission.DRILL_READ, "Drill");
        List<String> sites = access.sites(query.actor(), query.siteCode(), "Drill");
        return repository.searchDrills(new DrillRepository.DrillQuery(sites, query.drillType(), query.status(),
                query.from(), query.to(), query.search(), query.paging()));
    }

    @Transactional(readOnly = true)
    public DrillDetail get(UUID drillId, ActorContext actor) {
        Drill drill = records.require(drillId);
        access.require(actor, SflPermission.DRILL_READ, drill.siteCode(), "Drill", drillId.toString());
        return records.detail(drill);
    }

    /** The S174 drill templates a plan at this site may name. */
    @Transactional(readOnly = true)
    public List<DrillNotificationPort.Template> drillTemplates(String siteCode, ActorContext actor) {
        access.require(actor, SflPermission.DRILL_PLAN, siteCode, "Drill", null);
        return notifications.drillTemplates(siteCode);
    }

    public record CreateDrill(String siteCode, DrillPlan plan, String idempotencyKey, ActorContext actor,
            SourceChannel channel) {

        /** What a retry must repeat exactly for the key to return the original - everything but who and how. */
        String fingerprint() {
            return sha256(siteCode + "|" + plan);
        }
    }

    /**
     * Plans a drill. With an {@code Idempotency-Key}, a retry carrying the same key and body returns the drill the
     * first call created; the same key with a different body is refused.
     */
    @Transactional
    public DrillDetail create(CreateDrill command) {
        ActorContext actor = command.actor();
        access.require(actor, SflPermission.DRILL_PLAN, command.siteCode(), "Drill", null);
        String key = command.idempotencyKey() == null || command.idempotencyKey().isBlank() ? null
                : command.idempotencyKey().strip();
        if (key != null) {
            Optional<DrillRepository.CreationKey> earlier = repository.findCreationKey(actor.actorId(), key);
            if (earlier.isPresent()) {
                if (!earlier.get().requestHash().equals(command.fingerprint())) {
                    throw new DrillException(DrillErrorCode.DRILL_IDEMPOTENCY_KEY_CONFLICT);
                }
                return records.detail(records.require(earlier.get().drillId()));
            }
        }
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        Drill drill = repository.saveDrill(Drill.plan(id, command.siteCode(),
                "DRL-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT), command.plan(),
                RecordMetadata.createdBy(actor.actorId(), now, command.channel(), actor.correlationId())));
        if (key != null) {
            repository.recordCreationKey(drill.id(), key, command.fingerprint());
        }
        audit.record(actor, command.channel().name(), drill.siteCode(), "DRILL_PLANNED", "Drill", drill.id().toString(),
                null, drill, null);
        return records.detail(drill);
    }

    /** Revises the plan - allowed until the drill starts. A scheduled drill stays scheduled only if still ready. */
    @Transactional
    public DrillDetail revise(UUID drillId, DrillPlan plan, Long expectedVersion, ActorContext actor,
            SourceChannel channel) {
        Drill before = records.require(drillId);
        access.require(actor, SflPermission.DRILL_PLAN, before.siteCode(), "Drill", drillId.toString());
        before.metadata().requireVersion(expectedVersion);
        if (before.status() == DrillStatus.SCHEDULED) {
            requireReady(before.siteCode(), plan);
        }
        Drill after = repository.saveDrill(before.revise(plan, modified(before, actor, channel)));
        audit.record(actor, channel.name(), after.siteCode(), "DRILL_PLAN_REVISED", "Drill", drillId.toString(), before,
                after, null);
        return records.detail(after);
    }

    /**
     * Puts the drill on the calendar - SRS-SFL-S175-01. Refused unless the plan is ready, and with "Test/Real
     * Ambiguity" unless S174 confirms the template is a drill template: refused at setup, not caught after.
     *
     * @param when the date to schedule for; null keeps the plan's
     */
    @Transactional
    public DrillDetail schedule(UUID drillId, Instant when, Long expectedVersion, ActorContext actor,
            SourceChannel channel) {
        Drill before = records.require(drillId);
        access.require(actor, SflPermission.DRILL_PLAN, before.siteCode(), "Drill", drillId.toString());
        before.metadata().requireVersion(expectedVersion);
        Instant date = when == null ? before.plan().scheduledFor() : when;
        requireReady(before.siteCode(), before.plan().rescheduledFor(date));
        Drill after = repository.saveDrill(before.schedule(date, modified(before, actor, channel)));
        audit.record(actor, channel.name(), after.siteCode(), "DRILL_SCHEDULED", "Drill", drillId.toString(), before,
                after, null);
        events.scheduled(after, actor);
        return records.detail(after);
    }

    @Transactional
    public DrillDetail postpone(UUID drillId, String reason, Long expectedVersion, ActorContext actor,
            SourceChannel channel) {
        Drill before = records.require(drillId);
        access.require(actor, SflPermission.DRILL_PLAN, before.siteCode(), "Drill", drillId.toString());
        before.metadata().requireVersion(expectedVersion);
        Drill after = repository.saveDrill(before.postpone(reason, modified(before, actor, channel)));
        audit.record(actor, channel.name(), after.siteCode(), "DRILL_POSTPONED", "Drill", drillId.toString(), before,
                after, after.statusReason());
        return records.detail(after);
    }

    @Transactional
    public DrillDetail cancel(UUID drillId, String reason, Long expectedVersion, ActorContext actor,
            SourceChannel channel) {
        Drill before = records.require(drillId);
        access.require(actor, SflPermission.DRILL_PLAN, before.siteCode(), "Drill", drillId.toString());
        before.metadata().requireVersion(expectedVersion);
        Drill after = repository.saveDrill(before.cancel(reason, modified(before, actor, channel)));
        audit.record(actor, channel.name(), after.siteCode(), "DRILL_CANCELLED", "Drill", drillId.toString(), before,
                after, after.statusReason());
        return records.detail(after);
    }

    private void requireReady(String siteCode, DrillPlan plan) {
        ExecutionReadinessPolicy.requireReady(plan);
        notifications.requireDrillTemplate(siteCode, plan.notificationTemplateId());
    }

    private RecordMetadata modified(Drill drill, ActorContext actor, SourceChannel channel) {
        return drill.metadata().modifiedBy(actor.actorId(), clock.instant(), channel, actor.correlationId());
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
