package gh.edu.clet.sfl.safetysecurity.riskassessment.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
 * Row-level security on S165's tables from their first migration (Phase 2 SRS CORR-06, NFR-SEC3), proved
 * against {@code sfl_app} - and the coverage check that keeps it that way.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=false",
        "sfl.safety-security.messaging.drainer-enabled=false",
        "sfl.risk-assessment.scheduling.enabled=false"
})
@EnabledIf(value = "gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available")
class RiskAssessmentRowLevelSecurityTest extends SafetySecurityPostgresSupport {

    private static final String PASSWORD = "rls-test-s165";

    /**
     * Every table that may carry the site-scope policy in {@code safety_security} today: S165's site-scoped
     * tables, and nothing else. ADR 0010 leaves Phase 1 tables unpoliced for a later pass; a Phase 1 table
     * appearing here would mean a migration switched RLS on for it, and every sweep that reads it, unannounced.
     */
    private static final Set<String> S165_POLICED = Set.of("risk_assessments", "risk_assessment_versions",
            "risk_assessment_hazards", "risk_assessment_controls", "risk_assessment_sign_offs",
            "risk_assessment_review_flags", "risk_observed_activity_types");
    private static final Set<String> S175_POLICED = Set.of("drills", "drill_expectations", "drill_executions",
            "drill_baseline_persons", "drill_roll_call_gaps", "drill_reviews", "drill_findings",
            "drill_corrective_actions", "drill_frequency_requirements");
    private static final Set<String> PHASE_2_POLICED = java.util.stream.Stream.of(S165_POLICED, S175_POLICED)
            .flatMap(Set::stream).collect(java.util.stream.Collectors.toUnmodifiableSet());

    @Autowired private DataSource dataSource;

    private JdbcTemplate jdbc;
    private String siteA;
    private String siteB;

    @BeforeEach
    void seedAsOwner() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER ROLE sfl_app LOGIN PASSWORD '" + PASSWORD + "'");
        siteA = "RLA" + Math.abs(UUID.randomUUID().hashCode() % 100000);
        siteB = "RLB" + Math.abs(UUID.randomUUID().hashCode() % 100000);
        insertAssessment(siteA);
        insertAssessment(siteB);
    }

    @Test
    @DisplayName("an unscoped session sees no assessments - the policy fails closed")
    void unset_scope_sees_nothing() throws SQLException {
        assertThat(sitesVisibleTo(null)).isEmpty();
    }

    @Test
    @DisplayName("a scoped session sees its own site's assessments and not the other's")
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
                throw new AssertionError("an assessment outside the caller's scope must not be insertable");
            } catch (SQLException expected) {
                assertThat(expected.getSQLState()).isEqualTo("42501");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @DisplayName("every site-scoped S165 table carries the policy, and no Phase 1 table has gained one")
    void policy_coverage_is_exactly_the_phase_2_tables() {
        Set<String> policed = new TreeSet<>(jdbc.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'safety_security' AND c.relkind = 'r' AND c.relrowsecurity
                """, String.class));
        Set<String> withPolicy = new TreeSet<>(jdbc.queryForList("""
                SELECT tablename FROM pg_policies
                 WHERE schemaname = 'safety_security' AND policyname = 'site_scope_read'
                """, String.class));
        Set<String> s165SiteScoped = new TreeSet<>(jdbc.queryForList("""
                SELECT DISTINCT table_name FROM information_schema.columns
                 WHERE table_schema = 'safety_security' AND column_name = 'site_code'
                   AND (table_name LIKE 'risk\\_assessment%' OR table_name LIKE 'risk\\_observed%')
                """, String.class));

        assertThat(s165SiteScoped).as("every S165 table with a site_code").isEqualTo(S165_POLICED);
        assertThat(policed).as("tables with RLS enabled").isEqualTo(PHASE_2_POLICED);
        assertThat(withPolicy).as("tables carrying site_scope_read").isEqualTo(PHASE_2_POLICED);
    }

    private void insertAssessment(String site) {
        jdbc.update(insertSql(site));
    }

    private static String insertSql(String site) {
        return "INSERT INTO safety_security.risk_assessments (id, site_code, reference, activity_type, title,"
                + " latest_version, draft_version, created_by, created_at, last_modified_by, last_modified_at,"
                + " source_channel) VALUES ('" + UUID.randomUUID() + "', '" + site + "', 'RA-" + UUID.randomUUID()
                .toString().substring(0, 12) + "', 'HOT_WORK', 'RLS probe', 1, 1, 'rls-test', now(), 'rls-test',"
                + " now(), 'SYSTEM')";
    }

    private List<String> sitesVisibleTo(String scopes) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Connection connection = asApplicationRole()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                if (scopes != null) {
                    statement.execute("SET LOCAL app.site_scopes = '" + scopes + "'");
                }
                try (ResultSet rows = statement.executeQuery("SELECT DISTINCT site_code FROM "
                        + "safety_security.risk_assessments WHERE site_code IN ('" + siteA + "', '" + siteB + "')")) {
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
