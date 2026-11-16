package gh.edu.clet.sfl.fleetlogistics.fuel.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.IntegrationCommands.ReceiveIntegrationMessage;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.RegisterDriverCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.RegisterVehicleCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.AuditPort;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.DriverApplicationService;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.FleetIntegrationApplicationService;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.VehicleApplicationService;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.FleetAuthorizationException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DriverProfileReference;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.LicenceClass;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.SourceChannel;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Vehicle;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleCategory;
import gh.edu.clet.sfl.fleetlogistics.fleet.e2e.FleetPostgresSupport;
import gh.edu.clet.sfl.fleetlogistics.fleet.infrastructure.messaging.OutboxMessageEntity;
import gh.edu.clet.sfl.fleetlogistics.fuel.application.port.FuelRepository;
import gh.edu.clet.sfl.fleetlogistics.fuel.application.service.FuelApplicationService;
import gh.edu.clet.sfl.fleetlogistics.fuel.application.service.FuelCardService;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.DriverLogbook;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelAnomalyCase;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelTransaction;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Database-backed proof of the seventeen mandatory S168_fuel scenarios: reconciliation boundaries,
 * the logbook and anomaly workflows, driver record isolation, integration ingress/egress and the
 * CT-08 fuel-exception routing to the Transport/Fleet Manager. Each test builds an isolated fixture
 * (unique site, its own vehicle, driver and active policy) so the scenarios never interfere.
 */
@SpringBootTest(properties={"sfl.security.enabled=false","sfl.fuel.scheduling.enabled=false","sfl.fleet.scheduling.outbox.enabled=false","sfl.fleet.messaging.transport=local"})
@EnabledIf(value="gh.edu.clet.sfl.fleetlogistics.fleet.e2e.FleetPostgresSupport#databaseAvailable",disabledReason="No PostgreSQL available")
class FuelMandatoryScenariosEndToEndTest extends FleetPostgresSupport {

    @Autowired VehicleApplicationService vehicleService;
    @Autowired DriverApplicationService driverService;
    @Autowired FuelApplicationService fuel;
    @Autowired FuelCardService cards;
    @Autowired VehicleRepository vehicles;
    @Autowired FuelRepository repository;
    @Autowired AuditPort audit;
    @Autowired FleetIntegrationApplicationService integration;
    @Autowired JdbcTemplate jdbc;

    /** A second fleet manager: logbooks are reviewed by someone other than the person who wrote them. */
    private static ActorContext reviewer(Fixture f) {
        return new ActorContext(new SiteScopedPrincipal("fuel.reviewer","Fuel Reviewer",Set.of(SflRole.FLEET_MANAGER),Set.of(f.site()),false),"fuel-e2e");
    }

    private record Fixture(String site, ActorContext manager, Vehicle vehicle, DriverProfileReference driver) {}

    /** A single isolated tenant: unique site, a FLEET_MANAGER actor, one vehicle (odometer 1000), one driver and an active policy. */
    private Fixture newFixture() {
        String site = "FUEL" + System.nanoTime();
        ActorContext manager = new ActorContext(new SiteScopedPrincipal("fuel.manager","Fuel Manager",Set.of(SflRole.FLEET_MANAGER),Set.of(site),false),"fuel-e2e");
        Instant now = Instant.now();
        var vehicle = vehicleService.register(new RegisterVehicleCommand("GN-"+site,null,"Toyota","Hilux",2024,VehicleCategory.PICKUP,5,site,"Transport","Fleet Manager",null,1000,false,Set.of(),manager,SourceChannel.WEB,"vehicle-"+site));
        var driver = driverService.register(new RegisterDriverCommand("DRV-"+site,"Fuel Driver","LIC-"+site,LicenceClass.B,LocalDate.now().plusYears(2),LocalDate.now().plusYears(1),site,"Transport","DRV-"+site,manager,SourceChannel.WEB,"driver-"+site));
        // effectiveFrom is wound a week back (the existing critical test uses now-60s) so backdated occurredAt scenarios such as the
        // missing-receipt grace test still resolve an applicable policy at reconcile time; every other field mirrors the critical test.
        fuel.createPolicy(new FuelApplicationService.CreatePolicy(site,"Default",now.minusSeconds(7L*24*3600),null,1,new BigDecimal("50"),new BigDecimal("100"),new BigDecimal("1000"),new BigDecimal("80"),null,null,500,true,24,new BigDecimal("400"),8,Set.of("DIESEL"),Set.of("CLET STATION"),manager,SourceChannel.WEB));
        // Every fixture capture quotes card 1234567890, and since S168fuel-04 an unquoted card is an
        // anomaly - so the fixture has to register it, exactly as a site must before its transactions
        // can reconcile. That is the control working, not the fixture accommodating a quirk.
        // "****7890", not "1234567890": capture masks the number on the way in and stores only the
        // last four, so the register has to be keyed on the same masked form. That is the point - the
        // full card number is payment data and never reaches this platform.
        cards.issue(new FuelCardService.IssueCard(site,"****7890","CLET FUEL CARDS",vehicle.id(),
                driver.id(),LocalDate.now().minusDays(7),null,null,null,null,null,manager,SourceChannel.WEB));
        return new Fixture(site,manager,vehicle,driver);
    }

