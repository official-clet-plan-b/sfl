package gh.edu.clet.sfl.common.security;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public record SiteScopedPrincipal(
        String subjectId,
        String displayName,
        Set<SflRole> roles,
        Set<String> siteScopes,
        boolean serviceAccount,
        String loginName) {

    /** A principal without a separate login name - header actors and service accounts. */
    public SiteScopedPrincipal(String subjectId, String displayName, Set<SflRole> roles, Set<String> siteScopes,
            boolean serviceAccount) {
        this(subjectId, displayName, roles, siteScopes, serviceAccount, null);
    }

    /**
     * {@code loginName} is the name the person signs in with ({@code preferred_username}), when the
     * identity provider gives one. The subject is the stable key; the login name is what an
     * administrator knows and types, so records such as a driver's sign-in link may be keyed on it.
     */
    public SiteScopedPrincipal {
        loginName = loginName == null || loginName.isBlank() ? null : loginName.strip();
        subjectId = normalizeSubject(subjectId);
        displayName = displayName == null || displayName.isBlank() ? subjectId : displayName.strip();
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        siteScopes = normalizeSites(siteScopes);
    }

    public boolean hasRole(SflRole role) {
        return roles.contains(role);
    }

    public boolean hasAnyRole(Set<SflRole> allowedRoles) {
        return roles.stream().anyMatch(allowedRoles::contains);
    }

    public boolean canAccessSite(String siteCode) {
        return siteScopes.contains("*") || siteScopes.contains(normalizeSite(siteCode));
    }

    private static String normalizeSubject(String value) {
        return value == null || value.isBlank() ? "development-user" : value.strip();
    }

    private static Set<String> normalizeSites(Set<String> sites) {
        if (sites == null || sites.isEmpty()) {
            return Set.of();
        }
        return sites.stream()
                .filter(site -> site != null && !site.isBlank())
                .map(SiteScopedPrincipal::normalizeSite)
                .collect(Collectors.toUnmodifiableSet());
    }

    static String normalizeSite(String siteCode) {
        if (siteCode == null || siteCode.isBlank()) {
            return "";
        }
        return siteCode.strip().toUpperCase(Locale.ROOT);
    }
}