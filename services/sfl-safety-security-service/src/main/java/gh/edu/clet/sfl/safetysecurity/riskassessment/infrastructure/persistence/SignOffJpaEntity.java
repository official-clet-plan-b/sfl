package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.persistence;

import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SignOff;
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
@Table(name = "risk_assessment_sign_offs", schema = "safety_security")
public class SignOffJpaEntity {

    @Id
    private UUID id;
    @Column(name = "assessment_id", nullable = false)
    private UUID assessmentId;
    @Column(name = "version_id", nullable = false)
    private UUID versionId;
    @Column(name = "version_number", nullable = false)
    private int versionNumber;
    @Column(name = "site_code", nullable = false, length = 80)
    private String siteCode;
    @Column(name = "reviewer_id", nullable = false, length = 160)
    private String reviewerId;
    @Column(name = "reviewer_name", length = 200)
    private String reviewerName;
    @Column(name = "signed_off_at", nullable = false)
    private Instant signedOffAt;
    @Column(length = 2000)
    private String notes;
    @Column(name = "previous_review_due_at")
    private Instant previousReviewDueAt;
    @Column(name = "review_due_at", nullable = false)
    private Instant reviewDueAt;
    @Column(nullable = false)
    private boolean independent;
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

    protected SignOffJpaEntity() {
    }

    static SignOffJpaEntity from(SignOff s) {
        SignOffJpaEntity e = new SignOffJpaEntity();
        e.id = s.id();
        e.assessmentId = s.assessmentId();
        e.versionId = s.versionId();
        e.versionNumber = s.versionNumber();
        e.siteCode = s.siteCode();
        e.reviewerId = s.reviewerId();
        e.reviewerName = s.reviewerName();
        e.signedOffAt = s.signedOffAt();
        e.notes = s.notes();
        e.previousReviewDueAt = s.previousReviewDueAt();
        e.reviewDueAt = s.reviewDueAt();
        e.independent = s.independent();
        e.createdBy = s.metadata().createdBy();
        e.createdAt = s.metadata().createdAt();
        e.lastModifiedBy = s.metadata().lastModifiedBy();
        e.lastModifiedAt = s.metadata().lastModifiedAt();
        e.sourceChannel = s.metadata().sourceChannel();
        e.correlationId = s.metadata().correlationId();
        return e;
    }

    SignOff toDomain() {
        return new SignOff(id, assessmentId, versionId, versionNumber, siteCode, reviewerId, reviewerName, signedOffAt,
                notes, previousReviewDueAt, reviewDueAt, independent, RecordMetadata.rehydrate(createdBy, createdAt,
                        lastModifiedBy, lastModifiedAt, recordVersion, sourceChannel, correlationId));
    }
}