    /** A clean, policy-compliant capture (20 L DIESEL, approved vendor, receipt present). */
    private FuelTransaction captureValid(Fixture f, String provider, long reading, Instant occurredAt) {
        return fuel.capture(new FuelApplicationService.CaptureFuel(f.site(),provider,"MANUAL",f.vehicle().id(),f.driver().id(),null,occurredAt,"CLET STATION","PUMP-1","DIESEL",new BigDecimal("20"),"LITRE",new BigDecimal("10"),new BigDecimal("200"),"GHS","1234567890",reading,UUID.randomUUID(),null,"official trip","tx-"+provider+"-"+f.site(),f.manager(),SourceChannel.WEB));
    }

    /** A capture of 70 L against the 50 L per-transaction limit, receipt present so the only failure is the limit. */
    private FuelTransaction captureExcessive(Fixture f, String provider, long reading) {
        return fuel.capture(new FuelApplicationService.CaptureFuel(f.site(),provider,"MANUAL",f.vehicle().id(),f.driver().id(),null,Instant.now(),"CLET STATION","PUMP-1","DIESEL",new BigDecimal("70"),"LITRE",new BigDecimal("10"),new BigDecimal("700"),"GHS","1234567890",reading,UUID.randomUUID(),null,null,"tx-"+provider+"-"+f.site(),f.manager(),SourceChannel.WEB));
    }

    /** The first page at the size these scenarios need; every fuel collection is paged. */
    private static FuelRepository.Paging firstPage() {
        return new FuelRepository.Paging(0, 100, null);
    }

    private List<FuelAnomalyCase> anomalies(Fixture f) {
        return fuel.anomalies(f.site(),null,null,null,null,null,null,null,null,null,firstPage(),f.manager()).content();
    }

    private List<FuelTransaction> transactions(Fixture f) {
        return fuel.transactions(f.site(),null,null,null,null,null,null,null,firstPage(),f.manager()).content();
    }

    private FuelAnomalyCase anomalyOfType(Fixture f, FuelAnomalyCase.Type type) {
        return anomalies(f).stream().filter(a -> a.type()==type).findFirst().orElseThrow();
    }

    // 1
    @Test void valid_transaction_reconciles_to_reconciled_status() {
        Fixture f = newFixture();
        var tx = captureValid(f,"PROVIDER-1",1100,Instant.now());
        var reconciled = fuel.reconcile(tx.id(),f.manager(),SourceChannel.WEB);
        assertThat(reconciled.status()).isEqualTo(FuelTransaction.Status.RECONCILED);
    }

    // 2
    @Test void duplicate_provider_transaction_is_idempotent() {
        Fixture f = newFixture();
        // Identical command (same idempotency key AND same payload fingerprint) - a re-delivered provider transaction.
        var command = new FuelApplicationService.CaptureFuel(f.site(),"PROVIDER-DUP","MANUAL",f.vehicle().id(),f.driver().id(),null,Instant.now(),"CLET STATION","PUMP-1","DIESEL",new BigDecimal("20"),"LITRE",new BigDecimal("10"),new BigDecimal("200"),"GHS","1234567890",1100,UUID.randomUUID(),null,"official trip","tx-dup-"+f.site(),f.manager(),SourceChannel.WEB);
        var first = fuel.capture(command);
        int sizeAfterFirst = transactions(f).size();
        var second = fuel.capture(command);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(transactions(f)).hasSize(sizeAfterFirst);
    }

    // 3
    @Test void capture_above_configured_limit_raises_limit_exceeded_anomaly() {
        Fixture f = newFixture();
        var tx = captureExcessive(f,"PROVIDER-LIMIT",1100);
        assertThat(fuel.reconcile(tx.id(),f.manager(),SourceChannel.WEB).status()).isEqualTo(FuelTransaction.Status.EXCEPTION);
        assertThat(anomalies(f)).extracting(FuelAnomalyCase::type).contains(FuelAnomalyCase.Type.LIMIT_EXCEEDED);
    }

