package gh.edu.clet.sfl.safetysecurity.emergency.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.emergency.application.contract.EmergencyDrillTrigger;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.CommandIdempotencyPort;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.AuditPort;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.EmergencyRepository;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.EvidencePort;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.IntegrationEventPublisher;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.IntegrationSeamPorts.AccessControlLockdownPort;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.IntegrationSeamPorts.CctvEvidencePort;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.IntegrationSeamPorts.LifeSafetyEventPort;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.NotificationGatewayPort;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.event.EmergencyEventType;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.exception.EmergencyErrorCode;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.exception.EmergencyException;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.ChannelStatus;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.ChannelType;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.NotificationActivation;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.NotificationChannel;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.Priority;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.RetentionClass;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.SiteCode;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.SourceChannel;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.policy.BreakGlassPolicy;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.policy.DrillSeparationPolicy;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.NotificationTemplate;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SRS-SFL-S174-02: the emergency notification activation workflow, including break-glass and all-clear. */
@Service
public class ActivationService implements EmergencyDrillTrigger {

    private static final Logger log = LoggerFactory.getLogger(ActivationService.class);

    private final EmergencyRepository repository;
    private final EmergencyAccessPolicy access;
    private final AuditPort audit;
    private final IntegrationEventPublisher events;
    private final NotificationGatewayPort gateway;
    private final EvidencePort evidence;
    private final LifeSafetyEventPort lifeSafety;
    private final AccessControlLockdownPort lockdown;
    private final CctvEvidencePort cctv;
    private final CommandIdempotencyPort idempotency;
    private final Clock clock;

