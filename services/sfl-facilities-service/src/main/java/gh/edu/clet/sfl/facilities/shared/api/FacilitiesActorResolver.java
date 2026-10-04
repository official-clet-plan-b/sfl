package gh.edu.clet.sfl.facilities.shared.api;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.OidcRoleClaims;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
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
 * Produces the {@link ActorContext} every S152 command and query is authorised against.
 *
 * <p>Two sources, one interface - the swap the workplan requires:
 * <ul>
 *   <li><strong>Production:</strong> the OIDC/JWT resource-server principal, using standard claims
 *       ({@code sub}, {@code name}) plus the configured roles claim ({@code sfl.security.roles-claim})
 *       and the site-scope claim. No provider-specific type appears beyond this class.</li>
 *   <li><strong>Development:</strong> the {@code X-SFL-*} headers, active only when no authenticated
 *       JWT is present.</li>
 * </ul>
 *
 * <p>Supersedes {@link DevActorHeaderResolver}, which handled only the header half. That class is
 * retained so nothing injecting it breaks, but new code should resolve through this one.
 */
@Component
public class FacilitiesActorResolver {

    static final String HEADER_USER = "X-SFL-User";
    static final String HEADER_DISPLAY_NAME = "X-SFL-Display-Name";
    static final String HEADER_ROLES = "X-SFL-Roles";
    static final String HEADER_SITES = "X-SFL-Sites";
    static final String HEADER_CORRELATION_ID = "X-Correlation-ID";
    static final String HEADER_SOURCE_CHANNEL = "X-SFL-Source-Channel";
    static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";

    private static final String CLAIM_SITES = "site_scopes";

    private final String rolesClaim;

    public FacilitiesActorResolver(
            @Value("${sfl.security.roles-claim}") String rolesClaim) {
        this.rolesClaim = rolesClaim;
    }

    /** Resolves the actor for the current request. */
    public ActorContext resolve(HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.currentCorrelationId(request);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            return fromJwt(jwtToken.getToken(), correlationId);
        }
        return fromHeaders(request, correlationId);
    }

    /** The declared source channel for the current request; defaults to {@code WEB}. */
    public SourceChannel resolveSourceChannel(HttpServletRequest request) {
        String header = request.getHeader(HEADER_SOURCE_CHANNEL);
        if (header == null || header.isBlank()) {
            return SourceChannel.WEB;
        }
        try {
            return SourceChannel.valueOf(header.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            // An unknown channel is a caller mistake, not grounds to refuse the operation; WEB is the
            // honest default because that is what an unrecognised browser client is.
            return SourceChannel.WEB;
        }
    }

    /** The {@code Idempotency-Key} header, or {@code null} when the client did not supply one. */
    public String resolveIdempotencyKey(HttpServletRequest request) {
        String key = request.getHeader(HEADER_IDEMPOTENCY_KEY);
        return key == null || key.isBlank() ? null : key.strip();
    }

    private ActorContext fromJwt(Jwt jwt, String correlationId) {
        boolean serviceAccount = jwt.getClaimAsString("client_id") != null
                && jwt.getClaimAsString("preferred_username") == null;
        return new ActorContext(
                new SiteScopedPrincipal(jwt.getSubject(), jwt.getClaimAsString("name"), realmRoles(jwt),
                        claimAsSet(jwt, CLAIM_SITES), serviceAccount),
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

    private Set<SflRole> realmRoles(Jwt jwt) {
        return OidcRoleClaims.roleNames(jwt, rolesClaim).stream()
                .map(FacilitiesActorResolver::toRole)
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
                .map(FacilitiesActorResolver::toRole)
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
     * Unknown role names are ignored rather than rejected: the identity provider is free to carry roles
     * that mean nothing to this service, and an unknown role grants nothing.
     */
    private static SflRole toRole(String value) {
        try {
            return SflRole.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String header(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
