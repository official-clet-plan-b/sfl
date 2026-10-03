package gh.edu.clet.sfl.safetysecurity.permit.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitRepository;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.FlagType;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitFlag;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.SourceChannel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What S164 does when another SSEMP system tells it something that touches a permit: an S163 incident logged during permitted
 * work (S164-05), an S174 emergency declared over a permit's zone (S164-03). Each is a flag for review, raised once per permit and
 * reference. A flag never suspends a permit by itself - the SRS says "flagged for suspension review", and suspension is a person's
 * decision, with a reason.
 *
 * <p>Both run inside the other system's own transaction (the adapters are called in-process), so the flag and the incident or
 * activation commit together.
 */
@Service
public class PermitFlagService {

    private final PermitRepository repository;
    private final PermitRecorder recorder;
    private final PermitEvents events;

    public PermitFlagService(PermitRepository repository, PermitRecorder recorder, PermitEvents events) {
        this.repository = repository;
        this.recorder = recorder;
        this.events = events;
    }

    /** Flags every permit at the site that relies on the assessment and was in force now - "an incident occurs during permitted work". */
    @Transactional
    public int flagForIncident(String siteCode, UUID assessmentId, String incidentReference, ActorContext actor) {
        Instant now = recorder.now();
        return flag(repository.activePermitsForAssessment(siteCode, assessmentId, now), FlagType.INCIDENT, incidentReference,
                "Incident " + incidentReference + " was logged while this permit was in force and relies on the same risk assessment.", actor);
    }

    /** Flags the live permits in the affected zones - every live permit at the site when the emergency names no zone. */
    @Transactional
    public int flagZoneEmergency(String siteCode, List<UUID> zoneIds, String activationReference, ActorContext actor) {
        return flag(repository.livePermitsInZones(siteCode, zoneIds), FlagType.EMERGENCY_ZONE, activationReference,
                "Emergency " + activationReference + " was declared over this permit's zone: review it for suspension.", actor);
    }

    private int flag(List<Permit> permits, FlagType type, String reference, String detail, ActorContext actor) {
        Caller caller = new Caller(actor, SourceChannel.INTEGRATION);
        int raised = 0;
        Instant now = recorder.now();
        for (Permit permit : permits) {
            PermitFlag flag = new PermitFlag(UUID.randomUUID(), permit.id(), permit.siteCode(), type, reference, detail, "OPEN", now, null, null, null);
            if (repository.insertFlag(flag)) {
                recorder.history(permit, permit.status().name(), permit.status().name(), "PERMIT_FLAGGED_" + type, actor.actorId(), reference);
                recorder.audit(caller, "PERMIT_FLAGGED_" + type, permit, null, flag, detail);
                events.flagged(permit, type.name(), reference, now, actor);
                raised++;
            }
        }
        return raised;
    }
}