    public ActivationService(EmergencyRepository repository, EmergencyAccessPolicy access, AuditPort audit,
            IntegrationEventPublisher events, NotificationGatewayPort gateway, EvidencePort evidence,
            LifeSafetyEventPort lifeSafety, AccessControlLockdownPort lockdown, CctvEvidencePort cctv,
            CommandIdempotencyPort idempotency, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.audit = audit;
        this.events = events;
        this.gateway = gateway;
        this.evidence = evidence;
        this.lifeSafety = lifeSafety;
        this.lockdown = lockdown;
        this.cctv = cctv;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public record CreateActivation(String siteCode, UUID scenarioId, UUID templateId, List<UUID> audienceGroupIds,
            List<UUID> recipientZoneIds, List<ChannelType> channels, Priority priority, String incidentReference,
            String idempotencyKey, ActorContext actor, SourceChannel channel) {}

    public record EvidenceMeta(String fileName, String contentType, String storageReference, String sha256Hash,
            RetentionClass retentionClass) {}

    public record ActivationStatusView(NotificationActivation activation, List<NotificationChannel> channels,
            long acknowledgements) {}

    @Transactional
    public NotificationActivation createDraft(CreateActivation c) {
        SiteCode site = requireSite(c.siteCode());
        access.require(c.actor(), SflPermission.EMERGENCY_ACTIVATION_CREATE, site.value(), "NotificationActivation",
                null);
        String fingerprint = idempotency.fingerprint(activationPayload(c));
        var replay = idempotency.findExistingResult("create-emergency-activation", c.idempotencyKey(), fingerprint);
        if (replay.isPresent()) {
            return activation(replay.get(), c.actor(), SflPermission.EMERGENCY_ACTIVATION_READ);
        }
        requireRealTemplate(c.templateId());
        var activation = new NotificationActivation(UUID.randomUUID(), EmergencyNumbers.next("ACT"), site,
                c.scenarioId(), c.templateId(), c.audienceGroupIds(), c.recipientZoneIds(), c.channels(),
                NotificationActivation.Mode.ROUTINE, NotificationActivation.Status.DRAFT,
                c.priority() == null ? Priority.HIGH : c.priority(), c.incidentReference(), null, null, null, null,
                null, null, null, null, null, null, null, 0, false, null, null, meta(c.actor(), c.channel()));
        var saved = repository.saveActivation(activation);
        history(saved, null, "create", c.actor());
        audit.record(c.actor(), c.channel(), site.value(), "CREATE", "NotificationActivation", saved.id().toString(),
                null, saved, null);
        idempotency.recordResult("create-emergency-activation", c.idempotencyKey(), fingerprint, saved.id(),
                site.value(), c.actor().actorId());
        return saved;
    }

    @Transactional
    public NotificationActivation submit(UUID id, ActorContext actor, SourceChannel channel) {
        var before = activation(id, actor, SflPermission.EMERGENCY_ACTIVATION_CREATE);
        var after = transition(before, before.submit(meta(before, actor, channel)), "submit", actor, channel);
        events.publish(EmergencyEventType.EMERGENCY_ACTIVATION_SUBMITTED, "NotificationActivation", id.toString(),
                after.siteCode().value(), actor, Map.of("activationId", id, "status", after.status()));
        return after;
    }

    @Transactional
    public NotificationActivation approve(UUID id, ActorContext actor, SourceChannel channel) {
        var before = requireActivation(id);
        access.requireApproval(actor, SflPermission.EMERGENCY_ACTIVATION_APPROVE, before.siteCode().value(),
                "NotificationActivation", id.toString());
        var after = transition(before, before.approve(actor.actorId(), meta(before, actor, channel)), "approve",
                actor, channel);
        events.publish(EmergencyEventType.EMERGENCY_ACTIVATION_APPROVED, "NotificationActivation", id.toString(),
                after.siteCode().value(), actor, Map.of("activationId", id, "approvedBy", actor.actorId()));
        return after;
    }

    @Transactional
    public NotificationActivation reject(UUID id, String reason, ActorContext actor, SourceChannel channel) {
        var before = requireActivation(id);
        access.requireApproval(actor, SflPermission.EMERGENCY_ACTIVATION_APPROVE, before.siteCode().value(),
                "NotificationActivation", id.toString());
        return transition(before, before.reject(reason, meta(before, actor, channel)), "reject", actor, channel);
    }

    @Transactional
    public NotificationActivation cancel(UUID id, String reason, ActorContext actor, SourceChannel channel) {
        var before = activation(id, actor, SflPermission.EMERGENCY_ACTIVATION_CREATE);
        var cancelled = before.cancel(reason, meta(before, actor, channel));
        var after = transition(before, cancelled, "cancel", actor, channel, cancelled.closureReason());
        events.publish(EmergencyEventType.EMERGENCY_ACTIVATION_CANCELLED, "NotificationActivation", id.toString(),
                after.siteCode().value(), actor, Map.of("activationId", id, "reason", after.closureReason()));
        return after;
    }

    @Transactional
    public NotificationActivation activate(UUID id, ActorContext actor, SourceChannel channel) {
        var before = activation(id, actor, SflPermission.EMERGENCY_ACTIVATION_SEND);
        long start = clock.millis();
        var activated = before.activate(meta(before, actor, channel));
        activated = fanOut(activated, false, actor, channel);
        activated = activated.withFastLaneMillis(clock.millis() - start,
                activated.metadata().modifiedBy(actor.actorId(), clock.instant(), channel, actor.correlationId()));
        var after = transition(before, activated, "activate", actor, channel);
        events.publish(EmergencyEventType.EMERGENCY_NOTIFICATION_ACTIVATED, "NotificationActivation", id.toString(),
                after.siteCode().value(), actor, Map.of("activationId", id, "channels", channelNames(after),
                        "mode", after.mode()));
        return after;
    }

    @Transactional
    public NotificationActivation degradedFallback(UUID id, String fallbackPath, ActorContext actor,
            SourceChannel channel) {
        var before = activation(id, actor, SflPermission.EMERGENCY_ACTIVATION_SEND);
        var degraded = before.withDegradedFallback(fallbackPath, meta(before, actor, channel));
        degraded = fanOut(degraded, true, actor, channel);
        var after = transition(before, degraded, "degraded-fallback", actor, channel, degraded.fallbackPath());
        events.publish(EmergencyEventType.EMERGENCY_DEGRADED_FALLBACK_RECORDED, "NotificationActivation",
                id.toString(), after.siteCode().value(), actor, Map.of("activationId", id, "fallbackPath",
                        after.fallbackPath(), "channels", channelNames(after)));
        return after;
    }

    /** SRS/§0E break-glass: authorised role + break-glass-eligible template fires WITHOUT pre-approval. */
    @Transactional
    public NotificationActivation breakGlass(CreateActivation c) {
        SiteCode site = requireSite(c.siteCode());
        access.require(c.actor(), SflPermission.EMERGENCY_BREAK_GLASS_SEND, site.value(), "NotificationActivation",
                null);
        String fingerprint = idempotency.fingerprint(activationPayload(c));
        var replay = idempotency.findExistingResult("break-glass-emergency-activation", c.idempotencyKey(),
                fingerprint);
        if (replay.isPresent()) {
            return activation(replay.get(), c.actor(), SflPermission.EMERGENCY_ACTIVATION_READ);
        }
        boolean templateEligible = c.templateId() != null && repository.findTemplate(c.templateId())
                .map(gh.edu.clet.sfl.safetysecurity.emergency.domain.model.NotificationTemplate::breakGlassEligible)
                .orElse(false);
        boolean scenarioEligible = c.scenarioId() != null && repository.findScenario(c.scenarioId())
                .map(gh.edu.clet.sfl.safetysecurity.emergency.domain.model.EmergencyScenario::breakGlassEligible)
                .orElse(false);
        BreakGlassPolicy.requireEligible(templateEligible, scenarioEligible);
        requireRealTemplate(c.templateId());
        long start = clock.millis();
        var activation = new NotificationActivation(UUID.randomUUID(), EmergencyNumbers.next("BG"), site,
                c.scenarioId(), c.templateId(), c.audienceGroupIds(), c.recipientZoneIds(), c.channels(),
                NotificationActivation.Mode.BREAK_GLASS, NotificationActivation.Status.DRAFT,
                c.priority() == null ? Priority.CRITICAL : c.priority(), c.incidentReference(), null, null, null, null,
                null, null, null, null, null, null, null, 0, false, null, null, meta(c.actor(), c.channel()));
        var saved = repository.saveActivation(activation);
        var live = saved.breakGlassActivate(meta(saved, c.actor(), c.channel()));
        live = fanOut(live, false, c.actor(), c.channel());
        live = live.withFastLaneMillis(clock.millis() - start,
                live.metadata().modifiedBy(c.actor().actorId(), clock.instant(), c.channel(), c.actor().correlationId()));
        var after = transition(saved, live, "break-glass", c.actor(), c.channel());
        events.publish(EmergencyEventType.EMERGENCY_BREAK_GLASS_ACTIVATED, "NotificationActivation",
                after.id().toString(), site.value(), c.actor(), Map.of("activationId", after.id(),
                        "channels", channelNames(after), "priority", after.priority()));
        events.publish(EmergencyEventType.EMERGENCY_NOTIFICATION_ACTIVATED, "NotificationActivation",
                after.id().toString(), site.value(), c.actor(), Map.of("activationId", after.id(), "mode",
                        after.mode()));
        idempotency.recordResult("break-glass-emergency-activation", c.idempotencyKey(), fingerprint, after.id(),
                site.value(), c.actor().actorId());
        return after;
    }

    private Map<String, Object> activationPayload(CreateActivation c) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("siteCode", c.siteCode());
        payload.put("scenarioId", c.scenarioId());
        payload.put("templateId", c.templateId());
        payload.put("audienceGroupIds", c.audienceGroupIds());
        payload.put("recipientZoneIds", c.recipientZoneIds());
        payload.put("channels", c.channels());
        payload.put("priority", c.priority());
        payload.put("incidentReference", c.incidentReference());
        return payload;
    }

