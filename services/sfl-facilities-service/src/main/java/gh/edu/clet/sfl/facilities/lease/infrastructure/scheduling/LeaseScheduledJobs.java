package gh.edu.clet.sfl.facilities.lease.infrastructure.scheduling;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.facilities.lease.application.LeaseOpsService;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The lease daily control, on a timer: expiry, due-date alerts, expired documents and notice dates against the
 * current calendar. Every step is idempotent, so running it more often than daily only costs a query. Failures
 * are logged and swallowed - an uncaught exception from a fixed-delay task cancels the schedule for the life of
 * the process.
 */
@Component
@ConditionalOnProperty(name = "sfl.lease.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class LeaseScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(LeaseScheduledJobs.class);

    private static final SiteScopedPrincipal SYSTEM = new SiteScopedPrincipal("system.lease", "Lease scheduler",
            Set.of(SflRole.SFL_ADMIN), Set.of("*"), true);

    private final LeaseOpsService ops;

    public LeaseScheduledJobs(LeaseOpsService ops) {
        this.ops = ops;
    }

    @Scheduled(fixedDelayString = "${sfl.lease.control.interval-ms:3600000}",
            initialDelayString = "${sfl.lease.control.initial-delay-ms:210000}")
    public void dailyControl() {
        try {
            LeaseOpsService.ControlResult result = ops.dailyControl(new ActorContext(SYSTEM, UUID.randomUUID().toString()));
            if (result.expired() + result.alertsRaised() + result.noticeDatesMoved() + result.reviewsRaised() > 0) {
                log.info("Lease control: {} expired, {} alert(s) raised, {} notice date(s) moved, {} review order(s)", result.expired(), result.alertsRaised(),
                        result.noticeDatesMoved(), result.reviewsRaised());
            }
        } catch (RuntimeException failure) {
            log.error("Lease daily control failed; it will be retried on the next run", failure);
        }
    }
}