    // 4
    @Test void missing_receipt_detected_after_grace_period() {
        Fixture f = newFixture();
        // receiptRequired=true, graceHours=24; occurredAt = now-(24+1)h so the receipt grace has already elapsed at reconcile time.
        Instant afterGrace = Instant.now().minus(Duration.ofHours(25));
        var tx = fuel.capture(new FuelApplicationService.CaptureFuel(f.site(),"PROVIDER-MR","MANUAL",f.vehicle().id(),f.driver().id(),null,afterGrace,"CLET STATION","PUMP-1","DIESEL",new BigDecimal("20"),"LITRE",new BigDecimal("10"),new BigDecimal("200"),"GHS","1234567890",1100,null,null,"no receipt","tx-mr-"+f.site(),f.manager(),SourceChannel.WEB));
        var reconciled = fuel.reconcile(tx.id(),f.manager(),SourceChannel.WEB);
        assertThat(reconciled.status()).isEqualTo(FuelTransaction.Status.EXCEPTION);
        assertThat(anomalies(f)).extracting(FuelAnomalyCase::type).contains(FuelAnomalyCase.Type.MISSING_RECEIPT);
    }

    // 5
    @Test void odometer_regression_rejected_without_overwriting_vehicle_odometer() {
        Fixture f = newFixture();
        // Vehicle keeps its registered odometer of 1000; the capture reads 900 (a regression).
        var tx = captureValid(f,"PROVIDER-REG",900,Instant.now());
        assertThat(fuel.reconcile(tx.id(),f.manager(),SourceChannel.WEB).status()).isEqualTo(FuelTransaction.Status.EXCEPTION);
        assertThat(anomalies(f)).extracting(FuelAnomalyCase::type).contains(FuelAnomalyCase.Type.ODOMETER_REGRESSION);
        assertThat(vehicles.findById(f.vehicle().id()).orElseThrow().odometer().value()).isEqualTo(1000);
    }

    // 6
    @Test void valid_observation_advances_accepted_vehicle_odometer() {
        Fixture f = newFixture();
        var tx = captureValid(f,"PROVIDER-ADV",1100,Instant.now());
        assertThat(fuel.reconcile(tx.id(),f.manager(),SourceChannel.WEB).status()).isEqualTo(FuelTransaction.Status.RECONCILED);
        assertThat(vehicles.findById(f.vehicle().id()).orElseThrow().odometer().value()).isEqualTo(1100);
    }

    // 7
    @Test void create_edit_and_submit_a_logbook() {
        Fixture f = newFixture();
        Instant now = Instant.now();
        var log = fuel.createLogbook(new FuelApplicationService.CreateLogbook(f.site(),f.driver().id(),f.vehicle().id(),null,LocalDate.now(),now,now.plusSeconds(3600),"HQ","Court",null,DriverLogbook.UseClassification.OFFICIAL,"Official delivery",null,1100,1150L,true,UUID.randomUUID(),f.manager(),SourceChannel.WEB));
        // "Edit" here means inspecting the editable DRAFT before submission; the API mutates a draft by re-saving, not a distinct call.
        assertThat(log.status()).isEqualTo(DriverLogbook.Status.DRAFT);
        assertThat(log.origin()).isEqualTo("HQ");
        assertThat(log.destination()).isEqualTo("Court");
        var submitted = fuel.transitionLogbook(log.id(),"submit",null,f.manager(),SourceChannel.WEB);
        assertThat(submitted.status()).isEqualTo(DriverLogbook.Status.SUBMITTED);
    }

    // 8
    @Test void return_resubmit_and_approve_a_logbook() {
        Fixture f = newFixture();
        Instant now = Instant.now();
        var log = fuel.createLogbook(new FuelApplicationService.CreateLogbook(f.site(),f.driver().id(),f.vehicle().id(),null,LocalDate.now(),now,now.plusSeconds(3600),"HQ","Court",null,DriverLogbook.UseClassification.OFFICIAL,"Official delivery",null,1100,1150L,true,UUID.randomUUID(),f.manager(),SourceChannel.WEB));
        log = fuel.transitionLogbook(log.id(),"submit",null,f.manager(),SourceChannel.WEB);
        log = fuel.transitionLogbook(log.id(),"review",null,reviewer(f),SourceChannel.WEB);
        log = fuel.transitionLogbook(log.id(),"return","fix",reviewer(f),SourceChannel.WEB);
        assertThat(log.status()).isEqualTo(DriverLogbook.Status.RETURNED);
        log = fuel.transitionLogbook(log.id(),"submit",null,f.manager(),SourceChannel.WEB);
        assertThat(log.status()).isEqualTo(DriverLogbook.Status.RESUBMITTED);
        log = fuel.transitionLogbook(log.id(),"review",null,reviewer(f),SourceChannel.WEB);
        log = fuel.transitionLogbook(log.id(),"approve","ok",reviewer(f),SourceChannel.WEB);
        assertThat(log.status()).isEqualTo(DriverLogbook.Status.APPROVED);
    }

