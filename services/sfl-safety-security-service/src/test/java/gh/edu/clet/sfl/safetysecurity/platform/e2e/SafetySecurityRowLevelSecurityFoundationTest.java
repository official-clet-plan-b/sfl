package gh.edu.clet.sfl.safetysecurity.platform.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The V18 row-level-security mechanism itself, proved against {@code sfl_app} - ADR 0010.
 *
 * <p>A probe table the test creates and drops, so what is proved is the function and the policy rather
 * than any one module's tables (each Phase 2 module proves its own). Connects as {@code sfl_app}
 * explicitly for the reason facilities' {@code FacilitiesRowLevelSecurityTest} gives: the schema owner
 * bypasses RLS, so a test running as the owner would prove nothing.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=false",
        "sfl.safety-security.messaging.drainer-enabled=false"
})
@EnabledIf(value = "gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available")
class SafetySecurityRowLevelSecurityFoundationTest extends SafetySecurityPostgresSupport {

    private static final String PASSWORD = "rls-test-ssemp-foundation";

    @Autowired private DataSource dataSource;

    private JdbcTemplate jdbc;
    private String probe;

    @BeforeEach
    void createProbe() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER ROLE sfl_app LOGIN PASSWORD '" + PASSWORD + "'");
        probe = "rls_probe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.execute("CREATE TABLE safety_security." + probe + " (id UUID PRIMARY KEY, site_code VARCHAR(80))");
        jdbc.queryForObject("SELECT safety_security.apply_site_scope_policies(ARRAY['" + probe + "'])",
                Integer.class);
        jdbc.update("INSERT INTO safety_security." + probe + " VALUES (?, 'SITE-A')", UUID.randomUUID());
        jdbc.update("INSERT INTO safety_security." + probe + " VALUES (?, 'SITE-B')", UUID.randomUUID());
    }

    @AfterEach
    void dropProbe() {
        jdbc.execute("DROP TABLE IF EXISTS safety_security." + probe);
    }

    @Test
    @DisplayName("an unscoped session sees nothing - the policy fails closed")
    void unset_scope_sees_nothing() throws SQLException {
        assertThat(sitesVisibleTo(null)).isEmpty();
    }

    @Test
    @DisplayName("a scoped session sees its own site and not the other")
    void one_scope_sees_one_site() throws SQLException {
        assertThat(sitesVisibleTo("SITE-A")).containsExactly("SITE-A");
    }

    @Test
    @DisplayName("the cross-site scope sees across")
    void star_sees_everything() throws SQLException {
        assertThat(sitesVisibleTo("*")).containsExactlyInAnyOrder("SITE-A", "SITE-B");
    }

    @Test
    @DisplayName("a write outside the scope is refused by WITH CHECK, not silently dropped")
    void a_write_outside_scope_is_refused() throws SQLException {
        try (Connection connection = asApplicationRole()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL app.site_scopes = 'SITE-A'");
                statement.executeUpdate("INSERT INTO safety_security." + probe + " VALUES ('"
                        + UUID.randomUUID() + "', 'SITE-B')");
                throw new AssertionError("a row outside the caller's scope must not be insertable");
            } catch (SQLException expected) {
                assertThat(expected.getSQLState()).isEqualTo("42501");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @DisplayName("naming a table without a site_code is an error, never a silent skip")
    void a_table_without_site_code_is_refused() {
        String unscoped = probe + "_x";
        jdbc.execute("CREATE TABLE safety_security." + unscoped + " (id UUID PRIMARY KEY)");
        try {
            assertThatThrownBy(() -> jdbc.queryForObject(
                    "SELECT safety_security.apply_site_scope_policies(ARRAY['" + unscoped + "'])", Integer.class))
                    .isInstanceOf(UncategorizedSQLException.class)
                    .hasMessageContaining("has no site_code column");
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS safety_security." + unscoped);
        }
    }

    @Test
    @DisplayName("the platform tables sfl_app needs are usable without a policy")
    void sfl_app_can_use_the_outbox_and_audit_chain() throws SQLException {
        try (Connection connection = asApplicationRole()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement();
                    ResultSet outbox = statement.executeQuery(
                            "SELECT COUNT(*) FROM safety_security.outbox_messages")) {
                assertThat(outbox.next()).isTrue();
            }
            try (Statement statement = connection.createStatement();
                    ResultSet audit = statement.executeQuery("SELECT COUNT(*) FROM safety_security.audit_log")) {
                assertThat(audit.next()).isTrue();
            }
            connection.rollback();
        }
    }

    private List<String> sitesVisibleTo(String scopes) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Connection connection = asApplicationRole()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                if (scopes != null) {
                    statement.execute("SET LOCAL app.site_scopes = '" + scopes + "'");
                }
                try (ResultSet rows = statement.executeQuery(
                        "SELECT site_code FROM safety_security." + probe + " ORDER BY site_code")) {
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
