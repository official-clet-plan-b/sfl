package gh.edu.clet.sfl.safetysecurity.lifesafety.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.lifesafety.application.port.LifeSafetyRepository;
import gh.edu.clet.sfl.safetysecurity.lifesafety.application.port.OnSitePopulationPort;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.exception.LifeSafetyException;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.LifeSafetyEvent;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.MusterCheckIn;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.MusterSession;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.SourceChannel;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S162a-04: evacuation roll-call. Opens against the affected zone on a fire/panic event,
 * captures muster-point check-ins, and highlights who has not yet been accounted for against
 * whatever {@link OnSitePopulationPort} returns (S160 visitors and S160a occupancy since Phase 2 - see
 * that port's javadoc). A supplement to - never a replacement for - the certified system's own alarms.
 */
@Service
public class MusterService {

    private final LifeSafetyRepository repository;
    private final LifeSafetyAccessPolicy access;
    private final OnSitePopulationPort population;
    private final Clock clock;

    public MusterService(LifeSafetyRepository repository, LifeSafetyAccessPolicy access, OnSitePopulationPort population,
            Clock clock) {
        this.repository = repository;
        this.access = access;
        this.population = population;
        this.clock = clock;
    }

    public record RollCallView(MusterSession session, List<MusterCheckIn> checkIns, Set<String> outstanding) {
    }

    @Transactional
    public MusterSession openForEvent(LifeSafetyEvent event, ActorContext actor) {
        var existing = repository.findOpenMusterSession(event.siteCode(), event.zoneCode());
        if (existing.isPresent()) {
            return existing.get();
        }
        var now = clock.instant();
        var session = new MusterSession(UUID.randomUUID(), event.siteCode(), event.zoneCode(), event.id(),
                gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.MusterStatus.OPEN, now, null,
                RecordMetadata.createdBy(actor.actorId(), now, SourceChannel.SYSTEM, actor.correlationId()));
        return repository.saveMusterSession(session);
    }

    @Transactional
    public MusterCheckIn checkIn(UUID musterSessionId, String personRef, ActorContext actor) {
        var session = repository.findMusterSession(musterSessionId)
                .orElseThrow(() -> LifeSafetyException.notFound("MusterSession", musterSessionId));
        access.require(actor, SflPermission.LIFESAFETY_MUSTER_CHECKIN, session.siteCode(), "MusterSession",
                musterSessionId.toString());
        var checkIn = new MusterCheckIn(UUID.randomUUID(), musterSessionId, personRef, clock.instant(),
                SourceChannel.WEB);
        return repository.saveCheckIn(checkIn);
    }

    @Transactional
    public MusterSession close(UUID musterSessionId, ActorContext actor) {
        var session = repository.findMusterSession(musterSessionId)
                .orElseThrow(() -> LifeSafetyException.notFound("MusterSession", musterSessionId));
        access.require(actor, SflPermission.LIFESAFETY_MUSTER_CHECKIN, session.siteCode(), "MusterSession",
                musterSessionId.toString());
        var now = clock.instant();
        var closed = session.close(now,
                session.metadata().modifiedBy(actor.actorId(), now, SourceChannel.WEB, actor.correlationId()));
        return repository.saveMusterSession(closed);
    }

    @Transactional(readOnly = true)
    public RollCallView rollCall(UUID musterSessionId, ActorContext actor) {
        var session = repository.findMusterSession(musterSessionId)
                .orElseThrow(() -> LifeSafetyException.notFound("MusterSession", musterSessionId));
        access.require(actor, SflPermission.LIFESAFETY_MUSTER_READ, session.siteCode(), "MusterSession",
                musterSessionId.toString());
        var checkIns = repository.findCheckIns(musterSessionId);
        Set<String> outstanding = new HashSet<>(population.onSitePersons(session.siteCode(), session.zoneCode()));
        checkIns.forEach(c -> outstanding.remove(c.personRef()));
        return new RollCallView(session, checkIns, Set.copyOf(outstanding));
    }
}
