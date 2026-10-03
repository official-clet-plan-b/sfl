package gh.edu.clet.sfl.facilities.lostfound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport;
import gh.edu.clet.sfl.facilities.lostfound.application.LostFoundClaimService;
import gh.edu.clet.sfl.facilities.lostfound.application.LostFoundItemService;
import gh.edu.clet.sfl.facilities.lostfound.application.LostFoundItemService.Register;
import gh.edu.clet.sfl.facilities.lostfound.application.LostFoundOpsService;
import gh.edu.clet.sfl.facilities.lostfound.application.ports.LostFoundEstatePort;
import gh.edu.clet.sfl.facilities.lostfound.domain.Claim;
import gh.edu.clet.sfl.facilities.lostfound.domain.ClaimStatus;
import gh.edu.clet.sfl.facilities.lostfound.domain.EscalationReason;
import gh.edu.clet.sfl.facilities.lostfound.domain.EvidenceKind;
import gh.edu.clet.sfl.facilities.lostfound.domain.FoundItem;
import gh.edu.clet.sfl.facilities.lostfound.domain.ItemCategory;
import gh.edu.clet.sfl.facilities.lostfound.domain.ItemStatus;
import gh.edu.clet.sfl.facilities.lostfound.domain.StorageLocation;
import gh.edu.clet.sfl.facilities.lostfound.domain.VerificationMethod;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * S179 against a real PostgreSQL, run as the roles that hold each grant. S152 is a double; the incident port is
 * the real outbox adapter. What is under test is the register's own rules: masking, an immutable custody chain,
 * verification before release, competing claims, separation of duties, retention and purge.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sfl.facilities.messaging.drainer-enabled=false",
        "sfl.maintenance.scheduling.enabled=false",
        "sfl.booking.scheduling.enabled=false",
        "sfl.hygiene.scheduling.enabled=false",
        "sfl.waste.scheduling.enabled=false",
        "sfl.lostfound.scheduling.enabled=false",
})
@EnabledIf(value = "gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available; see FacilitiesPostgresSupport.unavailableReason()")
class LostFoundServicePostgresTest {

