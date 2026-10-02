package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.persistence;

import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentTemplate;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Hazard;
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
import java.util.List;
import java.util.UUID;

/** A template row; its hazards and controls are written by {@link AssessmentContentJdbcStore}, like a version's. */
@Entity
@Table(name = "risk_assessment_templates", schema = "safety_security")
public class AssessmentTemplateJpaEntity {

    @Id
    private UUID id;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(name = "activity_type", length = 80)
    private String activityType;
    @Column(length = 2000)
    private String description;
    @Column(nullable = false)
    private boolean active;
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

    protected AssessmentTemplateJpaEntity() {
    }

    static AssessmentTemplateJpaEntity create(UUID id) {
        AssessmentTemplateJpaEntity entity = new AssessmentTemplateJpaEntity();
        entity.id = id;
        return entity;
    }

    UUID id() {
        return id;
    }

    void apply(AssessmentTemplate t) {
        name = t.name();
        activityType = t.activityType();
        description = t.description();
        active = t.active();
        createdBy = t.metadata().createdBy();
        createdAt = t.metadata().createdAt();
        lastModifiedBy = t.metadata().lastModifiedBy();
        lastModifiedAt = t.metadata().lastModifiedAt();
        sourceChannel = t.metadata().sourceChannel();
        correlationId = t.metadata().correlationId();
    }

    AssessmentTemplate toDomain(List<Hazard> hazards) {
        return new AssessmentTemplate(id, name, activityType, description, active, hazards,
                RecordMetadata.rehydrate(createdBy, createdAt, lastModifiedBy, lastModifiedAt, recordVersion,
                        sourceChannel, correlationId));
    }
}
