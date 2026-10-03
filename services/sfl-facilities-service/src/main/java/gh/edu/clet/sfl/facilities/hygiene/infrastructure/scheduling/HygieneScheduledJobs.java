package gh.edu.clet.sfl.facilities.hygiene.infrastructure.scheduling;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneControlService;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The S170 overdue sweep, on a timer. Thin by design: build the system actor, call the service, log what
 * moved. {@code sfl.hygiene.missed-after-days} is how long past its due date a control may sit before it
 * is marked missed (default 7); the owner is told on the first sweep after the due date, HSE after that.
 * Failures are logged and swallowed - an uncaught exception from a fixed-delay task cancels the schedule
 * for the life of the process.
 */
@Component
@ConditionalOnProperty(name = "sfl.hygiene.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class HygieneScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(HygieneScheduledJobs.class);

    private static final SiteScopedPrincipal SYSTEM = new SiteScopedPrincipal("system.hygiene", "Hygiene scheduler",
            Set.of(SflRole.SFL_ADMIN), Set.of("*"), true);

    private final HygieneControlService controls;
    private final int missedAfterDays;

    public HygieneScheduledJobs(HygieneControlService controls,
            @Value("${sfl.hygiene.missed-after-days:7}") int missedAfterDays) {
        this.controls = controls;
        this.missedAfterDays = missedAfterDays;
    }

    @Scheduled(fixedDelayString = "${sfl.hygiene.sweep.interval-ms:900000}",
            initialDelayString = "${sfl.hygiene.sweep.initial-delay-ms:120000}")
    public void sweepOverdue() {
        try {
            HygieneControlService.SweepResult result = controls.sweep(missedAfterDays,
                    new ActorContext(SYSTEM, UUID.randomUUID().toString()));
            if (result.ownersNotified() + result.controlsMissed() + result.actionsEscalated() > 0) {
                log.info("Hygiene sweep: {} owner(s) notified, {} control(s) marked missed, {} overdue action(s) escalated",
                        result.ownersNotified(), result.controlsMissed(), result.actionsEscalated());
            }
        } catch (RuntimeException failure) {
            log.error("Hygiene overdue sweep failed; it will be retried on the next run", failure);
        }
    }
}
