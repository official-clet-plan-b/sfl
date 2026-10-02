package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.persistence;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.Status;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.Paging;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.RiskAssessmentPage;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.RiskAssessmentRepository;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ActivityTypes;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentContent;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentTemplate;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentVersion;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Hazard;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ObservedActivityType;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlag;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlagStatus;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewInterval;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewTrigger;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RiskAssessment;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SignOff;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * JPA-backed {@link RiskAssessmentRepository}.
 *
 * <p>Every save loads the managed entity first and applies the domain record to it, so Hibernate's
 * {@code @Version} check runs against the row actually in the database and the version it increments is
 * the one returned. {@code saveAndFlush} so the increment - and any constraint violation - happens here,
 * inside the service's transaction, rather than at commit where the caller can no longer react.
 */
@Component
public class RiskAssessmentRepositoryAdapter implements RiskAssessmentRepository {

    private final RiskAssessmentJpaRepository assessments;
    private final AssessmentVersionJpaRepository versions;
    private final SignOffJpaRepository signOffs;
    private final ReviewFlagJpaRepository flags;
    private final ObservedActivityTypeJpaRepository observations;
    private final AssessmentTemplateJpaRepository templates;
    private final ReviewIntervalJpaRepository intervals;
    private final AssessmentContentJdbcStore content;
    private final JdbcTemplate jdbc;

    public RiskAssessmentRepositoryAdapter(RiskAssessmentJpaRepository assessments,
            AssessmentVersionJpaRepository versions, SignOffJpaRepository signOffs, ReviewFlagJpaRepository flags,
            ObservedActivityTypeJpaRepository observations, AssessmentTemplateJpaRepository templates,
            ReviewIntervalJpaRepository intervals, AssessmentContentJdbcStore content, JdbcTemplate jdbc) {
        this.assessments = assessments;
        this.versions = versions;
        this.signOffs = signOffs;
        this.flags = flags;
        this.observations = observations;
        this.templates = templates;
        this.intervals = intervals;
        this.content = content;
        this.jdbc = jdbc;
    }

    // ---- assessments ------------------------------------------------------------------------------

