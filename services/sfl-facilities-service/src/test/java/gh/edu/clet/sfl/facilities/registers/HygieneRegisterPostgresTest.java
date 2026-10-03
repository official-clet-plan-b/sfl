package gh.edu.clet.sfl.facilities.registers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport;
import gh.edu.clet.sfl.facilities.registers.application.RegisterRecordService;
import gh.edu.clet.sfl.facilities.registers.application.RegisterRecordService.CreateCommand;
import gh.edu.clet.sfl.facilities.registers.domain.RegisterRecord;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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

/**
 * The S170 register against a real PostgreSQL - the service as a hygiene officer actually reaches it.
 *
 * <p>Four things were wrong that no mock could have shown. Creating or updating a record crashed on
 * every call, because a bare {@code Instant} cannot be bound to a PostgreSQL timestamp; the only two
 * roles allowed to write were the platform administrator and the integration engineer, so the
 * facilities and HSE owners named in the SRS were refused; the table had no row-level security; and a
 * tab that listed one record type hid every record of the other. Each has a test here, except
 * row-level security, which {@link RegisterRecordsRowLevelSecurityTest} covers from the database's own side.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sfl.facilities.messaging.drainer-enabled=false",
        "sfl.maintenance.scheduling.enabled=false",
        "sfl.booking.scheduling.enabled=false",
})
@EnabledIf(value = "gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available; see FacilitiesPostgresSupport.unavailableReason()")
class HygieneRegisterPostgresTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        FacilitiesPostgresSupport.datasource(registry);
    }

    @Autowired
    private RegisterRecordService records;

    private String site;

    @BeforeEach
    void freshSite() {
        // A site of its own per test, so a record from another test can never be mistaken for this one's.
        site = "HYG" + Math.abs(UUID.randomUUID().hashCode() % 1_000_000);
    }

    private static ActorContext actor(SflRole role, String... sites) {
        String id = role.name().toLowerCase() + "-s170";
        return new ActorContext(new SiteScopedPrincipal(id, id, Set.of(role), Set.of(sites), false), "corr-" + id);
    }

    private RegisterRecord create(ActorContext by, String type, String title, Instant due) {
        return records.create(new CreateCommand("S170", site, type, title, "PLANNED", "J. Mensah", due, "MEDIUM",
                "evidence ref DOC-1", by, SourceChannel.WEB));
    }

    private List<RegisterRecord> list(ActorContext by, String types) {
        return records.list("S170", site, types, by, SourceChannel.WEB);
    }

    @Test
    @DisplayName("a record can be created and its status changed, and the dates survive the database")
    void create_and_update_round_trip_the_timestamps() {
        ActorContext manager = actor(SflRole.FACILITIES_MANAGER, site);
        Instant due = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);

        RegisterRecord created = create(manager, "HYGIENE_AUDIT", "Kitchen audit", due);

        assertThat(created.dueAt()).isEqualTo(due);
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.siteCode()).isEqualTo(site);
        assertThat(created.version()).isZero();

        RegisterRecord updated = records.updateStatus(created.id(), "open", manager, SourceChannel.WEB);

        assertThat(updated.status()).isEqualTo("OPEN");
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.updatedAt()).isAfterOrEqualTo(created.updatedAt());
        assertThat(updated.dueAt()).isEqualTo(due);
    }

    @Test
    @DisplayName("a record with no due date is stored and read back as having none")
    void a_missing_due_date_stays_missing() {
        RegisterRecord created = create(actor(SflRole.HSE_MANAGER, site), "FINDING", "Undated", null);

        assertThat(created.dueAt()).isNull();
        assertThat(list(actor(SflRole.HSE_MANAGER, site), "FINDING")).extracting(RegisterRecord::id)
                .containsExactly(created.id());
    }

    @Test
    @DisplayName("a tab can list several record types, so a pest visit is not hidden among hygiene audits")
    void a_list_can_ask_for_more_than_one_type() {
        ActorContext manager = actor(SflRole.FACILITIES_MANAGER, site);
        RegisterRecord audit = create(manager, "HYGIENE_AUDIT", "Audit", null);
        RegisterRecord visit = create(manager, "PEST_VISIT", "Pest visit", null);
        RegisterRecord finding = create(manager, "FINDING", "Finding", null);

        assertThat(list(manager, "HYGIENE_AUDIT,PEST_VISIT")).extracting(RegisterRecord::id)
                .containsExactlyInAnyOrder(audit.id(), visit.id());
        assertThat(list(manager, "PEST_VISIT")).extracting(RegisterRecord::id).containsExactly(visit.id());
        assertThat(list(manager, " pest_visit , finding ")).extracting(RegisterRecord::id)
                .containsExactlyInAnyOrder(visit.id(), finding.id());
        assertThat(list(manager, null)).extracting(RegisterRecord::id)
                .containsExactlyInAnyOrder(audit.id(), visit.id(), finding.id());
        assertThat(list(manager, "")).hasSize(3);
    }

    @Test
    @DisplayName("a record type S170 does not have is a bad request, not an empty list")
    void an_unknown_type_is_refused() {
        ActorContext manager = actor(SflRole.FACILITIES_MANAGER, site);

        assertThatThrownBy(() -> list(manager, "HYGIENE_AUDIT,WIZARD")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Record type WIZARD is not valid for S170");
        assertThatThrownBy(() -> list(manager, "MENU")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the facilities director and manager and the HSE manager can create and change records")
    void the_system_owners_can_write() {
        for (SflRole owner : List.of(SflRole.FACILITIES_DIRECTOR, SflRole.FACILITIES_MANAGER, SflRole.HSE_MANAGER)) {
            ActorContext by = actor(owner, site);
            RegisterRecord created = create(by, "HYGIENE_AUDIT", "By " + owner, null);
            assertThat(records.updateStatus(created.id(), "OPEN", by, SourceChannel.WEB).status())
                    .as(owner + " can change status").isEqualTo("OPEN");
        }
    }

    @Test
    @DisplayName("the auditor and the technical administrator read the register but cannot change it")
    void the_readers_cannot_write() {
        RegisterRecord existing = create(actor(SflRole.FACILITIES_MANAGER, site), "HYGIENE_AUDIT", "Existing", null);

        for (SflRole reader : List.of(SflRole.COMPLIANCE_OFFICER, SflRole.DTI_ADMIN)) {
            ActorContext by = actor(reader, site);
            assertThat(list(by, null)).as(reader + " can read").extracting(RegisterRecord::id)
                    .contains(existing.id());
            assertThatThrownBy(() -> create(by, "HYGIENE_AUDIT", "Not allowed", null))
                    .as(reader + " cannot create").isInstanceOf(FacilitiesException.UnauthorizedScopeException.class);
            assertThatThrownBy(() -> records.updateStatus(existing.id(), "CLOSED", by, SourceChannel.WEB))
                    .as(reader + " cannot update").isInstanceOf(FacilitiesException.UnauthorizedScopeException.class);
        }
    }

    @Test
    @DisplayName("the requester, the contractor, the technician and a driver cannot even read hygiene records")
    void everyone_else_is_refused() {
        for (SflRole outsider : List.of(SflRole.IFIMP_REQUESTER, SflRole.VENDOR_TECHNICIAN, SflRole.IFIMP_TECHNICIAN,
                SflRole.FLEET_DRIVER)) {
            assertThatThrownBy(() -> list(actor(outsider, site), null)).as(outsider + " cannot read")
                    .isInstanceOf(FacilitiesException.UnauthorizedScopeException.class);
        }
    }

    @Test
    @DisplayName("a role that owns hygiene at one site cannot read or write at another")
    void site_scope_is_enforced() {
        ActorContext elsewhere = actor(SflRole.HSE_MANAGER, "SOMEWHERE-ELSE");
        RegisterRecord mine = create(actor(SflRole.HSE_MANAGER, site), "HYGIENE_AUDIT", "Mine", null);

        assertThatThrownBy(() -> list(elsewhere, null)).isInstanceOf(FacilitiesException.UnauthorizedScopeException.class);
        assertThatThrownBy(() -> create(elsewhere, "HYGIENE_AUDIT", "Not mine", null))
                .isInstanceOf(FacilitiesException.UnauthorizedScopeException.class);
        assertThatThrownBy(() -> records.updateStatus(mine.id(), "CLOSED", elsewhere, SourceChannel.WEB))
                .isInstanceOf(FacilitiesException.UnauthorizedScopeException.class);
    }

    @Test
    @DisplayName("the platform administrator holds every permission, so keeps full access to the register")
    void the_platform_administrator_keeps_full_access() {
        assertThat(create(actor(SflRole.SFL_ADMIN, "*"), "HYGIENE_AUDIT", "Admin", null).siteCode()).isEqualTo(site);
    }

    @Test
    @DisplayName("the integration engineer wrote S170 records only through the configuration grant, and no longer does")
    void the_integration_engineer_is_not_a_hygiene_owner() {
        // The SRS names Facilities and HSE as owners and an auditor as reader; an integration role is neither.
        assertThatThrownBy(() -> create(actor(SflRole.INTEGRATION_ENGINEER, site), "HYGIENE_AUDIT", "No", null))
                .isInstanceOf(FacilitiesException.UnauthorizedScopeException.class);
    }
}