    @Transactional
    public NotificationActivation afterActionApprove(UUID id, String justification, ActorContext actor,
            SourceChannel channel) {
        var before = requireActivation(id);
        access.requireApproval(actor, SflPermission.EMERGENCY_AFTER_ACTION_APPROVE, before.siteCode().value(),
                "NotificationActivation", id.toString());
        var after = transition(before, before.afterActionApprove(actor.actorId(), justification,
                meta(before, actor, channel)), "after-action-approve", actor, channel);
        events.publish(EmergencyEventType.EMERGENCY_AFTER_ACTION_APPROVED, "NotificationActivation", id.toString(),
                after.siteCode().value(), actor, Map.of("activationId", id, "approvedBy", actor.actorId()));
        return after;
    }

    @Transactional
    public NotificationActivation allClear(UUID id, ActorContext actor, SourceChannel channel) {
        var before = activation(id, actor, SflPermission.EMERGENCY_ALL_CLEAR_SEND);
        var after = transition(before, before.allClear(meta(before, actor, channel)), "all-clear", actor, channel);
        events.publish(EmergencyEventType.EMERGENCY_ALL_CLEAR_SENT, "NotificationActivation", id.toString(),
                after.siteCode().value(), actor, Map.of("activationId", id));
        return after;
    }

    @Transactional
    public NotificationActivation close(UUID id, String reason, EvidenceMeta evidenceMeta, ActorContext actor,
            SourceChannel channel) {
        var before = activation(id, actor, SflPermission.EMERGENCY_ACTIVATION_SEND);
        if (evidenceMeta == null || evidenceMeta.storageReference() == null
                || evidenceMeta.storageReference().isBlank()) {
            throw new EmergencyException(EmergencyErrorCode.EMERGENCY_CLOSURE_EVIDENCE_MISSING,
                    Map.of("activationId", id.toString()));
        }
        if (evidenceMeta.retentionClass() == null) {
            throw new EmergencyException(EmergencyErrorCode.EMERGENCY_RETENTION_CLASS_MISSING,
                    Map.of("activationId", id.toString()));
        }
        UUID evidenceId = evidence.register(new EvidencePort.EvidenceRegistration(before.siteCode().value(), id,
                "CLOSURE_SUMMARY", evidenceMeta.fileName(), evidenceMeta.contentType(),
                evidenceMeta.storageReference(), evidenceMeta.sha256Hash(), evidenceMeta.retentionClass(), actor,
                channel));
        var channels = repository.findChannels(id);
        String deliverySummary = deliverySummary(channels);
        String ackSummary = "acknowledged=" + repository.countAcknowledgements(id) + "; " + deliverySummary;
        var after = transition(before, before.close(reason, deliverySummary, ackSummary, evidenceId,
                meta(before, actor, channel)), "close", actor, channel);
        events.publish(EmergencyEventType.EMERGENCY_ACTIVATION_CLOSED, "NotificationActivation", id.toString(),
                        after.siteCode().value(), actor, Map.of("activationId", id, "closureReason", reason));
        return after;
    }

