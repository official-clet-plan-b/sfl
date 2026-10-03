package gh.edu.clet.sfl.facilities.lostfound.application;

import gh.edu.clet.sfl.facilities.lostfound.domain.Claim;
import gh.edu.clet.sfl.facilities.lostfound.domain.ClaimStatus;
import gh.edu.clet.sfl.facilities.lostfound.domain.CustodyEvent;
import gh.edu.clet.sfl.facilities.lostfound.domain.EscalationReason;
import gh.edu.clet.sfl.facilities.lostfound.domain.EvidenceKind;
import gh.edu.clet.sfl.facilities.lostfound.domain.FoundItem;
import gh.edu.clet.sfl.facilities.lostfound.domain.ItemCategory;
import gh.edu.clet.sfl.facilities.lostfound.domain.ItemStatus;
import gh.edu.clet.sfl.facilities.lostfound.domain.LfEscalation;
import gh.edu.clet.sfl.facilities.lostfound.domain.LfEvidence;
import gh.edu.clet.sfl.facilities.lostfound.domain.LfHistoryEntry;
import gh.edu.clet.sfl.facilities.lostfound.domain.RetentionPolicy;
import gh.edu.clet.sfl.facilities.lostfound.domain.StorageLocation;
import gh.edu.clet.sfl.facilities.lostfound.domain.VerificationMethod;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * SQL for the S179 tables. The service owns the transaction and the authorisation; this owns the columns.
 * Every query runs under the site-scope row-level policy. The custody table is additionally protected by a
 * database trigger that refuses UPDATE and DELETE, so "immutable" does not rest on this class behaving.
 */
@Repository
public class LostFoundStore {

    private static final String ITEM = """
            id, reference, claim_reference, site_code, category, public_description, private_description, found_location,
            found_at, finder_reference, initial_condition, status, unsafe, unsafe_reason, storage_location_id,
            retention_until, closed_at, created_by, created_at, updated_at, version""";

    private static final String CLAIM = """
            id, reference, item_id, site_code, claimant_name, claimant_contact, claimant_description, status,
            identity_verified, verification_method, verification_reference, verified_by, verified_at, decision_reason,
            decided_by, decided_at, released_by, released_at, closed_at, personal_data_purged_at, created_by,
            created_at, updated_at, version""";

    private static final String EVIDENCE = """
            id, item_id, claim_id, site_code, kind, reference, file_name, media_type, size_bytes, content_hash,
            retention_class, submitted_by, submitted_at""";

    private final JdbcTemplate jdbc;

    public LostFoundStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long nextSequence(String name) {
        return jdbc.queryForObject("SELECT nextval(?::regclass)", Long.class, "facilities." + name);
    }

    // ---- retention policy

    public List<RetentionPolicy> policies() {
        return jdbc.query("SELECT category, unclaimed_days, personal_data_days FROM facilities.lf_retention_policies"
                + " ORDER BY category", (rs, n) -> new RetentionPolicy(ItemCategory.valueOf(rs.getString(1)),
                        rs.getInt(2), rs.getInt(3)));
    }

    public RetentionPolicy policy(ItemCategory category) {
        return jdbc.queryForObject("SELECT category, unclaimed_days, personal_data_days FROM"
                + " facilities.lf_retention_policies WHERE category = ?", (rs, n) -> new RetentionPolicy(
                        ItemCategory.valueOf(rs.getString(1)), rs.getInt(2), rs.getInt(3)), category.name());
    }

    public void save(RetentionPolicy policy) {
        jdbc.update("""
                INSERT INTO facilities.lf_retention_policies (category, unclaimed_days, personal_data_days)
                VALUES (?, ?, ?)
                ON CONFLICT (category) DO UPDATE SET unclaimed_days = EXCLUDED.unclaimed_days,
                    personal_data_days = EXCLUDED.personal_data_days
                """, policy.category().name(), policy.unclaimedDays(), policy.personalDataDays());
    }

    // ---- storage locations

    public void insert(StorageLocation l) {
        jdbc.update("""
                INSERT INTO facilities.lf_storage_locations (id, site_code, code, name, secure, active, created_by,
                    created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, l.id(), l.siteCode(), l.code(), l.name(), l.secure(), l.active(), l.createdBy(),
                ts(l.createdAt()), ts(l.updatedAt()), l.version());
    }

    public Optional<StorageLocation> location(UUID id) {
        return jdbc.query("SELECT * FROM facilities.lf_storage_locations WHERE id = ?", (rs, n) -> location(rs), id)
                .stream().findFirst();
    }

    public List<StorageLocation> locations(String site) {
        return jdbc.query("SELECT * FROM facilities.lf_storage_locations WHERE site_code = ? ORDER BY code",
                (rs, n) -> location(rs), site);
    }

    public boolean update(StorageLocation l, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.lf_storage_locations SET name = ?, secure = ?, active = ?, updated_at = ?,
                    version = version + 1 WHERE id = ? AND version = ?
                """, l.name(), l.secure(), l.active(), ts(l.updatedAt()), l.id(), expectedVersion) == 1;
    }

