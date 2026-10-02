package gh.edu.clet.sfl.safetysecurity.lifesafety.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.safetysecurity.lifesafety.application.contract.DrillMusterControl;
import gh.edu.clet.sfl.safetysecurity.lifesafety.application.port.LifeSafetyRepository;
import gh.edu.clet.sfl.safetysecurity.lifesafety.application.port.OnSitePopulationPort;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.exception.LifeSafetyErrorCode;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.exception.LifeSafetyException;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.MusterCheckIn;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.MusterSession;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.MusterStatus;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.SourceChannel;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S162a's muster, run for a Phase 2 S175 drill - see {@link DrillMusterControl}. The same session and check-in
 * records as a real muster, tagged with the drill so a real fire event never joins it.
 */
@Service
public class DrillMusterService implements DrillMusterControl {

    private final LifeSafetyRepository repository;
    private final OnSitePopulationPort population;
    private final Clock clock;

    public DrillMusterService(LifeSafetyRepository repository, OnSitePopulationPort population, Clock clock) {
        this.repository = repository;
        this.population = population;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Baseline baseline(String siteCode, Instant takenAt) {
        var snapshot = population.snapshot(siteCode, null, takenAt);
        return new Baseline(snapshot.persons().stream()
                .map(p -> new Person(p.personRef(), p.displayName(), p.source().name())).toList(),
                snapshot.accessDataAsOf(), snapshot.takenAt());
    }

    @Override
    @Transactional
    public UUID open(String siteCode, String assemblyZone, UUID drillId, ActorContext actor) {
        var now = clock.instant();
        var session = new MusterSession(UUID.randomUUID(), siteCode, assemblyZone, null,
                MusterStatus.OPEN, now, null,
                RecordMetadata.createdBy(actor.actorId(), now, SourceChannel.SYSTEM, actor.correlationId()), drillId);
        return repository.saveMusterSession(session).id();
    }

    @Override
    @Transactional
    public CheckedIn checkIn(UUID sessionId, String personRef, ActorContext actor) {
        var session = repository.findMusterSession(sessionId)
                .orElseThrow(() -> LifeSafetyException.notFound("MusterSession", sessionId));
        if (session.status() != MusterStatus.OPEN) {
            throw new LifeSafetyException(LifeSafetyErrorCode.LIFESAFETY_INVALID_STATE_TRANSITION,
                    Map.of("musterSessionId", sessionId.toString(), "status", session.status().name()));
        }
        var saved = repository.saveCheckIn(new MusterCheckIn(UUID.randomUUID(), sessionId, personRef.strip(),
                clock.instant(), SourceChannel.WEB));
        return new CheckedIn(saved.personRef(), saved.checkedInAt());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CheckedIn> checkIns(UUID sessionId) {
        return repository.findCheckIns(sessionId).stream()
                .sorted(Comparator.comparing(MusterCheckIn::checkedInAt))
                .map(c -> new CheckedIn(c.personRef(), c.checkedInAt())).toList();
    }

    @Override
    @Transactional
    public void close(UUID sessionId, ActorContext actor) {
        var session = repository.findMusterSession(sessionId)
                .orElseThrow(() -> LifeSafetyException.notFound("MusterSession", sessionId));
        if (session.status() == MusterStatus.CLOSED) {
            return;
        }
        var now = clock.instant();
        repository.saveMusterSession(session.close(now,
                session.metadata().modifiedBy(actor.actorId(), now, SourceChannel.SYSTEM, actor.correlationId())));
    }
}
