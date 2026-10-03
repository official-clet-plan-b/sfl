package gh.edu.clet.sfl.safetysecurity.permit.domain.policy;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.common.security.SflRole;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Additive S164 role -> permission mapping, following {@code DrillPermissionMatrix}'s shape. No new role: the SRS's
 * Requester, Permit Authoriser, SOC Operator and HSE Director are existing SSEMP and IFIMP roles.
 *
 * <p>The independence rules - the requester never approves or verifies their own permit, the safety sign-off is never
 * the issuing authority - are about people, not roles, and are enforced per record by {@code ApprovalPolicy}.
 */
public final class PermitPermissionMatrix {

    private static final Map<SflRole, Set<SflPermission>> MATRIX = build();

    private PermitPermissionMatrix() {
    }

    public static boolean grants(Set<SflRole> roles, SflPermission permission) {
        return roles != null && roles.stream().anyMatch(r -> MATRIX.getOrDefault(r, Set.of()).contains(permission));
    }

    private static Map<SflRole, Set<SflPermission>> build() {
        Map<SflRole, Set<SflPermission>> m = new EnumMap<>(SflRole.class);
        EnumSet<SflPermission> all = EnumSet.noneOf(SflPermission.class);
        for (SflPermission p : SflPermission.values()) {
            if (p.name().startsWith("PERMIT_")) {
                all.add(p);
            }
        }
        m.put(SflRole.SFL_ADMIN, all);

        // HSE Director / Manager: the permit authoriser, and the owner of types, analytics and statutory evidence.
        m.put(SflRole.HSE_MANAGER, EnumSet.copyOf(all));

        // Security Director: independent safety sign-off and suspension; reads everything, owns no configuration.
        m.put(SflRole.SECURITY_DIRECTOR, EnumSet.of(SflPermission.PERMIT_READ, SflPermission.PERMIT_SAFETY_SIGN_OFF,
                SflPermission.PERMIT_SUSPEND, SflPermission.PERMIT_ANALYTICS_READ));

        // Facilities Director: issuing authority for facilities work, and may request.
        m.put(SflRole.FACILITIES_DIRECTOR, EnumSet.of(SflPermission.PERMIT_READ, SflPermission.PERMIT_REQUEST,
                SflPermission.PERMIT_APPROVE, SflPermission.PERMIT_SUSPEND, SflPermission.PERMIT_ANALYTICS_READ));

        // SOC Operator (S164-03): sees active permits and suspends one at once if conditions change.
        m.put(SflRole.SOC_OPERATOR, EnumSet.of(SflPermission.PERMIT_READ, SflPermission.PERMIT_SUSPEND));

        // Competent persons who verify isolations and record their removal - never for their own request.
        m.put(SflRole.FACILITIES_ENGINEER, EnumSet.of(SflPermission.PERMIT_READ, SflPermission.PERMIT_REQUEST,
                SflPermission.PERMIT_VERIFY_ISOLATION));
        m.put(SflRole.IFIMP_MAINTENANCE_SUPERVISOR, EnumSet.of(SflPermission.PERMIT_READ, SflPermission.PERMIT_REQUEST,
                SflPermission.PERMIT_VERIFY_ISOLATION));

        // Requesters: staff and contractor supervisors raise permits for their own work.
        m.put(SflRole.FACILITIES_MANAGER, EnumSet.of(SflPermission.PERMIT_READ, SflPermission.PERMIT_REQUEST));
        m.put(SflRole.CONSTRUCTION_PROJECT_MANAGER, EnumSet.of(SflPermission.PERMIT_READ, SflPermission.PERMIT_REQUEST));
        m.put(SflRole.VENDOR_TECHNICIAN, EnumSet.of(SflPermission.PERMIT_READ, SflPermission.PERMIT_REQUEST));

        // Compliance Officer: statutory evidence - reads, analyses and exports; changes nothing.
        m.put(SflRole.COMPLIANCE_OFFICER, EnumSet.of(SflPermission.PERMIT_READ, SflPermission.PERMIT_ANALYTICS_READ,
                SflPermission.PERMIT_EXPORT));

        // Command: cross-programme oversight, as on every SSEMP system.
        m.put(SflRole.COMMAND_ROLE, EnumSet.of(SflPermission.PERMIT_READ, SflPermission.PERMIT_ANALYTICS_READ));

        m.replaceAll((r, p) -> Set.copyOf(p));
        return Map.copyOf(m);
    }
}
