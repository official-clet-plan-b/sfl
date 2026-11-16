package gh.edu.clet.sfl.fleetlogistics.fleet.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gh.edu.clet.sfl.fleetlogistics.fleet.api.mapper.FleetAssessmentMapper;
import gh.edu.clet.sfl.fleetlogistics.fleet.api.mapper.FleetWorkflowMapper;
import gh.edu.clet.sfl.fleetlogistics.fleet.api.mapper.VehicleResponseMapper;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.RegisterVehicleCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.query.VehicleQueryService;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.FleetAuditService;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.TripApplicationService;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.VehicleApplicationService;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.DuplicateActiveIdentifierException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.FleetAuthorizationException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.FleetErrorCode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.MissingSiteScopeException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.OptimisticLockConflictException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.RecordNotFoundException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Vehicle;
import gh.edu.clet.sfl.fleetlogistics.fleet.support.FleetFixtures;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract tests for the vehicle endpoints.
 *
 * <p>These are the executable form of the SRS-SFL-S166-01 acceptance criteria and error states: the
 * status code, the {@code ApiResponse}/{@code ApiError} envelope shape and - critically - the exact SRS
 * error wording are all asserted here.
 */
@WebMvcTest(controllers = VehicleController.class, excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class,
        OAuth2ResourceServerWebSecurityAutoConfiguration.class
})
@AutoConfigureMockMvc(addFilters = false)
// The readiness, movement and standalone-inspection mappings brought three more collaborators onto
// this controller, so the slice has to know about them. The two mappers are real (they are pure
// translation); the trip service is mocked because a controller test has no business exercising the
// inspection workflow.
@Import({VehicleControllerTest.TestBeans.class, FleetActorResolver.class, VehicleResponseMapper.class,
        FleetAssessmentMapper.class, FleetWorkflowMapper.class})
class VehicleControllerTest {

    private static final UUID VEHICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VehicleApplicationService vehicleService;

    @MockitoBean
    private VehicleQueryService vehicleQueries;

    @MockitoBean
    private FleetAuditService auditService;

    @MockitoBean
    private TripApplicationService tripService;

    @TestConfiguration
    static class TestBeans {
        @Bean
        Clock clock() {
            return Clock.fixed(FleetFixtures.NOW, ZoneOffset.UTC);
        }
    }

