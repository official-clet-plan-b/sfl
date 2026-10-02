package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.persistence;

import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlag;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlagStatus;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewTrigger;
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
@Table(name = "risk_assessment_review_flags", schema = "safety_security")
public class ReviewFlagJpaEntity {

    @Id
    private UUID id;
    @Column(name = "assessment_id", nullable = false)
    private UUID assessmentId;
    @Column(name = "assessment_reference", nullable = false, length = 40)
    private String assessmentReference;
    @Column(name = "version_number", nullable = false)
    private int versionNumber;
    @Column(name = "site_code", nullable = false, length = 80)
    private String siteCode;
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private ReviewTrigger triggerType;
    @Column(name = "source_id", nullable = false, length = 120)
    private String sourceId;
    @Column(name = "source_reference", length = 80)
    private String sourceReference;
    @Column(length = 500)
    private String reason;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewFlagStatus status;
    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt;
    @Column(name = "deferred_until")
    private Instant deferredUntil;
    @Column(name = "deferral_reason", length = 1000)
    private String deferralReason;
    @Column(name = "deferred_by", length = 160)
    private String deferredBy;
    @Column(name = "deferral_count", nullable = false)
    private int deferralCount;
    @Column(name = "cleared_at")
    private Instant clearedAt;
    @Column(name = "cleared_by", length = 160)
    private String clearedBy;
    @Column(length = 4000)
    private String findings;
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

    protected ReviewFlagJpaEntity() {
    }

    static ReviewFlagJpaEntity create(UUID id) {
        ReviewFlagJpaEntity entity = new ReviewFlagJpaEntity();
        entity.id = id;
        return entity;
    }

    void apply(ReviewFlag f) {
        assessmentId = f.assessmentId();
        assessmentReference = f.assessmentReference();
        versionNumber = f.versionNumber();
        siteCode = f.siteCode();
        triggerType = f.trigger();
        sourceId = f.sourceId();
        sourceReference = f.sourceReference();
        reason = f.reason();
        status = f.status();
        raisedAt = f.raisedAt();
        deferredUntil = f.deferredUntil();
        deferralReason = f.deferralReason();
        deferredBy = f.deferredBy();
        deferralCount = f.deferralCount();
        clearedAt = f.clearedAt();
        clearedBy = f.clearedBy();
        findings = f.findings();
        createdBy = f.metadata().createdBy();
        createdAt = f.metadata().createdAt();
        lastModifiedBy = f.metadata().lastModifiedBy();
        lastModifiedAt = f.metadata().lastModifiedAt();
        sourceChannel = f.metadata().sourceChannel();
        correlationId = f.metadata().correlationId();
    }

    ReviewFlag toDomain() {
        return new ReviewFlag(id, assessmentId, assessmentReference, versionNumber, siteCode, triggerType, sourceId,
                sourceReference, reason, status, raisedAt, deferredUntil, deferralReason, deferredBy, deferralCount,
                clearedAt, clearedBy, findings, RecordMetadata.rehydrate(createdBy, createdAt, lastModifiedBy,
                        lastModifiedAt, recordVersion, sourceChannel, correlationId));
    }
}
