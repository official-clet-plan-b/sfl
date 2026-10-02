package gh.edu.clet.sfl.safetysecurity.drill.domain.policy;

import gh.edu.clet.sfl.safetysecurity.drill.domain.model.ComplianceStanding;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.FrequencyRequirement;
import java.time.Duration;
import java.time.Instant;

/**
 * SRS-SFL-S175-04: the next drill of a type is due one interval after the last completed, reviewed drill of
 * that type at the site - or, when there has never been one, one interval after the requirement took effect.
 * Cancelled and postponed drills never count; the caller passes only drills whose status
 * {@code countsTowardCompliance()}.
 */
public final class FrequencyCompliancePolicy {

    private FrequencyCompliancePolicy() {
    }

    public static Instant dueAt(FrequencyRequirement requirement, Instant lastCountedDrillAt) {
        Instant anchor = lastCountedDrillAt == null ? requirement.effectiveFrom() : lastCountedDrillAt;
        return anchor.plus(Duration.ofDays(requirement.intervalDays()));
    }

    public static ComplianceStanding standing(FrequencyRequirement requirement, Instant dueAt, Instant now) {
        if (now.isAfter(dueAt)) {
            return ComplianceStanding.COMPLIANCE_GAP;
        }
        if (!now.isBefore(dueAt.minus(Duration.ofDays(requirement.warningDays())))) {
            return ComplianceStanding.DUE_SOON;
        }
        return ComplianceStanding.COMPLIANT;
    }
}
