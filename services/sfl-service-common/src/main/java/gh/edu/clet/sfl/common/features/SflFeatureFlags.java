package gh.edu.clet.sfl.common.features;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which SFL systems are switched on in this deployment.
 *
 * <p>Read at startup from two comma-separated settings, so Infra can turn a module on or off by
 * changing an environment variable and restarting the service - no rebuild of the image or the UI:
 *
 * <ul>
 *   <li>{@code SFL_FEATURES_ENABLED_SYSTEMS} ({@code sfl.features.enabled-systems}) - an allow-list.
 *       Blank means every system is on, which keeps existing deployments unchanged.</li>
 *   <li>{@code SFL_FEATURES_DISABLED_SYSTEMS} ({@code sfl.features.disabled-systems}) - a deny-list
 *       applied after the allow-list. Useful for switching off one module in an otherwise full
 *       deployment.</li>
 * </ul>
 *
 * <p>Codes are matched case-insensitively ({@code S168a} and {@code S168A} are the same system).
 */
public final class SflFeatureFlags {

    public static final String ENABLED_PROPERTY = "sfl.features.enabled-systems";
    public static final String DISABLED_PROPERTY = "sfl.features.disabled-systems";

    private final Set<String> enabled;
    private final Set<String> disabled;

    private SflFeatureFlags(Set<String> enabled, Set<String> disabled) {
        this.enabled = enabled;
        this.disabled = disabled;
    }

    public static SflFeatureFlags of(String enabledSystems, String disabledSystems) {
        return new SflFeatureFlags(parse(enabledSystems), parse(disabledSystems));
    }

    /** Every system on - the default when nothing is configured. */
    public static SflFeatureFlags allEnabled() {
        return new SflFeatureFlags(Set.of(), Set.of());
    }

    public boolean isEnabled(String systemCode) {
        String code = normalise(systemCode);
        if (disabled.contains(code)) {
            return false;
        }
        return enabled.isEmpty() || enabled.contains(code);
    }

    static String normalise(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private static Set<String> parse(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(SflFeatureFlags::normalise)
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
