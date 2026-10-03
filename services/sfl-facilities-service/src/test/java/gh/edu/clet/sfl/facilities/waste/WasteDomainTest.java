package gh.edu.clet.sfl.facilities.waste;

import static org.assertj.core.api.Assertions.assertThat;

import gh.edu.clet.sfl.facilities.waste.domain.ApprovalStatus;
import gh.edu.clet.sfl.facilities.waste.domain.ChainPolicy;
import gh.edu.clet.sfl.facilities.waste.domain.CollectionStatus;
import gh.edu.clet.sfl.facilities.waste.domain.DestinationType;
import gh.edu.clet.sfl.facilities.waste.domain.EvidenceKind;
import gh.edu.clet.sfl.facilities.waste.domain.EvidenceStatus;
import gh.edu.clet.sfl.facilities.waste.domain.ExceptionType;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCarrier;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCollection;
import gh.edu.clet.sfl.facilities.waste.domain.WasteDestination;
import gh.edu.clet.sfl.facilities.waste.domain.WasteEvidence;
import gh.edu.clet.sfl.facilities.waste.domain.WasteMetrics;
import gh.edu.clet.sfl.facilities.waste.domain.WasteUnit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WasteDomainTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);

    @Test
    @DisplayName("units convert to kilograms to four places, from the configured factor")
    void unit_conversion() {
        assertThat(new WasteUnit("TONNE", "Tonne", new BigDecimal("1000")).toKilograms(new BigDecimal("1.25")))
                .isEqualByComparingTo("1250");
        assertThat(new WasteUnit("LB", "Pound", new BigDecimal("0.45359237")).toKilograms(new BigDecimal("10")))
                .isEqualByComparingTo("4.5359");
    }

    @Test
    @DisplayName("a collection moves forward one step at a time, a missed one can still be collected late, closed is final")
    void collection_transitions() {
        assertThat(CollectionStatus.SCHEDULED.canMoveTo(CollectionStatus.HANDED_OVER)).isFalse();
        assertThat(CollectionStatus.COLLECTED.canMoveTo(CollectionStatus.HANDED_OVER)).isTrue();
        assertThat(CollectionStatus.HANDED_OVER.canMoveTo(CollectionStatus.CLOSED)).isFalse();
        assertThat(CollectionStatus.MISSED.canMoveTo(CollectionStatus.COLLECTED)).isTrue();
        assertThat(CollectionStatus.CLOSED.canMoveTo(CollectionStatus.COLLECTED)).isFalse();
    }

    @Test
    @DisplayName("a carrier is refused when suspended, past its licence, or not approved for hazardous waste")
    void carrier_refusals() {
        assertThat(carrier(ApprovalStatus.APPROVED, TODAY.plusDays(1), true).refusal(true, TODAY)).isNull();
        assertThat(carrier(ApprovalStatus.SUSPENDED, TODAY.plusDays(1), true).refusal(false, TODAY)).contains("suspended");
        assertThat(carrier(ApprovalStatus.APPROVED, TODAY.minusDays(1), true).refusal(false, TODAY)).contains("expired");
        assertThat(carrier(ApprovalStatus.APPROVED, TODAY.plusDays(1), false).refusal(true, TODAY)).contains("hazardous");
        assertThat(carrier(ApprovalStatus.APPROVED, TODAY.plusDays(1), false).refusal(false, TODAY)).isNull();
    }

    @Test
    @DisplayName("a destination is refused when suspended, past its permit, or not accepting hazardous waste; landfill does not divert")
    void destination_refusals() {
        assertThat(destination(DestinationType.RECYCLER, ApprovalStatus.APPROVED, TODAY.plusDays(1), false)
                .refusal(false, TODAY)).isNull();
        assertThat(destination(DestinationType.RECYCLER, ApprovalStatus.APPROVED, TODAY.plusDays(1), false)
                .refusal(true, TODAY)).contains("hazardous");
        assertThat(destination(DestinationType.RECYCLER, ApprovalStatus.APPROVED, TODAY.minusDays(1), true)
                .refusal(false, TODAY)).contains("expired");
        assertThat(destination(DestinationType.LANDFILL, ApprovalStatus.APPROVED, TODAY, true).diverts()).isFalse();
        assertThat(destination(DestinationType.COMPOSTING, ApprovalStatus.APPROVED, TODAY, true).diverts()).isTrue();
    }

    @Test
    @DisplayName("hazardous waste with no accepted receiving evidence leaves the chain open, and says why")
    void hazardous_chain_needs_receiving_evidence() {
        WasteCollection hazardous = collection(true, "MAN-1", "CERT-1", false, true);
        List<ChainPolicy.Gap> gaps = ChainPolicy.gaps(hazardous,
                List.of(evidence(EvidenceKind.RECEIVING, EvidenceStatus.SUBMITTED),
                        evidence(EvidenceKind.CERTIFICATE, EvidenceStatus.ACCEPTED)), List.of());
        assertThat(gaps).extracting(ChainPolicy.Gap::type).containsExactly(ExceptionType.MISSING_RECEIVING_EVIDENCE);
    }

    @Test
    @DisplayName("every collection needs an accepted certificate; a complete chain has no gaps")
    void certificate_and_complete_chain() {
        WasteCollection plain = collection(false, null, null, false, true);
        assertThat(ChainPolicy.gaps(plain, List.of(), List.of())).extracting(ChainPolicy.Gap::type)
                .containsExactly(ExceptionType.MISSING_CERTIFICATE);
        WasteCollection complete = collection(true, "MAN-1", "CERT-1", false, true);
        assertThat(ChainPolicy.gaps(complete, List.of(evidence(EvidenceKind.RECEIVING, EvidenceStatus.ACCEPTED),
                evidence(EvidenceKind.CERTIFICATE, EvidenceStatus.ACCEPTED)), List.of())).isEmpty();
    }

    @Test
    @DisplayName("a contaminated, unreconciled collection cannot close")
    void contamination_blocks_closure() {
        WasteCollection contaminated = collection(false, null, "CERT-1", true, false);
        assertThat(ChainPolicy.gaps(contaminated, List.of(evidence(EvidenceKind.CERTIFICATE, EvidenceStatus.ACCEPTED)),
                List.of())).extracting(ChainPolicy.Gap::type).containsExactly(ExceptionType.CONTAMINATION);
    }

    @Test
    @DisplayName("percentages are one decimal place, and absent rather than zero when there is nothing to divide by")
    void percentages() {
        assertThat(WasteMetrics.percent(new BigDecimal("30"), new BigDecimal("120"))).isEqualByComparingTo("25.0");
        assertThat(WasteMetrics.percent(new BigDecimal("1"), new BigDecimal("3"))).isEqualByComparingTo("33.3");
        assertThat(WasteMetrics.percent(new BigDecimal("1"), BigDecimal.ZERO)).isNull();
        assertThat(WasteMetrics.percent(3L, 4L)).isEqualByComparingTo("75.0");
        assertThat(WasteMetrics.percent(0L, 0L)).isNull();
    }

    @Test
    @DisplayName("a spill is the most urgent exception and the only one that is also an incident")
    void exception_urgency() {
        assertThat(ExceptionType.SPILL.dueInDays()).isLessThan(ExceptionType.MISSED_COLLECTION.dueInDays());
        assertThat(ExceptionType.MISSING_CERTIFICATE.dueInDays()).isGreaterThan(ExceptionType.MISSED_COLLECTION.dueInDays());
        assertThat(ExceptionType.SPILL.needsIncident()).isTrue();
        assertThat(ExceptionType.CONTAMINATION.needsIncident()).isFalse();
    }

    private static WasteCarrier carrier(ApprovalStatus status, LocalDate expires, boolean hazardous) {
        return new WasteCarrier(UUID.randomUUID(), "CAR", "Carrier", "LIC-1", expires, hazardous, status, "u",
                Instant.now(), Instant.now(), 0);
    }

    private static WasteDestination destination(DestinationType type, ApprovalStatus status, LocalDate expires,
            boolean hazardous) {
        return new WasteDestination(UUID.randomUUID(), "DST", "Destination", type, "PERMIT-1", expires, hazardous,
                status, "u", Instant.now(), Instant.now(), 0);
    }

    private static WasteCollection collection(boolean hazardous, String manifest, String certificate,
            boolean contaminated, boolean reconciled) {
        return new WasteCollection(UUID.randomUUID(), "WST-C-1", "S", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), hazardous, TODAY, TODAY, BigDecimal.ONE, "KG", BigDecimal.ONE,
                null, manifest, certificate, certificate == null ? null : TODAY, contaminated, reconciled,
                CollectionStatus.HANDED_OVER, null, "u", Instant.now(), Instant.now(), 0);
    }

    private static WasteEvidence evidence(EvidenceKind kind, EvidenceStatus status) {
        return new WasteEvidence(UUID.randomUUID(), UUID.randomUUID(), "S", kind, "ref", "f.pdf", "application/pdf", 1,
                "0".repeat(64), "COMPLIANCE", status, "u", Instant.now(), null, null, null);
    }
}
