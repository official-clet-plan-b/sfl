package gh.edu.clet.sfl.facilities.waste;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import gh.edu.clet.sfl.facilities.waste.application.WasteCollectionService;
import gh.edu.clet.sfl.facilities.waste.application.WasteConfigService;
import gh.edu.clet.sfl.facilities.waste.application.WasteExceptionService;
import gh.edu.clet.sfl.facilities.waste.application.WasteReportService;
import gh.edu.clet.sfl.facilities.waste.application.ports.WasteEstatePort;
import gh.edu.clet.sfl.facilities.waste.application.ports.WasteWorkOrderPort;
import gh.edu.clet.sfl.facilities.waste.domain.ApprovalStatus;
import gh.edu.clet.sfl.facilities.waste.domain.CollectionStatus;
import gh.edu.clet.sfl.facilities.waste.domain.CustodyStep;
import gh.edu.clet.sfl.facilities.waste.domain.DestinationType;
import gh.edu.clet.sfl.facilities.waste.domain.EvidenceKind;
import gh.edu.clet.sfl.facilities.waste.domain.ExceptionStatus;
import gh.edu.clet.sfl.facilities.waste.domain.ExceptionType;
import gh.edu.clet.sfl.facilities.waste.domain.QuantityBasis;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCarrier;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCategory;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCollection;
import gh.edu.clet.sfl.facilities.waste.domain.WasteDestination;
import gh.edu.clet.sfl.facilities.waste.domain.WasteEvidence;
import gh.edu.clet.sfl.facilities.waste.domain.WasteException;
import gh.edu.clet.sfl.facilities.waste.domain.WastePoint;
import gh.edu.clet.sfl.facilities.waste.domain.WasteStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * S178 against a real PostgreSQL, run as the roles that hold each grant. S152 and S153 are doubles; the
 * point is S178's own rules: approval checked when waste moves, a hazardous chain that cannot close without
 * accepted evidence, measured never mixed with estimated, and a refusal that leaves an exception behind.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sfl.facilities.messaging.drainer-enabled=false",
        "sfl.maintenance.scheduling.enabled=false",
        "sfl.booking.scheduling.enabled=false",
        "sfl.hygiene.scheduling.enabled=false",
        "sfl.waste.scheduling.enabled=false",
})
@EnabledIf(value = "gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available; see FacilitiesPostgresSupport.unavailableReason()")
class WasteServicePostgresTest {

