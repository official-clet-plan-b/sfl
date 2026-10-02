package gh.edu.clet.sfl.safetysecurity.drill.application.service;

import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillMusterPort.CheckIn;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillNotificationPort.Sent;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.BaselinePerson;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillExecution;
import java.util.List;

/**
 * The live roll-call - SRS-SFL-S175-02: "gaps identified in real time". Everyone in the baseline who has not
 * checked in yet is {@code outstanding}; anyone who checked in but was not in the baseline is {@code unexpected}
 * (a visitor who never signed in, a badge that never registered) - itself a finding about the baseline.
 *
 * @param notification where the S174 drill notification stands; null if S174 could not be reached
 */
public record RollCallView(DrillExecution execution, List<BaselinePerson> baseline, List<CheckIn> checkIns,
        List<BaselinePerson> outstanding, List<String> unexpected, Sent notification) {
}