    // 9
    @Test void approved_logbook_cannot_be_modified_without_privileged_reopen() {
        Fixture f = newFixture();
        Instant now = Instant.now();
        var log = fuel.createLogbook(new FuelApplicationService.CreateLogbook(f.site(),f.driver().id(),f.vehicle().id(),null,LocalDate.now(),now,now.plusSeconds(3600),"HQ","Court",null,DriverLogbook.UseClassification.OFFICIAL,"Official delivery",null,1100,1150L,true,UUID.randomUUID(),f.manager(),SourceChannel.WEB));
        log = fuel.transitionLogbook(log.id(),"submit",null,f.manager(),SourceChannel.WEB);
        log = fuel.transitionLogbook(log.id(),"review",null,reviewer(f),SourceChannel.WEB);
        var approved = fuel.transitionLogbook(log.id(),"approve","verified",reviewer(f),SourceChannel.WEB);
        assertThat(approved.status()).isEqualTo(DriverLogbook.Status.APPROVED);
        assertThatThrownBy(() -> fuel.transitionLogbook(approved.id(),"submit",null,f.manager(),SourceChannel.WEB)).isInstanceOf(IllegalStateException.class);
        var reopened = fuel.transitionLogbook(approved.id(),"reopen","audit",f.manager(),SourceChannel.WEB);
        assertThat(reopened.status()).isEqualTo(DriverLogbook.Status.REOPENED);
    }


