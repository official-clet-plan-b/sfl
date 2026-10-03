package gh.edu.clet.sfl.safetysecurity.incident.support;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.safetysecurity.incident.application.port.SecurityIncidentRepository;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.CapaStatus;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.CorrectiveAction;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.IncidentEvidence;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.IncidentStatus;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.SecurityIncident;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.Severity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory {@link SecurityIncidentRepository} for S163 application-layer unit tests, following the
 * idiom {@code sfl-fleet-logistics-service}'s {@code fleet.support.FleetTestDoubles} uses: a real
 * implementation of the port's contract, not a mock, so a test that passes here is testing behaviour
 * (the CAPA-gated closure count, the version conflict) rather than interaction bookkeeping.
 */
public final class IncidentTestDoubles {

    private IncidentTestDoubles() {
    }

    public static ActorContext actor(String subject, SflRole role, String... sites) {
        return new ActorContext(new SiteScopedPrincipal(subject, subject, Set.of(role), Set.of(sites), false),
                "corr-test");
    }

    public static final class InMemorySecurityIncidentRepository implements SecurityIncidentRepository {

        private final Map<UUID, SecurityIncident> incidents = new LinkedHashMap<>();
        private final Map<UUID, CorrectiveAction> correctiveActions = new LinkedHashMap<>();
        private final Map<UUID, IncidentEvidence> evidence = new LinkedHashMap<>();

        @Override
        public SecurityIncident saveIncident(SecurityIncident incident) {
            incidents.put(incident.id(), incident);
            return incident;
        }

        @Override
        public Optional<SecurityIncident> findIncident(UUID id) {
            return Optional.ofNullable(incidents.get(id));
        }

        @Override
        public List<SecurityIncident> search(IncidentQuery query) {
            return incidents.values().stream()
                    .filter(i -> query.siteCode() == null || i.siteCode().equalsIgnoreCase(query.siteCode()))
                    .filter(i -> query.status() == null || i.status() == query.status())
                    .filter(i -> query.severity() == null || i.severity() == query.severity())
                    .limit(Math.max(1, query.limit()))
                    .toList();
        }

        @Override
        public CorrectiveAction saveCorrectiveAction(CorrectiveAction action) {
            correctiveActions.put(action.id(), action);
            return action;
        }

        @Override
        public Optional<CorrectiveAction> findCorrectiveAction(UUID id) {
            return Optional.ofNullable(correctiveActions.get(id));
        }

        @Override
        public List<CorrectiveAction> findCorrectiveActions(UUID incidentId) {
            return correctiveActions.values().stream()
                    .filter(a -> a.incidentId().equals(incidentId))
                    .sorted(Comparator.comparing(CorrectiveAction::createdAt))
                    .toList();
        }

        /** The real count, computed in memory - the same rule {@code CorrectiveActionJpaRepository#countOpenMandatory} enforces. */
        @Override
        public long countOpenMandatoryCorrectiveActions(UUID incidentId) {
            return correctiveActions.values().stream()
                    .filter(a -> a.incidentId().equals(incidentId))
                    .filter(CorrectiveAction::mandatory)
                    .filter(a -> a.status() == CapaStatus.OPEN || a.status() == CapaStatus.IN_PROGRESS)
                    .count();
        }

        @Override
        public IncidentEvidence saveEvidence(IncidentEvidence value) {
            evidence.put(value.id(), value);
            return value;
        }

        @Override
        public List<IncidentEvidence> findEvidence(UUID incidentId) {
            return evidence.values().stream().filter(e -> e.incidentId().equals(incidentId)).toList();
        }

        @Override
        public Map<IncidentStatus, Long> countByStatus(String siteCode) {
            Map<IncidentStatus, Long> counts = new EnumMap<>(IncidentStatus.class);
            incidents.values().stream().filter(i -> i.siteCode().equalsIgnoreCase(siteCode))
                    .forEach(i -> counts.merge(i.status(), 1L, Long::sum));
            return counts;
        }

        @Override
        public Map<Severity, Long> countBySeverity(String siteCode) {
            Map<Severity, Long> counts = new EnumMap<>(Severity.class);
            incidents.values().stream().filter(i -> i.siteCode().equalsIgnoreCase(siteCode))
                    .filter(i -> i.severity() != null)
                    .forEach(i -> counts.merge(i.severity(), 1L, Long::sum));
            return counts;
        }

        @Override
        public CapaCounts capaCounts(String siteCode, LocalDate today) {
            List<CorrectiveAction> site = correctiveActions.values().stream()
                    .filter(a -> a.siteCode().equalsIgnoreCase(siteCode)).toList();
            List<CorrectiveAction> open = site.stream().filter(a -> !a.status().isTerminal()).toList();
            long[] ageing = new long[4];
            open.forEach(a -> {
                long days = java.time.temporal.ChronoUnit.DAYS.between(
                        LocalDate.ofInstant(a.createdAt(), java.time.ZoneOffset.UTC), today);
                ageing[days <= 30 ? 0 : days <= 60 ? 1 : days <= 90 ? 2 : 3]++;
            });
            return new CapaCounts(open.size(), open.stream().filter(a -> a.isOverdue(today)).count(),
                    site.stream().filter(a -> a.status() == CapaStatus.VERIFIED).count(),
                    site.stream().filter(a -> a.status() == CapaStatus.CANCELLED).count(),
                    List.of(ageing[0], ageing[1], ageing[2], ageing[3]));
        }

        public List<SecurityIncident> all() {
            return new ArrayList<>(incidents.values());
        }
    }
}
