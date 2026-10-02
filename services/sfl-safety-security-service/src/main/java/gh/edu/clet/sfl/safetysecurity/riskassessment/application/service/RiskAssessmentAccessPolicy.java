package gh.edu.clet.sfl.safetysecurity.riskassessment.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentException;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.policy.RiskAssessmentPermissionMatrix;
import org.springframework.stereotype.Component;

/** Site-scoped permission enforcement for S165, delegating to {@link RiskAssessmentPermissionMatrix}. */
@Component
public class RiskAssessmentAccessPolicy {

    public void require(ActorContext actor, SflPermission permission, String site, String resource, String id) {
        if (!has(actor, permission) || !actor.principal().canAccessSite(site)) {
            throw RiskAssessmentException.unauthorizedScope(site, resource, id);
        }
    }

    /** For the global records - templates and review intervals - which belong to no site. */
    public void requirePermission(ActorContext actor, SflPermission permission, String resource, String id) {
        if (!has(actor, permission)) {
            throw RiskAssessmentException.unauthorizedScope(null, resource, id);
        }
    }

    public boolean has(ActorContext actor, SflPermission permission) {
        return RiskAssessmentPermissionMatrix.grants(actor.principal().roles(), permission);
    }
}
