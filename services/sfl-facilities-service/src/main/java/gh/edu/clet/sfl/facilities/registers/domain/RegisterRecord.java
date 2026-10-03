package gh.edu.clet.sfl.facilities.registers.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A persisted operational record shared by the S172, S177, S178 and S179 registers.
 *
 * <p>The record type and details fields carry the system-specific facts while the lifecycle, scope,
 * ownership and audit behaviour stays consistent with the rest of IFIMP. This is deliberately a
 * bounded register record, not an enterprise master-data replacement: sites, people, suppliers,
 * contracts and finance references remain owned by their source systems.
 */
public record RegisterRecord(
        UUID id,
        String systemCode,
        String siteCode,
        String recordType,
        String title,
        String status,
        String ownerReference,
        Instant dueAt,
        String severity,
        String details,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