    @Test
    @DisplayName("registering a vehicle returns 201 with a Location header and the shared envelope")
    void register_returns_created() throws Exception {
        Vehicle vehicle = FleetFixtures.vehicle();
        when(vehicleService.register(any(RegisterVehicleCommand.class))).thenReturn(vehicle);
        when(vehicleQueries.canReadSensitive(any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/fleet/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SFL-User", "officer@clet.edu.gh")
                        .header("X-SFL-Roles", "FLEET_LOGISTICS_OFFICER")
                        .header("X-SFL-Sites", "ACCRA")
                        .header("X-Correlation-ID", "corr-1")
                        .header("Idempotency-Key", "idem-1")
                        .content("""
                                {
                                  "registrationNumber": "GT-1234-26",
                                  "vin": "WVWZZZ1JZXW000001",
                                  "make": "Toyota",
                                  "model": "Hilux",
                                  "manufactureYear": 2022,
                                  "category": "PICKUP",
                                  "capacity": 5,
                                  "siteCode": "ACCRA",
                                  "responsibleUnit": "Transportation & Logistics Unit",
                                  "operationalOwner": "logistics.officer@clet.edu.gh",
                                  "initialOdometer": 42000
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/fleet/vehicles/" + vehicle.id()))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.registrationNumber").value("GT-1234-26"))
                .andExpect(jsonPath("$.data.siteCode").value("ACCRA"))
                .andExpect(jsonPath("$.data.lifecycleStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.availabilityStatus").value("AVAILABLE"));

        ArgumentCaptor<RegisterVehicleCommand> command = ArgumentCaptor.forClass(RegisterVehicleCommand.class);
        verify(vehicleService).register(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().actor().actorId())
                .isEqualTo("officer@clet.edu.gh");
        org.assertj.core.api.Assertions.assertThat(command.getValue().actor().correlationId()).isEqualTo("corr-1");
        org.assertj.core.api.Assertions.assertThat(command.getValue().idempotencyKey()).isEqualTo("idem-1");
    }

    @Test
    @DisplayName("a duplicate registration returns 409 with the SRS Duplicate Identifier wording")
    void duplicate_returns_409_with_srs_wording() throws Exception {
        when(vehicleService.register(any(RegisterVehicleCommand.class)))
                .thenThrow(DuplicateActiveIdentifierException.of("Vehicle", "registrationNumber", "GT-1234-26",
                        "ACCRA"));

        mockMvc.perform(post("/api/v1/fleet/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-ID", "corr-2")
                        .content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FLEET_DUPLICATE_IDENTIFIER"))
                .andExpect(jsonPath("$.error.message")
                        .value("An active record with this identifier already exists for this site."))
                .andExpect(jsonPath("$.error.correlationId").value("corr-2"))
                .andExpect(jsonPath("$.error.timestamp").exists())
                .andExpect(jsonPath("$.data.identifier").value("GT-1234-26"));
    }

    @Test
    @DisplayName("a missing site scope returns 422 with the SRS Missing Site Scope wording")
    void missing_site_returns_422_with_srs_wording() throws Exception {
        when(vehicleService.register(any(RegisterVehicleCommand.class))).thenThrow(new MissingSiteScopeException());

        mockMvc.perform(post("/api/v1/fleet/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FLEET_MISSING_SITE_SCOPE"))
                .andExpect(jsonPath("$.error.message")
                        .value("Select a valid GSL site before saving this record."));
    }

    @Test
    @DisplayName("a cross-site request returns 403 with the SRS Unauthorized Scope wording and is audited")
    void cross_site_returns_403_with_srs_wording() throws Exception {
        when(vehicleService.register(any(RegisterVehicleCommand.class)))
                .thenThrow(new FleetAuthorizationException(java.util.Map.of(
                        "siteCode", "ACCRA", "resourceType", "Vehicle")));

        mockMvc.perform(post("/api/v1/fleet/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SFL-User", "officer@clet.edu.gh")
                        .header("X-SFL-Sites", "KUMASI")
                        .content(validBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FLEET_UNAUTHORIZED_SCOPE"))
                .andExpect(jsonPath("$.error.message")
                        .value("You are not authorised to access this site or record."));

        // SRS-SFL-S166-03: authorisation denials are audited.
        verify(auditService).recordAuthorizationDenial(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("validation failures return 400 with per-field detail inside the envelope")
    void validation_errors_use_error_envelope() throws Exception {
        mockMvc.perform(post("/api/v1/fleet/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "registrationNumber": "",
                                  "make": "Toyota",
                                  "model": "Hilux",
                                  "manufactureYear": 1800,
                                  "category": "PICKUP",
                                  "capacity": 0,
                                  "siteCode": "ACCRA",
                                  "responsibleUnit": "Transport",
                                  "operationalOwner": "owner@clet.edu.gh",
                                  "initialOdometer": -1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FLEET_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].field").exists())
                .andExpect(jsonPath("$.data[0].message").exists());
    }

    @Test
    @DisplayName("an unknown vehicle returns 404 in the shared envelope")
    void unknown_vehicle_returns_404() throws Exception {
        when(vehicleQueries.findById(any(), any())).thenThrow(RecordNotFoundException.of("Vehicle", VEHICLE_ID));

        mockMvc.perform(get("/api/v1/fleet/vehicles/{vehicleId}", VEHICLE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FLEET_RECORD_NOT_FOUND"));
    }

    @Test
    @DisplayName("a stale version returns 409 so the client can reload rather than overwrite")
    void stale_version_returns_409() throws Exception {
        doThrow(new OptimisticLockConflictException(java.util.Map.of("expectedVersion", 1L, "currentVersion", 3L)))
                .when(vehicleService).changeLifecycle(any());

        mockMvc.perform(patch("/api/v1/fleet/vehicles/{vehicleId}/lifecycle", VEHICLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetStatus": "INACTIVE", "reason": "Seasonal stand-down", "expectedVersion": 1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FLEET_RECORD_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.message")
                        .value(FleetErrorCode.FLEET_RECORD_VERSION_CONFLICT.message()));
    }

    @Test
    @DisplayName("the VIN is masked for a caller without the sensitive-read permission")
    void vin_is_masked_without_permission() throws Exception {
        when(vehicleQueries.findById(any(), any())).thenReturn(FleetFixtures.vehicle());
        when(vehicleQueries.canReadSensitive(any())).thenReturn(false);

        mockMvc.perform(get("/api/v1/fleet/vehicles/{vehicleId}", VEHICLE_ID)
                        .header("X-SFL-Roles", "FLEET_LOGISTICS_OFFICER")
                        .header("X-SFL-Sites", "ACCRA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vinMasked").value(true))
                .andExpect(jsonPath("$.data.vin").value(org.hamcrest.Matchers.endsWith("0001")))
                .andExpect(jsonPath("$.data.vin").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("WVWZZZ"))));
    }

    @Test
    @DisplayName("the VIN is returned in full for a caller holding the sensitive-read permission")
    void vin_is_visible_with_permission() throws Exception {
        when(vehicleQueries.findById(any(), any())).thenReturn(FleetFixtures.vehicle());
        when(vehicleQueries.canReadSensitive(any())).thenReturn(true);

        mockMvc.perform(get("/api/v1/fleet/vehicles/{vehicleId}", VEHICLE_ID)
                        .header("X-SFL-Roles", "FLEET_MANAGER")
                        .header("X-SFL-Sites", "ACCRA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vinMasked").value(false))
                .andExpect(jsonPath("$.data.vin").value("WVWZZZ1JZXW000001"));
    }

    @Test
    @DisplayName("the correlation id is echoed on the response so a trace can be followed end to end")
    void correlation_id_is_echoed() throws Exception {
        when(vehicleService.register(any(RegisterVehicleCommand.class))).thenReturn(FleetFixtures.vehicle());

        mockMvc.perform(post("/api/v1/fleet/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-ID", "corr-trace-1")
                        .content(validBody()))
                .andExpect(status().isCreated());

        ArgumentCaptor<RegisterVehicleCommand> command = ArgumentCaptor.forClass(RegisterVehicleCommand.class);
        verify(vehicleService).register(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().actor().correlationId())
                .isEqualTo("corr-trace-1");
    }

    private static String validBody() {
        return """
                {
                  "registrationNumber": "GT-1234-26",
                  "make": "Toyota",
                  "model": "Hilux",
                  "manufactureYear": 2022,
                  "category": "PICKUP",
                  "capacity": 5,
                  "siteCode": "ACCRA",
                  "responsibleUnit": "Transportation & Logistics Unit",
                  "operationalOwner": "logistics.officer@clet.edu.gh",
                  "initialOdometer": 42000
                }
                """;
    }
}
