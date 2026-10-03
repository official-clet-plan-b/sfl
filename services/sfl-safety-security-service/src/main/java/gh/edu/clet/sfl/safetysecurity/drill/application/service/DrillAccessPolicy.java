package gh.edu.clet.sfl.safetysecurity.drill.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import gh.edu.clet.sfl.safetysecurity.drill.domain.policy.DrillPermissionMatrix;
import java.util.List;
import org.springframework.stereotype.Component;

/** Site-scoped permission enforcement for S175, delegating to {@link DrillPermissionMatrix}. */
@Component
public class DrillAccessPolicy {

    public void require(ActorContext actor, SflPermission permission, String site, String resource, String id) {
        if (!has(actor, permission) || !actor.principal().canAccessSite(site)) {
            throw DrillException.unauthorizedScope(site, resource, id);
        }
    }

    /** For cross-site reads - the calendar, the dashboard, compliance - narrowed afterwards by {@link #sites}. */
    public void requirePermission(ActorContext actor, SflPermission permission, String resource) {
        if (!has(actor, permission)) {
            throw DrillException.unauthorizedScope(null, resource, null);
        }
    }

    public boolean has(ActorContext actor, SflPermission permission) {
        return DrillPermissionMatrix.grants(actor.principal().roles(), permission);
    }

    /**
     * The sites a cross-site read covers: one site when asked for (and allowed), otherwise the caller's own
     * scopes, or null for a caller scoped to every site.
     */
    public List<String> sites(ActorContext actor, String siteCode, String resource) {
        if (siteCode != null && !siteCode.isBlank()) {
            String site = siteCode.strip().toUpperCase(java.util.Locale.ROOT);
            if (!actor.principal().canAccessSite(site)) {
                throw DrillException.unauthorizedScope(site, resource, null);
            }
            return List.of(site);
        }
        if (actor.principal().siteScopes().contains("*")) {
            return null;
        }
        return actor.principal().siteScopes().stream().map(s -> s.toUpperCase(java.util.Locale.ROOT)).sorted()
                .toList();
    }
}
