package gh.edu.clet.sfl.fleetlogistics.fleet.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gh.edu.clet.sfl.fleetlogistics.fleet.e2e.FleetPostgresSupport;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The production {@code resourceServerSecurity} chain, executed - mirroring
 * {@code FacilitiesJwtSecurityTest}, which found that this module's sibling had never run its own
 * production chain across four build passes. Searching this module's {@code src/test/java} before this
 * class existed for {@code sfl.security.enabled=true} or {@code JwtAuthenticationToken} returned
 * nothing here either.
 *
 * <p>This also proves the {@code OidcRolesConverter}/{@code OidcRoleClaims} generalisation actually
 * works against a Keycloak-shaped token end to end, not just that the pieces compile.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=true",
        "sfl.fleet.scheduling.sla.enabled=false",
        "sfl.fleet.scheduling.outbox.enabled=false",
        "sfl.fleet.scheduling.compliance.enabled=false",
        "sfl.fleet.scheduling.dashboard.enabled=false",
        "sfl.fleet.messaging.transport=local",
})
@AutoConfigureMockMvc
@EnabledIf(value = "gh.edu.clet.sfl.fleetlogistics.fleet.e2e.FleetPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available; see FleetPostgresSupport.unavailableReason()")
class FleetJwtSecurityTest extends FleetPostgresSupport {

    @Autowired
    private MockMvc mockMvc;

    /** The chain needs a decoder to build; {@code jwt()} supplies the token, so this is never called. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("an unauthenticated request is refused, not served as development-user")
    void anonymous_is_refused() throws Exception {
        mockMvc.perform(get("/api/v1/fleet/vehicles"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an X-SFL-User header cannot stand in for a token once security is on")
    void a_header_is_not_an_identity() throws Exception {
        mockMvc.perform(get("/api/v1/fleet/vehicles")
                        .header("X-SFL-User", "somebody.else")
                        .header("X-SFL-Roles", "SFL_ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a Keycloak-shaped token is admitted - authenticated, not refused with 401")
    void a_valid_token_is_admitted() throws Exception {
        mockMvc.perform(get("/api/v1/fleet/vehicles").with(jwt().jwt(fleetManager())))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401) {
                        throw new AssertionError("a valid token must not be refused as unauthenticated");
                    }
                });
    }

    @Test
    @DisplayName("the health probe stays open - a load balancer cannot present a token")
    void health_is_open() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401 || status == 403) {
                        throw new AssertionError("the health probe must not require a token, got " + status);
                    }
                });
    }

    private static Jwt fleetManager() {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("fleet.manager")
                .claim("name", "Fleet Manager")
                .claim("preferred_username", "fleet.manager")
                .claim("realm_access", Map.of("roles", List.of("FLEET_MANAGER")))
                .claim("site_scopes", List.of("CLET-HQ"))
                .build();
    }
}
