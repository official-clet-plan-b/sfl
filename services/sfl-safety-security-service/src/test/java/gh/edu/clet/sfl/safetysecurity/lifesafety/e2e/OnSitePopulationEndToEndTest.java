package gh.edu.clet.sfl.safetysecurity.lifesafety.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.application.port.AccessControlRepository;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.AccessDirection;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.AccessEvent;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.AccessEventKind;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.AccessZone;
import gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport;
import gh.edu.clet.sfl.safetysecurity.lifesafety.application.port.LifeSafetyRepository;
import gh.edu.clet.sfl.safetysecurity.lifesafety.application.port.OnSitePopulationPort;
import gh.edu.clet.sfl.safetysecurity.lifesafety.application.port.OnSitePopulationPort.OnSitePerson;
import gh.edu.clet.sfl.safetysecurity.lifesafety.application.port.OnSitePopulationPort.PopulationSnapshot;
import gh.edu.clet.sfl.safetysecurity.lifesafety.application.service.MusterService;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.LifeSafetyEvent;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.LifeSafetyEventKind;
import gh.edu.clet.sfl.safetysecurity.visitor.application.service.VisitorCheckInOutService;
import gh.edu.clet.sfl.safetysecurity.visitor.application.service.VisitorRegistrationService;
import gh.edu.clet.sfl.safetysecurity.visitor.domain.model.VisitPurpose;
import gh.edu.clet.sfl.safetysecurity.visitor.domain.model.VisitorVisit;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The real muster baseline - S162a-04, S160a-06, Phase 2 S175-02 - against a real PostgreSQL: S160's
 * checked-in visitors and S160a's access occupancy, in one list, with how fresh the access data was.
 */
@SpringBootTest(properties = {"sfl.security.enabled=false", "sfl.safety-security.messaging.drainer-enabled=false",
        "sfl.life-safety.scheduling.enabled=false", "sfl.emergency.scheduling.enabled=false"})
@EnabledIf(value = "gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available")
class OnSitePopulationEndToEndTest extends SafetySecurityPostgresSupport {

    @Autowired OnSitePopulationPort population;
    @Autowired MusterService muster;
    @Autowired LifeSafetyRepository lifeSafety;
    @Autowired AccessControlRepository access;
    @Autowired VisitorRegistrationService registration;
    @Autowired VisitorCheckInOutService checkInOut;

    private String site;
    private Instant lastEntry;

    @BeforeEach
    void onSite() {
        site = "POP" + Math.abs(UUID.randomUUID().hashCode() % 1_000_000);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        access.saveZone(new AccessZone(UUID.randomUUID(), site, "BLOCK-A", "Block A", null, "24x7", Map.of(), false,
                List.of(), null, accessMeta(now)));
        accessEvent("STAFF-1", "BLOCK-A", AccessDirection.ENTRY, now.minusSeconds(600));
        accessEvent("STAFF-2", "BLOCK-A", AccessDirection.ENTRY, now.minusSeconds(500));
        lastEntry = now.minusSeconds(400);
        accessEvent("STAFF-2", "BLOCK-A", AccessDirection.EXIT, lastEntry);

        ActorContext reception = new ActorContext(new SiteScopedPrincipal("reception", "Reception",
                Set.of(SflRole.RECEPTION_OFFICER), Set.of(site), false), "pop-e2e");
        VisitorVisit delivery = registration.preRegister(new VisitorRegistrationService.PreRegisterVisit(site,
                "Ama Mensah", "Acme Ltd", null, "stores-1", "Stores", VisitPurpose.DELIVERY,
                Instant.now().plusSeconds(60), Instant.now().plusSeconds(3600), reception,
                gh.edu.clet.sfl.safetysecurity.visitor.domain.model.SourceChannel.WEB));
        VisitorVisit badged = checkInOut.assignBadge(new VisitorCheckInOutService.AssignBadge(delivery.id(), "BADGE-9",
                List.of("BLOCK-A"), delivery.metadata().version(), reception,
                gh.edu.clet.sfl.safetysecurity.visitor.domain.model.SourceChannel.WEB));
        checkInOut.checkIn(new VisitorCheckInOutService.Transition(badged.id(), badged.metadata().version(), reception,
                gh.edu.clet.sfl.safetysecurity.visitor.domain.model.SourceChannel.WEB));
    }

    @Test
    @DisplayName("the baseline is S160's visitors and S160a's occupancy in one list, with the access data's age")
    void visitors_and_access_occupancy_in_one_list() {
        PopulationSnapshot snapshot = population.snapshot(site, null, Instant.now());

        assertThat(snapshot.personRefs()).containsExactlyInAnyOrder("STAFF-1", "BADGE-9");
        assertThat(snapshot.persons()).filteredOn(p -> p.personRef().equals("BADGE-9")).singleElement()
                .satisfies(p -> {
                    assertThat(p.source()).isEqualTo(OnSitePopulationPort.Source.VISITOR);
                    assertThat(p.displayName()).isEqualTo("Ama Mensah");
                });
        assertThat(snapshot.persons()).extracting(OnSitePerson::personRef).doesNotContain("STAFF-2");
        assertThat(snapshot.accessDataAsOf()).isEqualTo(lastEntry);
    }

    @Test
    @DisplayName("a real fire muster now has a baseline: who is on site and has not checked in")
    void a_fire_muster_lists_who_is_missing() {
        ActorContext soc = new ActorContext(new SiteScopedPrincipal("soc", "SOC", Set.of(SflRole.SOC_OPERATOR),
                Set.of(site), false), "pop-e2e");
        LifeSafetyEvent fire = lifeSafety.saveEvent(new LifeSafetyEvent(UUID.randomUUID(), site, "PANEL",
                "ext-" + UUID.randomUUID(), "DET-1", "BLOCK-A", LifeSafetyEventKind.FIRE, Instant.now(),
                gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.RecordMetadata.createdBy("panel", Instant.now(),
                        gh.edu.clet.sfl.safetysecurity.lifesafety.domain.model.SourceChannel.INTEGRATION, "pop")));
        var session = muster.openForEvent(fire, soc);
        muster.checkIn(session.id(), "STAFF-1", soc);

        assertThat(muster.rollCall(session.id(), soc).outstanding()).containsExactly("BADGE-9");
    }

    private void accessEvent(String person, String zone, AccessDirection direction, Instant at) {
        access.saveEvent(new AccessEvent(UUID.randomUUID(), site, "VENDOR-SIM", "ext-" + UUID.randomUUID(), "RDR-1",
                "DOOR-1", zone, person, AccessEventKind.GRANTED, direction, at, accessMeta(at)));
    }

    private static gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.RecordMetadata accessMeta(Instant at) {
        return gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.RecordMetadata.createdBy("vendor", at,
                gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.SourceChannel.INTEGRATION, "pop");
    }
}
