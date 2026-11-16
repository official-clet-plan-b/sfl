package gh.edu.clet.sfl.fleetlogistics.fuel.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.RegisterDriverCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.RegisterVehicleCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.DriverApplicationService;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.VehicleApplicationService;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.LicenceClass;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.SourceChannel;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleCategory;
import gh.edu.clet.sfl.fleetlogistics.fleet.e2e.FleetPostgresSupport;
import gh.edu.clet.sfl.fleetlogistics.fuel.application.service.FuelApplicationService;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.DriverLogbook;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelAnomalyCase;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Database-backed CT-08 plus the critical logbook and odometer boundaries. */
@SpringBootTest(properties={"sfl.security.enabled=false","sfl.fuel.scheduling.enabled=false","sfl.fleet.scheduling.outbox.enabled=false","sfl.fleet.messaging.transport=local"})
@EnabledIf(value="gh.edu.clet.sfl.fleetlogistics.fleet.e2e.FleetPostgresSupport#databaseAvailable",disabledReason="No PostgreSQL available")
class FuelCriticalScenariosEndToEndTest extends FleetPostgresSupport {
    @Autowired VehicleApplicationService vehicleService; @Autowired DriverApplicationService driverService;
    @Autowired FuelApplicationService fuel; @Autowired gh.edu.clet.sfl.fleetlogistics.fuel.application.service.FuelCardService cards; @Autowired VehicleRepository vehicles;

    @Test void valid_reconciliation_anomaly_workflow_logbook_and_odometer_are_persisted(){String site="FUEL"+System.nanoTime();ActorContext actor=new ActorContext(new SiteScopedPrincipal("fuel.manager","Fuel Manager",Set.of(SflRole.FLEET_MANAGER),Set.of(site),false),"fuel-e2e");Instant now=Instant.now();var vehicle=vehicleService.register(new RegisterVehicleCommand("GN-"+site,null,"Toyota","Hilux",2024,VehicleCategory.PICKUP,5,site,"Transport","Fleet Manager",null,1000,false,Set.of(),actor,SourceChannel.WEB,"vehicle-"+site));var driver=driverService.register(new RegisterDriverCommand("DRV-"+site,"Fuel Driver","LIC-"+site,LicenceClass.B,LocalDate.now().plusYears(2),LocalDate.now().plusYears(1),site,"Transport","DRV-"+site,actor,SourceChannel.WEB,"driver-"+site));fuel.createPolicy(new FuelApplicationService.CreatePolicy(site,"Default",now.minusSeconds(60),null,1,new BigDecimal("50"),new BigDecimal("100"),new BigDecimal("1000"),new BigDecimal("80"),null,null,500,true,24,new BigDecimal("400"),8,Set.of("DIESEL"),Set.of("CLET STATION"),actor,SourceChannel.WEB));
        // S168fuel-04: an unregistered card is now an anomaly, so the site registers its card first -
        // "****7890" because capture masks the number and stores only the last four.
        cards.issue(new gh.edu.clet.sfl.fleetlogistics.fuel.application.service.FuelCardService.IssueCard(site,"****7890","CLET FUEL CARDS",vehicle.id(),driver.id(),java.time.LocalDate.now().minusDays(7),null,null,null,null,null,actor,SourceChannel.WEB));
        var valid=fuel.capture(new FuelApplicationService.CaptureFuel(site,"PROVIDER-1","MANUAL",vehicle.id(),driver.id(),null,now,"CLET STATION","PUMP-1","DIESEL",new BigDecimal("20"),"LITRE",new BigDecimal("10"),new BigDecimal("200"),"GHS","1234567890",1100,UUID.randomUUID(),null,"official trip","tx-valid-"+site,actor,SourceChannel.WEB));var reconciled=fuel.reconcile(valid.id(),actor,SourceChannel.WEB);assertThat(reconciled.status()).isEqualTo(FuelTransaction.Status.RECONCILED);assertThat(vehicles.findById(vehicle.id()).orElseThrow().odometer().value()).isEqualTo(1100);
        var excessive=fuel.capture(new FuelApplicationService.CaptureFuel(site,"PROVIDER-2","MANUAL",vehicle.id(),driver.id(),null,now.plusSeconds(60),"CLET STATION","PUMP-1","DIESEL",new BigDecimal("70"),"LITRE",new BigDecimal("10"),new BigDecimal("700"),"GHS",null,1150,UUID.randomUUID(),null,null,"tx-anomaly-"+site,actor,SourceChannel.WEB));assertThat(fuel.reconcile(excessive.id(),actor,SourceChannel.WEB).status()).isEqualTo(FuelTransaction.Status.EXCEPTION);var anomalies=fuel.anomalies(site,null,null,null,null,null,null,null,null,null,new gh.edu.clet.sfl.fleetlogistics.fuel.application.port.FuelRepository.Paging(0,100,null),actor).content();assertThat(anomalies).extracting(FuelAnomalyCase::type).contains(FuelAnomalyCase.Type.LIMIT_EXCEEDED);
        var log=fuel.createLogbook(new FuelApplicationService.CreateLogbook(site,driver.id(),vehicle.id(),null,LocalDate.now(),now,now.plusSeconds(3600),"HQ","Court",null,DriverLogbook.UseClassification.OFFICIAL,"Official delivery",null,1100,1150L,true,UUID.randomUUID(),actor,SourceChannel.WEB));log=fuel.transitionLogbook(log.id(),"submit",null,actor,SourceChannel.WEB);ActorContext reviewer=new ActorContext(new SiteScopedPrincipal("fuel.reviewer","Fuel Reviewer",Set.of(SflRole.FLEET_MANAGER),Set.of(site),false),"fuel-e2e");log=fuel.transitionLogbook(log.id(),"review",null,reviewer,SourceChannel.WEB);log=fuel.transitionLogbook(log.id(),"approve","verified",reviewer,SourceChannel.WEB);assertThat(log.status()).isEqualTo(DriverLogbook.Status.APPROVED);assertThat(fuel.dashboard(site,actor)).containsEntry("transactionCount",2L).containsEntry("exceptionCount",1L);
    }
}
