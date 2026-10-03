package gh.edu.clet.sfl.facilities.lostfound.infrastructure.scheduling;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.facilities.lostfound.application.LostFoundOpsService;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The retention sweep, on a timer: escalates unclaimed items whose retention has ended and purges claimant data
 * past its period. Failures are logged and swallowed - an uncaught exception from a fixed-delay task cancels the
 * schedule for the life of the process.
 */
@Component
@ConditionalOnProperty(name = "sfl.lostfound.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class LostFoundScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(LostFoundScheduledJobs.class);

    private static final SiteScopedPrincipal SYSTEM = new SiteScopedPrincipal("system.lostfound", "Lost and found scheduler",
            Set.of(SflRole.SFL_ADMIN), Set.of("*"), true);

    private final LostFoundOpsService ops;

    public LostFoundScheduledJobs(LostFoundOpsService ops) {
        this.ops = ops;
    }

    @Scheduled(fixedDelayString = "${sfl.lostfound.sweep.interval-ms:3600000}",
            initialDelayString = "${sfl.lostfound.sweep.initial-delay-ms:180000}")
    public void sweepRetention() {
        try {
            LostFoundOpsService.SweepResult result = ops.sweep(new ActorContext(SYSTEM, UUID.randomUUID().toString()));
            if (result.retentionEscalations() + result.claimantRecordsPurged() > 0) {
                log.info("Lost-and-found sweep: {} retention escalation(s), {} claimant record(s) purged",
                        result.retentionEscalations(), result.claimantRecordsPurged());
            }
        } catch (RuntimeException failure) {
            log.error("Lost-and-found retention sweep failed; it will be retried on the next run", failure);
        }
    }
}