    // ---- items

    public boolean insert(FoundItem i) {
        return jdbc.update("INSERT INTO facilities.lf_items (" + ITEM + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (claim_reference) DO NOTHING",
                i.id(), i.reference(), i.claimReference(), i.siteCode(), i.category().name(), i.publicDescription(),
                i.privateDescription(), i.foundLocation(), ts(i.foundAt()), i.finderReference(), i.initialCondition(),
                i.status().name(), i.unsafe(), i.unsafeReason(), i.storageLocationId(), i.retentionUntil(),
                ts(i.closedAt()), i.createdBy(), ts(i.createdAt()), ts(i.updatedAt()), i.version()) == 1;
    }

    public Optional<FoundItem> item(UUID id) {
        return jdbc.query("SELECT " + ITEM + " FROM facilities.lf_items WHERE id = ?", (rs, n) -> item(rs), id)
                .stream().findFirst();
    }

    public Optional<FoundItem> itemByClaimReference(String claimReference) {
        return jdbc.query("SELECT " + ITEM + " FROM facilities.lf_items WHERE claim_reference = ?",
                (rs, n) -> item(rs), claimReference).stream().findFirst();
    }

    public boolean update(FoundItem i, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.lf_items SET public_description = ?, private_description = ?, found_location = ?,
                    initial_condition = ?, status = ?, unsafe = ?, unsafe_reason = ?, storage_location_id = ?,
                    retention_until = ?, closed_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, i.publicDescription(), i.privateDescription(), i.foundLocation(), i.initialCondition(),
                i.status().name(), i.unsafe(), i.unsafeReason(), i.storageLocationId(), i.retentionUntil(),
                ts(i.closedAt()), ts(i.updatedAt()), i.id(), expectedVersion) == 1;
    }

    public Page<FoundItem> items(String site, String status, String category, boolean openOnly, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE site_code = ?");
        List<Object> args = new ArrayList<>(List.of(site));
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (category != null) {
            where.append(" AND category = ?");
            args.add(category);
        }
        if (openOnly) {
            where.append(" AND status IN ('REGISTERED', 'STORED', 'ISOLATED')");
        }
        return page("facilities.lf_items", ITEM, where.toString(), args, " ORDER BY found_at DESC, reference DESC",
                page, size, (rs, n) -> item(rs));
    }

    /** Stored items whose retention has expired and which no claim is in play on - the sweep escalates these. */
    public List<FoundItem> expiredUnclaimed(LocalDate today) {
        return jdbc.query("SELECT " + ITEM + """
                 FROM facilities.lf_items i WHERE status IN ('REGISTERED', 'STORED') AND retention_until < ?
                   AND NOT EXISTS (SELECT 1 FROM facilities.lf_claims c WHERE c.item_id = i.id
                                   AND c.status IN ('RECEIVED', 'VERIFIED', 'APPROVED'))
                """, (rs, n) -> item(rs), today);
    }

    // ---- custody

    public void insert(CustodyEvent e) {
        jdbc.update("INSERT INTO facilities.lf_custody_events (id, item_id, site_code, from_party, to_party, location,"
                + " occurred_at, reason, recorded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", e.id(), e.itemId(),
                e.siteCode(), e.fromParty(), e.toParty(), e.location(), ts(e.occurredAt()), e.reason(), e.recordedBy());
    }

    public List<CustodyEvent> custody(UUID itemId) {
        return jdbc.query("SELECT * FROM facilities.lf_custody_events WHERE item_id = ? ORDER BY occurred_at, id",
                (rs, n) -> new CustodyEvent(rs.getObject("id", UUID.class), rs.getObject("item_id", UUID.class),
                        rs.getString("site_code"), rs.getString("from_party"), rs.getString("to_party"),
                        rs.getString("location"), instant(rs, "occurred_at"), rs.getString("reason"),
                        rs.getString("recorded_by")), itemId);
    }

    // ---- claims

    public void insert(Claim c) {
        jdbc.update("INSERT INTO facilities.lf_claims (" + CLAIM + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", c.id(), c.reference(), c.itemId(), c.siteCode(),
                c.claimantName(), c.claimantContact(), c.claimantDescription(), c.status().name(),
                c.identityVerified(), c.verificationMethod() == null ? null : c.verificationMethod().name(),
                c.verificationReference(), c.verifiedBy(), ts(c.verifiedAt()), c.decisionReason(), c.decidedBy(),
                ts(c.decidedAt()), c.releasedBy(), ts(c.releasedAt()), ts(c.closedAt()),
                ts(c.personalDataPurgedAt()), c.createdBy(), ts(c.createdAt()), ts(c.updatedAt()), c.version());
    }

    public Optional<Claim> claim(UUID id) {
        return jdbc.query("SELECT " + CLAIM + " FROM facilities.lf_claims WHERE id = ?", (rs, n) -> claim(rs), id)
                .stream().findFirst();
    }

    public boolean update(Claim c, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.lf_claims SET claimant_name = ?, claimant_contact = ?, claimant_description = ?,
                    status = ?, identity_verified = ?, verification_method = ?, verification_reference = ?,
                    verified_by = ?, verified_at = ?, decision_reason = ?, decided_by = ?, decided_at = ?,
                    released_by = ?, released_at = ?, closed_at = ?, personal_data_purged_at = ?, updated_at = ?,
                    version = version + 1
                 WHERE id = ? AND version = ?
                """, c.claimantName(), c.claimantContact(), c.claimantDescription(), c.status().name(),
                c.identityVerified(), c.verificationMethod() == null ? null : c.verificationMethod().name(),
                c.verificationReference(), c.verifiedBy(), ts(c.verifiedAt()), c.decisionReason(), c.decidedBy(),
                ts(c.decidedAt()), c.releasedBy(), ts(c.releasedAt()), ts(c.closedAt()),
                ts(c.personalDataPurgedAt()), ts(c.updatedAt()), c.id(), expectedVersion) == 1;
    }

    public List<Claim> claimsOf(UUID itemId) {
        return jdbc.query("SELECT " + CLAIM + " FROM facilities.lf_claims WHERE item_id = ? ORDER BY created_at",
                (rs, n) -> claim(rs), itemId);
    }

    public List<Claim> openClaimsExcept(UUID itemId, UUID claimId) {
        return jdbc.query("SELECT " + CLAIM + " FROM facilities.lf_claims WHERE item_id = ? AND id <> ?"
                + " AND status IN ('RECEIVED', 'VERIFIED', 'APPROVED')", (rs, n) -> claim(rs), itemId, claimId);
    }

    public Page<Claim> claims(String site, String status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE site_code = ?");
        List<Object> args = new ArrayList<>(List.of(site));
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        return page("facilities.lf_claims", CLAIM, where.toString(), args, " ORDER BY created_at DESC", page, size,
                (rs, n) -> claim(rs));
    }

    /** Closed claims whose personal data has outlived the configured period for the item's category. */
    public List<Claim> claimsDueForPurge(Instant now) {
        return jdbc.query("""
                SELECT c.* FROM facilities.lf_claims c
                 JOIN facilities.lf_items i ON i.id = c.item_id
                 JOIN facilities.lf_retention_policies p ON p.category = i.category
                WHERE c.closed_at IS NOT NULL AND c.personal_data_purged_at IS NULL
                  AND c.closed_at + (p.personal_data_days || ' days')::interval < ?
                """, (rs, n) -> claim(rs), ts(now));
    }

    // ---- evidence

    public void insert(LfEvidence e) {
        jdbc.update("INSERT INTO facilities.lf_evidence (" + EVIDENCE + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                e.id(), e.itemId(), e.claimId(), e.siteCode(), e.kind().name(), e.reference(), e.fileName(),
                e.mediaType(), e.sizeBytes(), e.contentHash(), e.retentionClass(), e.submittedBy(),
                ts(e.submittedAt()));
    }

    public List<LfEvidence> evidenceOf(UUID itemId) {
        return jdbc.query("SELECT " + EVIDENCE + " FROM facilities.lf_evidence WHERE item_id = ? ORDER BY submitted_at",
                (rs, n) -> evidence(rs), itemId);
    }

    // ---- escalations

    public boolean insert(LfEscalation e) {
        return jdbc.update("""
                INSERT INTO facilities.lf_escalations (id, site_code, item_id, claim_id, reason, escalated_to, detail,
                    incident_state, incident_reference, raised_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, e.id(), e.siteCode(), e.itemId(), e.claimId(), e.reason().name(), e.escalatedTo(), e.detail(),
                e.incidentState(), e.incidentReference(), ts(e.raisedAt())) == 1;
    }

    public Optional<LfEscalation> escalation(UUID id) {
        return jdbc.query("SELECT * FROM facilities.lf_escalations WHERE id = ?", (rs, n) -> escalation(rs), id)
                .stream().findFirst();
    }

    public void acknowledge(UUID id, String by, Instant at, String incidentReference) {
        jdbc.update("UPDATE facilities.lf_escalations SET acknowledged_by = ?, acknowledged_at = ?,"
                + " incident_reference = COALESCE(?, incident_reference),"
                + " incident_state = CASE WHEN ? IS NULL THEN incident_state ELSE 'LINKED' END"
                + " WHERE id = ? AND acknowledged_at IS NULL", by, ts(at), incidentReference, incidentReference, id);
    }

    public void linkIncident(UUID id, String reference) {
        jdbc.update("UPDATE facilities.lf_escalations SET incident_state = 'LINKED', incident_reference = ?"
                + " WHERE id = ?", reference, id);
    }

    public void setIncidentPending(UUID id) {
        jdbc.update("UPDATE facilities.lf_escalations SET incident_state = 'PENDING_MANUAL' WHERE id = ?", id);
    }

    public Page<LfEscalation> escalations(String site, boolean openOnly, int page, int size) {
        String where = " WHERE site_code = ?" + (openOnly ? " AND acknowledged_at IS NULL" : "");
        return page("facilities.lf_escalations", "*", where, List.of(site), " ORDER BY raised_at DESC", page, size,
                (rs, n) -> escalation(rs));
    }

    // ---- history

    public void record(LfHistoryEntry h) {
        jdbc.update("INSERT INTO facilities.lf_history (id, site_code, subject_type, subject_id, from_status, to_status,"
                + " actor, reason, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", h.id(), h.siteCode(),
                h.subjectType(), h.subjectId(), h.fromStatus(), h.toStatus(), h.actor(), h.reason(),
                ts(h.occurredAt()));
    }

    public List<LfHistoryEntry> history(UUID subjectId) {
        return jdbc.query("SELECT * FROM facilities.lf_history WHERE subject_id = ? ORDER BY occurred_at, id",
                (rs, n) -> new LfHistoryEntry(rs.getObject("id", UUID.class), rs.getString("site_code"),
                        rs.getString("subject_type"), rs.getObject("subject_id", UUID.class),
                        rs.getString("from_status"), rs.getString("to_status"), rs.getString("actor"),
                        rs.getString("reason"), instant(rs, "occurred_at")), subjectId);
    }

    // ---- metrics

    public AgeBuckets openByAge(String site, Instant now) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE found_at >= ?::timestamptz - interval '7 days'),
                       count(*) FILTER (WHERE found_at < ?::timestamptz - interval '7 days' AND found_at >= ?::timestamptz - interval '30 days'),
                       count(*) FILTER (WHERE found_at < ?::timestamptz - interval '30 days' AND found_at >= ?::timestamptz - interval '90 days'),
                       count(*) FILTER (WHERE found_at < ?::timestamptz - interval '90 days')
                  FROM facilities.lf_items WHERE site_code = ? AND status IN ('REGISTERED', 'STORED', 'ISOLATED')
                """, (rs, n) -> new AgeBuckets(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                ts(now), ts(now), ts(now), ts(now), ts(now), ts(now), site);
    }

    public List<LocationCount> openByLocation(String site) {
        return jdbc.query("""
                SELECT COALESCE(l.name, 'Not yet stored') AS name, count(*) FROM facilities.lf_items i
                  LEFT JOIN facilities.lf_storage_locations l ON l.id = i.storage_location_id
                 WHERE i.site_code = ? AND i.status IN ('REGISTERED', 'STORED', 'ISOLATED')
                 GROUP BY 1 ORDER BY 2 DESC, 1
                """, (rs, n) -> new LocationCount(rs.getString(1), rs.getLong(2)), site);
    }

    public Double meanHoursToRelease(String site) {
        return jdbc.queryForObject("""
                SELECT avg(extract(epoch FROM (released_at - created_at)) / 3600.0) FROM facilities.lf_claims
                 WHERE site_code = ? AND status = 'RELEASED' AND identity_verified
                """, Double.class, site);
    }

    /** {items with a chain, items whose chain is unbroken} - a break is a transfer that does not pick up where the last stopped. */
    public long[] custodyCompleteness(String site) {
        return jdbc.queryForObject("""
                WITH ordered AS (
                    SELECT item_id, from_party,
                           lag(to_party) OVER (PARTITION BY item_id ORDER BY occurred_at, id) AS previous_to
                      FROM facilities.lf_custody_events WHERE site_code = ?),
                broken AS (SELECT DISTINCT item_id FROM ordered WHERE previous_to IS NOT NULL AND previous_to <> from_party)
                SELECT (SELECT count(DISTINCT item_id) FROM ordered), (SELECT count(*) FROM broken)
                """, (rs, n) -> new long[] {rs.getLong(1), rs.getLong(1) - rs.getLong(2)}, site);
    }

    /** {items closed, items closed within policy}: returned before retention ran out, or disposed of only after it (or unsafe). */
    public long[] withinPolicy(String site) {
        return jdbc.queryForObject("""
                SELECT count(*), count(*) FILTER (WHERE
                           (status = 'RELEASED' AND closed_at::date <= retention_until)
                        OR (status = 'DISPOSED' AND (closed_at::date >= retention_until OR unsafe))
                        OR status = 'HANDED_TO_AUTHORITIES')
                  FROM facilities.lf_items WHERE site_code = ? AND status IN ('RELEASED', 'DISPOSED', 'HANDED_TO_AUTHORITIES')
                """, (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}, site);
    }

    public long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    public record AgeBuckets(long upTo7Days, long from8To30Days, long from31To90Days, long over90Days) {
    }

    public record LocationCount(String location, long items) {
    }

    // ---- paging and mapping

    public record Page<T>(List<T> items, long total, int page, int size) {
    }

    private <T> Page<T> page(String table, String columns, String where, List<Object> args, String order, int page,
            int size, RowMapper<T> mapper) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM " + table + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<T> items = jdbc.query("SELECT " + columns + " FROM " + table + where + order + " LIMIT ? OFFSET ?",
                mapper, pageArgs.toArray());
        return new Page<>(items, total == null ? 0 : total, page, size);
    }

    private static Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static StorageLocation location(ResultSet rs) throws SQLException {
        return new StorageLocation(rs.getObject("id", UUID.class), rs.getString("site_code"), rs.getString("code"),
                rs.getString("name"), rs.getBoolean("secure"), rs.getBoolean("active"), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static FoundItem item(ResultSet rs) throws SQLException {
        return new FoundItem(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("claim_reference"),
                rs.getString("site_code"), ItemCategory.valueOf(rs.getString("category")),
                rs.getString("public_description"), rs.getString("private_description"), rs.getString("found_location"),
                instant(rs, "found_at"), rs.getString("finder_reference"), rs.getString("initial_condition"),
                ItemStatus.valueOf(rs.getString("status")), rs.getBoolean("unsafe"), rs.getString("unsafe_reason"),
                rs.getObject("storage_location_id", UUID.class), rs.getObject("retention_until", LocalDate.class),
                instant(rs, "closed_at"), rs.getString("created_by"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static Claim claim(ResultSet rs) throws SQLException {
        String method = rs.getString("verification_method");
        return new Claim(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getObject("item_id", UUID.class),
                rs.getString("site_code"), rs.getString("claimant_name"), rs.getString("claimant_contact"),
                rs.getString("claimant_description"), ClaimStatus.valueOf(rs.getString("status")),
                rs.getBoolean("identity_verified"), method == null ? null : VerificationMethod.valueOf(method),
                rs.getString("verification_reference"), rs.getString("verified_by"), instant(rs, "verified_at"),
                rs.getString("decision_reason"), rs.getString("decided_by"), instant(rs, "decided_at"),
                rs.getString("released_by"), instant(rs, "released_at"), instant(rs, "closed_at"),
                instant(rs, "personal_data_purged_at"), rs.getString("created_by"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static LfEvidence evidence(ResultSet rs) throws SQLException {
        return new LfEvidence(rs.getObject("id", UUID.class), rs.getObject("item_id", UUID.class),
                rs.getObject("claim_id", UUID.class), rs.getString("site_code"),
                EvidenceKind.valueOf(rs.getString("kind")), rs.getString("reference"), rs.getString("file_name"),
                rs.getString("media_type"), rs.getLong("size_bytes"), rs.getString("content_hash"),
                rs.getString("retention_class"), rs.getString("submitted_by"), instant(rs, "submitted_at"));
    }

    private static LfEscalation escalation(ResultSet rs) throws SQLException {
        return new LfEscalation(rs.getObject("id", UUID.class), rs.getString("site_code"),
                rs.getObject("item_id", UUID.class), rs.getObject("claim_id", UUID.class),
                EscalationReason.valueOf(rs.getString("reason")), rs.getString("escalated_to"), rs.getString("detail"),
                rs.getString("incident_state"), rs.getString("incident_reference"), instant(rs, "raised_at"),
                rs.getString("acknowledged_by"), instant(rs, "acknowledged_at"));
    }
}
