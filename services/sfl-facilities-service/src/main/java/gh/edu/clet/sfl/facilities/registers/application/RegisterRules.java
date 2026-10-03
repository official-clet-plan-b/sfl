package gh.edu.clet.sfl.facilities.registers.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** System-specific vocabulary and lifecycle rules for S172, S177 and S179 (S170 and S178 have their own modules). */
final class RegisterRules {

    private static final Map<String, Set<String>> TYPES = Map.of(
            "S172", Set.of("SERVICE", "MENU", "DIETARY_REQUIREMENT", "SUPPLIER_CHECK", "FOOD_SAFETY_CHECK", "RECONCILIATION", "VARIANCE"),
            "S177", Set.of("LEASE", "TENANCY", "OBLIGATION", "RENEWAL", "RENT_REVIEW", "AMENDMENT", "TERMINATION"),
            "S179", Set.of("FOUND_ITEM", "STORAGE", "CLAIM", "VERIFICATION", "CUSTODY_TRANSFER", "RELEASE", "DISPOSAL"));

    private static final Map<String, Set<String>> STATUSES = Map.of(
            "S172", Set.of("DRAFT", "PENDING_APPROVAL", "CONFIRMED", "DELIVERED", "RECONCILIATION_PENDING", "CLOSED"),
            "S177", Set.of("DRAFT", "ACTIVE", "DUE", "PENDING_APPROVAL", "RENEWED", "TERMINATED"),
            "S179", Set.of("INTAKE", "STORED", "CLAIM_PENDING", "RELEASED", "DISPOSED", "CLOSED"));

    private RegisterRules() {
    }

    static void validate(String systemCode, String recordType, String status) {
        if (!TYPES.getOrDefault(systemCode, Set.of()).contains(recordType)) {
            throw new IllegalArgumentException("Record type " + recordType + " is not valid for " + systemCode);
        }
        if (!STATUSES.getOrDefault(systemCode, Set.of()).contains(status)) {
            throw new IllegalArgumentException("Status " + status + " is not valid for " + systemCode);
        }
    }

    static SflPermission readPermission(String systemCode) {
        return SflPermission.FACILITIES_SITE_READ;
    }

    static SflPermission writePermission(String systemCode) {
        return SflPermission.FACILITIES_CONFIG_MANAGE;
    }

    /**
     * The record types a list asks for: one, or several separated by commas, or none for every type.
     *
     * <p>A screen that groups two kinds of record under one tab has to be able to ask for both. A type the system does not have is a bad request, not an empty list.
     */
    static List<String> parseTypes(String systemCode, String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> types = Arrays.stream(raw.split(",")).map(String::strip).filter(type -> !type.isEmpty())
                .map(type -> type.toUpperCase(java.util.Locale.ROOT)).distinct().toList();
        for (String type : types) {
            if (!TYPES.getOrDefault(systemCode, Set.of()).contains(type)) {
                throw new IllegalArgumentException("Record type " + type + " is not valid for " + systemCode);
            }
        }
        return types;
    }

    static boolean validStatus(String systemCode, String status) {
        return STATUSES.getOrDefault(systemCode, Set.of()).contains(status);
    }
}
