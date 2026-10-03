package gh.edu.clet.sfl.safetysecurity.lifesafety.infrastructure.persistence;

import gh.edu.clet.sfl.safetysecurity.lifesafety.application.port.LifeSafetyRepository;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.ComplianceExceptionKind;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.ComplianceExceptionStatus;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.DetectorCoverage;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.FastLaneTrigger;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.InspectionSchedule;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.LifeSafetyComplianceException;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.LifeSafetyEvent;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.MusterCheckIn;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.MusterSession;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.MusterStatus;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** JPA-backed implementation of {@link LifeSafetyRepository}, delegating to one Spring Data repository per aggregate. */
@Component
public class LifeSafetyRepositoryAdapter implements LifeSafetyRepository {

    private final LifeSafetyEventJpaRepository events;
    private final FastLaneTriggerJpaRepository fastLaneTriggers;
    private final InspectionScheduleJpaRepository inspectionSchedules;
    private final LifeSafetyComplianceExceptionJpaRepository complianceExceptions;
    private final DetectorCoverageJpaRepository detectorCoverage;
    private final MusterSessionJpaRepository musterSessions;
    private final MusterCheckInJpaRepository musterCheckIns;

    public LifeSafetyRepositoryAdapter(LifeSafetyEventJpaRepository events,
            FastLaneTriggerJpaRepository fastLaneTriggers, InspectionScheduleJpaRepository inspectionSchedules,
            LifeSafetyComplianceExceptionJpaRepository complianceExceptions,
            DetectorCoverageJpaRepository detectorCoverage, MusterSessionJpaRepository musterSessions,
            MusterCheckInJpaRepository musterCheckIns) {
        this.events = events;
        this.fastLaneTriggers = fastLaneTriggers;
        this.inspectionSchedules = inspectionSchedules;
        this.complianceExceptions = complianceExceptions;
        this.detectorCoverage = detectorCoverage;
        this.musterSessions = musterSessions;
        this.musterCheckIns = musterCheckIns;
    }

    @Override
    public List<String> activeSites() {
        Set<String> sites = new LinkedHashSet<>(inspectionSchedules.findDistinctSiteCodes());
        sites.addAll(detectorCoverage.findDistinctSiteCodes());
        return List.copyOf(sites);
    }

    @Override
    public LifeSafetyEvent saveEvent(LifeSafetyEvent event) {
        return events.save(LifeSafetyEventJpaEntity.from(event)).toDomain();
    }

    @Override
    public Optional<LifeSafetyEvent> findEvent(UUID id) {
        return events.findById(id).map(LifeSafetyEventJpaEntity::toDomain);
    }

    @Override
    public List<LifeSafetyEvent> findEvents(String siteCode, int limit) {
        return events.findBySiteCodeOrderByOccurredAtDesc(siteCode, PageRequest.of(0, Math.max(1, limit))).stream()
                .map(LifeSafetyEventJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<LifeSafetyEvent> findLatestEvent(String siteCode) {
        return Optional.ofNullable(events.findFirstBySiteCodeOrderByOccurredAtDesc(siteCode))
                .map(LifeSafetyEventJpaEntity::toDomain);
    }

    @Override
    public FastLaneTrigger saveFastLaneTrigger(FastLaneTrigger trigger) {
        return fastLaneTriggers.save(FastLaneTriggerJpaEntity.from(trigger)).toDomain();
    }

    @Override
    public List<FastLaneTrigger> findFastLaneTriggers(String siteCode, int limit) {
        return fastLaneTriggers.findBySiteCodeOrderByTriggeredAtDesc(siteCode, PageRequest.of(0, Math.max(1, limit)))
                .stream().map(FastLaneTriggerJpaEntity::toDomain).toList();
    }

    @Override
    public InspectionSchedule saveInspectionSchedule(InspectionSchedule schedule) {
        return inspectionSchedules.save(InspectionScheduleJpaEntity.from(schedule)).toDomain();
    }

    @Override
    public Optional<InspectionSchedule> findInspectionSchedule(UUID id) {
        return inspectionSchedules.findById(id).map(InspectionScheduleJpaEntity::toDomain);
    }

    @Override
    public List<InspectionSchedule> findInspectionSchedules(String siteCode) {
        return inspectionSchedules.findBySiteCode(siteCode).stream().map(InspectionScheduleJpaEntity::toDomain)
                .toList();
    }

    @Override
    public List<InspectionSchedule> findOverdueInspectionSchedules(String siteCode, Instant asOf) {
        return inspectionSchedules.findBySiteCodeAndNextDueAtBefore(siteCode, asOf).stream()
                .map(InspectionScheduleJpaEntity::toDomain).toList();
    }

    @Override
    public LifeSafetyComplianceException saveComplianceException(LifeSafetyComplianceException exception) {
        return complianceExceptions.save(LifeSafetyComplianceExceptionJpaEntity.from(exception)).toDomain();
    }

    @Override
    public Optional<LifeSafetyComplianceException> findComplianceException(UUID id) {
        return complianceExceptions.findById(id).map(LifeSafetyComplianceExceptionJpaEntity::toDomain);
    }

    @Override
    public List<LifeSafetyComplianceException> findComplianceExceptions(String siteCode,
            ComplianceExceptionStatus status) {
        var found = status == null ? complianceExceptions.findBySiteCode(siteCode)
                : complianceExceptions.findBySiteCodeAndStatus(siteCode, status);
        return found.stream().map(LifeSafetyComplianceExceptionJpaEntity::toDomain).toList();
    }

    @Override
    public boolean hasOpenComplianceException(String siteCode, UUID refId, ComplianceExceptionKind kind) {
        return complianceExceptions.existsBySiteCodeAndRefIdAndKindAndStatus(siteCode, refId, kind,
                ComplianceExceptionStatus.OPEN);
    }

    @Override
    public DetectorCoverage saveDetectorCoverage(DetectorCoverage coverage) {
        return detectorCoverage.save(DetectorCoverageJpaEntity.from(coverage)).toDomain();
    }

    @Override
    public Optional<DetectorCoverage> findDetectorCoverage(UUID id) {
        return detectorCoverage.findById(id).map(DetectorCoverageJpaEntity::toDomain);
    }

    @Override
    public List<DetectorCoverage> findDetectorCoverages(String siteCode) {
        return detectorCoverage.findBySiteCode(siteCode).stream().map(DetectorCoverageJpaEntity::toDomain).toList();
    }

    @Override
    public MusterSession saveMusterSession(MusterSession session) {
        return musterSessions.save(MusterSessionJpaEntity.from(session)).toDomain();
    }

    @Override
    public Optional<MusterSession> findMusterSession(UUID id) {
        return musterSessions.findById(id).map(MusterSessionJpaEntity::toDomain);
    }

    @Override
    public Optional<MusterSession> findOpenMusterSession(String siteCode, String zoneCode) {
        // A drill's session is never the one a real event joins: a fire during a drill opens its own.
        return musterSessions.findFirstBySiteCodeAndZoneCodeAndStatusAndDrillIdIsNull(siteCode, zoneCode,
                        MusterStatus.OPEN)
                .map(MusterSessionJpaEntity::toDomain);
    }

    @Override
    public MusterCheckIn saveCheckIn(MusterCheckIn checkIn) {
        return musterCheckIns.save(MusterCheckInJpaEntity.from(checkIn)).toDomain();
    }

    @Override
    public List<MusterCheckIn> findCheckIns(UUID musterSessionId) {
        return musterCheckIns.findByMusterSessionId(musterSessionId).stream().map(MusterCheckInJpaEntity::toDomain)
                .toList();
    }
}
