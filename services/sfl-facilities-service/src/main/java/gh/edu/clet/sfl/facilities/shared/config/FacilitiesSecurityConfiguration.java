package gh.edu.clet.sfl.facilities.shared.config;

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
 * Provider-neutral by design: token validation is pure OIDC/JWKS driven by
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, and roles are read from whichever
 * claim {@code sfl.security.roles-claim} names (see {@link OidcRolesConverter}) rather than one
 * provider's specific shape. Swapping identity provider is configuration, not code. The platform's
 * OIDC provider is Zitadel; there is no local user or password store anywhere in this service.
 */
@Configuration(proxyBeanMethods = false)
class FacilitiesSecurityConfiguration {

    @Bean
    @ConditionalOnProperty(name = "sfl.security.enabled", havingValue = "false")
    @Profile({"test", "local", "dev"})
    SecurityFilterChain developmentSecurity(HttpSecurity http) throws Exception {
        LoggerFactory.getLogger(getClass()).warn(
                "sfl.security.enabled=false: every facilities endpoint is UNAUTHENTICATED and the actor is "
                        + "whatever the X-SFL-* headers claim. Local development only.");
        return http
                .csrf(csrf -> csrf.disable())
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
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/", "/index.html", "/actuator/health/**", "/api/v1/system/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(
                        oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(new OidcRolesConverter(rolesClaim))))
                .build();
    }
}
