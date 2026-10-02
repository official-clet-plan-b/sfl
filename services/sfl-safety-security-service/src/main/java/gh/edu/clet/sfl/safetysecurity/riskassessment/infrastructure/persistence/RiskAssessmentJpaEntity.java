package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.persistence;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RiskAssessment;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SourceChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_assessments", schema = "safety_security")
public class RiskAssessmentJpaEntity {

    @Id
    private UUID id;
    @Column(name = "site_code", nullable = false, length = 80)
    private String siteCode;
    @Column(nullable = false, length = 40)
    private String reference;
    @Column(name = "activity_type", length = 80)
    private String activityType;
    @Column(name = "location_code", length = 80)
    private String locationCode;
    @Column(name = "template_id")
    private UUID templateId;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(name = "current_version")
    private Integer currentVersion;
    @Column(name = "draft_version")
    private Integer draftVersion;
    @Column(name = "latest_version", nullable = false)
    private int latestVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "current_risk_level", length = 20)
    private RiskLevel currentRiskLevel;
    @Column(name = "current_review_due_at")
    private Instant currentReviewDueAt;
    @Column(name = "current_author_id", length = 160)
    private String currentAuthorId;
    @Column(name = "current_signed_off_by", length = 160)
    private String currentSignedOffBy;
    @Column(name = "created_by", nullable = false, length = 160)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "last_modified_by", nullable = false, length = 160)
    private String lastModifiedBy;
    @Column(name = "last_modified_at", nullable = false)
    private Instant lastModifiedAt;
    @Version
    @Column(name = "record_version", nullable = false)
    private long recordVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "source_channel", nullable = false, length = 20)
    private SourceChannel sourceChannel;
    @Column(name = "correlation_id", length = 120)
    private String correlationId;

    protected RiskAssessmentJpaEntity() {
    }

    static RiskAssessmentJpaEntity create(UUID id) {
        RiskAssessmentJpaEntity entity = new RiskAssessmentJpaEntity();
        entity.id = id;
        return entity;
    }

    /** Every field but the version, which Hibernate owns. */
    void apply(RiskAssessment a) {
        siteCode = a.siteCode();
        reference = a.reference();
        activityType = a.activityType();
        locationCode = a.locationCode();
        templateId = a.templateId();
        title = a.title();
        currentVersion = a.currentVersion();
        draftVersion = a.draftVersion();
        latestVersion = a.latestVersion();
        currentRiskLevel = a.currentRiskLevel();
        currentReviewDueAt = a.currentReviewDueAt();
        currentAuthorId = a.currentAuthorId();
        currentSignedOffBy = a.currentSignedOffBy();
        createdBy = a.metadata().createdBy();
        createdAt = a.metadata().createdAt();
        lastModifiedBy = a.metadata().lastModifiedBy();
        lastModifiedAt = a.metadata().lastModifiedAt();
        sourceChannel = a.metadata().sourceChannel();
        correlationId = a.metadata().correlationId();
    }

    RiskAssessment toDomain() {
        return new RiskAssessment(id, siteCode, reference, activityType, locationCode, templateId, title,
                currentVersion, draftVersion, latestVersion, currentRiskLevel, currentReviewDueAt, currentAuthorId,
                currentSignedOffBy, RecordMetadata.rehydrate(createdBy, createdAt, lastModifiedBy, lastModifiedAt,
                        recordVersion, sourceChannel, correlationId));
    }
}
