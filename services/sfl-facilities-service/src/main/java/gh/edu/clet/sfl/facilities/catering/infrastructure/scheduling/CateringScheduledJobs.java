package gh.edu.clet.sfl.facilities.catering.infrastructure.scheduling;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.facilities.catering.application.CateringWorkOrderService;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Asks S153 for corrective work for open catering exceptions, and retries what it has not yet answered. Failures are logged, never thrown. */
@Component
@ConditionalOnProperty(name = "sfl.catering.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class CateringScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(CateringScheduledJobs.class);

    private static final SiteScopedPrincipal SYSTEM = new SiteScopedPrincipal("system.catering", "Catering scheduler",
            Set.of(SflRole.SFL_ADMIN), Set.of("*"), true);

    private final CateringWorkOrderService workOrders;

    public CateringScheduledJobs(CateringWorkOrderService workOrders) {
        this.workOrders = workOrders;
    }

    @Scheduled(fixedDelayString = "${sfl.catering.work-orders.interval-ms:300000}", initialDelayString = "${sfl.catering.work-orders.initial-delay-ms:150000}")
    public void workOrders() {
        try {
            int raised = workOrders.sweep(new ActorContext(SYSTEM, UUID.randomUUID().toString()));
            if (raised > 0) {
                log.info("Catering: {} S153 work order(s) raised", raised);
            }
        } catch (RuntimeException failure) {
            log.error("Catering work-order sweep failed; it will be retried on the next run", failure);
        }
    }
}
