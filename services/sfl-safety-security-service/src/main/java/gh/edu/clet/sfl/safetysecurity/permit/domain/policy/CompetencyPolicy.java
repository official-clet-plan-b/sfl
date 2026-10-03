package gh.edu.clet.sfl.safetysecurity.permit.domain.policy;

import gh.edu.clet.sfl.safetysecurity.permit.domain.model.CompetencyCheck;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitType;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitWorker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every worker named on a permit must have a competent, unexpired check for each competence its type requires. A missing
 * check is an exception exactly as an expired one is: competence is recorded, never assumed.
 */
public final class CompetencyPolicy {

    public enum Reason { MISSING, NOT_COMPETENT, EXPIRED }

    public record Exception(PermitWorker worker, String competency, Reason reason) {
    }

    private CompetencyPolicy() {
    }

    /** @param through the instant the check must still cover - the permit's end, or an extension's */
    public static List<Exception> exceptions(PermitType type, List<PermitWorker> workers, List<CompetencyCheck> checks, Instant through) {
        List<Exception> found = new ArrayList<>();
        for (PermitWorker worker : workers) {
            for (String competency : type.requiredCompetencies()) {
                List<CompetencyCheck> mine = checks.stream()
                        .filter(c -> c.workerId().equals(worker.id()) && c.competencyCode().equalsIgnoreCase(competency))
                        .sorted(Comparator.comparing(CompetencyCheck::checkedAt).reversed()).toList();
                if (mine.isEmpty()) {
                    found.add(new Exception(worker, competency, Reason.MISSING));
                    continue;
                }
                CompetencyCheck latest = mine.get(0);
                if (!latest.competent()) {
                    found.add(new Exception(worker, competency, Reason.NOT_COMPETENT));
                } else if (!latest.coversThrough(through)) {
                    found.add(new Exception(worker, competency, Reason.EXPIRED));
                }
            }
        }
        return found;
    }
}
