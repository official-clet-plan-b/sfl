package gh.edu.clet.sfl.safetysecurity.incident.application.contract;

import gh.edu.clet.sfl.common.security.ActorContext;
import java.util.UUID;

/**
 * Told when an incident's risk context is saved - published by S163 in its own {@code contract} package,
 * the way facilities' Phase 2 providers publish theirs, so a sibling module can react without S163
 * knowing it exists.
 *
 * <p>The implementation today is S165's (Phase 2 SRS S165-04: "An S163 incident linked to a permit (S164)
 * or activity with a current risk assessment automatically flags that assessment for out-of-cycle
 * review"). It is called inside the incident's own transaction, so the flag and the incident commit
 * together - "within the same operational day" - and an observer that refuses (a link naming no
 * assessment at the incident's site) refuses the save with it.
 *
 * <p>Zero implementations is a valid deployment: S163 does not depend on S165 being present.
 */
public interface IncidentRiskObserver {

    void riskContextRecorded(IncidentRiskContext context);

    /**
     * @param riskAssessmentId the linked S165 assessment, or null
     * @param activityType the activity the incident happened during, or null
     */
    record IncidentRiskContext(UUID incidentId, String incidentReference, String siteCode, UUID riskAssessmentId,
            String activityType, ActorContext actor) {

        public boolean isEmpty() {
            return riskAssessmentId == null && (activityType == null || activityType.isBlank());
        }
    }
}
