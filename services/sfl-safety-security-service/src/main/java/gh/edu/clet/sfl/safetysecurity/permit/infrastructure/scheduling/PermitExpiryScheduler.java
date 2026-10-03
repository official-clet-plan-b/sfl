package gh.edu.clet.sfl.safetysecurity.permit.infrastructure.scheduling;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitMonitoringService;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SRS-SFL-S164-04's sweep: escalates permits nearing or past their validity expiry to the authoriser, once per level. One transaction per
 * permit, failures logged per item, on a platform thread so the row-level-security scope is every site - as the S165 and S175 sweeps are.
 */
@Component
@ConditionalOnProperty(name = "sfl.permit.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class PermitExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PermitExpiryScheduler.class);

    private final PermitMonitoringService monitoring;
    private final Clock clock;

    public PermitExpiryScheduler(PermitMonitoringService monitoring, Clock clock) {
        this.monitoring = monitoring;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${sfl.permit.scheduling.fixed-delay:PT5M}", initialDelayString = "${sfl.permit.scheduling.initial-delay:PT1M}")
    public void sweep() {
        ActorContext system = new ActorContext(new SiteScopedPrincipal("permit-expiry-scheduler", "Permit Expiry Scheduler", Set.of(SflRole.SFL_ADMIN),
                Set.of("*"), true), "permit-expiry-sweep-" + clock.instant().toEpochMilli());
        int nearing = 0;
        int overdue = 0;
        for (UUID id : monitoring.openPermitIds()) {
            try {
                PermitMonitoringService.SweepResult result = monitoring.sweep(id, system);
                nearing += result.nearingExpiry();
                overdue += result.overdue();
            } catch (RuntimeException e) {
                log.warn("Permit expiry sweep could not process permit {}", id, e);
            }
        }
        if (nearing + overdue > 0) {
            log.info("S164 sweep: {} permit(s) nearing expiry, {} overdue escalated", nearing, overdue);
        }
    }
}
