package gh.edu.clet.sfl.safetysecurity.drill.application.service;

import gh.edu.clet.sfl.safetysecurity.drill.domain.model.Drill;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillCorrectiveAction;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillExecution;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillFinding;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillReview;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.RollCallGap;
import java.util.List;
import java.util.UUID;

/**
 * Everything about one drill, for its detail screen. Derived facts are fields, not methods, so they reach JSON.
 *
 * @param unactionedFindingIds findings flagged "Unactioned Finding" (S175-03) right now
 * @param overdueActionIds open corrective actions past their due date (S175-03's closure rule)
 */
public record DrillDetail(Drill drill, DrillExecution execution, List<RollCallGap> gaps, DrillReview review,
        List<DrillFinding> findings, List<DrillCorrectiveAction> correctiveActions, List<UUID> unactionedFindingIds,
        List<UUID> overdueActionIds) {
}
