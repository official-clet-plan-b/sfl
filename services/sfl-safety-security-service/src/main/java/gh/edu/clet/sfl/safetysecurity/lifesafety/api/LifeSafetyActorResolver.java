package gh.edu.clet.sfl.safetysecurity.lifesafety.api;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.OidcRoleClaims;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.SourceChannel;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Produces the {@link ActorContext} every S162a command/query is authorised against. Own copy per
 * module, mirroring {@code IncidentActorResolver} rather than reusing it directly.
 */
@Component
public class LifeSafetyActorResolver {

    static final String HEADER_USER = "X-SFL-User";
    static final String HEADER_DISPLAY_NAME = "X-SFL-Display-Name";
    static final String HEADER_ROLES = "X-SFL-Roles";
    static final String HEADER_SITES = "X-SFL-Sites";
    static final String HEADER_CORRELATION_ID = "X-Correlation-ID";
    static final String HEADER_SOURCE_CHANNEL = "X-SFL-Source-Channel";

    private final String rolesClaim;

    public LifeSafetyActorResolver(
            @Value("${sfl.security.roles-claim}") String rolesClaim) {
        this.rolesClaim = rolesClaim;
    }

    public ActorContext resolve(HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            return fromJwt(jwtToken.getToken(), correlationId);
        }
        return fromHeaders(request, correlationId);
    }

    public SourceChannel resolveSourceChannel(HttpServletRequest request) {
        String header = request.getHeader(HEADER_SOURCE_CHANNEL);
        if (header == null || header.isBlank()) {
            return SourceChannel.WEB;
        }
        try {
            return SourceChannel.valueOf(header.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return SourceChannel.WEB;
        }
    }

    public String resolveCorrelationId(HttpServletRequest request) {
        String value = request.getHeader(HEADER_CORRELATION_ID);
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.strip();
    }

    private ActorContext fromJwt(Jwt jwt, String correlationId) {
        Set<SflRole> roles = realmRoles(jwt);
        Set<String> sites = claimAsSet(jwt, "site_scopes");
        boolean serviceAccount = jwt.getClaimAsString("client_id") != null
                && jwt.getClaimAsString("preferred_username") == null;
        return new ActorContext(
                new SiteScopedPrincipal(jwt.getSubject(), jwt.getClaimAsString("name"), roles, sites, serviceAccount),
                correlationId);
    }

    private ActorContext fromHeaders(HttpServletRequest request, String correlationId) {
        String userId = header(request, HEADER_USER, "development-user");
        Set<SflRole> roles = parseRoles(request.getHeader(HEADER_ROLES));
        Set<String> sites = parseCsv(request.getHeader(HEADER_SITES));
        return new ActorContext(
                new SiteScopedPrincipal(userId, request.getHeader(HEADER_DISPLAY_NAME), roles, sites, false),
                correlationId);
    }

    private Set<SflRole> realmRoles(Jwt jwt) {
        return OidcRoleClaims.roleNames(jwt, rolesClaim).stream().map(LifeSafetyActorResolver::toRole)
                .filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> claimAsSet(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        if (value instanceof java.util.Collection<?> values) {
            return values.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        }
        return parseCsv(jwt.getClaimAsString(claim));
    }

    private static Set<SflRole> parseRoles(String header) {
        return parseCsv(header).stream().map(LifeSafetyActorResolver::toRole)
                .filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> parseCsv(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(header.split(",")).map(String::strip)
                .filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

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
