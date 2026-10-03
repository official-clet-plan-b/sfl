package gh.edu.clet.sfl.safetysecurity.permit.infrastructure.integration;

import gh.edu.clet.sfl.safetysecurity.incident.application.contract.IncidentRiskObserver;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitFlagService;
import org.springframework.stereotype.Component;

/**
 * The one S164 class that knows the incident module exists - SRS-SFL-S164-05 "given an incident occurs during permitted work, when the
 * incident is logged in S163, then the related permit is automatically flagged for the investigation record". Through S163's published
 * {@link IncidentRiskObserver}, in the incident's own transaction: the flag commits with the incident.
 *
 * <p>The match is the assessment the incident names: a permit relying on that assessment, at that site, in force when the incident is
 * logged. An incident that names no assessment cannot be matched automatically; an authoriser links it by hand.
 */
@Component
public class IncidentPermitFlagAdapter implements IncidentRiskObserver {

    private final PermitFlagService flags;

    public IncidentPermitFlagAdapter(PermitFlagService flags) {
        this.flags = flags;
    }

    @Override
    public void riskContextRecorded(IncidentRiskContext context) {
        if (context.riskAssessmentId() != null) {
            flags.flagForIncident(context.siteCode(), context.riskAssessmentId(), context.incidentReference(), context.actor());
        }
    }
}
