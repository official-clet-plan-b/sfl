package gh.edu.clet.sfl.facilities.registers;

import static org.assertj.core.api.Assertions.assertThat;

import gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Row-level security on {@code facilities.register_records}, from the database's own side (ADR 0007).
 *
 * <p>The service checks site scope itself, but the table was created without the policy, so anything
 * that read it directly - a report, a console, a future endpoint that forgot - saw every site. The
 * coverage test names a table that lacks the policy; this proves the policy does what it should: an
 * unscoped session sees nothing, a scoped one sees its own site, and a write for another site is
 * refused rather than dropped. Seeded as the owner, because the point is that data which exists is
 * nonetheless invisible.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sfl.facilities.messaging.drainer-enabled=false",
        "sfl.maintenance.scheduling.enabled=false",
        "sfl.booking.scheduling.enabled=false",
})
@EnabledIf(value = "gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available; see FacilitiesPostgresSupport.unavailableReason()")
class RegisterRecordsRowLevelSecurityTest {

    private static final String PASSWORD = "rls-test-registers";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        FacilitiesPostgresSupport.datasource(registry);
    }

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private String siteA;
    private String siteB;

    @BeforeEach
    void seedAsOwner() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER ROLE sfl_app LOGIN PASSWORD '" + PASSWORD + "'");
        siteA = "P3A" + Math.abs(UUID.randomUUID().hashCode() % 100000);
        siteB = "P3B" + Math.abs(UUID.randomUUID().hashCode() % 100000);
        insert(siteA, "RLS-P3-" + siteA);
        insert(siteB, "RLS-P3-" + siteB);
    }

    @Test
    @DisplayName("an unscoped session sees no register record")
    void unscoped_session_sees_nothing() throws SQLException {
        assertThat(titlesVisibleTo(null)).isEmpty();
    }

    @Test
    @DisplayName("a scoped session sees only its own site's records")
    void scoped_session_sees_only_its_site() throws SQLException {
        List<String> visible = titlesVisibleTo(siteA);

        assertThat(visible).contains("RLS-P3-" + siteA);
        assertThat(visible).doesNotContain("RLS-P3-" + siteB);
    }

    @Test
    @DisplayName("the cross-site scope sees every site's records")
    void star_scope_sees_every_site() throws SQLException {
        assertThat(titlesVisibleTo("*")).contains("RLS-P3-" + siteA, "RLS-P3-" + siteB);
    }

    @Test
    @DisplayName("a write outside the caller's scope is refused with 42501, not silently dropped")
    void write_outside_scope_is_refused() throws SQLException {
        try (Connection connection = asApplicationRole()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL app.site_scopes = '" + siteA + "'");
                statement.executeUpdate(insertSql(siteB, "RLS-FORBIDDEN"));
                throw new AssertionError("a record for a site outside the caller's scope must not be insertable");
            } catch (SQLException expected) {
                assertThat(expected.getSQLState()).isEqualTo("42501");
            } finally {
                connection.rollback();
            }
        }
    }

    private void insert(String siteCode, String title) {
        jdbc.update(insertSql(siteCode, title));
    }

    private static String insertSql(String siteCode, String title) {
        Timestamp now = Timestamp.from(Instant.now());
        return "INSERT INTO facilities.register_records (id, system_code, site_code, record_type, title, status,"
                + " created_by, created_at, updated_at, version) VALUES ('" + UUID.randomUUID() + "', 'S170', '"
                + siteCode + "', 'HYGIENE_AUDIT', '" + title + "', 'PLANNED', 'rls-seed', '" + now + "', '" + now
                + "', 0)";
    }

    private List<String> titlesVisibleTo(String scopes) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Connection connection = asApplicationRole()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                if (scopes != null) {
                    statement.execute("SET LOCAL app.site_scopes = '" + scopes + "'");
                }
                try (ResultSet rows = statement.executeQuery(
                        "SELECT title FROM facilities.register_records WHERE title LIKE 'RLS-P3-%'")) {
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
