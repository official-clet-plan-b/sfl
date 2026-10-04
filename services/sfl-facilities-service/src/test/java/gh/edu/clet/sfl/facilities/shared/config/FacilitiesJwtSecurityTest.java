package gh.edu.clet.sfl.facilities.shared.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The production security chain, executed.
 *
 * <p><strong>Why this test did not exist and had to.</strong> {@code resourceServerSecurity} - the
 * chain that runs in every environment that is not a developer's laptop - had no test at all. Searching
 * every {@code src/test/java} in the reactor for that bean name,
 * {@code sfl.security.enabled=true} or {@code JwtAuthenticationToken} returned nothing. The only
 * filter chain that will ever face a real user had never been executed across four build passes,
 * while the suite reported green off the development chain that permits everything.
 *
 * <p>That is the same shape as the defect A2 found in the mandatory-scenario suites and the one C-18
 * found in the audit chain: a control that exists, is believed to work, and is never run.
 *
 * <p><strong>A full context, not a slice, and that is not laziness.</strong> The first attempt at
 * this was a {@code @WebMvcTest} and it could not work: a slice has no {@code HttpSecurity} bean for
 * a filter chain to be built on, which is exactly why the existing controller tests exclude the
 * resource-server auto-configuration and set {@code addFilters = false}. Testing the chain means
 * booting the application that owns it - so this needs a database, like every other honest test in
 * this service.
 *
 * <p>The decoder is mocked because {@code jwt()} injects an already-decoded token. What is under test
 * is the chain's decision, not JOSE parsing; requiring a live JWKS endpoint would make this the kind
 * of test that gets disabled the first time somebody works offline.
 */
@SpringBootTest(properties = {
        // The whole point. Every other Spring test in this service runs with this false, which is
        // precisely how the production chain went untested.
        "sfl.security.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sfl.facilities.messaging.drainer-enabled=false",
        "sfl.maintenance.scheduling.enabled=false",
        "sfl.booking.scheduling.enabled=false",
})
@AutoConfigureMockMvc
@EnabledIf(value = "gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available; see FacilitiesPostgresSupport.unavailableReason()")
class FacilitiesJwtSecurityTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        FacilitiesPostgresSupport.datasource(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    /** The chain needs a decoder to build; {@code jwt()} supplies the token, so this is never called. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("an unauthenticated request is refused, not served as development-user")
    void anonymous_is_refused() throws Exception {
        mockMvc.perform(get("/api/v1/facilities/sites"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an X-SFL-User header cannot stand in for a token once security is on")
    void a_header_is_not_an_identity() throws Exception {
        // The header path stays for local development. This pins that it cannot assert an identity
        // while the chain is armed - which is the risk of keeping it at all, and the reason AVAMP's
        // raw `@RequestHeader` actor had to go in this same pass.
        mockMvc.perform(get("/api/v1/facilities/sites")
                        .header("X-SFL-User", "somebody.else")
                        .header("X-SFL-Roles", "SFL_ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token is admitted and its realm roles reach the actor")
    void a_valid_token_is_admitted() throws Exception {
        mockMvc.perform(get("/api/v1/facilities/sites").with(jwt().jwt(facilitiesManager())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a token whose roles do not carry the permission is refused, and refused as 403")
    void a_token_without_the_permission_is_forbidden() throws Exception {
        // 401 and 403 are different answers to different questions - "who are you" versus "you may
        // not". A chain that returned 401 here would tell an authenticated user to sign in again.
        mockMvc.perform(get("/api/v1/facilities/audit/integrity").with(jwt().jwt(requester())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the health probe stays open - a load balancer cannot present a token")
    void health_is_open() throws Exception {
        // Asserted as "not refused" rather than 200. The probe reports 503 here because this pass
        // added the AMQP starter and no broker is running in a test, and that is the right answer to
        // "are you healthy" - it is not the question. What this pins is that the chain lets the probe
        // through at all, which a 401 would not.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401 || status == 403) {
                        throw new AssertionError("the health probe must not require a token, got " + status);
                    }
                });
    }

    /**
     * A token shaped the way the platform's primary OIDC provider (Keycloak) issues them.
     *
     * <p>{@code realm_access.roles} and {@code site_scopes} are the two claims
     * {@code FacilitiesActorResolver.fromJwt} reads (the first via {@code OidcRolesConverter}, the
     * second directly), so a change here or in {@code deploy/idp}'s bootstrap that is not made in both
     * places fails this test rather than surfacing in production as an actor with no roles and no
     * sites.
     */
    private static Jwt facilitiesManager() {
        return token("facilities.manager", "Facilities Manager", "FACILITIES_MANAGER");
    }

    /** A requester holds seven facilities permissions and the audit integrity check is not one. */
    private static Jwt requester() {
        return token("akosua.requester", "Akosua Requester", "IFIMP_REQUESTER");
    }

    private static Jwt token(String subject, String name, String projectRole) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .claim("name", name)
                .claim("preferred_username", subject)
                .claim("realm_access", Map.of("roles", List.of(projectRole)))
                .claim("site_scopes", List.of("MAIN"))
                .build();
    }
}
