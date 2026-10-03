package gh.edu.clet.sfl.facilities.lostfound;

import static org.assertj.core.api.Assertions.assertThat;

import gh.edu.clet.sfl.facilities.lostfound.domain.Claim;
import gh.edu.clet.sfl.facilities.lostfound.domain.ClaimReference;
import gh.edu.clet.sfl.facilities.lostfound.domain.ClaimStatus;
import gh.edu.clet.sfl.facilities.lostfound.domain.CustodyChain;
import gh.edu.clet.sfl.facilities.lostfound.domain.CustodyEvent;
import gh.edu.clet.sfl.facilities.lostfound.domain.EvidenceKind;
import gh.edu.clet.sfl.facilities.lostfound.domain.FoundItem;
import gh.edu.clet.sfl.facilities.lostfound.domain.ItemCategory;
import gh.edu.clet.sfl.facilities.lostfound.domain.ItemStatus;
import gh.edu.clet.sfl.facilities.lostfound.domain.LfEvidence;
import gh.edu.clet.sfl.facilities.lostfound.domain.ReleasePolicy;
import gh.edu.clet.sfl.facilities.lostfound.domain.RetentionPolicy;
import gh.edu.clet.sfl.facilities.lostfound.domain.VerificationMethod;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LostFoundDomainTest {

    @Test
    @DisplayName("a claim reference is random, readable and carries nothing about the item")
    void claim_reference() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String reference = ClaimReference.generate();
            assertThat(reference).matches("LF-[A-HJKMNP-Z2-9]{8}");
            seen.add(reference);
        }
        assertThat(seen).hasSize(500);
    }

    @Test
    @DisplayName("identifying and valuable categories are private and need secure storage; keys and clothing do not")
    void categories() {
        assertThat(ItemCategory.DOCUMENT.identifying()).isTrue();
        assertThat(ItemCategory.DOCUMENT.requiresSecureStorage()).isTrue();
        assertThat(ItemCategory.CASH_VALUABLES.requiresSecureStorage()).isTrue();
        assertThat(ItemCategory.KEYS.requiresSecureStorage()).isFalse();
        assertThat(ItemCategory.CLOTHING.identifying()).isFalse();
    }

    @Test
    @DisplayName("an item's retention ends the configured number of days after it was found")
    void retention() {
        assertThat(new RetentionPolicy(ItemCategory.KEYS, 60, 30).retentionUntil(LocalDate.of(2026, 1, 1)))
                .isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    @DisplayName("a chain is complete when every transfer picks up where the last stopped, and broken otherwise")
    void custody_chain() {
        Instant t = Instant.parse("2026-06-01T10:00:00Z");
        CustodyEvent first = event("Finder", "Desk", t);
        CustodyEvent second = event("Desk", "Store", t.plusSeconds(60));
        assertThat(CustodyChain.complete(List.of(first, second))).isTrue();
        assertThat(CustodyChain.complete(List.of(first, event("Someone else", "Store", t.plusSeconds(60))))).isFalse();
        assertThat(CustodyChain.complete(List.of(second, first))).isFalse();
        assertThat(CustodyChain.complete(List.of())).isFalse();
        assertThat(CustodyChain.currentHolder(List.of(first, second))).isEqualTo("Store");
    }

    @Test
    @DisplayName("the masked item shows the controlled description and nothing that could identify the owner or the finder")
    void masking() {
        FoundItem item = item(ItemStatus.STORED, false);
        FoundItem masked = item.masked();
        assertThat(masked.privateDescription()).isNull();
        assertThat(masked.finderReference()).isNull();
        assertThat(masked.publicDescription()).isEqualTo("Black bag");
        Claim claim = claim(ClaimStatus.RECEIVED, false).masked();
        assertThat(claim.claimantName()).isNull();
        assertThat(claim.claimantContact()).isNull();
        assertThat(claim.claimantDescription()).isNull();
    }

    @Test
    @DisplayName("a release with nothing in its way has no blockers")
    void release_clear() {
        FoundItem item = item(ItemStatus.STORED, false);
        Claim claim = claim(ClaimStatus.APPROVED, true);
        assertThat(ReleasePolicy.blockers(item, claim, List.of(), List.of(receipt(claim)))).isEmpty();
    }

    @Test
    @DisplayName("every blocker is named: unverified identity, no approval, no receipt, a competing claim, an unsafe item")
    void release_blockers() {
        Claim unverified = claim(ClaimStatus.RECEIVED, false);
        List<String> blockers = ReleasePolicy.blockers(item(ItemStatus.ISOLATED, true), unverified,
                List.of(claim(ClaimStatus.RECEIVED, false)), List.of());
        assertThat(blockers).hasSize(5);
        assertThat(String.join(" ", blockers)).contains("unsafe", "identity", "approved", "Another claim", "receipt");
    }

    @Test
    @DisplayName("a receipt for a different claim does not count")
    void receipt_must_be_for_this_claim() {
        Claim claim = claim(ClaimStatus.APPROVED, true);
        Claim other = claim(ClaimStatus.APPROVED, true);
        assertThat(ReleasePolicy.blockers(item(ItemStatus.STORED, false), claim, List.of(), List.of(receipt(other))))
                .containsExactly("No release receipt has been filed for this claim.");
    }

    private static CustodyEvent event(String from, String to, Instant at) {
        return new CustodyEvent(UUID.randomUUID(), UUID.randomUUID(), "S", from, to, "Desk", at, null, "u");
    }

    private static FoundItem item(ItemStatus status, boolean unsafe) {
        return new FoundItem(UUID.randomUUID(), "LF-I-1", "LF-AAAAAAAA", "S", ItemCategory.BAG, "Black bag",
                "Contains a passport in the name of A. Person", "Lobby", Instant.now(), "Visitor J. Doe", "Good", status,
                unsafe, null, UUID.randomUUID(), LocalDate.now().plusDays(30), null, "u", Instant.now(), Instant.now(), 0);
    }

    private static Claim claim(ClaimStatus status, boolean verified) {
        return new Claim(UUID.randomUUID(), "LF-C-1", UUID.randomUUID(), "S", "A. Person", "a@example.test", "A black bag",
                status, verified, verified ? VerificationMethod.ID_DOCUMENT : null, verified ? "VER-1" : null, "v",
                Instant.now(), null, null, null, null, null, null, null, "u", Instant.now(), Instant.now(), 0);
    }

    private static LfEvidence receipt(Claim claim) {
        return new LfEvidence(UUID.randomUUID(), claim.itemId(), claim.id(), "S", EvidenceKind.RELEASE_RECEIPT, "REC",
                "r.pdf", "application/pdf", 1, "0".repeat(64), "COMPLIANCE", "u", Instant.now());
    }
}
