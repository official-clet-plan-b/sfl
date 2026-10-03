package gh.edu.clet.sfl.facilities.waste.infrastructure.scheduling;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.facilities.waste.application.WasteCollectionService;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The missed-collection sweep, on a timer. {@code sfl.waste.missed-after-days} (default 1) is how long past its
 * scheduled date a collection may sit unrecorded before it is marked missed and raised to the facilities
 * owner. Failures are logged and swallowed - an uncaught exception from a fixed-delay task cancels the
 * schedule for the life of the process.
 */
@Component
@ConditionalOnProperty(name = "sfl.waste.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class WasteScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(WasteScheduledJobs.class);

    private static final SiteScopedPrincipal SYSTEM = new SiteScopedPrincipal("system.waste", "Waste scheduler",
            Set.of(SflRole.SFL_ADMIN), Set.of("*"), true);

    private final WasteCollectionService collections;
    private final int missedAfterDays;

    public WasteScheduledJobs(WasteCollectionService collections,
            @Value("${sfl.waste.missed-after-days:1}") int missedAfterDays) {
        this.collections = collections;
        this.missedAfterDays = missedAfterDays;
    }

    @Scheduled(fixedDelayString = "${sfl.waste.sweep.interval-ms:900000}",
            initialDelayString = "${sfl.waste.sweep.initial-delay-ms:150000}")
    public void sweepMissed() {
        try {
            int marked = collections.sweepMissed(missedAfterDays,
                    new ActorContext(SYSTEM, UUID.randomUUID().toString()));
            if (marked > 0) {
                log.info("Waste sweep marked {} collection(s) missed", marked);
            }
        } catch (RuntimeException failure) {
            log.error("Waste missed-collection sweep failed; it will be retried on the next run", failure);
        }
    }
}
