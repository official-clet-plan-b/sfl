package gh.edu.clet.sfl.safetysecurity.emergency.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.AuditPort;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.EmergencyRepository;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.IntegrationEventPublisher;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.event.EmergencyEventType;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.exception.EmergencyErrorCode;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.exception.EmergencyException;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.AudienceGroup;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.ChannelType;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.EmergencyScenario;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.NotificationTemplate;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.policy.DrillSeparationPolicy;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.Priority;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.RecipientZone;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.RecordLifecycle;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.SiteCode;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.SourceChannel;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SRS-SFL-S174-01: create and maintain the emergency notification operational records (site-scoped). */
@Service
public class EmergencyRecordsService {

    private final EmergencyRepository repository;
    private final EmergencyAccessPolicy access;
    private final AuditPort audit;
    private final IntegrationEventPublisher events;
    private final Clock clock;

    public EmergencyRecordsService(EmergencyRepository repository, EmergencyAccessPolicy access, AuditPort audit,
            IntegrationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** @param drill a drill-only template (Phase 2 S175-01) - see {@link DrillSeparationPolicy} */
    public record CreateTemplate(String siteCode, String templateCode, String title, String body,
            List<ChannelType> channels, boolean breakGlassEligible, ActorContext actor, SourceChannel channel,
            boolean drill) {

        public CreateTemplate(String siteCode, String templateCode, String title, String body,
                List<ChannelType> channels, boolean breakGlassEligible, ActorContext actor, SourceChannel channel) {
            this(siteCode, templateCode, title, body, channels, breakGlassEligible, actor, channel, false);
        }
    }

    public record CreateScenario(String siteCode, String scenarioCode, String name, Priority priority,
            UUID defaultTemplateId, boolean breakGlassEligible, ActorContext actor, SourceChannel channel) {}

    public record CreateAudienceGroup(String siteCode, String groupCode, String name, String directoryReference,
            int recipientCount, ActorContext actor, SourceChannel channel) {}

    public record CreateRecipientZone(String siteCode, String zoneCode, String name, String locationReference,
            ActorContext actor, SourceChannel channel) {}

    @Transactional
    public NotificationTemplate createTemplate(CreateTemplate c) {
        SiteCode site = requireSite(c.siteCode());
        access.require(c.actor(), SflPermission.EMERGENCY_TEMPLATE_MANAGE, site.value(), "NotificationTemplate", null);
        String code = number(c.templateCode(), "TPL");
        repository.findTemplateByCode(site.value(), code).filter(NotificationTemplate::active)
                .ifPresent(existing -> { throw duplicate(code); });
        // S175-01 "Test/Real Ambiguity": refused here, at setup, never discovered at send.
        DrillSeparationPolicy.requireConsistentTemplate(c.drill(), c.breakGlassEligible(), c.title(), c.body());
        var template = new NotificationTemplate(UUID.randomUUID(), code, site, c.title(), c.body(), c.channels(),
                c.breakGlassEligible(), RecordLifecycle.ACTIVE, meta(c.actor(), c.channel()), c.drill());
        var saved = repository.saveTemplate(template);
        audit.record(c.actor(), c.channel(), site.value(), "CREATE", "NotificationTemplate", saved.id().toString(),
                null, saved, null);
        events.publish(EmergencyEventType.EMERGENCY_TEMPLATE_CREATED, "NotificationTemplate", saved.id().toString(),
                site.value(), c.actor(), Map.of("templateId", saved.id(), "templateCode", saved.templateCode(),
                        "breakGlassEligible", saved.breakGlassEligible(), "drill", saved.drill()));
        return saved;
    }

    @Transactional
    public EmergencyScenario createScenario(CreateScenario c) {
        SiteCode site = requireSite(c.siteCode());
        access.require(c.actor(), SflPermission.EMERGENCY_SCENARIO_MANAGE, site.value(), "EmergencyScenario", null);
        String code = number(c.scenarioCode(), "SCN");
        repository.findScenarioByCode(site.value(), code).filter(EmergencyScenario::active)
                .ifPresent(existing -> { throw duplicate(code); });
        var scenario = new EmergencyScenario(UUID.randomUUID(), code, site, c.name(), c.priority(),
                c.defaultTemplateId(), c.breakGlassEligible(), RecordLifecycle.ACTIVE, meta(c.actor(), c.channel()));
        var saved = repository.saveScenario(scenario);
        audit.record(c.actor(), c.channel(), site.value(), "CREATE", "EmergencyScenario", saved.id().toString(), null,
                saved, null);
        return saved;
    }

    @Transactional
    public AudienceGroup createAudienceGroup(CreateAudienceGroup c) {
        SiteCode site = requireSite(c.siteCode());
        access.require(c.actor(), SflPermission.EMERGENCY_AUDIENCE_MANAGE, site.value(), "AudienceGroup", null);
        String code = number(c.groupCode(), "AUD");
        repository.findAudienceGroupByCode(site.value(), code).filter(AudienceGroup::active)
                .ifPresent(existing -> { throw duplicate(code); });
        var group = new AudienceGroup(UUID.randomUUID(), code, site, c.name(), c.directoryReference(),
                Math.max(c.recipientCount(), 0), RecordLifecycle.ACTIVE, meta(c.actor(), c.channel()));
        var saved = repository.saveAudienceGroup(group);
        audit.record(c.actor(), c.channel(), site.value(), "CREATE", "AudienceGroup", saved.id().toString(), null,
                saved, null);
        return saved;
    }

    @Transactional
    public RecipientZone createRecipientZone(CreateRecipientZone c) {
        SiteCode site = requireSite(c.siteCode());
        access.require(c.actor(), SflPermission.EMERGENCY_AUDIENCE_MANAGE, site.value(), "RecipientZone", null);
        String code = number(c.zoneCode(), "ZON");
        repository.findRecipientZoneByCode(site.value(), code).filter(RecipientZone::active)
                .ifPresent(existing -> { throw duplicate(code); });
        var zone = new RecipientZone(UUID.randomUUID(), code, site, c.name(), c.locationReference(),
                RecordLifecycle.ACTIVE, meta(c.actor(), c.channel()));
        var saved = repository.saveRecipientZone(zone);
        audit.record(c.actor(), c.channel(), site.value(), "CREATE", "RecipientZone", saved.id().toString(), null,
                saved, null);
        return saved;
    }

    // ---- queries ---------------------------------------------------------------------------------

    public EmergencyRepository.EmergencyPage<NotificationTemplate> templates(String site, String search,
            RecordLifecycle lifecycle, Boolean breakGlassEligible, EmergencyRepository.Paging paging,
            ActorContext actor) {
        access.require(actor, SflPermission.EMERGENCY_TEMPLATE_READ, site, "NotificationTemplate", null);
        return repository.findTemplates(new EmergencyRepository.RecordQuery(List.of(SiteCode.of(site).value()),
                search, lifecycle, breakGlassEligible, paging));
    }

    public NotificationTemplate template(UUID id, ActorContext actor) {
        var t = repository.findTemplate(id).orElseThrow(() -> EmergencyException.notFound("NotificationTemplate", id));
        access.require(actor, SflPermission.EMERGENCY_TEMPLATE_READ, t.siteCode().value(), "NotificationTemplate",
                id.toString());
        return t;
    }

    public EmergencyRepository.EmergencyPage<EmergencyScenario> scenarios(String site, String search,
            RecordLifecycle lifecycle, Boolean breakGlassEligible, EmergencyRepository.Paging paging,
            ActorContext actor) {
        access.require(actor, SflPermission.EMERGENCY_SCENARIO_READ, site, "EmergencyScenario", null);
        return repository.findScenarios(new EmergencyRepository.RecordQuery(List.of(SiteCode.of(site).value()),
                search, lifecycle, breakGlassEligible, paging));
    }

    public EmergencyScenario scenario(UUID id, ActorContext actor) {
        var s = repository.findScenario(id).orElseThrow(() -> EmergencyException.notFound("EmergencyScenario", id));
        access.require(actor, SflPermission.EMERGENCY_SCENARIO_READ, s.siteCode().value(), "EmergencyScenario",
                id.toString());
        return s;
    }

    public EmergencyRepository.EmergencyPage<AudienceGroup> audienceGroups(String site, String search,
            RecordLifecycle lifecycle, EmergencyRepository.Paging paging, ActorContext actor) {
        access.require(actor, SflPermission.EMERGENCY_AUDIENCE_READ, site, "AudienceGroup", null);
        return repository.findAudienceGroups(new EmergencyRepository.RecordQuery(List.of(SiteCode.of(site).value()),
                search, lifecycle, null, paging));
    }

    public AudienceGroup audienceGroup(UUID id, ActorContext actor) {
        var a = repository.findAudienceGroup(id).orElseThrow(() -> EmergencyException.notFound("AudienceGroup", id));
        access.require(actor, SflPermission.EMERGENCY_AUDIENCE_READ, a.siteCode().value(), "AudienceGroup",
                id.toString());
        return a;
    }

    /**
     * Corrects an audience group's size and directory pointer.
     *
     * <p>Closes the sharp edge in gap 6. {@code recipientCount} is what the service fans out to and
     * the denominator every delivery and acknowledgement percentage is read against - and it could
     * not be corrected through any endpoint. A group sized at zero sent to nobody and reported a
     * completely successful broadcast. The name is deliberately **not** editable: activations already
     * closed cite this group, and renaming it would rewrite what they say they were sent to.
     */
    @Transactional
    public AudienceGroup updateAudienceGroup(UUID id, String directoryReference, Integer recipientCount,
            ActorContext actor, SourceChannel channel) {
        var before = repository.findAudienceGroup(id)
                .orElseThrow(() -> EmergencyException.notFound("AudienceGroup", id));
        access.require(actor, SflPermission.EMERGENCY_AUDIENCE_MANAGE, before.siteCode().value(), "AudienceGroup",
                id.toString());
        if (recipientCount != null && recipientCount < 0) {
            throw new IllegalArgumentException("recipientCount cannot be negative");
        }
        var after = new AudienceGroup(before.id(), before.groupCode(), before.siteCode(), before.name(),
                directoryReference == null ? before.directoryReference() : directoryReference,
                recipientCount == null ? before.recipientCount() : recipientCount, before.lifecycle(),
                before.metadata().modifiedBy(actor.actorId(), clock.instant(), channel, actor.correlationId()));
        var saved = repository.saveAudienceGroup(after);
        audit.record(actor, channel, saved.siteCode().value(), "UPDATE", "AudienceGroup", id.toString(), before,
                saved, null);
        return saved;
    }

    /**
     * Retires or reinstates a master-data record.
     *
     * <p>The other half of gap 6. Every one of these records carries {@code withLifecycle} on the
     * domain type and nothing called it, so an obsolete template stayed selectable forever. Archiving
     * is not deletion: activations that cite the record still resolve it, which is the whole reason
     * the lifecycle exists rather than a delete.
     */
    @Transactional
    public Object setLifecycle(String resourceType, UUID id, RecordLifecycle lifecycle, ActorContext actor,
            SourceChannel channel) {
        Instant now = clock.instant();
        return switch (resourceType) {
            case "NotificationTemplate" -> {
                var before = repository.findTemplate(id)
                        .orElseThrow(() -> EmergencyException.notFound("NotificationTemplate", id));
                access.require(actor, SflPermission.EMERGENCY_TEMPLATE_MANAGE, before.siteCode().value(),
                        "NotificationTemplate", id.toString());
                var saved = repository.saveTemplate(before.withLifecycle(lifecycle,
                        before.metadata().modifiedBy(actor.actorId(), now, channel, actor.correlationId())));
                audit.record(actor, channel, saved.siteCode().value(), "UPDATE", "NotificationTemplate",
                        id.toString(), before, saved, null);
                yield saved;
            }
            case "EmergencyScenario" -> {
                var before = repository.findScenario(id)
                        .orElseThrow(() -> EmergencyException.notFound("EmergencyScenario", id));
                access.require(actor, SflPermission.EMERGENCY_SCENARIO_MANAGE, before.siteCode().value(),
                        "EmergencyScenario", id.toString());
                var saved = repository.saveScenario(before.withLifecycle(lifecycle,
                        before.metadata().modifiedBy(actor.actorId(), now, channel, actor.correlationId())));
                audit.record(actor, channel, saved.siteCode().value(), "UPDATE", "EmergencyScenario", id.toString(),
                        before, saved, null);
                yield saved;
            }
            case "AudienceGroup" -> {
                var before = repository.findAudienceGroup(id)
                        .orElseThrow(() -> EmergencyException.notFound("AudienceGroup", id));
                access.require(actor, SflPermission.EMERGENCY_AUDIENCE_MANAGE, before.siteCode().value(),
                        "AudienceGroup", id.toString());
                var saved = repository.saveAudienceGroup(before.withLifecycle(lifecycle,
                        before.metadata().modifiedBy(actor.actorId(), now, channel, actor.correlationId())));
                audit.record(actor, channel, saved.siteCode().value(), "UPDATE", "AudienceGroup", id.toString(),
                        before, saved, null);
                yield saved;
            }
            case "RecipientZone" -> {
                var before = repository.findZone(id)
                        .orElseThrow(() -> EmergencyException.notFound("RecipientZone", id));
                access.require(actor, SflPermission.EMERGENCY_AUDIENCE_MANAGE, before.siteCode().value(),
                        "RecipientZone", id.toString());
                var saved = repository.saveRecipientZone(before.withLifecycle(lifecycle,
                        before.metadata().modifiedBy(actor.actorId(), now, channel, actor.correlationId())));
                audit.record(actor, channel, saved.siteCode().value(), "UPDATE", "RecipientZone", id.toString(),
                        before, saved, null);
                yield saved;
            }
            default -> throw new IllegalArgumentException("Unknown emergency record type: " + resourceType);
        };
    }

    public EmergencyRepository.EmergencyPage<RecipientZone> recipientZones(String site, String search,
            RecordLifecycle lifecycle, EmergencyRepository.Paging paging, ActorContext actor) {
        access.require(actor, SflPermission.EMERGENCY_AUDIENCE_READ, site, "RecipientZone", null);
        return repository.findRecipientZones(new EmergencyRepository.RecordQuery(List.of(SiteCode.of(site).value()),
                search, lifecycle, null, paging));
    }

    public RecipientZone recipientZone(UUID id, ActorContext actor) {
        var z = repository.findZone(id).orElseThrow(() -> EmergencyException.notFound("RecipientZone", id));
        access.require(actor, SflPermission.EMERGENCY_AUDIENCE_READ, z.siteCode().value(), "RecipientZone",
                id.toString());
        return z;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private RecordMetadata meta(ActorContext actor, SourceChannel channel) {
        Instant now = clock.instant();
        return RecordMetadata.createdBy(actor.actorId(), now, channel, actor.correlationId());
    }

    private static SiteCode requireSite(String siteCode) {
        if (siteCode == null || siteCode.isBlank()) {
            throw new EmergencyException(EmergencyErrorCode.EMERGENCY_MISSING_SITE_SCOPE);
        }
        return SiteCode.of(siteCode);
    }

    private static String number(String provided, String prefix) {
        return provided == null || provided.isBlank() ? EmergencyNumbers.next(prefix) : provided.strip();
    }

    private static EmergencyException duplicate(String code) {
        return new EmergencyException(EmergencyErrorCode.EMERGENCY_DUPLICATE_IDENTIFIER, Map.of("identifier", code));
    }
}
