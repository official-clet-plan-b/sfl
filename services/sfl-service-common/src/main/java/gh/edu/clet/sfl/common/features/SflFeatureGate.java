package gh.edu.clet.sfl.common.features;

import gh.edu.clet.sfl.common.features.SflFeatureFlagStore.FlagOverride;
import gh.edu.clet.sfl.common.features.SflSystemCatalogue.SystemDefinition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;

/**
 * Applies feature flags to one service's {@link SflSystemCatalogue}.
 *
 * <p>Whether a system is on comes from two places, in this order:
 *
 * <ol>
 *   <li>An administrator's override, saved in the service's database from the dashboard. Takes
 *       effect immediately - no restart, no redeploy.</li>
 *   <li>The deployment default from {@link SflFeatureFlags} (environment variables).</li>
 * </ol>
 *
 * <p>Two answers are kept apart on purpose:
 *
 * <ul>
 *   <li>{@link #isEnabled(String)} - is the system switched on? This is what the UI shows.</li>
 *   <li>{@link #isApiOpen(String)} - may its API be called? True when it is switched on <em>or</em>
 *       when a switched-on system depends on it. Switching off the vehicle register's screens must
 *       not break fuel reconciliation, which reads vehicles.</li>
 * </ul>
 *
 * <p>Overrides are re-read from the store at most every {@link #REFRESH_INTERVAL}, so a change made
 * through another replica is picked up shortly; a change made through this one applies at once.
 */
public final class SflFeatureGate {

    public static final Duration REFRESH_INTERVAL = Duration.ofSeconds(15);

    private static final Logger LOG = LoggerFactory.getLogger(SflFeatureGate.class);

    private final SflSystemCatalogue catalogue;
    private final SflFeatureFlags defaults;
    private final SflFeatureFlagStore store;
    private final Clock clock;
    private final Map<String, SystemDefinition> byCode = new LinkedHashMap<>();
    private final List<Map.Entry<String, String>> patternsBySpecificity;
    private final AntPathMatcher matcher = new AntPathMatcher();

    private volatile State state;
    private volatile Instant loadedAt = Instant.MIN;

    public SflFeatureGate(SflSystemCatalogue catalogue, SflFeatureFlags defaults) {
        this(catalogue, defaults, SflFeatureFlagStore.inMemory(), Clock.systemUTC());
    }

