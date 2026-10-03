package gh.edu.clet.sfl.safetysecurity.platform.infrastructure.persistence;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.AuditPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Append-only, tamper-evident audit log for {@code safety_security}, one table shared by every
 * module in the schema rather than one per module.
 *
 * <p>Deliberately JDBC, matching S174's {@code JdbcAuditAdapter} in the same service, even though
 * {@code safety_security} is Hibernate's JPA default schema. An append-only chain table with no
 * update path and one write shape gets nothing from an {@code @Entity} mapping, and JDBC keeps the
 * hash computation and the insert in the same place, the way the S174 precedent already does.
 *
 * <p>Each row links to the previous via a hash over its scalar who/what/when + chain fields;
 * {@link #verifyChain()} replays and reports the first divergence, so a mutated row is detected.
 * Before/after images are stored as JSON for forensics.
 */
@Component
public class AuditAdapter implements AuditPort {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public AuditAdapter(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void record(ActorContext actor, String sourceChannel, String siteScope, String action,
            String resourceType, String resourceId, Object beforeValue, Object afterValue, String reason) {
        long sequence = nextSequence();
        String previousHash = jdbc.query(
                "SELECT record_hash FROM safety_security.audit_log WHERE sequence_no=?",
                rs -> rs.next() ? rs.getString(1) : null, sequence - 1);
        // PostgreSQL stores microseconds; a Java Instant on Linux carries nanoseconds. The hash is over epoch milliseconds, so an
        // instant a fraction of a microsecond short of a millisecond boundary is hashed in one millisecond and read back, rounded up,
        // in the next - and that record replays as tampered. Truncating first makes what is stored and what is hashed the same.
        Instant occurredAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String recordHash = hash(sequence, actor.actorId(), action, resourceType, resourceId, siteScope,
                sourceChannel, occurredAt, previousHash);
        jdbc.update("""
                INSERT INTO safety_security.audit_log (id,sequence_no,actor,action,resource_type,resource_id,
                    site_scope,before_value,after_value,source_channel,correlation_id,reason,occurred_at,
                    previous_hash,record_hash)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?)
                """, UUID.randomUUID(), sequence, actor.actorId(), action, resourceType, resourceId, siteScope,
                jsonOrNull(beforeValue), jsonOrNull(afterValue), sourceChannel, actor.correlationId(), reason,
                OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC), previousHash, recordHash);
    }

    @Override
    public AuditVerification verifyChain() {
        var rows = jdbc.query("""
                SELECT sequence_no,actor,action,resource_type,resource_id,site_scope,source_channel,occurred_at,
                    previous_hash,record_hash FROM safety_security.audit_log ORDER BY sequence_no
                """, (rs, n) -> new Row(rs.getLong("sequence_no"), rs.getString("actor"), rs.getString("action"),
                rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("site_scope"),
                rs.getString("source_channel"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("previous_hash"), rs.getString("record_hash")));
        String previousHash = null;
        long checked = 0;
        for (Row row : rows) {
            String expected = hash(row.sequenceNo, row.actor, row.action, row.resourceType, row.resourceId,
                    row.siteScope, row.sourceChannel, row.occurredAt, previousHash);
            if (!expected.equals(row.recordHash)) {
                return new AuditVerification(false, checked, row.sequenceNo,
                        "Audit hash divergence at sequence " + row.sequenceNo);
            }
            previousHash = row.recordHash;
            checked++;
        }
        return new AuditVerification(true, checked, null, null);
    }

    /**
     * Hands out the next sequence number from {@code audit_log_sequence_no_seq} (see the V13
     * migration) rather than the {@code SELECT MAX(sequence_no)+1} this replaced. The old read-then-
     * insert had no lock spanning the read and the write, so two concurrent audit writes from
     * different subdomains sharing this one table could compute the same "next" value; the
     * {@code UNIQUE(sequence_no)} constraint then let the second insert fail with a constraint
     * violation, aborting an otherwise-valid business transaction. {@code nextval()} is a single
     * atomic operation on Postgres - every caller gets a distinct value with no shared window to race
     * in, even under heavy concurrency.
     *
     * <p>One residual, accepted trade-off: {@code nextval()} is not transactional (a value handed out
     * to a transaction that later rolls back is never reused, and a value can be committed out of
     * numeric order relative to when it was drawn). {@link #record} still looks up
     * {@code previous_hash} for {@code sequence - 1} at insert time, so if two audit writes are ever
     * truly concurrent enough to commit out of sequence order, the later-committing row's hash link
     * can point at a {@code previous_hash} of {@code null} instead of the row that ends up before it.
     * {@link #verifyChain()} exists precisely to surface that kind of anomaly for investigation; in
     * practice, audit writes are triggered by already-serialized business transactions per aggregate,
     * so true concurrent commits across unrelated aggregates racing to adjacent sequence numbers are
     * rare, and no longer cost the business transaction itself a failure.
     */
    private long nextSequence() {
        Long next = jdbc.queryForObject("SELECT nextval('safety_security.audit_log_sequence_no_seq')", Long.class);
        if (next == null) {
            // Postgres's nextval() never actually returns SQL NULL - it raises an error instead if the
            // sequence is missing - so this is unreachable in practice. Guarded anyway rather than let
            // the auto-unboxing NPE on the return statement stand as the failure mode for a genuinely
            // corrupted deployment (the sequence dropped or renamed underneath a running service).
            throw new IllegalStateException("audit_log_sequence_no_seq.nextval() returned no value");
        }
        return next;
    }

    private String hash(long sequence, String actor, String action, String resourceType, String resourceId,
            String siteScope, String sourceChannel, Instant occurredAt, String previousHash) {
        String canonical = sequence + "|" + n(actor) + "|" + n(action) + "|" + n(resourceType) + "|" + n(resourceId)
                + "|" + n(siteScope) + "|" + n(sourceChannel) + "|" + occurredAt.toEpochMilli() + "|" + n(previousHash);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String jsonOrNull(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    private static String n(String value) {
        return value == null ? "" : value;
    }

    private record Row(long sequenceNo, String actor, String action, String resourceType, String resourceId,
            String siteScope, String sourceChannel, Instant occurredAt, String previousHash, String recordHash) {
    }
}
