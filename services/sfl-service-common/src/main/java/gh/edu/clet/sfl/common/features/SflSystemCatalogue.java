package gh.edu.clet.sfl.common.features;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The systems one service hosts, and which of its API paths belong to each.
 *
 * <p>Each service declares one of these as a bean. That is what makes a feature flag mean something
 * on the server: the {@link SflFeatureGateFilter} uses the path patterns to refuse calls into a
 * switched-off system, and {@link SflFeatureGate#snapshot()} reports the systems to the UI.
 *
 * @param systems the systems this service hosts, each with its API path patterns
 * @param alwaysOpen path patterns no flag may close - system info, actor permissions, the feature
 *     report itself
 */
public record SflSystemCatalogue(List<SystemDefinition> systems, List<String> alwaysOpen) {

    public SflSystemCatalogue {
        systems = List.copyOf(Objects.requireNonNull(systems, "systems"));
        alwaysOpen = List.copyOf(Objects.requireNonNull(alwaysOpen, "alwaysOpen"));
    }

    /**
     * One system.
     *
     * @param code the system code, e.g. {@code S166}
     * @param label what the system is called
     * @param apiPatterns Ant-style path patterns served by this system
     * @param requires systems whose API this one calls. Switching this system on keeps those APIs
     *     reachable even when their own screens are switched off - fuel reads the vehicle register,
     *     booking reads the estate.
     */
    public record SystemDefinition(String code, String label, List<String> apiPatterns, Set<String> requires) {

        public SystemDefinition {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(label, "label");
            apiPatterns = List.copyOf(apiPatterns);
            requires = Set.copyOf(requires);
        }

        public static SystemDefinition of(String code, String label, List<String> apiPatterns, String... requires) {
            return new SystemDefinition(code, label, apiPatterns, Set.of(requires));
        }
    }
}
