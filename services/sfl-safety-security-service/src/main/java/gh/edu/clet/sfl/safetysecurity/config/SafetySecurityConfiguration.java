package gh.edu.clet.sfl.safetysecurity.config;

import gh.edu.clet.sfl.common.security.OidcRolesConverter;
import gh.edu.clet.sfl.common.security.TimeoutBoundedJwtDecoders;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The SSEMP filter chains - now the only ones in this deployable.
 *
 * <p><strong>One chain, not two.</strong> When S174 was folded in, it brought an identical pair of
 * chains whose bean methods were also called {@code developmentSecurity} and {@code resourceServerSecurity}.
 * Two beans of the same name in one context is a startup failure, not a merge - so the emergency pair
 * was deleted and its permit list absorbed below. This was the fourth near-verbatim copy of the same
 * ninety lines across the estate; collapsing it is the point of the merge rather than a side effect.
 *
 * <p>Added 1 August 2026. This module had <strong>no security configuration at all</strong> - the
 * absence the go-live readiness pack recorded under G-01 and which was never closed with the rest of
 * that item. The consequence was not that the service was open: it was the opposite. With no chain
 * declared, Spring Security's default secured <em>everything</em> including {@code /actuator/health},
 * so the service answered {@code 401} to its own health probe, and {@code SFL_SECURITY_ENABLED=false}
 * had no effect because nothing read the property.
 *
 * <p>A service whose liveness probe returns 401 is a service every orchestrator treats as dead. It went
 * unnoticed for the same reason the missing main class did - nothing had ever started this module.
 *
 * <p>The two chains are the platform convention and the reasoning is A1's:
 *
 * <ul>
 *   <li><strong>Secure is what an absent property selects.</strong> {@code matchIfMissing = true} sits on
 *       the resource-server chain, not the open one, so an environment that forgets the variable gets
 *       authentication rather than an open API.</li>
 *   <li><strong>Taking the open path is loud.</strong> It logs a warning naming this service on every
 *       startup, because the previous default let an unauthenticated deployment look normal.</li>
 *   <li><strong>The health probe and {@code /api/v1/system/info} stay reachable without a token.</strong>
 *       A load balancer cannot present one.</li>
 * </ul>
 *
 * <p>Token validation is pure OIDC/JWKS driven by
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, and roles are read from whichever claim
 * {@code sfl.security.roles-claim} names (see {@link OidcRolesConverter}) rather than one provider's
 * specific shape - the platform's OIDC provider is Zitadel, but nothing here is written against it by
 * name.
 *
 * <p>None of this makes any SSEMP system exist. It makes the foundation deployable and monitorable on
 * the same terms as its four siblings.
 */
@Configuration(proxyBeanMethods = false)
class SafetySecurityConfiguration {

    @Bean
    @ConditionalOnProperty(name = "sfl.security.enabled", havingValue = "false")
    @Profile({"test", "local", "dev"})
    SecurityFilterChain developmentSecurity(HttpSecurity http) throws Exception {
        LoggerFactory.getLogger(getClass()).warn(
                "sfl.security.enabled=false: every safety-security endpoint is UNAUTHENTICATED and the actor is "
                        + "whatever the X-SFL-* headers claim - including /api/v1/emergency, where that means "
                        + "anyone can fire a mass notification. Local development only.");
        return http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    /**
     * Bounded so a slow or unreachable identity provider fails a request in seconds rather than
     * blocking its thread indefinitely - see {@link TimeoutBoundedJwtDecoders} for the full reasoning
     * and the fail-fast-per-request vs. startup-warming trade-off this makes deliberately.
     */
    @Bean
    @ConditionalOnProperty(name = "sfl.security.enabled", havingValue = "true", matchIfMissing = true)
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${sfl.security.jwks.connect-timeout:PT3S}") Duration connectTimeout,
            @Value("${sfl.security.jwks.read-timeout:PT3S}") Duration readTimeout) {
        return TimeoutBoundedJwtDecoders.fromIssuerLocation(issuerUri, connectTimeout, readTimeout);
    }

    @Bean
    @ConditionalOnProperty(name = "sfl.security.enabled", havingValue = "true", matchIfMissing = true)
    SecurityFilterChain resourceServerSecurity(HttpSecurity http,
            @Value("${sfl.security.roles-claim}") String rolesClaim)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/api/v1/system/info").permitAll()
                        // S174's public surfaces, carried over when the emergency deployable was folded
                        // into this one. Provider callbacks are authenticated at the application layer by
                        // HMAC and source allowlist (SRS-SFL-S174-04), not by a bearer token - an SMS or
                        // voice gateway posting a delivery receipt has no way to present one. The notice
                        // page is a public operational surface; Swagger is not - unauthenticated schema
                        // recon of the module handling incidents and emergency notifications is not a
                        // trade a notice page needs, and facilities never opened it either.
                        // S160a's integration endpoints are authenticated at the application layer by HMAC
                        // and source allowlist (SRS-SFL-S160a-01), the same reasoning as the emergency
                        // provider-callbacks line above: a door controller or an HRMS batch job has no SFL
                        // bearer token to present. S161's CCTV, S162's intrusion-panel and S162a's
                        // life-safety-feed integration endpoints are authenticated the same way
                        // (SRS-SFL-S161-01/04, SRS-SFL-S162-01, SRS-SFL-S162a-01): a VMS, an alarm panel or
                        // a fire panel gateway has no SFL bearer token either.
                        .requestMatchers("/", "/index.html", "/emergency/**",
                                "/api/v1/emergency/provider-callbacks/**", "/api/v1/access-control/integration/**",
                                "/api/v1/cctv/integration/**", "/api/v1/intrusion/integration/**",
                                "/api/v1/life-safety/integration/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(
                        oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(new OidcRolesConverter(rolesClaim))))
                .build();
    }
}