    public SflFeatureGate(SflSystemCatalogue catalogue, SflFeatureFlags defaults, SflFeatureFlagStore store,
            Clock clock) {
        this.catalogue = catalogue;
        this.defaults = defaults;
        this.store = store;
        this.clock = clock;
        catalogue.systems().forEach(system -> byCode.put(SflFeatureFlags.normalise(system.code()), system));
        this.patternsBySpecificity = catalogue.systems().stream()
                .flatMap(system -> system.apiPatterns().stream().map(pattern -> Map.entry(pattern, system.code())))
                // Longest pattern first, so /api/v1/fleet/vehicles/tracking/** wins over /api/v1/fleet/**.
                .sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length()).reversed())
                .toList();
        this.state = State.of(Map.of(), byCode, defaults);
    }

    public boolean isEnabled(String systemCode) {
        return current().enabled.contains(SflFeatureFlags.normalise(systemCode));
    }

    public boolean isApiOpen(String systemCode) {
        return current().openApis.contains(SflFeatureFlags.normalise(systemCode));
    }

    /** Whether this service hosts the system. */
    public boolean hosts(String systemCode) {
        return byCode.containsKey(SflFeatureFlags.normalise(systemCode));
    }

    /** The system that owns a request path, if any. */
    public Optional<String> systemFor(String path) {
        if (catalogue.alwaysOpen().stream().anyMatch(pattern -> matcher.match(pattern, path))) {
            return Optional.empty();
        }
        return patternsBySpecificity.stream()
                .filter(entry -> matcher.match(entry.getKey(), path))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /** Whether a request path may be served. Paths owned by no system are always served. */
    public boolean allows(String path) {
        return systemFor(path).map(this::isApiOpen).orElse(true);
    }

    /** Every system this service hosts, in catalogue order, with whether it is switched on. */
    public Map<String, Boolean> snapshot() {
        State current = current();
        Map<String, Boolean> result = new LinkedHashMap<>();
        catalogue.systems().forEach(system ->
                result.put(system.code(), current.enabled.contains(SflFeatureFlags.normalise(system.code()))));
        return result;
    }

    /** Every system with its full state, for the administration screen. */
    public List<SystemState> details() {
        State current = current();
        return catalogue.systems().stream().map(system -> {
            String code = SflFeatureFlags.normalise(system.code());
            FlagOverride override = current.overrides.get(code);
            return new SystemState(system.code(), system.label(), current.enabled.contains(code),
                    defaults.isEnabled(code), override != null,
                    override == null ? null : override.updatedBy(),
                    override == null ? null : override.updatedAt(),
                    List.copyOf(system.requires()));
        }).toList();
    }

    /** Switches a system on or off, persisting the choice. Applies immediately. */
    public synchronized void set(String systemCode, boolean enabled, String actor) {
        String code = requireHosted(systemCode);
        store.save(code, enabled, actor, clock.instant());
        reload();
        LOG.info("Feature flag {} set to {} by {}", code, enabled ? "ON" : "OFF", actor);
    }

    /** Drops an override, so the deployment default applies again. */
    public synchronized void reset(String systemCode, String actor) {
        String code = requireHosted(systemCode);
        store.clear(code);
        reload();
        LOG.info("Feature flag {} reset to its deployment default by {}", code, actor);
    }

    public SflSystemCatalogue catalogue() {
        return catalogue;
    }

    private String requireHosted(String systemCode) {
        String code = SflFeatureFlags.normalise(systemCode);
        if (!byCode.containsKey(code)) {
            throw new IllegalArgumentException("This service does not host system " + systemCode);
        }
        return code;
    }

    private State current() {
        if (Duration.between(loadedAt, clock.instant()).compareTo(REFRESH_INTERVAL) >= 0) {
            synchronized (this) {
                if (Duration.between(loadedAt, clock.instant()).compareTo(REFRESH_INTERVAL) >= 0) {
                    try {
                        reload();
                    } catch (RuntimeException failure) {
                        // Keep serving the last known state; a database blip must not flip modules.
                        LOG.warn("Could not reload feature flags; keeping the last known state: {}",
                                failure.getMessage());
                        loadedAt = clock.instant();
                    }
                }
            }
        }
        return state;
    }

    private void reload() {
        Map<String, FlagOverride> overrides = new HashMap<>();
        store.load().forEach((code, override) -> overrides.put(SflFeatureFlags.normalise(code), override));
        state = State.of(overrides, byCode, defaults);
        loadedAt = clock.instant();
    }

    /** One system's state, as the administration screen shows it. */
    public record SystemState(String code, String label, boolean enabled, boolean defaultEnabled,
            boolean overridden, String updatedBy, Instant updatedAt, List<String> requires) {
    }

    private record State(Map<String, FlagOverride> overrides, Set<String> enabled, Set<String> openApis) {

        static State of(Map<String, FlagOverride> overrides, Map<String, SystemDefinition> byCode,
                SflFeatureFlags defaults) {
            Set<String> enabled = new HashSet<>();
            byCode.keySet().forEach(code -> {
                FlagOverride override = overrides.get(code);
                if (override != null ? override.enabled() : defaults.isEnabled(code)) {
                    enabled.add(code);
                }
            });

            Set<String> open = new HashSet<>();
            Deque<String> pending = new ArrayDeque<>(enabled);
            while (!pending.isEmpty()) {
                String code = pending.pop();
                if (!open.add(code)) {
                    continue;
                }
                SystemDefinition system = byCode.get(code);
                if (system != null) {
                    system.requires().stream().map(SflFeatureFlags::normalise).forEach(pending::add);
                }
            }
            return new State(Map.copyOf(overrides), Set.copyOf(enabled), Set.copyOf(open));
        }
    }
}
