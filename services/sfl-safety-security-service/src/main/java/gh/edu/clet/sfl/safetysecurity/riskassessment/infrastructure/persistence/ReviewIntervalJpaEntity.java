package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.persistence;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewInterval;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SourceChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "risk_assessment_review_intervals", schema = "safety_security")
public class ReviewIntervalJpaEntity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 20)
    private RiskLevel riskLevel;
    @Column(name = "interval_days", nullable = false)
    private int intervalDays;
    @Column(name = "reminder_lead_days", nullable = false)
    private int reminderLeadDays;
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

    protected ReviewIntervalJpaEntity() {
    }

    static ReviewIntervalJpaEntity create(RiskLevel level) {
        ReviewIntervalJpaEntity entity = new ReviewIntervalJpaEntity();
        entity.riskLevel = level;
        return entity;
    }

    void apply(ReviewInterval i) {
        intervalDays = i.intervalDays();
        reminderLeadDays = i.reminderLeadDays();
        createdBy = i.metadata().createdBy();
        createdAt = i.metadata().createdAt();
        lastModifiedBy = i.metadata().lastModifiedBy();
        lastModifiedAt = i.metadata().lastModifiedAt();
        sourceChannel = i.metadata().sourceChannel();
        correlationId = i.metadata().correlationId();
    }

    ReviewInterval toDomain() {
        return new ReviewInterval(riskLevel, intervalDays, reminderLeadDays, RecordMetadata.rehydrate(createdBy,
                createdAt, lastModifiedBy, lastModifiedAt, recordVersion, sourceChannel, correlationId));
    }
}
