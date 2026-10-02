package gh.edu.clet.sfl.safetysecurity.drill.infrastructure.scheduling;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillComplianceService;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SRS-SFL-S175-04's sweep: raises "Compliance Gap" for each site and drill type past its required interval, once per
 * lapse. Mirrors {@code RiskAssessmentScheduler}: one transaction per requirement, failures logged per item, on a
 * platform thread so the row-level-security scope is every site.
 */
@Component
@ConditionalOnProperty(name = "sfl.drill.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class DrillComplianceScheduler {

    private static final Logger log = LoggerFactory.getLogger(DrillComplianceScheduler.class);

    private final DrillComplianceService compliance;
    private final Clock clock;

    public DrillComplianceScheduler(DrillComplianceService compliance, Clock clock) {
        this.compliance = compliance;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${sfl.drill.scheduling.fixed-delay:PT1H}",
            initialDelayString = "${sfl.drill.scheduling.initial-delay:PT1M}")
    public void sweep() {
        ActorContext system = new ActorContext(new SiteScopedPrincipal("drill-compliance-scheduler",
                "Drill Compliance Scheduler", Set.of(SflRole.SFL_ADMIN), Set.of("*"), true),
                "drill-compliance-sweep-" + clock.instant().toEpochMilli());
        int flagged = 0;
        for (UUID requirementId : compliance.sweepCandidates()) {
            try {
                flagged += compliance.sweep(requirementId, system) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("Drill compliance sweep could not process requirement {}", requirementId, e);
            }
        }
        if (flagged > 0) {
            log.info("S175 sweep: {} compliance gap(s) raised", flagged);
        }
    }
}