    private static final String HASH = "c".repeat(64);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        FacilitiesPostgresSupport.datasource(registry);
    }

    @Autowired private WasteConfigService config;
    @Autowired private WasteCollectionService collections;
    @Autowired private WasteExceptionService exceptions;
    @Autowired private WasteReportService reports;
    @MockitoBean private WasteEstatePort estate;
    @MockitoBean private WasteWorkOrderPort workOrders;

    private String site;
    private String tag;
    private Caller manager;
    private Caller director;
    private Caller hse;
    private WastePoint point;
    private WasteStream general;
    private WasteStream hazardous;
    private WasteStream recycling;
    private WasteCarrier carrier;
    private WasteCarrier plainCarrier;
    private WasteDestination recycler;
    private WasteDestination treatment;
    private WasteDestination landfill;

    @BeforeEach
    void setUp() {
        site = "WST" + Math.abs(UUID.randomUUID().hashCode() % 1_000_000);
        tag = site.substring(3);
        when(estate.siteExists(anyString())).thenReturn(true);
        when(estate.siteOfRoom(any())).thenReturn(Optional.empty());
        when(workOrders.raise(any(), any(), any(), anyString(), anyString()))
                .thenReturn(new WasteWorkOrderPort.RaisedWorkOrder(UUID.randomUUID(), "WO-1"));
        manager = caller("fm-user", SflRole.FACILITIES_MANAGER);
        director = caller("director-user", SflRole.FACILITIES_DIRECTOR);
        hse = caller("hse-user", SflRole.HSE_MANAGER);
        LocalDate far = LocalDate.now().plusYears(1);
        point = config.createPoint(site, "P" + tag, "Loading bay", null, "Skip 1", manager);
        general = config.createStream("GEN" + tag, "General waste", WasteCategory.GENERAL, false, false, null, manager);
        recycling = config.createStream("REC" + tag, "Paper and card", WasteCategory.RECYCLABLE, false, true, null, manager);
        hazardous = config.createStream("HAZ" + tag, "Lab chemicals", WasteCategory.CHEMICAL, true, false, "Keep sealed", manager);
        carrier = config.createCarrier("CAR" + tag, "SafeHaul", "LIC-" + tag, far, true, manager);
        plainCarrier = config.createCarrier("PLN" + tag, "Plain Haulage", "LIC-P" + tag, far, false, manager);
        recycler = config.createDestination("RCY" + tag, "GreenCycle", DestinationType.RECYCLER, "PRM-1", far, false, manager);
        treatment = config.createDestination("TRT" + tag, "ChemSafe", DestinationType.TREATMENT, "PRM-2", far, true, manager);
        landfill = config.createDestination("LDF" + tag, "City tip", DestinationType.LANDFILL, "PRM-3", far, false, manager);
    }

    private static Caller caller(String id, SflRole role) {
        return new Caller(new ActorContext(new SiteScopedPrincipal(id, id, Set.of(role), Set.of("*"), false), "corr-" + id),
                SourceChannel.WEB);
    }

    private Caller scoped(String id, SflRole role, String... sites) {
        return new Caller(new ActorContext(new SiteScopedPrincipal(id, id, Set.of(role), Set.of(sites), false), "corr-" + id),
                SourceChannel.WEB);
    }

    private WasteCollection scheduled(WasteStream stream, WasteCarrier by) {
        return collections.schedule(site, stream.id(), point.id(), by.id(), LocalDate.now(), manager);
    }

    private WasteCollection collected(WasteStream stream, WasteCarrier by, String manifest) {
        WasteCollection c = scheduled(stream, by);
        return collections.record(c.id(), LocalDate.now(), new BigDecimal("2"), "TONNE", QuantityBasis.MEASURED, manifest,
                null, manager);
    }

    private WasteEvidence accepted(UUID collectionId, EvidenceKind kind, Caller submitter, Caller verifier) {
        WasteEvidence e = collections.submitEvidence(collectionId, kind, "REC-" + kind, "doc.pdf", "application/pdf", 100,
                HASH, submitter);
        return collections.reviewEvidence(e.id(), true, null, verifier);
    }

    @Test
    @DisplayName("a hazardous stream cannot be counted as diverted; a unit is configuration")
    void configuration_rules() {
        assertThatThrownBy(() -> config.createStream("BAD" + tag, "Bad", WasteCategory.CHEMICAL, true, true, null, manager))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(config.saveUnit("STONE" + tag.substring(0, 2), "Stone", new BigDecimal("6.35029"), manager).code())
                .startsWith("STONE");
        assertThat(config.configuration(site, manager).units()).extracting(u -> u.code()).contains("KG", "TONNE");
    }

    @Test
    @DisplayName("the original measurement is kept beside the normalised kilograms, and an estimate is flagged")
    void measurement_is_preserved() {
        WasteCollection c = collected(general, carrier, null);

        assertThat(c.quantity()).isEqualByComparingTo("2");
        assertThat(c.unit()).isEqualTo("TONNE");
        assertThat(c.quantityKg()).isEqualByComparingTo("2000");
        assertThat(c.quantityBasis()).isEqualTo(QuantityBasis.MEASURED);
        assertThat(c.status()).isEqualTo(CollectionStatus.COLLECTED);
        assertThat(collections.get(c.id(), manager).custody()).extracting(e -> e.step()).containsExactly(CustodyStep.COLLECTED);
    }

    @Test
    @DisplayName("a quantity must be flagged measured or estimated, in a known unit, and not dated in the future")
    void recording_validation() {
        WasteCollection c = scheduled(general, carrier);
        assertThatThrownBy(() -> collections.record(c.id(), null, BigDecimal.ONE, "KG", null, null, null, manager))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("basis");
        assertThatThrownBy(() -> collections.record(c.id(), null, BigDecimal.ONE, "FURLONG", QuantityBasis.MEASURED, null, null, manager))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown unit");
        assertThatThrownBy(() -> collections.record(c.id(), LocalDate.now().plusDays(1), BigDecimal.ONE, "KG",
                QuantityBasis.MEASURED, null, null, manager)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("hazardous waste needs its manifest when collected")
    void hazardous_needs_manifest() {
        WasteCollection c = scheduled(hazardous, carrier);
        assertThatThrownBy(() -> collections.record(c.id(), null, BigDecimal.ONE, "KG", QuantityBasis.MEASURED, null, null, manager))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("manifest");
    }

    @Test
    @DisplayName("a carrier not approved for hazardous waste cannot be scheduled for it")
    void carrier_not_approved_for_hazardous() {
        assertThatThrownBy(() -> scheduled(hazardous, plainCarrier)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.WASTE_CARRIER_UNAPPROVED));
    }

    @Test
    @DisplayName("a handover to an unapproved destination is blocked, and the block is raised as an exception that survives the refusal")
    void unapproved_destination_blocks_and_raises() {
        WasteCollection c = collected(hazardous, carrier, "MAN-1");

        assertThatThrownBy(() -> collections.handOver(c.id(), recycler.id(), null, null, manager))
                .isInstanceOfSatisfying(FacilitiesException.class,
                        e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.WASTE_DESTINATION_UNAPPROVED));

        assertThat(collections.get(c.id(), manager).collection().status()).isEqualTo(CollectionStatus.COLLECTED);
        assertThat(collections.get(c.id(), manager).openExceptions()).extracting(WasteException::exceptionType)
                .containsExactly(ExceptionType.UNAPPROVED_DESTINATION);
    }

    @Test
    @DisplayName("a carrier suspended after scheduling blocks the handover: approval is checked when the waste moves")
    void approval_is_checked_at_handover() {
        WasteCollection c = collected(general, carrier, null);
        config.updateCarrier(carrier.id(), null, null, null, ApprovalStatus.SUSPENDED, null, manager);

        assertThatThrownBy(() -> collections.handOver(c.id(), recycler.id(), null, null, manager))
                .isInstanceOfSatisfying(FacilitiesException.class,
                        e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.WASTE_CARRIER_UNAPPROVED));
        assertThat(collections.get(c.id(), manager).openExceptions()).extracting(WasteException::exceptionType)
                .containsExactly(ExceptionType.UNAPPROVED_CARRIER);
    }

    @Test
    @DisplayName("closing a hazardous collection with no receiving evidence leaves the chain open and escalates")
    void hazardous_closure_without_evidence_stays_open() {
        WasteCollection c = collected(hazardous, carrier, "MAN-1");
        collections.handOver(c.id(), treatment.id(), "Treatment plant gate", null, manager);

        assertThatThrownBy(() -> collections.close(c.id(), null, manager)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> {
                    assertThat(e.code()).isEqualTo(FacilitiesErrorCode.WASTE_CHAIN_OPEN);
                    assertThat(e.getMessage()).contains("receiving evidence");
                });
        assertThatThrownBy(() -> collections.confirmDestination(c.id(), null, manager)).isInstanceOfSatisfying(
                FacilitiesException.class, e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.WASTE_CHAIN_OPEN));

        WasteCollectionService.CollectionDetail detail = collections.get(c.id(), manager);
        assertThat(detail.collection().status()).isEqualTo(CollectionStatus.HANDED_OVER);
        assertThat(detail.openExceptions()).extracting(WasteException::exceptionType)
                .contains(ExceptionType.MISSING_RECEIVING_EVIDENCE, ExceptionType.MISSING_CERTIFICATE);
        assertThat(detail.openExceptions()).allMatch(x -> x.escalatedTo().equals("FACILITIES_OWNER"));
    }

    @Test
    @DisplayName("a hazardous chain closes end to end on accepted evidence, with a complete custody trail")
    void hazardous_chain_closes() {
        WasteCollection c = collected(hazardous, carrier, "MAN-7");
        collections.handOver(c.id(), treatment.id(), "Gate 2", null, manager);
        accepted(c.id(), EvidenceKind.RECEIVING, manager, director);
        collections.confirmDestination(c.id(), null, manager);
        accepted(c.id(), EvidenceKind.CERTIFICATE, manager, hse);
        collections.recordCertificate(c.id(), "CERT-42", LocalDate.now(), null, manager);

        WasteCollection closed = collections.close(c.id(), null, manager);

        assertThat(closed.status()).isEqualTo(CollectionStatus.CLOSED);
        assertThat(closed.closedAt()).isNotNull();
        WasteCollectionService.CollectionDetail detail = collections.get(c.id(), manager);
        assertThat(detail.custody()).extracting(e -> e.step()).containsExactly(CustodyStep.COLLECTED,
                CustodyStep.HANDED_OVER, CustodyStep.RECEIVED_AT_DESTINATION, CustodyStep.TREATED);
        assertThat(detail.openExceptions()).isEmpty();
    }

    @Test
    @DisplayName("evidence cannot be accepted by whoever submitted it, nor by a role without the verify grant")
    void evidence_separation_of_duties() {
        WasteCollection c = collected(general, carrier, null);
        WasteEvidence e = collections.submitEvidence(c.id(), EvidenceKind.CERTIFICATE, "REC", "c.pdf", "application/pdf",
                10, HASH, director);

        assertThatThrownBy(() -> collections.reviewEvidence(e.id(), true, null, director)).isInstanceOfSatisfying(
                FacilitiesException.class, x -> assertThat(x.code()).isEqualTo(FacilitiesErrorCode.WASTE_SELF_VERIFICATION)
                        .isEqualTo(FacilitiesErrorCode.WASTE_SELF_VERIFICATION));
        assertThatThrownBy(() -> collections.reviewEvidence(e.id(), true, null, manager)).isInstanceOf(RuntimeException.class);
        assertThat(collections.reviewEvidence(e.id(), false, "Illegible", hse).status().name()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("a contaminated collection stays unreconciled, raises a corrective action, and cannot close until reconciled")
    void contamination() {
        WasteCollection c = collected(recycling, carrier, null);
        collections.handOver(c.id(), recycler.id(), null, null, manager);
        accepted(c.id(), EvidenceKind.CERTIFICATE, manager, director);
        collections.recordCertificate(c.id(), "CERT-9", LocalDate.now(), null, manager);
        collections.confirmDestination(c.id(), null, manager);

        WasteCollection contaminated = collections.markContaminated(c.id(), "Food waste mixed into card", manager);
        assertThat(contaminated.contaminated()).isTrue();
        assertThat(contaminated.quantityReconciled()).isFalse();
        WasteException exception = collections.get(c.id(), manager).openExceptions().get(0);
        assertThat(exception.exceptionType()).isEqualTo(ExceptionType.CONTAMINATION);
        assertThat(exception.workOrderState()).isEqualTo("RAISED");

        assertThatThrownBy(() -> collections.close(c.id(), null, manager)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.WASTE_CHAIN_OPEN));
        assertThatThrownBy(() -> exceptions.resolve(exception.id(), "Sorted", null, manager))
                .isInstanceOf(FacilitiesException.class);

        collections.reconcile(c.id(), new BigDecimal("1500"), "KG", QuantityBasis.MEASURED, null, manager);
        exceptions.resolve(exception.id(), "Contaminant removed and quantity restated", null, manager);

        assertThat(collections.close(c.id(), null, manager).status()).isEqualTo(CollectionStatus.CLOSED);
    }

    @Test
    @DisplayName("a spill is raised once, asks S163 for an incident that stays pending, and raises an S153 work order")
    void spill() {
        WasteCollection c = collected(general, carrier, null);

        WasteException spill = exceptions.report(site, c.id(), ExceptionType.SPILL, "Skip leaked at bay 2", "K. Owusu", manager);
        WasteException again = exceptions.report(site, c.id(), ExceptionType.SPILL, "Duplicate report", null, manager);

        assertThat(again.id()).isEqualTo(spill.id());
        assertThat(spill.incidentState()).isEqualTo("PENDING_MANUAL");
        assertThat(spill.workOrderState()).isEqualTo("RAISED");
        WasteException linked = exceptions.linkIncident(spill.id(), "INC-2026-77", manager);
        assertThat(linked.incidentState()).isEqualTo("LINKED");
    }

    @Test
    @DisplayName("when S153 refuses, the exception stands and says PENDING_MANUAL, and a retry raises the order")
    void work_order_failure_is_honest() {
        when(workOrders.raise(any(), any(), any(), anyString(), anyString())).thenThrow(new IllegalStateException("S153 down"));
        WasteCollection c = collected(general, carrier, null);

        WasteException spill = exceptions.report(site, c.id(), ExceptionType.SPILL, "Leak", null, manager);

        assertThat(spill.workOrderState()).isEqualTo("PENDING_MANUAL");
        doReturn(new WasteWorkOrderPort.RaisedWorkOrder(UUID.randomUUID(), "WO-5")).when(workOrders)
                .raise(any(), any(), any(), anyString(), anyString());
        assertThat(exceptions.retryWorkOrder(spill.id(), manager).workOrderNumber()).isEqualTo("WO-5");
    }

    @Test
    @DisplayName("the sweep marks an unrecorded collection missed, raises it to the facilities owner once, and a late collection resolves it")
    void missed_collection_sweep() {
        WasteCollection due = collections.schedule(site, general.id(), point.id(), carrier.id(), LocalDate.now().minusDays(3),
                manager);
        ActorContext system = new ActorContext(new SiteScopedPrincipal("system.waste", "Waste scheduler",
                Set.of(SflRole.SFL_ADMIN), Set.of("*"), true), "sweep");

        collections.sweepMissed(1, system);
        collections.sweepMissed(1, system);

        WasteCollectionService.CollectionDetail detail = collections.get(due.id(), manager);
        assertThat(detail.collection().status()).isEqualTo(CollectionStatus.MISSED);
        assertThat(detail.openExceptions()).hasSize(1);
        assertThat(detail.openExceptions().get(0).exceptionType()).isEqualTo(ExceptionType.MISSED_COLLECTION);

        collections.record(due.id(), LocalDate.now(), BigDecimal.TEN, "KG", QuantityBasis.MEASURED, null, null, manager);
        assertThat(collections.get(due.id(), manager).openExceptions()).isEmpty();
    }

    @Test
    @DisplayName("diversion is worked out from measured kilograms only; estimates are totalled apart and the report names its sources and rules")
    void metrics_separate_measured_from_estimated() {
        WasteCollection paper = collected(recycling, carrier, null);
        collections.handOver(paper.id(), recycler.id(), null, null, manager);
        WasteCollection rubbish = scheduled(general, carrier);
        collections.record(rubbish.id(), null, new BigDecimal("2000"), "KG", QuantityBasis.MEASURED, null, null, manager);
        collections.handOver(rubbish.id(), landfill.id(), null, null, manager);
        WasteCollection guess = scheduled(recycling, carrier);
        collections.record(guess.id(), null, new BigDecimal("500"), "KG", QuantityBasis.ESTIMATED, null, null, manager);

        WasteReportService.Report report = reports.report(site, null, null, manager);

        assertThat(report.measuredKg()).isEqualByComparingTo("4000");
        assertThat(report.estimatedKg()).isEqualByComparingTo("500");
        assertThat(report.diversionRatePercent()).isEqualByComparingTo("50.0");
        assertThat(report.estimatedCollections()).isEqualTo(1);
        assertThat(report.estimationRule()).contains("ESTIMATED");
        assertThat(report.lines()).flatExtracting(l -> l.sourceReferences())
                .contains(paper.reference(), rubbish.reference(), guess.reference());
        assertThat(reports.dashboard(site, 90, manager).diversionRatePercent()).isEqualByComparingTo("50.0");
    }

    @Test
    @DisplayName("certificate completion counts handed-over collections that hold a certificate")
    void certificate_completion() {
        WasteCollection a = collected(general, carrier, null);
        collections.handOver(a.id(), recycler.id(), null, null, manager);
        collections.recordCertificate(a.id(), "CERT-A", LocalDate.now(), null, manager);
        WasteCollection b = collected(general, carrier, null);
        collections.handOver(b.id(), recycler.id(), null, null, manager);

        assertThat(reports.dashboard(site, 90, manager).certificateCompletionPercent()).isEqualByComparingTo("50.0");
    }

    @Test
    @DisplayName("roles: the requester reads nothing, a roleless site is refused, only the verifier accepts evidence")
    void permissions() {
        WasteCollection c = collected(general, carrier, null);
        Caller requester = scoped("req", SflRole.IFIMP_REQUESTER, site);
        Caller elsewhere = scoped("fm-else", SflRole.FACILITIES_MANAGER, "OTHER-SITE");
        Caller auditor = scoped("aud", SflRole.COMPLIANCE_OFFICER, site);

        assertThatThrownBy(() -> collections.list(site, null, null, false, 0, 10, requester)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> collections.list(site, null, null, false, 0, 10, elsewhere)).isInstanceOf(RuntimeException.class);
        assertThat(collections.list(site, null, null, false, 0, 10, auditor).items()).hasSize(1);
        assertThatThrownBy(() -> collections.markMissed(c.id(), "x", null, auditor)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> config.createCarrier("X" + tag, "X", "L", LocalDate.now().plusDays(5), false, auditor))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("unknown ids are not found, a stale version conflicts, and filters apply on the server")
    void lookup_and_versioning() {
        assertThatThrownBy(() -> collections.get(UUID.randomUUID(), manager)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.RECORD_NOT_FOUND));
        WasteCollection c = scheduled(general, carrier);
        collections.record(c.id(), null, BigDecimal.ONE, "KG", QuantityBasis.MEASURED, null, c.version(), manager);
        assertThatThrownBy(() -> collections.markMissed(c.id(), "late", c.version(), manager)).isInstanceOf(FacilitiesException.class);
        assertThat(collections.list(site, "COLLECTED", null, false, 0, 10, manager).total()).isEqualTo(1);
        assertThat(collections.list(site, null, hazardous.id(), false, 0, 10, manager).items()).isEmpty();
        assertThatThrownBy(() -> collections.list(site, "WIZARD", null, false, 0, 10, manager))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(exceptions.list(site, ExceptionStatus.OPEN.name(), null, 0, 10, manager).items()).isNotNull();
    }
}
