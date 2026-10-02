package gh.edu.clet.sfl.safetysecurity.drill.domain.model;

import java.util.Objects;

/**
 * One module's part in a drill, written before the drill runs and judged after it - SRS-SFL-S175-05:
 * "access control simulates lockdown, fleet confirms emergency-vehicle readiness without a real dispatch".
 *
 * @param successCriterion how success is measured, so "did it work" has a defined answer
 * @param outcome null until the after-action review records it
 */
public record ModuleExpectation(DrillModule module, String expectation, String successCriterion,
        ExpectationOutcome outcome, String outcomeNotes) {

    public ModuleExpectation {
        Objects.requireNonNull(module, "module is required");
        expectation = blankToNull(expectation);
        successCriterion = blankToNull(successCriterion);
        outcomeNotes = blankToNull(outcomeNotes);
        if (expectation == null) {
            throw new IllegalArgumentException("expectation is required");
        }
    }

    /** A planned expectation, not yet judged. */
    public static ModuleExpectation planned(DrillModule module, String expectation, String successCriterion) {
        return new ModuleExpectation(module, expectation, successCriterion, null, null);
    }

    public ModuleExpectation judged(ExpectationOutcome outcome, String notes) {
        return new ModuleExpectation(module, expectation, successCriterion, outcome, notes);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
