package gh.edu.clet.sfl.safetysecurity.drill.application.service;

import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillRepository;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.Drill;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillCorrectiveAction;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillFinding;
import gh.edu.clet.sfl.safetysecurity.drill.domain.policy.ReviewCompletenessPolicy;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Loading a drill and assembling its {@link DrillDetail} - shared by every S175 service. */
@Component
class DrillRecords {

    private final DrillRepository repository;
    private final Clock clock;

    DrillRecords(DrillRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    Drill require(UUID drillId) {
        return repository.findDrill(drillId).orElseThrow(() -> DrillException.notFound("Drill", drillId));
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }

    static Set<UUID> findingsWithActions(List<DrillCorrectiveAction> actions) {
        return actions.stream().map(DrillCorrectiveAction::findingId).collect(Collectors.toUnmodifiableSet());
    }

    DrillDetail detail(Drill drill) {
        List<DrillFinding> findings = repository.findFindings(drill.id());
        List<DrillCorrectiveAction> actions = repository.findCorrectiveActions(drill.id());
        LocalDate today = today();
        return new DrillDetail(drill, repository.findExecution(drill.id()).orElse(null),
                repository.findGaps(drill.id()), repository.findReview(drill.id()).orElse(null), findings, actions,
                ReviewCompletenessPolicy.unactioned(findings, findingsWithActions(actions)).stream()
                        .map(DrillFinding::id).toList(),
                actions.stream().filter(a -> a.isOverdue(today)).map(DrillCorrectiveAction::id).toList());
    }
}
