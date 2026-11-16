package gh.edu.clet.sfl.common.security;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * Builds a {@link JwtDecoder} whose JWKS fetch (and, on first use, its OIDC-discovery lookup of the
 * {@code jwks_uri}) is bounded by an explicit connect and read timeout, shared across facilities,
 * fleet-logistics and safety-security so all three fail the same way under a slow or unreachable
 * identity provider.
 *
 * <p>Spring Boot's own auto-configuration (activated when only
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} is set) builds the equivalent decoder
 * with a default {@code RestTemplate} that has no connect or read timeout. Discovery and the JWKS fetch
 * both happen lazily, on the first token this instance validates - not at startup - so an unreachable or
 * stalled identity provider is invisible until then. With no read timeout, that first validation (and
 * every request thread behind it, once traffic ramps up) can block indefinitely, which is a direct path
 * to exhausting the web server's request-thread pool off one slow dependency.
 *
 * <p><strong>Chosen behaviour: lazy discovery, bounded wait, fail per-request.</strong> This still does
 * not warm the JWKS cache at startup - each service's readiness probe (`/actuator/health`) does not
 * depend on the identity provider being reachable, matching how these services are deployed today (a
 * service that cannot start because Zitadel is briefly unavailable is a worse outage than one that
 * starts and returns 401s until the identity provider recovers). What changes is that a stalled fetch now
 * fails within {@code connectTimeout + readTimeout} instead of hanging forever: a request thread that
 * would have blocked indefinitely instead fails fast with a 401, freeing the thread and the connection it
 * held. If eager startup warming is wanted for a given deployment, that is a separate, explicit decision
 * - not something this class makes on a service's behalf.
 */
public final class TimeoutBoundedJwtDecoders {

    private TimeoutBoundedJwtDecoders() {
    }

    public static JwtDecoder fromIssuerLocation(String issuerUri, Duration connectTimeout, Duration readTimeout) {
        return fromIssuerLocation(issuerUri, null, connectTimeout, readTimeout);
    }

    /**
     * As {@link #fromIssuerLocation(String, Duration, Duration)}, but when {@code jwkSetUri} is set the
     * signing keys are fetched from there instead of through OIDC discovery at the issuer, while tokens
     * must still carry {@code issuerUri} as their {@code iss}. This is for an identity provider the
     * browser reaches at one address (the issuer, e.g. {@code http://localhost:8080/realms/sfl}) and the
     * service reaches at another (e.g. {@code http://idp:8080/...} inside a container network):
     * discovery at the internal address reports the public issuer, which Spring rejects as a mismatch,
     * so every sign-in failed with a 500.
     */
    public static JwtDecoder fromIssuerLocation(String issuerUri, String jwkSetUri, Duration connectTimeout,
            Duration readTimeout) {
        boolean explicitKeys = jwkSetUri != null && !jwkSetUri.isBlank();
        // NimbusJwtDecoder.withIssuerLocation(...).build() performs OIDC discovery immediately, during
        // build() - not on first decode - so building it directly as the bean would make every service
        // fail to start whenever the identity provider is briefly unreachable at boot. SupplierJwtDecoder
        // (also what Spring Boot's own issuer-uri auto-configuration uses internally) defers that build,
        // and the discovery call it makes, to the first token actually decoded, and only ever caches a
        // successful build - a failed attempt is retried on the next token rather than wedging the
        // decoder permanently broken until restart.
        return new SupplierJwtDecoder(() -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(connectTimeout);
            requestFactory.setReadTimeout(readTimeout);
            RestTemplate restTemplate = new RestTemplate(requestFactory);
            if (explicitKeys) {
                NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).restOperations(restTemplate).build();
                decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
                return decoder;
            }
            return NimbusJwtDecoder.withIssuerLocation(issuerUri).restOperations(restTemplate).build();
        });
    }
}