    @Transactional
    public NotificationActivation reopen(UUID id, String reason, ActorContext actor, SourceChannel channel) {
        var before = activation(id, actor, SflPermission.EMERGENCY_ACTIVATION_SEND);
        var reopened = before.reopen(reason, meta(before, actor, channel));
        var after = transition(before, reopened, "reopen", actor, channel, reopened.closureReason());
        events.publish(EmergencyEventType.EMERGENCY_ACTIVATION_REOPENED, "NotificationActivation", id.toString(),
                after.siteCode().value(), actor, Map.of("activationId", id, "reason", after.closureReason()));
        return after;
    }

    /** Scheduled/authorised SLA escalation of an active activation with outstanding acknowledgements. */
    @Transactional
    public void escalateForSla(UUID id, ActorContext actor, SourceChannel channel) {
        var before = repository.findActivation(id).orElse(null);
        if (before == null || !before.active() || before.status() == NotificationActivation.Status.ESCALATED) {
            return;
        }
        var after = transition(before, before.escalate("Acknowledgement SLA breached", meta(before, actor, channel)),
                "escalate", actor, channel);
        events.publish(EmergencyEventType.EMERGENCY_NOTIFICATION_STATUS_RECEIVED, "NotificationActivation",
                id.toString(), after.siteCode().value(), actor, Map.of("activationId", id, "status", after.status(),
                        "escalationLevel", after.escalationLevel()));
    }

    // ---- queries ---------------------------------------------------------------------------------

    public NotificationActivation get(UUID id, ActorContext actor) {
        return activation(id, actor, SflPermission.EMERGENCY_ACTIVATION_READ);
    }

