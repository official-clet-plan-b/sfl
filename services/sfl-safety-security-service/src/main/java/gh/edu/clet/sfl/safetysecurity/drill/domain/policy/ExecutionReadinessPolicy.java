package gh.edu.clet.sfl.safetysecurity.drill.domain.policy;

import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillErrorCode;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillPlan;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillType;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.ModuleExpectation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a plan needs before it can be scheduled, so the day of the drill holds no surprises.
 *
 * <p>SRS-SFL-S175-05's validation is the sharpest of these: "A combined drill's per-module expectations must be
 * explicit in the plan before execution, so whether it worked has a defined answer". A combined drill names at
 * least two modules; every expectation, on any drill, says how success is measured. Whether the S174 template
 * is a drill template is checked against S174 itself, by the scheduling service, in the same call.
 */
public final class ExecutionReadinessPolicy {

    private ExecutionReadinessPolicy() {
    }

    public static void requireReady(DrillPlan plan) {
        List<String> expectationProblems = expectationProblems(plan);
        if (!expectationProblems.isEmpty()) {
            throw new DrillException(DrillErrorCode.DRILL_EXPECTATIONS_REQUIRED,
                    Map.of("problems", expectationProblems));
        }
        List<String> problems = new ArrayList<>();
        if (plan.scheduledFor() == null) {
            problems.add("Choose when the drill will run.");
        }
        if (plan.assemblyZone() == null) {
            problems.add("Name the assembly point the roll-call is taken at.");
        }
        if (plan.notificationTemplateId() == null) {
            problems.add("Choose the S174 drill template the notification is sent with.");
        }
        if (plan.audienceGroupIds().isEmpty() && plan.recipientZoneIds().isEmpty()) {
            problems.add("Choose at least one S174 audience group or zone to notify.");
        }
        if (!problems.isEmpty()) {
            throw new DrillException(DrillErrorCode.DRILL_NOT_READY, Map.of("problems", problems));
        }
    }

    static List<String> expectationProblems(DrillPlan plan) {
        List<String> problems = new ArrayList<>();
        if (plan.drillType() == DrillType.COMBINED
                && plan.expectations().stream().map(ModuleExpectation::module).distinct().count() < 2) {
            problems.add("A combined drill names at least two participating modules.");
        }
        for (ModuleExpectation e : plan.expectations()) {
            if (e.successCriterion() == null) {
                problems.add(e.module() + ": say how success is measured for \"" + e.expectation() + "\".");
            }
        }
        return problems;
    }
}
