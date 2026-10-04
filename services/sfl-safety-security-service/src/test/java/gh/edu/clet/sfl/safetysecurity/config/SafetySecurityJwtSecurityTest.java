package gh.edu.clet.sfl.safetysecurity.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport;
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
 * {@code FacilitiesJwtSecurityTest}. This also confirms that removing the stray
 * {@code .httpBasic(...)} call and generalising role extraction to {@code OidcRolesConverter} left the
 * chain's actual behaviour intact: still refuses anonymous callers, still admits a valid token.
 */
@SpringBootTest(properties = {"sfl.security.enabled=true"})
@AutoConfigureMockMvc
@EnabledIf(value = "gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available; see SafetySecurityPostgresSupport.unavailableReason()")
class SafetySecurityJwtSecurityTest extends SafetySecurityPostgresSupport {

    @Autowired
    private MockMvc mockMvc;

    /** The chain needs a decoder to build; {@code jwt()} supplies the token, so this is never called. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("an unauthenticated request is refused, not served as development-user")
    void anonymous_is_refused() throws Exception {
        mockMvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an X-SFL-User header cannot stand in for a token once security is on")
    void a_header_is_not_an_identity() throws Exception {
        mockMvc.perform(get("/api/v1/incidents")
                        .header("X-SFL-User", "somebody.else")
                        .header("X-SFL-Roles", "SFL_ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a Keycloak-shaped token is admitted - authenticated, not refused with 401")
    void a_valid_token_is_admitted() throws Exception {
        mockMvc.perform(get("/api/v1/incidents").with(jwt().jwt(hseManager())))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401) {
                        throw new AssertionError("a valid token must not be refused as unauthenticated");
                    }
                });
    }

    @Test
    @DisplayName("HTTP Basic is not a second way in - no local user store exists")
    void basic_auth_is_not_accepted() throws Exception {
        mockMvc.perform(get("/api/v1/incidents").header("Authorization", "Basic dXNlcjpwYXNzd29yZA=="))
                .andExpect(status().isUnauthorized());
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

    private static Jwt hseManager() {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("hse.manager")
                .claim("name", "HSE Manager")
                .claim("preferred_username", "hse.manager")
                .claim("realm_access", Map.of("roles", List.of("HSE_MANAGER")))
                .claim("site_scopes", List.of("CLET-HQ"))
                .build();
    }
}
