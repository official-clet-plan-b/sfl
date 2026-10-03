package gh.edu.clet.sfl.safetysecurity.permit.application.port;

import gh.edu.clet.sfl.safetysecurity.permit.domain.model.CompetencyCheck;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.EscalationLevel;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.HistoryEntry;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Isolation;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitApproval;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitEscalation;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitEvidence;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitExtension;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitFlag;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitNotification;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitSuspension;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitType;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitWorker;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything S164 reads and writes. A versioned record (a permit, a permit type) is updated only where the stored version is
 * the one the caller read - a concurrent writer that saved first turns this save into a conflict, not a lost update.
 */
public interface PermitRepository {

    // ---- types
    List<PermitType> types(boolean activeOnly);

    Optional<PermitType> findType(UUID id);

    Optional<PermitType> findTypeByCode(String code);

    PermitType insertType(PermitType type);

    PermitType updateType(PermitType type, long expectedVersion);

    // ---- permits
    long nextReference();

    Permit insertPermit(Permit permit);

    Optional<Permit> findPermit(UUID id);

    /** Writes the permit's mutable columns and bumps its version; a stale expected version is a conflict. */
    Permit updatePermit(Permit permit, long expectedVersion);

    /** @param sites null for every site */
    PermitPage<Permit> search(PermitQuery query);

    record PermitQuery(List<String> sites, UUID permitTypeId, PermitStatus status, String contractor, Instant from, Instant to,
            boolean openOnly, boolean overdueOnly, Instant now, String text, Paging paging) {
    }

    /** Open permits at the given sites (null = all), for the dashboard and the sweep. */
    List<Permit> openPermits(List<String> sites);

    /** Permits at a site, in a zone (or every zone when {@code zoneIds} is empty), that are live: active or suspended. */
    List<Permit> livePermitsInZones(String siteCode, List<UUID> zoneIds);

    /** Active permits at a site relying on the assessment, whose window covers {@code at}. */
    List<Permit> activePermitsForAssessment(String siteCode, UUID assessmentId, Instant at);

    // ---- parts of a permit
    List<PermitWorker> workers(UUID permitId);

    PermitWorker insertWorker(PermitWorker worker);

    Optional<PermitWorker> findWorker(UUID id);

    void deleteWorker(UUID id);

    List<CompetencyCheck> checks(UUID permitId);

    CompetencyCheck insertCheck(CompetencyCheck check);

    List<Isolation> isolations(UUID permitId);

    Isolation insertIsolation(Isolation isolation);

    Optional<Isolation> findIsolation(UUID id);

    Isolation updateIsolation(Isolation isolation);

    void deleteIsolation(UUID id);

    List<PermitApproval> approvals(UUID permitId);

    PermitApproval insertApproval(PermitApproval approval);

    List<PermitExtension> extensions(UUID permitId);

    Optional<PermitExtension> findExtension(UUID id);

    PermitExtension insertExtension(PermitExtension extension);

    PermitExtension updateExtension(PermitExtension extension);

    List<PermitSuspension> suspensions(UUID permitId);

    PermitSuspension insertSuspension(PermitSuspension suspension);

    void markResumed(UUID permitId, Instant at);

    List<PermitNotification> notifications(UUID permitId);

    PermitNotification insertNotification(PermitNotification notification);

    List<PermitEvidence> evidence(UUID permitId);

    PermitEvidence insertEvidence(PermitEvidence evidence);

    List<PermitFlag> flags(UUID permitId);

    /** False if this permit already carries a flag of that type and reference - raising it twice is a no-op. */
    boolean insertFlag(PermitFlag flag);

    Optional<PermitFlag> findFlag(UUID id);

    PermitFlag updateFlag(PermitFlag flag);

    List<PermitFlag> openFlags(List<String> sites);

    List<PermitEscalation> escalations(UUID permitId);

    /** False if that level has already been raised for the permit. */
    boolean insertEscalation(PermitEscalation escalation);

    List<PermitEscalation> escalationsAt(List<String> sites, EscalationLevel level);

    void insertHistory(HistoryEntry entry);

    List<HistoryEntry> history(UUID permitId);

    // ---- analytics
    List<CountRow> volumeByType(List<String> sites, Instant from, Instant to);

    List<CountRow> volumeByContractor(List<String> sites, Instant from, Instant to);

    List<CountRow> volumeByOutcome(List<String> sites, Instant from, Instant to);

    /** Mean hours from issue to close, over closed permits. Null when none. */
    Double meanOpenHours(List<String> sites, Instant from, Instant to);

    long flaggedForIncident(List<String> sites, Instant from, Instant to);

    record CountRow(String key, long count) {
    }
}
