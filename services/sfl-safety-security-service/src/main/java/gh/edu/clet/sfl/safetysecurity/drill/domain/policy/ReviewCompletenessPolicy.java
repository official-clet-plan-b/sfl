package gh.edu.clet.sfl.safetysecurity.drill.domain.policy;

import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillErrorCode;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillFinding;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillPlan;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillReview;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.ModuleExpectation;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.RollCallGap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * When an after-action review is complete enough to submit:
 * <ul>
 *   <li>it has a summary;</li>
 *   <li>every roll-call gap has been followed up (S175-02: "gap list carried into after-action report");</li>
 *   <li>every module expectation has a recorded outcome (S175-05's acceptance criterion);</li>
 *   <li>every finding has a corrective action or a no-action justification - otherwise "Unactioned Finding"
 *       (S175-03's acceptance criterion).</li>
 * </ul>
 */
public final class ReviewCompletenessPolicy {

    private ReviewCompletenessPolicy() {
    }

    public static void requireSubmittable(DrillReview review, DrillPlan plan, List<RollCallGap> gaps,
            List<DrillFinding> findings, Set<UUID> findingsWithActions) {
        if (review == null || review.summary() == null) {
            throw new DrillException(DrillErrorCode.DRILL_REVIEW_SUMMARY_REQUIRED);
        }
        List<String> openGaps = gaps.stream().filter(RollCallGap::open).map(RollCallGap::personRef).toList();
        if (!openGaps.isEmpty()) {
            throw new DrillException(DrillErrorCode.DRILL_GAPS_UNRESOLVED, Map.of("personRefs", openGaps));
        }
        List<String> unjudged = plan.expectations().stream().filter(e -> e.outcome() == null)
                .map(ModuleExpectation::module).map(Enum::name).toList();
        if (!unjudged.isEmpty()) {
            throw new DrillException(DrillErrorCode.DRILL_EXPECTATION_OUTCOME_REQUIRED, Map.of("modules", unjudged));
        }
        List<String> unactioned = unactioned(findings, findingsWithActions).stream()
                .map(f -> f.id().toString()).toList();
        if (!unactioned.isEmpty()) {
            throw new DrillException(DrillErrorCode.DRILL_UNACTIONED_FINDING, Map.of("findingIds", unactioned));
        }
    }

    /** Findings flagged "Unactioned Finding" on the record before anyone tries to submit. */
    public static List<DrillFinding> unactioned(List<DrillFinding> findings, Set<UUID> findingsWithActions) {
        return findings.stream()
                .filter(f -> f.noActionJustification() == null && !findingsWithActions.contains(f.id()))
                .toList();
    }
}
