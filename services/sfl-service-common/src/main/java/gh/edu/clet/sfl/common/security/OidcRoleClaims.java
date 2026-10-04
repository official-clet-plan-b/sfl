package gh.edu.clet.sfl.common.security;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads the raw role-name strings out of a JWT's configured roles claim - the piece
 * {@link OidcRolesConverter} and every service's {@code *ActorResolver} both need, so it exists once
 * rather than as six near-identical private methods.
 *
 * <p>Accepts either shape a provider might put at the claim: a flat list of role-name strings, or a
 * map whose keys are role names (Zitadel's {@code urn:zitadel:iam:org:project:roles} shape). A
 * dotted claim name such as {@code realm_access.roles} also reads nested claims, which keeps the
 * standard Keycloak token shape selectable through configuration without coupling the services to
 * Keycloak.
 */
public final class OidcRoleClaims {

    private OidcRoleClaims() {
    }

    public static Set<String> roleNames(Jwt jwt, String claimName) {
        Object claim = jwt.getClaim(claimName);
        if (claim == null && claimName.contains(".")) {
            claim = nestedClaim(jwt, claimName);
        }
        if (claim instanceof Map<?, ?> map) {
            return map.keySet().stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        }
        if (claim instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }

    private static Object nestedClaim(Jwt jwt, String claimName) {
        Object current = jwt.getClaims();
        for (String segment : claimName.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }
}
