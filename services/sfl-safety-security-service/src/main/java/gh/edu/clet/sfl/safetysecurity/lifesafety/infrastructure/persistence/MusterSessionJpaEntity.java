package gh.edu.clet.sfl.safetysecurity.lifesafety.infrastructure.persistence;

import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.MusterSession;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.MusterStatus;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.SourceChannel;
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
@Table(name = "lifesafety_muster_sessions", schema = "safety_security")
public class MusterSessionJpaEntity {

    @Id
    private UUID id;
    @Column(name = "site_code", nullable = false, length = 80)
    private String siteCode;
    @Column(name = "zone_code", nullable = false, length = 80)
    private String zoneCode;
    @Column(name = "triggering_event_id")
    private UUID triggeringEventId;
    @Column(name = "drill_id")
    private UUID drillId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MusterStatus status;
    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;
    @Column(name = "closed_at")
    private Instant closedAt;
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

    protected MusterSessionJpaEntity() {
    }

    public static MusterSessionJpaEntity from(MusterSession s) {
        MusterSessionJpaEntity e = new MusterSessionJpaEntity();
        e.apply(s);
        return e;
    }

    public void apply(MusterSession s) {
        id = s.id();
        siteCode = s.siteCode();
        zoneCode = s.zoneCode();
        triggeringEventId = s.triggeringEventId();
        drillId = s.drillId();
        status = s.status();
        openedAt = s.openedAt();
        closedAt = s.closedAt();
        createdBy = s.metadata().createdBy();
        createdAt = s.metadata().createdAt();
        lastModifiedBy = s.metadata().lastModifiedBy();
        lastModifiedAt = s.metadata().lastModifiedAt();
        sourceChannel = s.metadata().sourceChannel();
        correlationId = s.metadata().correlationId();
    }

    public MusterSession toDomain() {
        RecordMetadata metadata = RecordMetadata.rehydrate(createdBy, createdAt, lastModifiedBy, lastModifiedAt,
                recordVersion, sourceChannel, correlationId);
        return new MusterSession(id, siteCode, zoneCode, triggeringEventId, status, openedAt, closedAt, metadata,
                drillId);
    }
}
