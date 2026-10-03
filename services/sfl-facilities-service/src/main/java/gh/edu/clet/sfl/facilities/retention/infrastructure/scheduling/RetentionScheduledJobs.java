package gh.edu.clet.sfl.facilities.retention.infrastructure.scheduling;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.facilities.retention.application.RetentionService;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Anonymises sensitive personal data whose retention period has ended. Idempotent; failures are logged, never thrown. */
@Component
@ConditionalOnProperty(name = "sfl.retention.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduledJobs.class);

    private static final SiteScopedPrincipal SYSTEM = new SiteScopedPrincipal("system.retention", "Retention scheduler",
            Set.of(SflRole.SFL_ADMIN), Set.of("*"), true);

    private final RetentionService retention;

    public RetentionScheduledJobs(RetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${sfl.retention.interval-ms:86400000}", initialDelayString = "${sfl.retention.initial-delay-ms:240000}")
    public void anonymise() {
        try {
            int count = retention.anonymiseDietary(new ActorContext(SYSTEM, UUID.randomUUID().toString()));
            if (count > 0) {
                log.info("Retention: {} dietary need(s) anonymised", count);
            }
        } catch (RuntimeException failure) {
            log.error("Retention run failed; it will be retried on the next run", failure);
        }
    }
}
