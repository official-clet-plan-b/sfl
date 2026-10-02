package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.integration;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.safetysecurity.platform.application.integration.InboundIntegrationEvent;
import gh.edu.clet.sfl.safetysecurity.platform.application.integration.IntegrationEventHandler;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.CoverageService;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * IFIMP -> S165, SRS-SFL-S165-03: every work type an S176 construction project declares is an activity the
 * platform actually performs, and a coverage gap if no current assessment covers it at that site.
 *
 * <p>The SRS's own acceptance criterion: "Given a new construction work type is used in S176 with no
 * matching risk assessment, when the coverage report runs, then that work type appears as a gap." S176
 * declares its work types on registration and repeats them when works start
 * ({@code docs/facilities/S176_Event_Contracts.md}); both are handled, and the second only bumps the
 * occurrence count of what the first recorded.
 *
 * <p>A payload with no site or no work types is logged and dropped, never thrown: it can never be handled,
 * and throwing would make the broker redeliver it forever.
 */
@Component
public class ConstructionActivityTypesHandler implements IntegrationEventHandler {

    static final String PROJECT_REGISTERED = "sfl.ifimp.project-registered.v1";
    static final String PROJECT_STARTED = "sfl.ifimp.project-started.v1";
    static final String SOURCE_SYSTEM = "S176";

    private static final Logger log = LoggerFactory.getLogger(ConstructionActivityTypesHandler.class);
    private static final Set<String> HANDLED = Set.of(PROJECT_REGISTERED, PROJECT_STARTED);

    private final CoverageService coverage;

    public ConstructionActivityTypesHandler(CoverageService coverage) {
        this.coverage = coverage;
    }

    @Override
    public boolean handles(String eventType) {
        return HANDLED.contains(eventType);
    }

    @Override
    public void handle(InboundIntegrationEvent event) {
        String site = event.siteCode() != null ? event.siteCode() : event.text("siteCode");
        List<String> workTypes = event.texts("workTypes");
        if (site == null || site.isBlank() || workTypes.isEmpty()) {
            log.warn("{} {} carries no site or no work types; nothing to record for S165 coverage", event.eventType(),
                    event.messageId());
            return;
        }
        String reference = event.text("projectReference") != null ? event.text("projectReference")
                : event.aggregateId();
        ActorContext actor = new ActorContext(new SiteScopedPrincipal("risk-assessment-coverage",
                "S165 coverage feed", Set.of(SflRole.SERVICE_INTEGRATION), Set.of(site), true),
                event.correlationId() != null ? event.correlationId() : event.messageId().toString());
        for (String workType : workTypes) {
            try {
                coverage.observe(site, workType, SOURCE_SYSTEM, reference, actor);
            } catch (IllegalArgumentException malformed) {
                log.warn("{} {} work type '{}' was not recorded: {}", event.eventType(), event.messageId(), workType,
                        malformed.getMessage());
            }
        }
    }
}
