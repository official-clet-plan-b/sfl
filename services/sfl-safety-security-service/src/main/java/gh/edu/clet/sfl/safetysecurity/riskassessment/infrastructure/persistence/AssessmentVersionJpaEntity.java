package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.persistence;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.Status;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentContent;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentVersion;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RecordMetadata;
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

/**
 * One version row. Its hazards and controls are not mapped here - {@link AssessmentContentJdbcStore}
 * writes them, after this row is flushed, so the replace-on-edit of a draft's hazards runs as one ordered
 * delete-then-insert rather than as Hibernate's inserts-before-deletes.
 */
@Entity
@Table(name = "risk_assessment_versions", schema = "safety_security")
public class AssessmentVersionJpaEntity {

    @Id
    private UUID id;
    @Column(name = "assessment_id", nullable = false)
    private UUID assessmentId;
    @Column(name = "site_code", nullable = false, length = 80)
    private String siteCode;
    @Column(name = "version_number", nullable = false)
    private int versionNumber;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(length = 4000)
    private String summary;
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 20)
    private RiskLevel riskLevel;
    @Column(name = "residual_score", nullable = false)
    private int residualScore;
    @Column(name = "author_id", nullable = false, length = 160)
    private String authorId;
    @Column(name = "author_name", length = 200)
    private String authorName;
    @Column(name = "review_interval_days")
    private Integer reviewIntervalDays;
    @Column(name = "review_due_at")
    private Instant reviewDueAt;
    @Column(name = "review_reminder_sent_at")
    private Instant reviewReminderSentAt;
    @Column(name = "review_lapsed_at")
    private Instant reviewLapsedAt;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(name = "published_by", length = 160)
    private String publishedBy;
    @Column(name = "signed_off_by", length = 160)
    private String signedOffBy;
    @Column(name = "signed_off_by_name", length = 200)
    private String signedOffByName;
    @Column(name = "signed_off_at")
    private Instant signedOffAt;
    @Column(name = "superseded_at")
    private Instant supersededAt;
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

    protected AssessmentVersionJpaEntity() {
    }

    static AssessmentVersionJpaEntity create(UUID id) {
        AssessmentVersionJpaEntity entity = new AssessmentVersionJpaEntity();
        entity.id = id;
        return entity;
    }

    UUID id() {
        return id;
    }

    String siteCode() {
        return siteCode;
    }

    void apply(AssessmentVersion v) {
        assessmentId = v.assessmentId();
        siteCode = v.siteCode();
        versionNumber = v.versionNumber();
        status = v.status();
        title = v.content().title();
        summary = v.content().summary();
        riskLevel = v.riskLevel();
        residualScore = v.content().residualScore();
        authorId = v.authorId();
        authorName = v.authorName();
        reviewIntervalDays = v.reviewIntervalDays();
        reviewDueAt = v.reviewDueAt();
        reviewReminderSentAt = v.reviewReminderSentAt();
        reviewLapsedAt = v.reviewLapsedAt();
        publishedAt = v.publishedAt();
        publishedBy = v.publishedBy();
        signedOffBy = v.signedOffBy();
        signedOffByName = v.signedOffByName();
        signedOffAt = v.signedOffAt();
        supersededAt = v.supersededAt();
        createdBy = v.metadata().createdBy();
        createdAt = v.metadata().createdAt();
        lastModifiedBy = v.metadata().lastModifiedBy();
        lastModifiedAt = v.metadata().lastModifiedAt();
        sourceChannel = v.metadata().sourceChannel();
        correlationId = v.metadata().correlationId();
    }

    /** @param content the hazards and controls, loaded separately by the content store */
    AssessmentVersion toDomain(AssessmentContent content) {
        return new AssessmentVersion(id, assessmentId, siteCode, versionNumber, status, content, authorId, authorName,
                reviewIntervalDays, reviewDueAt, reviewReminderSentAt, reviewLapsedAt, publishedAt, publishedBy,
                signedOffBy, signedOffByName, signedOffAt, supersededAt, RecordMetadata.rehydrate(createdBy, createdAt,
                        lastModifiedBy, lastModifiedAt, recordVersion, sourceChannel, correlationId));
    }

    String title() {
        return title;
    }

    String summary() {
        return summary;
    }
}