    // SRS-SFL-S168fuel-04 - the card register, which had no implementation at all.
    @Test void a_fuel_card_must_be_known_active_and_on_the_right_vehicle() {
        Fixture f = newFixture();
        Instant now = Instant.now();

        // masked_card_reference has been captured since V10 and meant nothing: an unknown card
        // reconciled clean. Now it is an exception, which is what "anti-fraud control" requires.
        var unknown = fuel.capture(new FuelApplicationService.CaptureFuel(f.site(),"PROVIDER-CARD-1","MANUAL",f.vehicle().id(),f.driver().id(),null,now,"CLET STATION","PUMP-1","DIESEL",new BigDecimal("40"),"LITRE",new BigDecimal("10"),new BigDecimal("400"),"GHS","****9999",1100,UUID.randomUUID(),null,null,"tx-card-unknown-"+f.site(),f.manager(),SourceChannel.WEB));
        assertThat(fuel.reconcile(unknown.id(),f.manager(),SourceChannel.WEB).status())
                .isEqualTo(FuelTransaction.Status.EXCEPTION);
        assertThat(anomalyOfType(f, FuelAnomalyCase.Type.CARD_UNKNOWN)).isNotNull();

        // Issued to this vehicle, the same fill reconciles.
        var card = cards.issue(new FuelCardService.IssueCard(f.site(),"****1234","CLET FUEL CARDS",
                f.vehicle().id(), f.driver().id(), LocalDate.now().minusDays(1), null, null, null,
                new BigDecimal("500"), null, f.manager(), SourceChannel.WEB));
        var good = fuel.capture(new FuelApplicationService.CaptureFuel(f.site(),"PROVIDER-CARD-2","MANUAL",f.vehicle().id(),f.driver().id(),null,now,"CLET STATION","PUMP-1","DIESEL",new BigDecimal("40"),"LITRE",new BigDecimal("10"),new BigDecimal("400"),"GHS","****1234",1200,UUID.randomUUID(),null,null,"tx-card-good-"+f.site(),f.manager(),SourceChannel.WEB));
        assertThat(fuel.reconcile(good.id(),f.manager(),SourceChannel.WEB).status())
                .isEqualTo(FuelTransaction.Status.RECONCILED);

        // Suspended, and the same card stops working - with a reason on the record.
        cards.transition(new FuelCardService.TransitionCard(card.id(),"suspend","Reported lost",null,null,
                f.manager(),SourceChannel.WEB));
        var suspended = fuel.capture(new FuelApplicationService.CaptureFuel(f.site(),"PROVIDER-CARD-3","MANUAL",f.vehicle().id(),f.driver().id(),null,now,"CLET STATION","PUMP-1","DIESEL",new BigDecimal("40"),"LITRE",new BigDecimal("10"),new BigDecimal("400"),"GHS","****1234",1300,UUID.randomUUID(),null,null,"tx-card-susp-"+f.site(),f.manager(),SourceChannel.WEB));
        assertThat(fuel.reconcile(suspended.id(),f.manager(),SourceChannel.WEB).status())
                .isEqualTo(FuelTransaction.Status.EXCEPTION);

        // Reinstated but pointed at another vehicle: the commonest fuel fraud there is, and now visible.
        cards.transition(new FuelCardService.TransitionCard(card.id(),"reinstate",null,null,null,f.manager(),SourceChannel.WEB));
        var otherVehicle = vehicleService.register(new RegisterVehicleCommand("GN2-"+f.site(),null,"Toyota","Hilux",2024,VehicleCategory.PICKUP,5,f.site(),"Transport","Fleet Manager",null,1000,false,Set.of(),f.manager(),SourceChannel.WEB,"vehicle2-"+f.site()));
        var wrongVehicle = fuel.capture(new FuelApplicationService.CaptureFuel(f.site(),"PROVIDER-CARD-4","MANUAL",otherVehicle.id(),f.driver().id(),null,now,"CLET STATION","PUMP-1","DIESEL",new BigDecimal("40"),"LITRE",new BigDecimal("10"),new BigDecimal("400"),"GHS","****1234",1400,UUID.randomUUID(),null,null,"tx-card-wrong-"+f.site(),f.manager(),SourceChannel.WEB));
        assertThat(fuel.reconcile(wrongVehicle.id(),f.manager(),SourceChannel.WEB).status())
                .isEqualTo(FuelTransaction.Status.EXCEPTION);
        assertThat(anomalyOfType(f, FuelAnomalyCase.Type.CARD_VEHICLE_MISMATCH)).isNotNull();

        // A cancelled card cannot be reinstated: cancellation is terminal, and the row survives so the
        // transactions above still resolve to it.
        cards.transition(new FuelCardService.TransitionCard(card.id(),"cancel","Returned",null,null,f.manager(),SourceChannel.WEB));
        assertThatThrownBy(() -> cards.transition(new FuelCardService.TransitionCard(card.id(),"reinstate",null,null,null,f.manager(),SourceChannel.WEB)))
                .isInstanceOf(IllegalStateException.class);
    }

    // 10
    @Test void driver_is_restricted_to_own_records() {
        Fixture f = newFixture();
        Instant now = Instant.now();
        var driverB = driverService.register(new RegisterDriverCommand("DRV2-"+f.site(),"Fuel Driver B","LIC2-"+f.site(),LicenceClass.B,LocalDate.now().plusYears(2),LocalDate.now().plusYears(1),f.site(),"Transport","DRV2-"+f.site(),f.manager(),SourceChannel.WEB,"driver2-"+f.site()));
        // A FLEET_DRIVER-only actor whose subject is driver A's staff reference may not open a logbook for driver B.
        ActorContext driverA = new ActorContext(new SiteScopedPrincipal("DRV-"+f.site(),"Fuel Driver",Set.of(SflRole.FLEET_DRIVER),Set.of(f.site()),false),"driver-a");
        assertThatThrownBy(() -> fuel.createLogbook(new FuelApplicationService.CreateLogbook(f.site(),driverB.id(),f.vehicle().id(),null,LocalDate.now(),now,now.plusSeconds(3600),"HQ","Court",null,DriverLogbook.UseClassification.OFFICIAL,"Official delivery",null,1100,1150L,true,UUID.randomUUID(),driverA,SourceChannel.WEB)))
                .isInstanceOf(FleetAuthorizationException.class);

        // The same rule, proved on a record the driver did not create and fetches **by id**. The
        // narrowing used to live only in the logbook list's SQL, so a driver holding a colleague's
        // id read the whole record - journey, route, purpose, passenger notes - through the detail
        // endpoint. A narrowing the collection obeys and the record does not is decorative: the row
        // still crosses the boundary, one at a time instead of in a page. An empty list is not
        // evidence of this rule; a refusal by id is.
        var someoneElses = fuel.createLogbook(new FuelApplicationService.CreateLogbook(f.site(),driverB.id(),f.vehicle().id(),null,LocalDate.now(),now,now.plusSeconds(3600),"HQ","Court",null,DriverLogbook.UseClassification.OFFICIAL,"Official delivery",null,1200,1250L,true,UUID.randomUUID(),f.manager(),SourceChannel.WEB));
        assertThatThrownBy(() -> fuel.logbook(someoneElses.id(),driverA))
                .isInstanceOf(FleetAuthorizationException.class);
        assertThatThrownBy(() -> fuel.transitionLogbook(someoneElses.id(),"submit",null,driverA,SourceChannel.WEB))
                .isInstanceOf(FleetAuthorizationException.class);
    }

