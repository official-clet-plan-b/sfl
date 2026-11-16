package gh.edu.clet.sfl.common.features;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Where runtime feature-flag overrides are kept - one row per system an administrator has changed.
 *
 * <p>No row means the deployment default applies, so "reset to default" is a delete.
 */
public interface SflFeatureFlagStore {

    Map<String, FlagOverride> load();

    void save(String systemCode, boolean enabled, String updatedBy, Instant updatedAt);

    void clear(String systemCode);

    record FlagOverride(boolean enabled, String updatedBy, Instant updatedAt) {
    }

    /** Keeps overrides in memory only. For tests, and services with no database. */
    static SflFeatureFlagStore inMemory() {
        Map<String, FlagOverride> rows = new ConcurrentHashMap<>();
        return new SflFeatureFlagStore() {
            @Override
            public Map<String, FlagOverride> load() {
                return Map.copyOf(rows);
            }

            @Override
            public void save(String systemCode, boolean enabled, String updatedBy, Instant updatedAt) {
                rows.put(systemCode, new FlagOverride(enabled, updatedBy, updatedAt));
            }

            @Override
            public void clear(String systemCode) {
                rows.remove(systemCode);
            }
        };
    }

    /** The service's {@code <schema>.sfl_feature_flags} table. */
    final class Jdbc implements SflFeatureFlagStore {

        private static final Pattern TABLE = Pattern.compile("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?");

        private final JdbcTemplate jdbc;
        private final String table;

        public Jdbc(JdbcTemplate jdbc, String table) {
            this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
            if (table == null || !TABLE.matcher(table).matches()) {
                throw new IllegalArgumentException("Invalid feature flag table name: " + table);
            }
            this.table = table;
        }

        @Override
        public Map<String, FlagOverride> load() {
            Map<String, FlagOverride> rows = new HashMap<>();
            jdbc.query("SELECT system_code, enabled, updated_by, updated_at FROM " + table, rs -> {
                rows.put(rs.getString("system_code"), new FlagOverride(rs.getBoolean("enabled"),
                        rs.getString("updated_by"), rs.getTimestamp("updated_at").toInstant()));
            });
            return rows;
        }

        @Override
        public void save(String systemCode, boolean enabled, String updatedBy, Instant updatedAt) {
            jdbc.update("INSERT INTO " + table + " (system_code, enabled, updated_by, updated_at) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT (system_code) DO UPDATE SET enabled = EXCLUDED.enabled, "
                    + "updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at",
                    systemCode, enabled, updatedBy, Timestamp.from(updatedAt));
        }

        @Override
        public void clear(String systemCode) {
            jdbc.update("DELETE FROM " + table + " WHERE system_code = ?", systemCode);
        }
    }
}
