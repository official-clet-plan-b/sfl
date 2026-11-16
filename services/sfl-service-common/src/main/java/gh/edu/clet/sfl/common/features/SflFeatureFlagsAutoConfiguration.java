package gh.edu.clet.sfl.common.features;

import java.time.Clock;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires feature flags into any service that declares an {@link SflSystemCatalogue}.
 *
 * <p>A service with no catalogue (the portal) gets nothing, and behaves exactly as before. When
 * {@code sfl.features.table} names a table, administrator overrides are kept there and survive a
 * restart; without it they live in memory only.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(SflSystemCatalogue.class)
public class SflFeatureFlagsAutoConfiguration {

    public static final String TABLE_PROPERTY = "sfl.features.table";

    @Bean
    SflFeatureGate sflFeatureGate(SflSystemCatalogue catalogue, Environment environment,
            ObjectProvider<JdbcTemplate> jdbc) {
        SflFeatureFlags defaults = SflFeatureFlags.of(
                environment.getProperty(SflFeatureFlags.ENABLED_PROPERTY, ""),
                environment.getProperty(SflFeatureFlags.DISABLED_PROPERTY, ""));
        String table = environment.getProperty(TABLE_PROPERTY, "");
        JdbcTemplate template = jdbc.getIfAvailable();
        SflFeatureFlagStore store = table.isBlank() || template == null
                ? SflFeatureFlagStore.inMemory()
                : new SflFeatureFlagStore.Jdbc(template, table);
        SflFeatureGate gate = new SflFeatureGate(catalogue, defaults, store, Clock.systemUTC());
        LoggerFactory.getLogger(getClass()).info("SFL feature flag defaults: {}; overrides kept in {}",
                catalogue.systems().stream().map(system -> system.code() + "=" + defaults.isEnabled(system.code()))
                        .toList(),
                table.isBlank() || template == null ? "memory" : table);
        return gate;
    }

    @Bean
    SflFeatureFlagsApi sflFeatureFlagsApi(SflFeatureGate gate) {
        return new SflFeatureFlagsApi(gate);
    }

    @Bean
    FilterRegistrationBean<SflFeatureGateFilter> sflFeatureGateFilter(SflFeatureGate gate) {
        FilterRegistrationBean<SflFeatureGateFilter> registration =
                new FilterRegistrationBean<>(new SflFeatureGateFilter(gate));
        // After Spring Security's chain (order -100), so authentication is decided first.
        registration.setOrder(0);
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