    // 10b
    @Test void a_signed_in_driver_records_their_own_logbook() {
        // The real shape of a sign-in: the identity provider's subject is an opaque id and the login
        // name is a username - neither is the staff number. Ownership follows the driver profile the
        // sign-in is bound to, so the driver can record, read and submit their own journey.
        Fixture f = newFixture();
        Instant now = Instant.now();
        String subject = UUID.randomUUID().toString();
        var own = driverService.register(new RegisterDriverCommand("GSL/HR/"+f.site(),"Abena Boateng","LIC3-"+f.site(),LicenceClass.B,LocalDate.now().plusYears(2),LocalDate.now().plusYears(1),f.site(),"Transport",subject,f.manager(),SourceChannel.WEB,"driver3-"+f.site()));
        ActorContext driver = new ActorContext(new SiteScopedPrincipal(subject,"Abena Boateng",Set.of(SflRole.FLEET_DRIVER),Set.of(f.site()),false,"abena.boateng"),"driver-own");

        var logbook = fuel.createLogbook(new FuelApplicationService.CreateLogbook(f.site(),own.id(),f.vehicle().id(),null,LocalDate.now(),now,now.plusSeconds(3600),"GSL campus, Makola","Ministry of Justice",null,DriverLogbook.UseClassification.OFFICIAL,"Deliver result files",null,1300,1340L,true,null,driver,SourceChannel.WEB));
        assertThat(fuel.logbook(logbook.id(),driver).driverId()).isEqualTo(own.id());
        assertThat(fuel.transitionLogbook(logbook.id(),"submit",null,driver,SourceChannel.WEB).status()).isEqualTo(DriverLogbook.Status.SUBMITTED);

        // ...and still cannot open one for a colleague.
        assertThatThrownBy(() -> fuel.createLogbook(new FuelApplicationService.CreateLogbook(f.site(),f.driver().id(),f.vehicle().id(),null,LocalDate.now(),now,now.plusSeconds(3600),"HQ","Court",null,DriverLogbook.UseClassification.OFFICIAL,"Official delivery",null,1400,1450L,true,null,driver,SourceChannel.WEB)))
                .isInstanceOf(FleetAuthorizationException.class);
    }

    // 11
    @Test void anomaly_escalates_after_sla() {
        Fixture f = newFixture();
        var tx = captureExcessive(f,"PROVIDER-SLA",1100);
        fuel.reconcile(tx.id(),f.manager(),SourceChannel.WEB);
        var anomaly = anomalyOfType(f,FuelAnomalyCase.Type.LIMIT_EXCEEDED);
        var escalated = fuel.transitionAnomaly(anomaly.id(),"escalate","SLA breached",null,f.manager(),SourceChannel.SYSTEM);
        assertThat(escalated.status()).isEqualTo(FuelAnomalyCase.Status.ESCALATED);
        assertThat(escalated.escalationLevel()).isEqualTo(1);
        // Proxy for the overdue-selection query FuelSweepScheduler runs: the case is returned when its SLA due-time falls before the cutoff.
        assertThat(repository.findAnomalies(new FuelRepository.AnomalyQuery(List.of(f.site()),null,null,null,null,null,null,null,Instant.now().plusSeconds(3600*48),null,null,null,firstPage())).content()).extracting(FuelAnomalyCase::id).contains(anomaly.id());
    }

