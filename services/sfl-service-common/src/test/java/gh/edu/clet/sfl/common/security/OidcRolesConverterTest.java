package gh.edu.clet.sfl.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

class OidcRolesConverterTest {

    private static final String CLAIM = "urn:zitadel:iam:org:project:roles";

    @Test
    void reads_roles_from_a_zitadel_style_map_claim() {
        Jwt jwt = tokenWithClaim(CLAIM, Map.of(
                "FLEET_MANAGER", Map.of("60011234567891011", "clet"),
                "FLEET_DRIVER", Map.of("60011234567891011", "clet")));

        AbstractAuthenticationToken token = new OidcRolesConverter(CLAIM).convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_FLEET_MANAGER", "ROLE_FLEET_DRIVER");
    }

    @Test
    void reads_roles_from_a_flat_list_claim() {
        Jwt jwt = tokenWithClaim(CLAIM, List.of("SFL_ADMIN", "auditor"));

        AbstractAuthenticationToken token = new OidcRolesConverter(CLAIM).convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_SFL_ADMIN", "ROLE_AUDITOR");
    }

    @Test
    void reads_roles_from_a_nested_keycloak_style_claim() {
        Jwt jwt = tokenWithClaim("realm_access", Map.of("roles", List.of("FLEET_DRIVER")));

        AbstractAuthenticationToken token = new OidcRolesConverter("realm_access.roles").convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_FLEET_DRIVER");
    }

    @Test
    void an_absent_claim_grants_no_authorities() {
        Jwt jwt = tokenWithClaim("some-other-claim", "value");

        AbstractAuthenticationToken token = new OidcRolesConverter(CLAIM).convert(jwt);

        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void the_subject_carries_through_unchanged() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("driver.one")
                .claim(CLAIM, List.of("FLEET_DRIVER")).issuedAt(Instant.EPOCH).expiresAt(Instant.MAX).build();

        AbstractAuthenticationToken token = new OidcRolesConverter(CLAIM).convert(jwt);

        assertThat(token.getName()).isEqualTo("driver.one");
    }

    private static Jwt tokenWithClaim(String claimName, Object claimValue) {
        return Jwt.withTokenValue("token").header("alg", "none").subject("test-subject")
                .claim(claimName, claimValue).issuedAt(Instant.EPOCH).expiresAt(Instant.MAX).build();
    }
}