    public EmergencyRepository.EmergencyPage<NotificationActivation> list(String site,
            NotificationActivation.Status status, NotificationActivation.Mode mode, Priority priority,
            String incidentReference, Boolean openOnly, Boolean liveOnly, Boolean afterActionOutstanding,
            UUID scenarioId, UUID templateId, Instant from, Instant to, EmergencyRepository.Paging paging,
            ActorContext actor) {
        access.require(actor, SflPermission.EMERGENCY_ACTIVATION_READ, site, "NotificationActivation", null);
        return repository.findActivations(new EmergencyRepository.ActivationQuery(List.of(SiteCode.of(site).value()),
                status, mode, priority, incidentReference, openOnly, liveOnly, afterActionOutstanding, scenarioId,
                templateId, from, to, paging));
    }

    /**
     * The activation's recorded transitions.
     *
     * <p>Closes gap 4. {@code saveActivationHistory} has been called on every transition since day
     * one and nothing ever read it back, so the detail screen reconstructed a timeline from whatever
     * fields the record still carried - which silently omitted any transition that left none.
     */
    public List<EmergencyRepository.ActivationHistoryEntry> history(UUID id, ActorContext actor) {
        activation(id, actor, SflPermission.EMERGENCY_ACTIVATION_READ);
        return repository.findActivationHistory(id);
    }

    /** Per-recipient delivery receipts and acknowledgements. Closes gap 8. */
    public DeliveryDetail deliveryDetail(UUID id, ActorContext actor) {
        activation(id, actor, SflPermission.EMERGENCY_ACTIVATION_READ);
        return new DeliveryDetail(repository.findReceipts(id), repository.findAcknowledgements(id));
    }

    /**
     * Every provider fact recorded against one activation.
     *
     * <p>{@code failedRecipientCount} on the dashboard used to be a number with nothing behind it:
     * the receipts carry the recipient, provider, provider message id and the provider's own reason,
     * and none of it was readable.
     */
    public record DeliveryDetail(List<gh.edu.clet.sfl.safetysecurity.emergency.domain.model.DeliveryReceipt> receipts,
            List<gh.edu.clet.sfl.safetysecurity.emergency.domain.model.Acknowledgement> acknowledgements) {}

    public ActivationStatusView status(UUID id, ActorContext actor) {
        var activation = activation(id, actor, SflPermission.EMERGENCY_ACTIVATION_READ);
        return new ActivationStatusView(activation, repository.findChannels(id),
                repository.countAcknowledgements(id));
    }

    // ---- internals -------------------------------------------------------------------------------

    private NotificationActivation fanOut(NotificationActivation activation, boolean degraded, ActorContext actor,
            SourceChannel channel) {
        int target = targetCount(activation);
        for (ChannelType type : activation.channels()) {
            var result = gateway.send(activation.id(), type, activation.siteCode().value(), target, degraded,
                    activation.mode() == NotificationActivation.Mode.DRILL, actor);
            var existing = repository.findChannel(activation.id(), type);
            var record = existing.orElseGet(() -> new NotificationChannel(UUID.randomUUID(), activation.id(),
                    activation.siteCode(), type, ChannelStatus.PENDING, target, 0, 0, 0, 0,
                    meta(activation, actor, channel)));
            var sent = new NotificationChannel(record.id(), activation.id(), activation.siteCode(), type,
                    ChannelStatus.SENDING, target, result.accepted(), 0, 0, 0,
                    record.metadata().modifiedBy(actor.actorId(), clock.instant(), channel, actor.correlationId()));
            repository.saveChannel(sent);
        }
        // Observe-only / seam-only context (no certified life-safety actuation - Arch §0E). The
        // result was previously discarded, which made "observe-only" observe nothing at all - an
        // investigator reading this activation's audit trail had no way to see what the seam
        // reported, or that it was consulted. Logged rather than persisted onto the activation:
        // Phase-1's recorded seam always returns empty (no live feed yet - see
        // RecordedIntegrationSeams), so there is nothing yet worth a schema change to store.
        Optional<String> lifeSafetyEvent = lifeSafety.latestLifeSafetyEvent(activation.siteCode().value());
        log.info("Life-safety context for activation {} at site {}: {}", activation.id(),
                activation.siteCode().value(), lifeSafetyEvent.orElse("none reported"));
        for (UUID zone : activation.recipientZoneIds()) {
            lockdown.recordLockdownContext(activation.id(), zone.toString());
            cctv.preserveContext(activation.id(), zone.toString());
        }
        return activation;
    }

