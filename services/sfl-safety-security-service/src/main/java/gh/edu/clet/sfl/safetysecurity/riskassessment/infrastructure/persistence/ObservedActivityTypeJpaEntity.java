package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.persistence;

import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ObservedActivityType;
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

@Entity
@Table(name = "risk_observed_activity_types", schema = "safety_security")
public class ObservedActivityTypeJpaEntity {

    @Id
    private UUID id;
    @Column(name = "site_code", nullable = false, length = 80)
    private String siteCode;
    @Column(name = "activity_type", nullable = false, length = 80)
    private String activityType;
    @Column(name = "source_system", nullable = false, length = 20)
    private String sourceSystem;
    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
    @Column(nullable = false)
    private long occurrences;
    @Column(name = "last_source_reference", length = 120)
    private String lastSourceReference;
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

    protected ObservedActivityTypeJpaEntity() {
    }

    static ObservedActivityTypeJpaEntity create(UUID id) {
        ObservedActivityTypeJpaEntity entity = new ObservedActivityTypeJpaEntity();
        entity.id = id;
        return entity;
    }

    void apply(ObservedActivityType o) {
        siteCode = o.siteCode();
        activityType = o.activityType();
        sourceSystem = o.sourceSystem();
        firstSeenAt = o.firstSeenAt();
        lastSeenAt = o.lastSeenAt();
        occurrences = o.occurrences();
        lastSourceReference = o.lastSourceReference();
        createdBy = o.metadata().createdBy();
        createdAt = o.metadata().createdAt();
        lastModifiedBy = o.metadata().lastModifiedBy();
        lastModifiedAt = o.metadata().lastModifiedAt();
        sourceChannel = o.metadata().sourceChannel();
        correlationId = o.metadata().correlationId();
    }

    ObservedActivityType toDomain() {
        return new ObservedActivityType(id, siteCode, activityType, sourceSystem, firstSeenAt, lastSeenAt, occurrences,
                lastSourceReference, RecordMetadata.rehydrate(createdBy, createdAt, lastModifiedBy, lastModifiedAt,
                        recordVersion, sourceChannel, correlationId));
    }
}