    private static final String HASH = "f".repeat(64);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        FacilitiesPostgresSupport.datasource(registry);
    }

    @Autowired private LostFoundItemService items;
    @Autowired private LostFoundClaimService claims;
    @Autowired private LostFoundOpsService ops;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private LostFoundEstatePort estate;

    private String site;
    private Caller desk;
    private Caller reception;
    private Caller director;
    private Caller security;
    private StorageLocation shelf;
    private StorageLocation safe;

    @BeforeEach
    void setUp() {
        site = "LFD" + Math.abs(UUID.randomUUID().hashCode() % 1_000_000);
        when(estate.siteExists(anyString())).thenReturn(true);
        desk = caller("desk-user", SflRole.FACILITIES_MANAGER, site);
        reception = caller("reception-user", SflRole.RECEPTION_OFFICER, site);
        director = caller("director-user", SflRole.FACILITIES_DIRECTOR, site);
        security = caller("security-user", SflRole.SECURITY_DIRECTOR, site);
        shelf = items.createLocation(site, "SHELF", "General shelf", false, desk);
        safe = items.createLocation(site, "SAFE", "Cash safe", true, desk);
    }

    private static Caller caller(String id, SflRole role, String... sites) {
        return new Caller(new ActorContext(new SiteScopedPrincipal(id, id, Set.of(role), Set.of(sites), false), "corr-" + id),
                SourceChannel.WEB);
    }

    private FoundItem register(ItemCategory category, StorageLocation where, boolean unsafe) {
        return items.register(new Register(site, category, "Black bag", "Passport inside, in the name of A. Person",
                "Main lobby", Instant.now().minus(1, ChronoUnit.HOURS), "Visitor J. Doe", "Good", unsafe,
                unsafe ? "Unattended and ticking" : null, where == null ? null : where.id(), desk));
    }

    private FoundItem stored() {
        return register(ItemCategory.KEYS, shelf, false);
    }

    private void file(UUID itemId, UUID claimId, EvidenceKind kind, Caller by) {
        items.submitEvidence(itemId, claimId, kind, "REC-" + kind, "doc.pdf", "application/pdf", 10, HASH, by);
    }

    /** Receive, verify (reception), approve (director). Returns the approved claim. */
    private Claim approved(FoundItem item) {
        Claim claim = claims.receive(item.id(), "A. Person", "a@example.test", "A black bag with keys", desk);
        claims.verifyIdentity(claim.id(), VerificationMethod.ID_DOCUMENT, "VER-1", null, reception);
        return claims.approve(claim.id(), "Description matches", null, director);
    }

    @Test
    @DisplayName("registering gives the claimant a non-sensitive reference, a dated retention, and a custody chain that starts with the finder")
    void registering() {
        FoundItem item = register(ItemCategory.KEYS, shelf, false);

        assertThat(item.claimReference()).matches("LF-[A-Z0-9]{8}").doesNotContain(item.reference());
        assertThat(item.status()).isEqualTo(ItemStatus.STORED);
        assertThat(item.retentionUntil()).isAfter(java.time.LocalDate.now().plusDays(55));
        LostFoundItemService.ItemDetail detail = items.get(item.id(), desk);
        assertThat(detail.custody()).extracting(e -> e.fromParty()).containsExactly("Visitor J. Doe", detail.custody().get(0).toParty());
        assertThat(detail.custody()).allSatisfy(e -> assertThat(e.location()).isNotBlank());
        assertThat(detail.custodyComplete()).isTrue();
    }

    @Test
    @DisplayName("a category that must be kept secure cannot go on an ordinary shelf")
    void secure_storage() {
        assertThatThrownBy(() -> register(ItemCategory.DOCUMENT, shelf, false)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.LF_STORAGE_NOT_SECURE));
        assertThat(register(ItemCategory.DOCUMENT, safe, false).status()).isEqualTo(ItemStatus.STORED);
    }

    @Test
    @DisplayName("a transfer takes its sender from whoever holds the item, so the chain stays unbroken")
    void transfer_chain() {
        FoundItem item = register(ItemCategory.KEYS, null, false);
        items.transfer(item.id(), "Security office", "Security desk", "Moved for safekeeping", desk);
        items.transfer(item.id(), "Store: general shelf", "Shelf", null, desk);

        LostFoundItemService.ItemDetail detail = items.get(item.id(), desk);
        assertThat(detail.custody()).hasSize(3);
        assertThat(detail.custody().get(2).fromParty()).isEqualTo("Security office");
        assertThat(detail.custodyComplete()).isTrue();
        assertThatThrownBy(() -> items.transfer(item.id(), " ", "Desk", null, desk)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the database refuses to change or remove a custody event")
    void custody_is_immutable() {
        FoundItem item = stored();
        assertThatThrownBy(() -> jdbc.update("UPDATE facilities.lf_custody_events SET to_party = 'Nobody' WHERE item_id = ?", item.id()))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM facilities.lf_custody_events WHERE item_id = ?", item.id()))
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("without the private grant an item is masked: no private detail, no finder, no claimant, no evidence")
    void masking() {
        FoundItem item = register(ItemCategory.DOCUMENT, safe, false);
        Claim claim = claims.receive(item.id(), "A. Person", "a@example.test", "A passport", desk);
        Caller auditor = caller("aud", SflRole.COMPLIANCE_OFFICER, site);
        file(item.id(), null, EvidenceKind.PHOTO, desk);

        LostFoundItemService.ItemDetail masked = items.get(item.id(), auditor);
        assertThat(masked.privateView()).isFalse();
        assertThat(masked.item().privateDescription()).isNull();
        assertThat(masked.item().finderReference()).isNull();
        assertThat(masked.item().publicDescription()).isEqualTo("Black bag");
        assertThat(masked.claims()).allSatisfy(c -> assertThat(c.claimantName()).isNull());
        assertThat(masked.custody().get(0).fromParty()).isEqualTo("Finder");
        assertThat(items.list(site, null, null, false, 0, 10, auditor).items()).allSatisfy(i -> assertThat(i.privateDescription()).isNull());
        assertThat(claims.list(site, null, 0, 10, auditor).items()).allSatisfy(c -> assertThat(c.claimantContact()).isNull());
        assertThatThrownBy(() -> items.evidence(item.id(), auditor)).isInstanceOf(RuntimeException.class);

        LostFoundItemService.ItemDetail full = items.get(item.id(), desk);
        assertThat(full.item().privateDescription()).contains("Passport");
        assertThat(full.claims().get(0).claimantName()).isEqualTo(claim.claimantName());
        assertThat(items.evidence(item.id(), desk)).hasSize(1);
    }

    @Test
    @DisplayName("looking up by claim reference shows only the controlled description and the status")
    void lookup() {
        FoundItem item = register(ItemCategory.BAG, shelf, false);
        LostFoundItemService.PublicView view = items.lookup(item.claimReference().toLowerCase(), caller("aud", SflRole.COMPLIANCE_OFFICER, site));
        assertThat(view.publicDescription()).isEqualTo("Black bag");
        assertThat(view.status()).isEqualTo(ItemStatus.STORED);
        assertThatThrownBy(() -> items.lookup("LF-NOSUCHREF", desk)).isInstanceOf(FacilitiesException.class);
    }

    @Test
    @DisplayName("a claimant sees the private detail only after their identity is verified")
    void claimant_view() {
        FoundItem item = register(ItemCategory.BAG, shelf, false);
        Claim claim = claims.receive(item.id(), "A. Person", "a@example.test", "A black bag", desk);
        assertThat(claims.claimantView(claim.id(), desk).privateDescription()).isNull();
        claims.verifyIdentity(claim.id(), VerificationMethod.STAFF_ID, "VER-2", null, reception);
        assertThat(claims.claimantView(claim.id(), desk).privateDescription()).contains("Passport");
    }

    @Test
    @DisplayName("an item is released to a verified claimant, after approval, with a receipt, and the chain ends with the claim reference, not a name")
    void release_happy_path() {
        FoundItem item = stored();
        Claim approved = approved(item);
        file(item.id(), approved.id(), EvidenceKind.RELEASE_RECEIPT, desk);

        Claim released = claims.release(approved.id(), true, "Signed for at the desk", null, desk);

        assertThat(released.status()).isEqualTo(ClaimStatus.RELEASED);
        LostFoundItemService.ItemDetail detail = items.get(item.id(), desk);
        assertThat(detail.item().status()).isEqualTo(ItemStatus.RELEASED);
        assertThat(detail.custody().get(detail.custody().size() - 1).toParty()).isEqualTo("Claimant " + released.reference());
        assertThat(detail.custody().get(detail.custody().size() - 1).toParty()).doesNotContain("A. Person");
        assertThat(detail.custodyComplete()).isTrue();
    }

    @Test
    @DisplayName("releasing to an unverified claimant is blocked, and the reason is recorded where the refusal cannot undo it")
    void unverified_release_is_blocked_and_recorded() {
        FoundItem item = stored();
        Claim claim = claims.receive(item.id(), "A. Person", "a@example.test", null, desk);

        assertThatThrownBy(() -> claims.release(claim.id(), true, null, null, desk)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> {
                    assertThat(e.code()).isEqualTo(FacilitiesErrorCode.LF_RELEASE_BLOCKED);
                    assertThat(e.getMessage()).contains("identity has not been verified", "not been approved", "receipt");
                });

        assertThat(items.get(item.id(), desk).item().status()).isEqualTo(ItemStatus.STORED);
        assertThat(jdbc.queryForList("SELECT reason FROM facilities.lf_history WHERE subject_id = ? AND reason LIKE 'Release refused%'",
                String.class, claim.id())).hasSize(1).allSatisfy(r -> assertThat(r).contains("identity has not been verified"));
    }

    @Test
    @DisplayName("competing claims block release, escalate to security, and release resumes once the rival is refused")
    void competing_claims() {
        FoundItem item = stored();
        Claim first = claims.receive(item.id(), "A. Person", "a@example.test", null, desk);
        Claim second = claims.receive(item.id(), "B. Other", "b@example.test", null, desk);
        claims.verifyIdentity(first.id(), VerificationMethod.ID_DOCUMENT, "VER-1", null, reception);

        assertThatThrownBy(() -> claims.approve(first.id(), null, null, director)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.LF_COMPETING_CLAIMS));
        assertThat(ops.escalations(site, true, 0, 10, desk).items()).extracting(e -> e.reason())
                .contains(EscalationReason.COMPETING_CLAIMS);

        claims.refuse(second.id(), "Could not describe the contents", null, desk);
        assertThat(claims.approve(first.id(), null, null, director).status()).isEqualTo(ClaimStatus.APPROVED);
    }

    @Test
    @DisplayName("whoever verified cannot approve, and whoever approved cannot hand the item over")
    void separation_of_duties() {
        FoundItem item = stored();
        Claim claim = claims.receive(item.id(), "A. Person", "a@example.test", null, desk);
        claims.verifyIdentity(claim.id(), VerificationMethod.ID_DOCUMENT, "VER-1", null, director);

        assertThatThrownBy(() -> claims.approve(claim.id(), null, null, director)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.LF_SELF_APPROVAL));
        Claim approved = claims.approve(claim.id(), null, null, security);
        file(item.id(), approved.id(), EvidenceKind.RELEASE_RECEIPT, security);
        assertThatThrownBy(() -> claims.release(approved.id(), true, null, null, security)).isInstanceOfSatisfying(
                FacilitiesException.class, e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.LF_SELF_APPROVAL));
        assertThat(claims.release(approved.id(), true, null, null, desk).status()).isEqualTo(ClaimStatus.RELEASED);
    }

    @Test
    @DisplayName("a claimant who declines the item at the desk is recorded as refusing, and the item stays in store")
    void declined_at_handover() {
        FoundItem item = stored();
        Claim approved = approved(item);
        file(item.id(), approved.id(), EvidenceKind.RELEASE_RECEIPT, desk);

        Claim declined = claims.release(approved.id(), false, "Not the right bag", null, desk);

        assertThat(declined.status()).isEqualTo(ClaimStatus.REFUSED);
        assertThat(declined.decisionReason()).contains("declined", "Not the right bag");
        assertThat(items.get(item.id(), desk).item().status()).isEqualTo(ItemStatus.STORED);
    }

    @Test
    @DisplayName("an unsafe item is isolated, escalated to security and the emergency procedures with an incident pending, and cannot be claimed")
    void unsafe_item() {
        FoundItem item = register(ItemCategory.OTHER, null, true);

        assertThat(item.status()).isEqualTo(ItemStatus.ISOLATED);
        var escalation = ops.escalations(site, true, 0, 10, desk).items().get(0);
        assertThat(escalation.reason()).isEqualTo(EscalationReason.UNSAFE_ITEM);
        assertThat(escalation.escalatedTo()).isEqualTo("SECURITY_EMERGENCY");
        assertThat(escalation.incidentState()).isEqualTo("PENDING_MANUAL");
        assertThatThrownBy(() -> claims.receive(item.id(), "A", "a@example.test", null, desk)).isInstanceOfSatisfying(
                FacilitiesException.class, e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.LF_ITEM_ISOLATED));
        assertThat(ops.linkIncident(escalation.id(), "INC-2026-9", desk).incidentState()).isEqualTo("LINKED");
    }

    @Test
    @DisplayName("marking an item unsafe refuses the claims in play")
    void marking_unsafe_refuses_open_claims() {
        FoundItem item = stored();
        Claim claim = claims.receive(item.id(), "A. Person", "a@example.test", null, desk);

        items.markUnsafe(item.id(), "Leaking fluid", desk);

        assertThat(items.get(item.id(), desk).claims().get(0).status()).isEqualTo(ClaimStatus.REFUSED);
        assertThat(claim.status()).isEqualTo(ClaimStatus.RECEIVED);
    }

    @Test
    @DisplayName("disposal needs the retention period to have ended, a filed authorisation from someone else, and an approver")
    void disposal() {
        items.setPolicy(site, ItemCategory.KEYS, 1, 30, director);
        FoundItem item = items.register(new Register(site, ItemCategory.KEYS, "Keys on a red ring", null, "Car park",
                Instant.now().minus(5, ChronoUnit.DAYS), "Guard", "Worn", false, null, shelf.id(), desk));
        FoundItem fresh = stored();

        assertThatThrownBy(() -> items.dispose(fresh.id(), null, director)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.LF_RETENTION_NOT_EXPIRED));
        assertThatThrownBy(() -> items.dispose(item.id(), null, director)).isInstanceOf(FacilitiesException.class)
                .hasMessageContaining("disposal authorisation");
        file(item.id(), null, EvidenceKind.DISPOSAL_AUTHORISATION, director);
        assertThatThrownBy(() -> items.dispose(item.id(), null, director)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.LF_SELF_APPROVAL));
        assertThatThrownBy(() -> items.dispose(item.id(), null, desk)).isInstanceOf(RuntimeException.class);

        assertThat(items.dispose(item.id(), null, security).status()).isEqualTo(ItemStatus.DISPOSED);
        assertThat(items.get(item.id(), desk).custody().get(items.get(item.id(), desk).custody().size() - 1).toParty()).isEqualTo("Disposal");
    }

    @Test
    @DisplayName("handing an item to the authorities needs the authority's receipt on file")
    void authorities() {
        FoundItem item = stored();
        assertThatThrownBy(() -> items.handToAuthorities(item.id(), "Ghana Police Service", null, director))
                .hasMessageContaining("receipt");
        file(item.id(), null, EvidenceKind.AUTHORITY_RECEIPT, desk);
        assertThat(items.handToAuthorities(item.id(), "Ghana Police Service", null, director).status())
                .isEqualTo(ItemStatus.HANDED_TO_AUTHORITIES);
    }

    @Test
    @DisplayName("the sweep escalates an unclaimed item past retention once, and purges a closed claim's personal data, auditably")
    void retention_sweep() {
        items.setPolicy(site, ItemCategory.KEYS, 1, 1, director);
        FoundItem old = items.register(new Register(site, ItemCategory.KEYS, "Old keys", null, "Hall",
                Instant.now().minus(10, ChronoUnit.DAYS), "Guard", "Worn", false, null, shelf.id(), desk));
        FoundItem other = items.register(new Register(site, ItemCategory.KEYS, "More keys", null, "Hall",
                Instant.now().minus(1, ChronoUnit.HOURS), "Guard", "Worn", false, null, shelf.id(), desk));
        Claim claim = claims.receive(other.id(), "A. Person", "a@example.test", "keys", desk);
        claims.refuse(claim.id(), "Not theirs", null, desk);
        jdbc.update("UPDATE facilities.lf_claims SET closed_at = now() - interval '5 days' WHERE id = ?", claim.id());
        ActorContext system = new ActorContext(new SiteScopedPrincipal("system.lostfound", "Scheduler", Set.of(SflRole.SFL_ADMIN),
                Set.of("*"), true), "sweep");

        LostFoundOpsService.SweepResult first = ops.sweep(system);
        LostFoundOpsService.SweepResult second = ops.sweep(system);

        assertThat(first.retentionEscalations()).isGreaterThanOrEqualTo(1);
        assertThat(second.retentionEscalations()).isZero();
        assertThat(ops.escalations(site, true, 0, 10, desk).items()).anyMatch(e -> e.itemId().equals(old.id())
                && e.reason() == EscalationReason.RETENTION_EXPIRED);
        Claim purged = items.get(other.id(), desk).claims().get(0);
        assertThat(purged.claimantName()).isNull();
        assertThat(purged.claimantContact()).isNull();
        assertThat(purged.personalDataPurgedAt()).isNotNull();
        assertThat(purged.status()).isEqualTo(ClaimStatus.REFUSED);
        assertThat(second.claimantRecordsPurged()).isZero();
    }

    @Test
    @DisplayName("the dashboard counts open items by age and place, custody completeness, and closures within policy")
    void dashboard() {
        FoundItem item = stored();
        Claim approved = approved(item);
        file(item.id(), approved.id(), EvidenceKind.RELEASE_RECEIPT, desk);
        claims.release(approved.id(), true, null, null, desk);
        register(ItemCategory.KEYS, shelf, false);
        register(ItemCategory.OTHER, null, false);

        LostFoundOpsService.Dashboard d = ops.dashboard(site, desk);

        assertThat(d.openByAge().upTo7Days()).isEqualTo(2);
        assertThat(d.openByLocation()).extracting(l -> l.location()).contains("General shelf", "Not yet stored");
        assertThat(d.custodyCompletenessPercent()).isEqualByComparingTo("100.0");
        assertThat(d.withinPolicyPercent()).isEqualByComparingTo("100.0");
        assertThat(d.meanHoursToVerifiedRelease()).isNotNull();
    }

    @Test
    @DisplayName("roles: the requester reads nothing, another site is refused, the compliance officer reads masked, the facilities manager cannot approve")
    void permissions() {
        FoundItem item = stored();
        Claim claim = claims.receive(item.id(), "A. Person", "a@example.test", null, desk);
        claims.verifyIdentity(claim.id(), VerificationMethod.ID_DOCUMENT, "VER-1", null, reception);

        assertThatThrownBy(() -> items.list(site, null, null, false, 0, 10, caller("req", SflRole.IFIMP_REQUESTER, site)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> items.list(site, null, null, false, 0, 10, caller("fm", SflRole.FACILITIES_MANAGER, "OTHER")))
                .isInstanceOf(RuntimeException.class);
        assertThat(items.list(site, null, null, false, 0, 10, caller("aud", SflRole.COMPLIANCE_OFFICER, site)).items()).hasSize(1);
        assertThatThrownBy(() -> claims.approve(claim.id(), null, null, desk)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> items.createLocation(site, "X", "X", false, caller("aud", SflRole.COMPLIANCE_OFFICER, site)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("unknown ids are not found, a stale version conflicts, and filters apply on the server")
    void lookup_and_versioning() {
        assertThatThrownBy(() -> items.get(UUID.randomUUID(), desk)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.RECORD_NOT_FOUND));
        FoundItem item = register(ItemCategory.KEYS, null, false);
        items.store(item.id(), shelf.id(), item.version(), desk);
        assertThatThrownBy(() -> items.store(item.id(), shelf.id(), item.version(), desk)).isInstanceOfSatisfying(
                FacilitiesException.class, e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.VERSION_CONFLICT));
        assertThat(items.list(site, "STORED", "KEYS", false, 0, 10, desk).total()).isEqualTo(1);
        assertThat(items.list(site, null, "BAG", false, 0, 10, desk).items()).isEmpty();
        assertThatThrownBy(() -> items.list(site, "WIZARD", null, false, 0, 10, desk)).isInstanceOf(IllegalArgumentException.class);
    }
}