    private int targetCount(NotificationActivation activation) {
        // One bounded IN query rather than one SELECT per audience group - this runs on the
        // emergency-activation fan-out, the life-safety broadcast path where added latency matters most.
        int total = 0;
        for (var group : repository.findAudienceGroupsByIds(activation.audienceGroupIds())) {
            total += group.recipientCount();
        }
        return total;
    }

    private static String deliverySummary(List<NotificationChannel> channels) {
        int sent = 0;
        int delivered = 0;
        int failed = 0;
        for (NotificationChannel c : channels) {
            sent += c.sentCount();
            delivered += c.deliveredCount();
            failed += c.failedCount();
        }
        return "channels=" + channels.size() + "; sent=" + sent + "; delivered=" + delivered + "; failed=" + failed;
    }

    private static List<String> channelNames(NotificationActivation a) {
        return a.channels().stream().map(Enum::name).toList();
    }

    private NotificationActivation transition(NotificationActivation before, NotificationActivation after,
            String action, ActorContext actor, SourceChannel channel) {
        return transition(before, after, action, actor, channel, null);
    }

    private NotificationActivation transition(NotificationActivation before, NotificationActivation after,
            String action, ActorContext actor, SourceChannel channel, String comment) {
        var saved = repository.saveActivation(after);
        history(saved, before.status().name(), action, actor, comment);
        audit.record(actor, channel, saved.siteCode().value(), "STATE_TRANSITION", "NotificationActivation",
                saved.id().toString(), before, saved, null);
        return saved;
    }

    private void history(NotificationActivation a, String fromStatus, String action, ActorContext actor) {
        history(a, fromStatus, action, actor, null);
    }

    private void history(NotificationActivation a, String fromStatus, String action, ActorContext actor,
            String comment) {
        repository.saveActivationHistory(a.id(), fromStatus, a.status().name(), action, actor.actorId(), comment,
                clock.instant(), actor.correlationId());
    }

    private NotificationActivation activation(UUID id, ActorContext actor, SflPermission permission) {
        var a = requireActivation(id);
        access.require(actor, permission, a.siteCode().value(), "NotificationActivation", id.toString());
        return a;
    }

    /** S175-01: a real alert never goes out on a drill template. */
    private void requireRealTemplate(UUID templateId) {
        if (templateId != null) {
            repository.findTemplate(templateId).ifPresent(DrillSeparationPolicy::requireRealTemplate);
        }
    }

    // ---- drill mode (Phase 2 S175-01) ------------------------------------------------------------

    @Override
    @Transactional
    public DrillNotification trigger(DrillNotificationRequest request, ActorContext actor) {
        SiteCode site = requireSite(request.siteCode());
        NotificationTemplate template = request.templateId() == null ? null
                : repository.findTemplate(request.templateId()).orElseThrow(
                        () -> EmergencyException.notFound("NotificationTemplate", request.templateId()));
        if (template == null) {
            throw new EmergencyException(EmergencyErrorCode.EMERGENCY_TEST_REAL_AMBIGUITY,
                    Map.of("reason", "A drill is only sent with a drill template."));
        }
        DrillSeparationPolicy.requireDrillTemplate(template);
        if (!template.siteCode().equals(site) || !template.active()) {
            throw new EmergencyException(EmergencyErrorCode.EMERGENCY_VALIDATION_FAILED,
                    Map.of("reason", "The drill template must be active and belong to " + site.value() + "."));
        }
        List<ChannelType> channels = request.channels() == null || request.channels().isEmpty() ? template.channels()
                : request.channels().stream().map(ChannelType::valueOf).toList();
        SourceChannel channel = SourceChannel.API;
        var draft = new NotificationActivation(UUID.randomUUID(), EmergencyNumbers.next("DRL"), site, null,
                template.id(), request.audienceGroupIds(), request.recipientZoneIds(), channels,
                NotificationActivation.Mode.DRILL, NotificationActivation.Status.DRAFT, Priority.LOW,
                request.drillReference(), null, null, null, null, null, null, null, null, null, null, null, 0, false,
                null, null, meta(actor, channel));
        var saved = repository.saveActivation(draft);
        long start = clock.millis();
        var live = fanOut(saved.drillActivate(meta(saved, actor, channel)), false, actor, channel);
        live = live.withFastLaneMillis(clock.millis() - start,
                live.metadata().modifiedBy(actor.actorId(), clock.instant(), channel, actor.correlationId()));
        var after = transition(saved, live, "drill-send", actor, channel, request.drillReference());
        events.publish(EmergencyEventType.EMERGENCY_DRILL_NOTIFICATION_SENT, "NotificationActivation",
                after.id().toString(), site.value(), actor, Map.of("activationId", after.id(), "drillReference",
                        String.valueOf(request.drillReference()), "channels", channelNames(after)));
        return drillNotification(after);
    }