    @Override
    public RiskAssessment saveAssessment(RiskAssessment assessment) {
        RiskAssessmentJpaEntity entity = assessments.findById(assessment.id())
                .orElseGet(() -> RiskAssessmentJpaEntity.create(assessment.id()));
        entity.apply(assessment);
        return assessments.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<RiskAssessment> findAssessment(UUID id) {
        return assessments.findById(id).map(RiskAssessmentJpaEntity::toDomain);
    }

    @Override
    public RiskAssessmentPage<RiskAssessment> searchAssessments(AssessmentQuery query, Paging paging) {
        Specification<RiskAssessmentJpaEntity> spec = (root, criteria, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (query.siteCode() != null) {
                where.add(cb.equal(root.get("siteCode"), query.siteCode()));
            }
            String activity = ActivityTypes.normalise(query.activityType());
            if (activity != null) {
                where.add(cb.equal(root.get("activityType"), activity));
            }
            if (query.riskLevel() != null) {
                where.add(cb.equal(root.get("currentRiskLevel"), query.riskLevel()));
            }
            if (query.text() != null && !query.text().isBlank()) {
                String like = "%" + query.text().strip().toLowerCase(Locale.ROOT) + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("reference")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("locationCode"), "")), like)));
            }
            if (query.standing() != null) {
                where.add(switch (query.standing()) {
                    case DRAFT_ONLY -> cb.isNull(root.get("currentVersion"));
                    case PUBLISHED -> cb.isNotNull(root.get("currentVersion"));
                    case LAPSED -> cb.lessThanOrEqualTo(root.<Instant>get("currentReviewDueAt"), query.now());
                    // A pre-filter only: HIGH/CRITICAL in date, signed off by nobody or by the author. The
                    // service reports the exact standing per row from the currency rule.
                    case AWAITING_INDEPENDENT_SIGN_OFF -> cb.and(
                            root.get("currentRiskLevel").in(RiskLevel.HIGH, RiskLevel.CRITICAL),
                            cb.greaterThan(root.<Instant>get("currentReviewDueAt"), query.now()),
                            cb.or(cb.isNull(root.get("currentSignedOffBy")),
                                    cb.equal(cb.lower(root.get("currentSignedOffBy")),
                                            cb.lower(root.get("currentAuthorId")))));
                });
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Page<RiskAssessmentJpaEntity> page = assessments.findAll(spec,
                PageRequest.of(paging.page(), paging.size(), sort(paging.sort())));
        return RiskAssessmentPage.of(page.getContent().stream().map(RiskAssessmentJpaEntity::toDomain).toList(),
                paging, page.getTotalElements());
    }

    /** {@code reviewDueAt} (soonest first, the default), {@code reference}, or {@code lastModifiedAt,desc}. */
    private static Sort sort(String requested) {
        if (requested != null) {
            String[] parts = requested.split(",");
            String field = parts[0].strip();
            boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].strip());
            if (List.of("reference", "title", "lastModifiedAt", "currentReviewDueAt").contains(field)) {
                return desc ? Sort.by(field).descending() : Sort.by(field).ascending();
            }
        }
        return Sort.by(Sort.Order.asc("currentReviewDueAt").nullsLast(), Sort.Order.asc("reference"));
    }

    /** Two columns the domain does not model, so plain JDBC rather than widening the entity for them. */
    @Override
    public void recordCreationKey(UUID assessmentId, String idempotencyKey, String requestHash) {
        jdbc.update("UPDATE safety_security.risk_assessments SET creation_idempotency_key = ?, "
                + "creation_request_hash = ? WHERE id = ?", idempotencyKey, requestHash, assessmentId);
    }

    @Override
    public Optional<CreationKey> findCreationKey(String createdBy, String idempotencyKey) {
        return jdbc.query("SELECT id, creation_request_hash FROM safety_security.risk_assessments "
                        + "WHERE created_by = ? AND creation_idempotency_key = ?",
                (rs, n) -> new CreationKey(rs.getObject("id", UUID.class), rs.getString("creation_request_hash")),
                createdBy, idempotencyKey).stream().findFirst();
    }

    @Override
    public List<RiskAssessment> findAssessments(String siteCode) {
        return assessments.findBySiteCodeOrderByReferenceAsc(siteCode).stream()
                .map(RiskAssessmentJpaEntity::toDomain).toList();
    }

    @Override
    public List<RiskAssessment> findPublishedAssessments(String siteCode) {
        return assessments.findBySiteCodeAndCurrentVersionIsNotNull(siteCode).stream()
                .map(RiskAssessmentJpaEntity::toDomain).toList();
    }

    @Override
    public List<RiskAssessment> findPublishedByActivityType(String siteCode, String activityType) {
        return assessments.findBySiteCodeAndActivityTypeAndCurrentVersionIsNotNull(siteCode,
                ActivityTypes.normalise(activityType)).stream().map(RiskAssessmentJpaEntity::toDomain).toList();
    }

    // ---- versions ---------------------------------------------------------------------------------

    @Override
    public AssessmentVersion saveVersion(AssessmentVersion version) {
        Optional<AssessmentVersionJpaEntity> existing = versions.findById(version.id());
        boolean contentChanged = existing.map(e -> !hydrate(List.of(e)).get(0).content().equals(version.content()))
                .orElse(true);
        AssessmentVersionJpaEntity entity = existing.orElseGet(() -> AssessmentVersionJpaEntity.create(version.id()));
        entity.apply(version);
        AssessmentVersionJpaEntity saved = versions.saveAndFlush(entity);
        if (contentChanged) {
            content.replaceVersionHazards(saved.id(), saved.siteCode(), version.content().hazards(),
                    version.metadata());
        }
        return saved.toDomain(version.content());
    }

    @Override
    public Optional<AssessmentVersion> findVersion(UUID assessmentId, int versionNumber) {
        return versions.findByAssessmentIdAndVersionNumber(assessmentId, versionNumber)
                .map(entity -> hydrate(List.of(entity)).get(0));
    }

    @Override
    public List<AssessmentVersion> findVersions(UUID assessmentId) {
        return hydrate(versions.findByAssessmentIdOrderByVersionNumberDesc(assessmentId));
    }

    @Override
    public List<AssessmentVersion> findPublishedVersions(String siteCode) {
        return hydrate(versions.findBySiteCodeAndStatus(siteCode, Status.PUBLISHED));
    }

    @Override
    public List<AssessmentVersion> findPublishedVersionsDueBy(Instant cutoff) {
        return hydrate(versions.findByStatusAndReviewDueAtLessThanEqual(Status.PUBLISHED, cutoff));
    }

    private List<AssessmentVersion> hydrate(List<AssessmentVersionJpaEntity> entities) {
        Map<UUID, List<Hazard>> hazards = content.versionHazards(
                entities.stream().map(AssessmentVersionJpaEntity::id).toList());
        return entities.stream()
                .map(entity -> entity.toDomain(new AssessmentContent(entity.title(), entity.summary(),
                        hazards.getOrDefault(entity.id(), List.of()))))
                .toList();
    }

    // ---- sign-offs --------------------------------------------------------------------------------

    @Override
    public SignOff saveSignOff(SignOff signOff) {
        return signOffs.saveAndFlush(SignOffJpaEntity.from(signOff)).toDomain();
    }

    @Override
    public List<SignOff> findSignOffs(UUID assessmentId) {
        return signOffs.findByAssessmentIdOrderBySignedOffAtDesc(assessmentId).stream()
                .map(SignOffJpaEntity::toDomain).toList();
    }

    // ---- review flags -----------------------------------------------------------------------------

    @Override
    public ReviewFlag saveFlag(ReviewFlag flag) {
        ReviewFlagJpaEntity entity = flags.findById(flag.id()).orElseGet(() -> ReviewFlagJpaEntity.create(flag.id()));
        entity.apply(flag);
        return flags.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<ReviewFlag> findFlag(UUID id) {
        return flags.findById(id).map(ReviewFlagJpaEntity::toDomain);
    }

    @Override
    public Optional<ReviewFlag> findFlag(UUID assessmentId, ReviewTrigger trigger, String sourceId) {
        return flags.findByAssessmentIdAndTriggerTypeAndSourceId(assessmentId, trigger, sourceId)
                .map(ReviewFlagJpaEntity::toDomain);
    }

    @Override
    public List<ReviewFlag> findFlags(UUID assessmentId) {
        return flags.findByAssessmentIdOrderByRaisedAtDesc(assessmentId).stream().map(ReviewFlagJpaEntity::toDomain)
                .toList();
    }

    @Override
    public RiskAssessmentPage<ReviewFlag> searchFlags(String siteCode, ReviewFlagStatus status, Paging paging) {
        PageRequest request = PageRequest.of(paging.page(), paging.size(), Sort.by("raisedAt").ascending());
        Page<ReviewFlagJpaEntity> page = status == null ? flags.findBySiteCode(siteCode, request)
                : flags.findBySiteCodeAndStatus(siteCode, status, request);
        return RiskAssessmentPage.of(page.getContent().stream().map(ReviewFlagJpaEntity::toDomain).toList(), paging,
                page.getTotalElements());
    }

    @Override
    public long countFlags(String siteCode, ReviewFlagStatus status) {
        return flags.countBySiteCodeAndStatus(siteCode, status);
    }

    @Override
    public List<ReviewFlag> findExpiredDeferrals(Instant now) {
        return flags.findByStatusAndDeferredUntilLessThanEqual(ReviewFlagStatus.DEFERRED, now).stream()
                .map(ReviewFlagJpaEntity::toDomain).toList();
    }

    // ---- observed activity types ------------------------------------------------------------------

    @Override
    public ObservedActivityType saveObservation(ObservedActivityType observation) {
        ObservedActivityTypeJpaEntity entity = observations.findById(observation.id())
                .orElseGet(() -> ObservedActivityTypeJpaEntity.create(observation.id()));
        entity.apply(observation);
        return observations.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<ObservedActivityType> findObservation(String siteCode, String activityType, String sourceSystem) {
        return observations.findBySiteCodeAndActivityTypeAndSourceSystem(siteCode, activityType, sourceSystem)
                .map(ObservedActivityTypeJpaEntity::toDomain);
    }

    @Override
    public List<ObservedActivityType> findObservations(String siteCode) {
        return observations.findBySiteCode(siteCode).stream().map(ObservedActivityTypeJpaEntity::toDomain).toList();
    }

    // ---- templates --------------------------------------------------------------------------------

    @Override
    public AssessmentTemplate saveTemplate(AssessmentTemplate template) {
        AssessmentTemplateJpaEntity entity = templates.findById(template.id())
                .orElseGet(() -> AssessmentTemplateJpaEntity.create(template.id()));
        entity.apply(template);
        AssessmentTemplateJpaEntity saved = templates.saveAndFlush(entity);
        content.replaceTemplateHazards(saved.id(), template.hazards());
        return saved.toDomain(template.hazards());
    }

    @Override
    public Optional<AssessmentTemplate> findTemplate(UUID id) {
        return templates.findById(id).map(entity -> hydrateTemplates(List.of(entity)).get(0));
    }

    @Override
    public List<AssessmentTemplate> findTemplates(boolean activeOnly) {
        return hydrateTemplates(activeOnly ? templates.findByActiveTrueOrderByNameAsc()
                : templates.findAllByOrderByNameAsc());
    }

    private List<AssessmentTemplate> hydrateTemplates(List<AssessmentTemplateJpaEntity> entities) {
        Map<UUID, List<Hazard>> hazards = content.templateHazards(
                entities.stream().map(AssessmentTemplateJpaEntity::id).toList());
        return entities.stream().map(e -> e.toDomain(hazards.getOrDefault(e.id(), List.of()))).toList();
    }

    // ---- review intervals -------------------------------------------------------------------------

    @Override
    public List<ReviewInterval> findReviewIntervals() {
        return intervals.findAll().stream().map(ReviewIntervalJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<ReviewInterval> findReviewInterval(RiskLevel riskLevel) {
        return intervals.findById(riskLevel).map(ReviewIntervalJpaEntity::toDomain);
    }

    @Override
    public ReviewInterval saveReviewInterval(ReviewInterval interval) {
        ReviewIntervalJpaEntity entity = intervals.findById(interval.riskLevel())
                .orElseGet(() -> ReviewIntervalJpaEntity.create(interval.riskLevel()));
        entity.apply(interval);
        return intervals.saveAndFlush(entity).toDomain();
    }
}
