package gh.edu.clet.sfl.safetysecurity.permit.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitRepository;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.EscalationLevel;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitEscalation;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.ExpiryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S164-04: a permit approaching its validity expiry without close-out escalates to the authoriser, and one that has passed
 * it appears on the overdue-permit dashboard (which reads the same fact). Each level is raised once per permit, so the sweep is safe
 * to run as often as it likes. It never closes or suspends anything: an overdue permit is a person's to resolve.
 */
@Service
public class PermitMonitoringService {

    private final PermitRepository repository;
    private final PermitRecorder recorder;
    private final PermitEvents events;
    private final Duration warnBefore;

    public PermitMonitoringService(PermitRepository repository, PermitRecorder recorder, PermitEvents events,
            @Value("${sfl.permit.expiry-warning-minutes:60}") long warnMinutes) {
        this.repository = repository;
        this.recorder = recorder;
        this.events = events;
        this.warnBefore = Duration.ofMinutes(Math.max(1, warnMinutes));
    }

    public Duration warnBefore() {
        return warnBefore;
    }

    public record SweepResult(int nearingExpiry, int overdue) {
    }

    public List<UUID> openPermitIds() {
        return repository.openPermits(null).stream().map(Permit::id).toList();
    }

    /** One permit, one transaction: a failure on one never stops the rest. */
    @Transactional
    public SweepResult sweep(UUID permitId, ActorContext system) {
        Permit permit = repository.findPermit(permitId).orElse(null);
        if (permit == null) {
            return new SweepResult(0, 0);
        }
        Caller caller = Caller.system(system);
        Instant now = recorder.now();
        int nearing = 0;
        int overdue = 0;
        for (EscalationLevel level : ExpiryPolicy.owed(permit, now, warnBefore)) {
            if (!repository.insertEscalation(new PermitEscalation(UUID.randomUUID(), permit.id(), permit.siteCode(), level, now))) {
                continue;
            }
            recorder.history(permit, permit.status().name(), permit.status().name(), "PERMIT_ESCALATED_" + level, system.actorId(),
                    "Validity ends " + permit.endsAt());
            recorder.audit(caller, "PERMIT_ESCALATED_" + level, permit, null, level, null);
            if (level == EscalationLevel.NEARING_EXPIRY) {
                events.expiring(permit, now, system);
                nearing++;
            } else {
                events.overdue(permit, now, system);
                overdue++;
            }
        }
        return new SweepResult(nearing, overdue);
    }
}