    @Override
    @Transactional(readOnly = true)
    public DrillNotification status(UUID activationId) {
        return drillNotification(requireDrillActivation(activationId));
    }

    @Override
    @Transactional
    public DrillNotification close(UUID activationId, ActorContext actor) {
        var before = requireDrillActivation(activationId);
        if (before.status() == NotificationActivation.Status.CLOSED) {
            return drillNotification(before);
        }
        var channels = repository.findChannels(activationId);
        var closed = before.closeDrill(deliverySummary(channels),
                "acknowledged=" + repository.countAcknowledgements(activationId), meta(before, actor, SourceChannel.API));
        var after = transition(before, closed, "drill-close", actor, SourceChannel.API);
        events.publish(EmergencyEventType.EMERGENCY_DRILL_NOTIFICATION_CLOSED, "NotificationActivation",
                activationId.toString(), after.siteCode().value(), actor, Map.of("activationId", activationId,
                        "drillReference", String.valueOf(after.incidentReference())));
        return drillNotification(after);
    }

    private NotificationActivation requireDrillActivation(UUID id) {
        var activation = requireActivation(id);
        if (activation.mode() != NotificationActivation.Mode.DRILL) {
            throw new EmergencyException(EmergencyErrorCode.EMERGENCY_TEST_REAL_AMBIGUITY,
                    Map.of("reason", "Activation " + activation.activationNumber() + " is not a drill."));
        }
        return activation;
    }

    private DrillNotification drillNotification(NotificationActivation activation) {
        int target = 0;
        int sent = 0;
        int delivered = 0;
        int failed = 0;
        for (NotificationChannel c : repository.findChannels(activation.id())) {
            target += c.targetCount();
            sent += c.sentCount();
            delivered += c.deliveredCount();
            failed += c.failedCount();
        }
        // The activation's creation is the send: trigger creates and fans out in one transaction.
        return new DrillNotification(activation.id(), activation.activationNumber(), activation.status().name(),
                activation.metadata().createdAt(), target, sent, delivered, failed,
                repository.countAcknowledgements(activation.id()));
    }

    private NotificationActivation requireActivation(UUID id) {
        return repository.findActivation(id).orElseThrow(() -> EmergencyException.notFound("NotificationActivation", id));
    }

    private RecordMetadata meta(ActorContext actor, SourceChannel channel) {
        Instant now = clock.instant();
        return RecordMetadata.createdBy(actor.actorId(), now, channel, actor.correlationId());
    }

    private RecordMetadata meta(NotificationActivation a, ActorContext actor, SourceChannel channel) {
        return a.metadata().modifiedBy(actor.actorId(), clock.instant(), channel, actor.correlationId());
    }

    private static SiteCode requireSite(String siteCode) {
        if (siteCode == null || siteCode.isBlank()) {
            throw new EmergencyException(EmergencyErrorCode.EMERGENCY_MISSING_SITE_SCOPE);
        }
        return SiteCode.of(siteCode);
    }
}
