package gh.edu.clet.sfl.safetysecurity.permit.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitException;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.PermitPermissionMatrix;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Site-scoped permission enforcement for S164, delegating to {@link PermitPermissionMatrix}. */
@Component
public class PermitAccessPolicy {

    public void require(ActorContext actor, SflPermission permission, String site, String resource, String id) {
        if (!has(actor, permission) || !actor.principal().canAccessSite(site)) {
            throw PermitException.unauthorizedScope(site, resource, id);
        }
    }

    /** For global records (permit types) and cross-site reads, narrowed afterwards by {@link #sites}. */
    public void requirePermission(ActorContext actor, SflPermission permission, String resource) {
        if (!has(actor, permission)) {
            throw PermitException.unauthorizedScope(null, resource, null);
        }
    }

    public boolean has(ActorContext actor, SflPermission permission) {
        return PermitPermissionMatrix.grants(actor.principal().roles(), permission);
    }

    /** The sites a cross-site read covers: one when asked for (and allowed), otherwise the caller's own scopes, or null for every site. */
    public List<String> sites(ActorContext actor, String siteCode, String resource) {
        if (siteCode != null && !siteCode.isBlank()) {
            String site = siteCode.strip().toUpperCase(Locale.ROOT);
            if (!actor.principal().canAccessSite(site)) {
                throw PermitException.unauthorizedScope(site, resource, null);
            }
            return List.of(site);
        }
        if (actor.principal().siteScopes().contains("*")) {
            return null;
        }
        return actor.principal().siteScopes().stream().map(s -> s.toUpperCase(Locale.ROOT)).sorted().toList();
    }
}
