package gh.edu.clet.sfl.safetysecurity.drill.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillRepository;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.ComplianceStanding;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillType;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.FrequencyRequirement;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.SourceChannel;
import gh.edu.clet.sfl.safetysecurity.drill.domain.policy.FrequencyCompliancePolicy;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.AuditPort;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S175-04: "Required frequency is configurable per site and drill type; the system flags sites approaching
 * or exceeding their required interval." Only a completed, reviewed drill counts. A lapse raises "Compliance Gap"
 * once per due date, from the daily sweep.
 */
@Service
public class DrillComplianceService {

    private final DrillRepository repository;
    private final DrillAccessPolicy access;
    private final DrillEvents events;
    private final AuditPort audit;
    private final Clock clock;

    public DrillComplianceService(DrillRepository repository, DrillAccessPolicy access, DrillEvents events,
            AuditPort audit, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Where one site stands against one requirement.
     *
     * @param lastCountedDrillAt when the last reviewed drill of this type started; null if there has never been one
     */
    public record ComplianceRow(FrequencyRequirement requirement, Instant lastCountedDrillAt, Instant dueAt,
            ComplianceStanding standing) {
    }

    /** Every requirement across the caller's sites (or one site), most urgent first. */
    @Transactional(readOnly = true)
    public List<ComplianceRow> compliance(String siteCode, ActorContext actor) {
        access.requirePermission(actor, SflPermission.DRILL_COMPLIANCE_READ, "DrillFrequencyRequirement");
        return rows(access.sites(actor, siteCode, "DrillFrequencyRequirement"));
    }

    List<ComplianceRow> rows(List<String> sites) {
        Instant now = clock.instant();
        return repository.findRequirements(sites).stream().map(r -> row(r, now))
                .sorted(Comparator.comparing(ComplianceRow::dueAt)).toList();
    }

    public record SetRequirement(String siteCode, DrillType drillType, int intervalDays, int warningDays,
            Instant effectiveFrom, ActorContext actor, SourceChannel channel) {
    }

    /**
     * Creates or revises the requirement for a site and drill type. A revision keeps its original effective date -
     * changing the interval moves the due date, it does not restart the obligation.
     */
    @Transactional
    public ComplianceRow setRequirement(SetRequirement command) {
        ActorContext actor = command.actor();
        String site = command.siteCode() == null ? null : command.siteCode().strip().toUpperCase(Locale.ROOT);
        access.require(actor, SflPermission.DRILL_CONFIGURE, site, "DrillFrequencyRequirement", null);
        if (command.drillType() == null) {
            throw new IllegalArgumentException("drillType is required");
        }
        Instant now = clock.instant();
        FrequencyRequirement before = repository.findRequirement(site, command.drillType()).orElse(null);
        FrequencyRequirement after = before == null
                ? new FrequencyRequirement(UUID.randomUUID(), site, command.drillType(), command.intervalDays(),
                        command.warningDays(), command.effectiveFrom() == null ? now : command.effectiveFrom(), null,
                        RecordMetadata.createdBy(actor.actorId(), now, command.channel(), actor.correlationId()))
                : before.revise(command.intervalDays(), command.warningDays(),
                        before.metadata().modifiedBy(actor.actorId(), now, command.channel(), actor.correlationId()));
        FrequencyRequirement saved = repository.saveRequirement(after);
        audit.record(actor, command.channel().name(), site, before == null ? "DRILL_REQUIREMENT_SET"
                : "DRILL_REQUIREMENT_REVISED", "DrillFrequencyRequirement", saved.id().toString(), before, saved, null);
        return row(saved, now);
    }

    /** Every requirement's id - what the sweep walks, one transaction each. */
    @Transactional(readOnly = true)
    public List<UUID> sweepCandidates() {
        return repository.findRequirements(null).stream().map(FrequencyRequirement::id).toList();
    }

    /**
     * Raises "Compliance Gap" for a lapsed requirement, once per due date: a site that stays lapsed is not re-flagged
     * daily, and one that completes a drill and lapses again is flagged again.
     *
     * @return true when the event was raised by this call
     */
    @Transactional
    public boolean sweep(UUID requirementId, ActorContext system) {
        FrequencyRequirement requirement = repository.findRequirement(requirementId)
                .orElseThrow(() -> DrillException.notFound("DrillFrequencyRequirement", requirementId));
        Instant now = clock.instant();
        ComplianceRow row = row(requirement, now);
        if (row.standing() != ComplianceStanding.COMPLIANCE_GAP
                || row.dueAt().equals(requirement.gapFlaggedForDueAt())) {
            return false;
        }
        FrequencyRequirement flagged = repository.saveRequirement(requirement.gapFlagged(row.dueAt(),
                requirement.metadata().modifiedBy(system.actorId(), now, SourceChannel.SYSTEM,
                        system.correlationId())));
        audit.record(system, SourceChannel.SYSTEM.name(), requirement.siteCode(), "DRILL_COMPLIANCE_GAP",
                "DrillFrequencyRequirement", requirement.id().toString(), requirement, flagged, "Compliance Gap");
        events.complianceGap(flagged, row.dueAt(), row.lastCountedDrillAt(), system);
        return true;
    }

    private ComplianceRow row(FrequencyRequirement requirement, Instant now) {
        Instant last = repository.lastCountedDrillAt(requirement.siteCode(), requirement.drillType()).orElse(null);
        Instant due = FrequencyCompliancePolicy.dueAt(requirement, last);
        return new ComplianceRow(requirement, last, due, FrequencyCompliancePolicy.standing(requirement, due, now));
    }
}
