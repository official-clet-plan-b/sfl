package gh.edu.clet.sfl.safetysecurity.drill.domain.policy;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.common.security.SflRole;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Additive S175 role -> permission mapping, following {@code RiskAssessmentPermissionMatrix}'s shape.
 *
 * <p>No new role. The SRS's user stories name the HSE / Security Officer (plans and triggers), the SOC Operator
 * (runs roll-call), the HSE Director (reviews and tracks corrective actions) and the Security Director
 * (frequency compliance and combined drills) - all existing SSEMP roles.
 */
public final class DrillPermissionMatrix {

    private static final Map<SflRole, Set<SflPermission>> MATRIX = build();

    private DrillPermissionMatrix() {
    }

    public static boolean grants(Set<SflRole> roles, SflPermission permission) {
        return roles != null && roles.stream().anyMatch(r -> MATRIX.getOrDefault(r, Set.of()).contains(permission));
    }

    private static Map<SflRole, Set<SflPermission>> build() {
        Map<SflRole, Set<SflPermission>> m = new EnumMap<>(SflRole.class);
        EnumSet<SflPermission> all = EnumSet.noneOf(SflPermission.class);
        for (var p : SflPermission.values()) {
            if (p.name().startsWith("DRILL_")) {
                all.add(p);
            }
        }
        m.put(SflRole.SFL_ADMIN, all);

        // HSE Officer / Director (S175-01, -03): plans drills, runs them, reviews them, tracks their corrective
        // actions to closure and sets the statutory frequencies.
        m.put(SflRole.HSE_MANAGER, EnumSet.copyOf(all));

        // Security Director (S175-04, -05): owns frequency compliance and plans combined drills across modules.
        m.put(SflRole.SECURITY_DIRECTOR, EnumSet.copyOf(all));

        // SOC Operator (S175-02): runs the drill on the day - starts it, takes the roll-call, follows up gaps.
        m.put(SflRole.SOC_OPERATOR, EnumSet.of(SflPermission.DRILL_READ, SflPermission.DRILL_EXECUTE));

        // Emergency Coordinator: holds S162a muster check-in in a real evacuation, so does the same in a drill,
        // and the drill notification goes out on their S174 path.
        m.put(SflRole.EMERGENCY_COORDINATOR, EnumSet.of(SflPermission.DRILL_READ, SflPermission.DRILL_EXECUTE));

        // Compliance Officer (co-owner per the mapping: C&A): statutory evidence - reads drills and compliance.
        m.put(SflRole.COMPLIANCE_OFFICER, EnumSet.of(SflPermission.DRILL_READ,
                SflPermission.DRILL_COMPLIANCE_READ));

        // Command (cross-programme oversight, as on every other SSEMP system): reads, changes nothing.
        m.put(SflRole.COMMAND_ROLE, EnumSet.of(SflPermission.DRILL_READ, SflPermission.DRILL_COMPLIANCE_READ));

        m.replaceAll((r, p) -> Set.copyOf(p));
        return Map.copyOf(m);
    }
}
