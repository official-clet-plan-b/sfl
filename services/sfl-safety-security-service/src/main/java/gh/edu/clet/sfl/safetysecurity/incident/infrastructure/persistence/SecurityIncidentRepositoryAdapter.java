package gh.edu.clet.sfl.safetysecurity.incident.infrastructure.persistence;

import gh.edu.clet.sfl.safetysecurity.incident.application.port.SecurityIncidentRepository;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.CorrectiveAction;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.IncidentEvidence;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.IncidentStatus;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.SecurityIncident;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.Severity;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

/** The one adapter behind {@link SecurityIncidentRepository}, following {@code VisitorRepositoryAdapter}'s shape. */
@Repository
public class SecurityIncidentRepositoryAdapter implements SecurityIncidentRepository {

    private final SecurityIncidentJpaRepository incidents;
    private final CorrectiveActionJpaRepository correctiveActions;
    private final IncidentEvidenceJpaRepository evidence;

    public SecurityIncidentRepositoryAdapter(SecurityIncidentJpaRepository incidents,
            CorrectiveActionJpaRepository correctiveActions, IncidentEvidenceJpaRepository evidence) {
        this.incidents = incidents;
        this.correctiveActions = correctiveActions;
        this.evidence = evidence;
    }

    @Override
    public SecurityIncident saveIncident(SecurityIncident incident) {
        SecurityIncidentJpaEntity entity = incidents.findById(incident.id())
                .orElseGet(SecurityIncidentJpaEntity::new);
        entity.apply(incident);
        // saveAndFlush, not save: recordVersion is a JPA @Version field Hibernate only increments at
        // flush time. A plain save() defers the flush to transaction commit, so toDomain() below would
        // read the pre-increment value and hand the caller a version number the database has already
        // moved past - the next command's requireVersion check would then fail against its own,
        // correctly-persisted change. Flushing here makes the returned aggregate's version match what
        // is actually committed.
        return incidents.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<SecurityIncident> findIncident(UUID id) {
        return incidents.findById(id).map(SecurityIncidentJpaEntity::toDomain);
    }

    @Override
    public List<SecurityIncident> search(IncidentQuery query) {
        return incidents.search(query.siteCode(), query.status(), query.severity(), page(query.limit())).stream()
                .map(SecurityIncidentJpaEntity::toDomain)
                .toList();
    }

    @Override
    public CorrectiveAction saveCorrectiveAction(CorrectiveAction action) {
        CorrectiveActionJpaEntity entity = correctiveActions.findById(action.id())
                .orElseGet(CorrectiveActionJpaEntity::new);
        entity.apply(action);
        return correctiveActions.save(entity).toDomain();
    }

    @Override
    public Optional<CorrectiveAction> findCorrectiveAction(UUID id) {
        return correctiveActions.findById(id).map(CorrectiveActionJpaEntity::toDomain);
    }

    @Override
    public List<CorrectiveAction> findCorrectiveActions(UUID incidentId) {
        return correctiveActions.findByIncidentIdOrderByCreatedAtAsc(incidentId).stream()
                .map(CorrectiveActionJpaEntity::toDomain)
                .toList();
    }

    @Override
    public long countOpenMandatoryCorrectiveActions(UUID incidentId) {
        return correctiveActions.countOpenMandatory(incidentId);
    }

    @Override
    public IncidentEvidence saveEvidence(IncidentEvidence value) {
        IncidentEvidenceJpaEntity entity = evidence.findById(value.id()).orElseGet(IncidentEvidenceJpaEntity::new);
        entity.apply(value);
        return evidence.save(entity).toDomain();
    }

    @Override
    public List<IncidentEvidence> findEvidence(UUID incidentId) {
        return evidence.findByIncidentIdOrderByUploadedAtAsc(incidentId).stream()
                .map(IncidentEvidenceJpaEntity::toDomain)
                .toList();
    }

    @Override
    public Map<IncidentStatus, Long> countByStatus(String siteCode) {
        Map<IncidentStatus, Long> counts = new EnumMap<>(IncidentStatus.class);
        for (Object[] row : incidents.countByStatus(siteCode)) {
            counts.put((IncidentStatus) row[0], (Long) row[1]);
        }
        return counts;
    }

    @Override
    public Map<Severity, Long> countBySeverity(String siteCode) {
        Map<Severity, Long> counts = new EnumMap<>(Severity.class);
        for (Object[] row : incidents.countBySeverity(siteCode)) {
            counts.put((Severity) row[0], (Long) row[1]);
        }
        return counts;
    }

    @Override
    public CapaCounts capaCounts(String siteCode, LocalDate today) {
        Object[] row = correctiveActions.capaCounts(siteCode, today).get(0);
        long[] n = new long[row.length];
        for (int i = 0; i < row.length; i++) {
            n[i] = ((Number) row[i]).longValue();
        }
        return new CapaCounts(n[0], n[1], n[2], n[3], List.of(n[4], n[5], n[6], n[7]));
    }

    private static PageRequest page(int limit) {
        return PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
    }
}
