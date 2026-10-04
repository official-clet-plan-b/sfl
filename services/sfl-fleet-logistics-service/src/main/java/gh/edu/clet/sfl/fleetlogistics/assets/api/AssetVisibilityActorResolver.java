package gh.edu.clet.sfl.fleetlogistics.assets.api;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.OidcRoleClaims;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Who is calling AVAMP.
 *
 * <p><strong>The first defect this closed.</strong> Every controller method took the actor as
 * {@code @RequestHeader(name = "X-SFL-User", defaultValue = "development-user")} and passed it
 * straight into the command. That is fine while security is off and the header *is* the actor. It
 * stops being fine the moment authentication is on: the request carries a verified JWT principal,
 * and the service would still have attributed the action to whatever string the caller chose - or,
 * absent one, to a user literally called {@code development-user}.
 *
 * <p><strong>The second, closed on 1 August 2026.</strong> Resolving an identity is not the same as
 * having one. This returned a bare {@code String} - an actor id and nothing else, no roles and no
 * site scopes - so even after A1 there was nothing for an authorisation check to read, and the
 * service duly had none. It now returns a full {@link ActorContext}, which is what
 * {@code AssetVisibilityAccessPolicy} needs to answer both of its questions.
 *
 * <p>The claim names are the platform's - the configured roles claim ({@code sfl.security.roles-claim})
 * and {@code site_scopes}, both issued by the platform's OIDC provider and read identically by the
 * other three services. AVAMP is brought into line rather than given a fourth convention.
 *
 * <p><strong>The header path is not deleted.</strong> With {@code sfl.security.enabled=false} there
 * is no JWT, the dashboard sends {@code X-SFL-*}, and local development works as it did. What changed
 * is the precedence: a header can no longer override a verified identity.
 */
@Component
public class AssetVisibilityActorResolver {

    static final String HEADER_USER = "X-SFL-User";
    static final String HEADER_DISPLAY_NAME = "X-SFL-Display-Name";
    static final String HEADER_ROLES = "X-SFL-Roles";
    static final String HEADER_SITES = "X-SFL-Sites";
    static final String HEADER_CORRELATION_ID = "X-Correlation-ID";

    private static final String CLAIM_SITES = "site_scopes";

    private final String rolesClaim;

    public AssetVisibilityActorResolver(
            @Value("${sfl.security.roles-claim}") String rolesClaim) {
        this.rolesClaim = rolesClaim;
    }

    /** The actor, with the roles and site scopes an authorisation check needs. */
    public ActorContext resolve(HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            return fromJwt(jwtToken.getToken(), correlationId);
        }
        return fromHeaders(request, correlationId);
    }

    /** The actor id a command is attributed to, and every audit row is written under. */
    public String resolveActor(HttpServletRequest request) {
        return resolve(request).actorId();
    }

    public String resolveCorrelationId(HttpServletRequest request) {
        return request.getHeader(HEADER_CORRELATION_ID);
    }

    private ActorContext fromJwt(Jwt jwt, String correlationId) {
        // A service account presents a client_id and no username. AVAMP is fed by integration
        // principals, so this is the ordinary case here rather than an edge one.
        boolean serviceAccount = jwt.getClaimAsString("client_id") != null
                && jwt.getClaimAsString("preferred_username") == null;
        return new ActorContext(
                new SiteScopedPrincipal(
                        // `sub` rather than `preferred_username`: the subject is stable across a
                        // rename, and an audit trail that changes retroactively is not one.
                        jwt.getSubject(),
                        jwt.getClaimAsString("name"),
                        realmRoles(jwt),
                        claimAsSet(jwt, CLAIM_SITES),
                        serviceAccount),
                correlationId);
    }

    private ActorContext fromHeaders(HttpServletRequest request, String correlationId) {
        String userId = header(request, HEADER_USER, "development-user");
        return new ActorContext(
                new SiteScopedPrincipal(userId, request.getHeader(HEADER_DISPLAY_NAME),
                        parseRoles(request.getHeader(HEADER_ROLES)), parseCsv(request.getHeader(HEADER_SITES)),
                        false),
                correlationId);
    }

    private static String header(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private Set<SflRole> realmRoles(Jwt jwt) {
        return OidcRoleClaims.roleNames(jwt, rolesClaim).stream()
                .map(AssetVisibilityActorResolver::toRole)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> claimAsSet(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        if (value instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        }
        return parseCsv(jwt.getClaimAsString(claim));
    }

    private static Set<SflRole> parseRoles(String header) {
        return parseCsv(header).stream()
                .map(AssetVisibilityActorResolver::toRole)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> parseCsv(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(header.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Unknown role names are ignored rather than rejected: the identity provider carries roles that
     * mean nothing to this service, and an unknown role grants nothing.
     */
    private static SflRole toRole(String name) {
        try {
            return SflRole.valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
