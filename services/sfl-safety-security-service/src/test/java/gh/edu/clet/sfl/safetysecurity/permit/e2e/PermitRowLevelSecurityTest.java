package gh.edu.clet.sfl.safetysecurity.permit.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Row-level security on S164's tables from their first migration (Phase 2 SRS CORR-06, NFR-SEC3), proved against {@code sfl_app}. The
 * coverage of which tables carry the policy is asserted in {@code RiskAssessmentRowLevelSecurityTest}, which owns the exact set.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=false",
        "sfl.safety-security.messaging.drainer-enabled=false",
        "sfl.risk-assessment.scheduling.enabled=false",
        "sfl.permit.scheduling.enabled=false"
})
@EnabledIf(value = "gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport#databaseAvailable", disabledReason = "No PostgreSQL available")
class PermitRowLevelSecurityTest extends SafetySecurityPostgresSupport {

    private static final String PASSWORD = "rls-test-s164";

    @Autowired private DataSource dataSource;

    private JdbcTemplate jdbc;
    private String siteA;
    private String siteB;

    @BeforeEach
    void seedAsOwner() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER ROLE sfl_app LOGIN PASSWORD '" + PASSWORD + "'");
        siteA = "PTA" + Math.abs(UUID.randomUUID().hashCode() % 100000);
        siteB = "PTB" + Math.abs(UUID.randomUUID().hashCode() % 100000);
        jdbc.update(insertSql(siteA));
        jdbc.update(insertSql(siteB));
    }

    @Test
    @DisplayName("an unscoped session sees no permits - the policy fails closed")
    void unset_scope_sees_nothing() throws SQLException {
        assertThat(sitesVisibleTo(null)).isEmpty();
    }

    @Test
    @DisplayName("a scoped session sees its own site's permits and not the other's")
    void one_scope_sees_one_site() throws SQLException {
        assertThat(sitesVisibleTo(siteA)).contains(siteA).doesNotContain(siteB);
    }

    @Test
    @DisplayName("the cross-site scope sees across")
    void star_sees_everything() throws SQLException {
        assertThat(sitesVisibleTo("*")).contains(siteA, siteB);
    }

    @Test
    @DisplayName("a write outside the scope is refused by WITH CHECK, not silently dropped")
    void a_write_outside_scope_is_refused() throws SQLException {
        try (Connection connection = asApplicationRole()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL app.site_scopes = '" + siteA + "'");
                statement.executeUpdate(insertSql(siteB));
                throw new AssertionError("a permit outside the caller's scope must not be insertable");
            } catch (SQLException expected) {
                assertThat(expected.getSQLState()).isEqualTo("42501");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @DisplayName("permit types are organisation-wide configuration: no site code, no policy, readable to the application role")
    void permit_types_are_global() throws SQLException {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'safety_security' AND table_name = 'permit_types'"
                + " AND column_name = 'site_code'", Long.class)).isZero();
        try (Connection connection = asApplicationRole(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM safety_security.permit_types")) {
            rows.next();
            assertThat(rows.getLong(1)).isGreaterThanOrEqualTo(3);
        }
    }

    private static String insertSql(String site) {
        return "INSERT INTO safety_security.permits (id, permit_type_id, site_code, reference, work_type, title, work_description, location_code, starts_at, ends_at,"
                + " status, supervisor_reference, origin_system, requested_by, created_by, created_at, updated_at) SELECT '" + UUID.randomUUID() + "', id, '" + site
                + "', 'PTW-RLS-" + UUID.randomUUID().toString().substring(0, 8) + "', code, 'RLS probe', 'probe', 'Block A', now(), now() + interval '1 hour', 'DRAFT', 'sup',"
                + " 'NONE', 'rls-test', 'rls-test', now(), now() FROM safety_security.permit_types WHERE code = 'HOT_WORK'";
    }

    private List<String> sitesVisibleTo(String scopes) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Connection connection = asApplicationRole()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                if (scopes != null) {
                    statement.execute("SET LOCAL app.site_scopes = '" + scopes + "'");
                }
                try (ResultSet rows = statement.executeQuery("SELECT DISTINCT site_code FROM safety_security.permits WHERE site_code IN ('" + siteA + "', '" + siteB + "')")) {
                    while (rows.next()) {
                        found.add(rows.getString(1));
                    }
                }
            }
            connection.rollback();
        }
        return found;
    }

    private Connection asApplicationRole() throws SQLException {
        String url;
        try (Connection owner = dataSource.getConnection()) {
            url = owner.getMetaData().getURL();
        }
        return DriverManager.getConnection(url, "sfl_app", PASSWORD);
    }
}
