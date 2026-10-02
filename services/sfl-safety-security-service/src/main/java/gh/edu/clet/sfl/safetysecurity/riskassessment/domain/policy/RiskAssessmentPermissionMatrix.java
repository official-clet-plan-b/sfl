package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.policy;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.common.security.SflRole;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Additive S165 role -> permission mapping, following {@code LifeSafetyPermissionMatrix}'s shape.
 *
 * <p>No new role. The Phase 2 SRS's user class for this system is "HSE Officer / Director - authors and
 * reviews risk assessments" (§2.3), which is {@link SflRole#HSE_MANAGER}'s remit already (S163, S162a).
 * S165-02's independence rule is about <em>people</em> - the reviewer is not the author - not about roles,
 * so two HSE managers satisfy it and one does not; {@code SignOffPolicy} enforces that per record.
 */
public final class RiskAssessmentPermissionMatrix {

    private static final Map<SflRole, Set<SflPermission>> MATRIX = build();

    private RiskAssessmentPermissionMatrix() {
    }

    public static boolean grants(Set<SflRole> roles, SflPermission permission) {
        return roles != null && roles.stream().anyMatch(r -> MATRIX.getOrDefault(r, Set.of()).contains(permission));
    }

    private static Map<SflRole, Set<SflPermission>> build() {
        Map<SflRole, Set<SflPermission>> m = new EnumMap<>(SflRole.class);
        EnumSet<SflPermission> all = EnumSet.noneOf(SflPermission.class);
        for (var p : SflPermission.values()) {
            if (p.name().startsWith("RISK_ASSESSMENT_")) {
                all.add(p);
            }
        }
        m.put(SflRole.SFL_ADMIN, all);

        // HSE Officer / Director (§2.3; S165-01, -02, -03, -04): authors, publishes, signs off, works the
        // review-flag queue, reads the analytics and sets the review cycle.
        m.put(SflRole.HSE_MANAGER, EnumSet.copyOf(all));

        // Security Director / SOC (§2.3): plans drills and monitors active permits, both of which stand on
        // a current assessment - sees the library and its coverage, authors nothing.
        m.put(SflRole.SECURITY_DIRECTOR, EnumSet.of(SflPermission.RISK_ASSESSMENT_READ,
                SflPermission.RISK_ASSESSMENT_ANALYTICS_READ));

        // Investigator (S165-04): an incident is linked to the assessment it happened under, so whoever
        // investigates it reads that assessment. Clearing the flag is HSE's review, not theirs.
        m.put(SflRole.INCIDENT_INVESTIGATOR, EnumSet.of(SflPermission.RISK_ASSESSMENT_READ));

        // Compliance Officer (merged with the former Auditor): statutory evidence that high-risk work stood
        // on a current, signed-off assessment - reads everything, changes nothing.
        m.put(SflRole.COMPLIANCE_OFFICER, EnumSet.of(SflPermission.RISK_ASSESSMENT_READ,
                SflPermission.RISK_ASSESSMENT_ANALYTICS_READ));

        // Command (cross-programme oversight, as on every other SSEMP system): reads, changes nothing.
        m.put(SflRole.COMMAND_ROLE, EnumSet.of(SflPermission.RISK_ASSESSMENT_READ,
                SflPermission.RISK_ASSESSMENT_ANALYTICS_READ));

        // The two IFIMP roles whose systems the mapping lists as S165 consumers (S176 construction, and
        // S173, whose S173-03 requires a linked, current assessment for a higher-risk event): each has to
        // find the current assessment to link it. Read only.
        m.put(SflRole.CONSTRUCTION_PROJECT_MANAGER, EnumSet.of(SflPermission.RISK_ASSESSMENT_READ));
        m.put(SflRole.EVENT_LOGISTICS_COORDINATOR, EnumSet.of(SflPermission.RISK_ASSESSMENT_READ));

        m.replaceAll((r, p) -> Set.copyOf(p));
        return Map.copyOf(m);
    }
}