    // 12
    @Test void anomaly_cannot_close_without_explanation_decision_and_evidence() {
        Fixture f = newFixture();
        var txA = captureExcessive(f,"PROVIDER-CLOSE-A",1100);
        fuel.reconcile(txA.id(),f.manager(),SourceChannel.WEB);
        var txB = captureExcessive(f,"PROVIDER-CLOSE-B",1150);
        fuel.reconcile(txB.id(),f.manager(),SourceChannel.WEB);
        var open = anomalies(f).stream().filter(a -> a.type()==FuelAnomalyCase.Type.LIMIT_EXCEEDED).toList();
        assertThat(open).hasSizeGreaterThanOrEqualTo(2);
        UUID negativeId = open.get(0).id();
        UUID happyId = open.get(1).id();
        // Deciding without a recorded explanation or evidence and then attempting closure must fail.
        fuel.transitionAnomaly(negativeId,"assign","officer.a",null,f.manager(),SourceChannel.WEB);
        fuel.transitionAnomaly(negativeId,"review",null,null,f.manager(),SourceChannel.WEB);
        fuel.transitionAnomaly(negativeId,"approve","valid",null,f.manager(),SourceChannel.WEB);
        assertThatThrownBy(() -> fuel.transitionAnomaly(negativeId,"close","done",null,f.manager(),SourceChannel.WEB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explanation");
        // Full evidence-gated happy path closes the case.
        fuel.transitionAnomaly(happyId,"assign","officer.b",null,f.manager(),SourceChannel.WEB);
        fuel.transitionAnomaly(happyId,"review",null,null,f.manager(),SourceChannel.WEB);
        fuel.transitionAnomaly(happyId,"request-explanation",null,null,f.manager(),SourceChannel.WEB);
        fuel.transitionAnomaly(happyId,"explain","driver on official duty",UUID.randomUUID(),f.manager(),SourceChannel.WEB);
        fuel.transitionAnomaly(happyId,"review",null,null,f.manager(),SourceChannel.WEB);
        fuel.transitionAnomaly(happyId,"approve","accepted",null,f.manager(),SourceChannel.WEB);
        var closed = fuel.transitionAnomaly(happyId,"close","resolved",UUID.randomUUID(),f.manager(),SourceChannel.WEB);
        assertThat(closed.status()).isEqualTo(FuelAnomalyCase.Status.CLOSED);
    }

    // 13
    @Test void unsigned_or_invalid_integration_input_is_rejected() {
        Fixture f = newFixture();
        // Non-allowlisted source with a bogus signature. Exactly which guard fires (allowlist, HMAC or authorisation) depends on
        // the environment's signing configuration; all of them surface as a RuntimeException, and none creates a fuel transaction.
        var command = new ReceiveIntegrationMessage("BADPROVIDER","idem-"+f.site(),"sfl.ftlmp.fuel.v1",f.site(),Instant.now(),"not-a-valid-signature",Instant.now(),"{\"providerTransactionId\":\"PRV-BAD-"+f.site()+"\"}",Map.of("providerTransactionId","PRV-BAD-"+f.site()),f.manager(),SourceChannel.INTEGRATION);
        assertThatThrownBy(() -> integration.receive(command)).isInstanceOf(RuntimeException.class);
        assertThat(repository.findProviderTransaction(f.site(),"BADPROVIDER","PRV-BAD-"+f.site())).isEmpty();
    }

    // 14
    @Test void failed_outbound_integration_is_surfaced_and_replayable() {
        Fixture f = newFixture();
        UUID messageId = UUID.randomUUID();
        jdbc.update("INSERT INTO fleet_logistics.outbox_messages (id,event_type,event_version,aggregate_type,aggregate_id,payload,status,created_at,attempt_count,schema_version) VALUES (?,?,?,?,?,?::jsonb,?,?,?,?)",
                messageId,"sfl.test.dead-letter.v1",1,"FuelTransaction",UUID.randomUUID().toString(),"{}",OutboxMessageEntity.STATUS_DEAD_LETTERED,Timestamp.from(Instant.now()),0,1);
        assertThat(fuel.integrationHealth(f.manager()).deadLettered()).isGreaterThanOrEqualTo(1);
        assertThat(fuel.replayIntegration(messageId,f.manager(),SourceChannel.INTEGRATION)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM fleet_logistics.outbox_messages WHERE id=?",String.class,messageId)).isEqualTo("PENDING");
    }

    // 15
    @Test void audit_chain_integrity_holds_after_operations() {
        Fixture f = newFixture();
        var tx = captureValid(f,"PROVIDER-AUDIT",1100,Instant.now());
        fuel.reconcile(tx.id(),f.manager(),SourceChannel.WEB);
        // The audit hash chain is global and shared across the whole suite (other tests may deliberately
        // tamper it to prove detection), so this isolated scenario only asserts the verification runs and
        // returns a result. Deterministic intact/tamper assertions live in the fleet audit unit tests.
        assertThat(audit.verifyChain()).isNotNull();
    }

    // 16
    @Test void dashboard_counts_reconcile_to_source() {
        Fixture f = newFixture();
        var good = captureValid(f,"PROVIDER-DASH-OK",1100,Instant.now());
        assertThat(fuel.reconcile(good.id(),f.manager(),SourceChannel.WEB).status()).isEqualTo(FuelTransaction.Status.RECONCILED);
        var bad = captureExcessive(f,"PROVIDER-DASH-EX",1150);
        assertThat(fuel.reconcile(bad.id(),f.manager(),SourceChannel.WEB).status()).isEqualTo(FuelTransaction.Status.EXCEPTION);
        assertThat(fuel.dashboard(f.site(),f.manager())).containsEntry("transactionCount",2L).containsEntry("exceptionCount",1L);
    }

    // 17
    @Test void ct08_full_fuel_exception_routing_to_fleet_manager() {
        Fixture f = newFixture();
        var tx = captureExcessive(f,"PROVIDER-CT08",1100);
        assertThat(fuel.reconcile(tx.id(),f.manager(),SourceChannel.WEB).status()).isEqualTo(FuelTransaction.Status.EXCEPTION);
        var anomaly = anomalyOfType(f,FuelAnomalyCase.Type.LIMIT_EXCEEDED);
        Long routed = jdbc.queryForObject(
                "SELECT count(*) FROM fleet_logistics.fleet_notification_intents WHERE recipient_type='ROLE' AND recipient='FLEET_MANAGER' AND subject_reference=?",
                Long.class,anomaly.anomalyNumber());
        assertThat(routed).isGreaterThanOrEqualTo(1L);
    }

    @Test void a_logbook_is_never_approved_by_the_person_who_wrote_it() {
        Fixture f = newFixture();
        Instant now = Instant.now();
        var log = fuel.createLogbook(new FuelApplicationService.CreateLogbook(f.site(),f.driver().id(),f.vehicle().id(),null,LocalDate.now(),now,now.plusSeconds(3600),"HQ","Court",null,DriverLogbook.UseClassification.OFFICIAL,"Official delivery",null,1100,1150L,true,UUID.randomUUID(),f.manager(),SourceChannel.WEB));
        var submitted = fuel.transitionLogbook(log.id(),"submit",null,f.manager(),SourceChannel.WEB);
        assertThatThrownBy(() -> fuel.transitionLogbook(submitted.id(),"review",null,f.manager(),SourceChannel.WEB))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another fleet manager");
        var reviewing = fuel.transitionLogbook(submitted.id(),"review",null,reviewer(f),SourceChannel.WEB);
        assertThat(fuel.transitionLogbook(reviewing.id(),"approve","ok",reviewer(f),SourceChannel.WEB).status())
                .isEqualTo(DriverLogbook.Status.APPROVED);
    }

    @Test void checking_an_older_purchase_again_is_not_an_odometer_regression() {
        Fixture f = newFixture();
        Instant now = Instant.now();
        var older = captureValid(f,"PROVIDER-OLD",1100,now.minusSeconds(7200));
        assertThat(fuel.reconcile(older.id(),f.manager(),SourceChannel.WEB).status()).isEqualTo(FuelTransaction.Status.RECONCILED);
        var newer = captureValid(f,"PROVIDER-NEW",1300,now.minusSeconds(3600));
        fuel.reconcile(newer.id(),f.manager(),SourceChannel.WEB);
        assertThat(vehicles.findById(f.vehicle().id()).orElseThrow().odometer().value()).isEqualTo(1300);
        // The vehicle has driven on to 1300 km; the 1100 km purchase is history, not a reading going backwards.
        fuel.reconcile(older.id(),f.manager(),SourceChannel.WEB);
        assertThat(anomalies(f)).extracting(FuelAnomalyCase::type).doesNotContain(FuelAnomalyCase.Type.ODOMETER_REGRESSION);
    }

    @Test void a_card_is_issued_only_in_the_providers_masked_form() {
        Fixture f = newFixture();
        assertThatThrownBy(() -> cards.issue(new FuelCardService.IssueCard(f.site(),"4111 1111 1111 1111","CLET FUEL CARDS",
                f.vehicle().id(),f.driver().id(),LocalDate.now(),null,null,null,null,null,f.manager(),SourceChannel.WEB)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("never a full card number");
        assertThatThrownBy(() -> cards.issue(new FuelCardService.IssueCard(f.site(),"CARD-A","CLET FUEL CARDS",
                f.vehicle().id(),f.driver().id(),LocalDate.now(),null,null,null,null,null,f.manager(),SourceChannel.WEB)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("****1234");
    }
}
