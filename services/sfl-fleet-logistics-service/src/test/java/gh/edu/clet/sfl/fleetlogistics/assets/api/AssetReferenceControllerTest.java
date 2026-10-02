package gh.edu.clet.sfl.fleetlogistics.assets.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import gh.edu.clet.sfl.fleetlogistics.assets.application.AssetVisibilityAccessPolicy;
import gh.edu.clet.sfl.fleetlogistics.assets.application.AssetVisibilityService;
import gh.edu.clet.sfl.fleetlogistics.assets.application.MoveAssetCommand;
import gh.edu.clet.sfl.fleetlogistics.assets.application.RegisterAssetCommand;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetCategory;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetReference;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetStatus;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.LocationType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The AVAMP register's HTTP contract, and - as of 1 August 2026 - who is allowed to reach it.
 *
 * <p>These five tests passed for months against a controller with no authorisation whatsoever, which
 * is the point worth recording: they asserted the shape of every response and never once asserted who
 * was permitted to ask for it. A contract test that only describes the happy path cannot tell you the
 * door is unlocked. The {@link Refusals} nested class is the half that was missing.
 */
@WebMvcTest(controllers = AssetReferenceController.class, excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class,
        OAuth2ResourceServerWebSecurityAutoConfiguration.class
},
        // Keep the slice to AVAMP. When AVAMP was its own deployable there was nothing else to
        // exclude; under the merged FTLMP service `@WebMvcTest` scans every @ControllerAdvice on the
        // classpath, so `FleetApiExceptionHandler` gets instantiated here and drags in
        // `FleetAuditService`, which this slice has no reason to provide. Narrowing the advice with
        // `basePackages` does not help - that governs which controllers a handler *applies to*, not
        // whether the bean is created.
        //
        // The negative lookahead rather than a literal `..fleet..` exclusion: a handler added under
        // fuel or dispatch later would otherwise reintroduce exactly this failure, and it would
        // present as an unrelated AVAMP test breaking for no visible reason.
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "gh\\.edu\\.clet\\.sfl\\.fleetlogistics\\.(?!assets\\.).*"))
@AutoConfigureMockMvc(addFilters = false)
// Both are real collaborators, not mocks. What the resolver resolves *to* is the thing the controller
// records and the thing the policy judges - mocking either would let the header path or the matrix
// regress without a single test noticing.
@Import({AssetVisibilityActorResolver.class, AssetVisibilityAccessPolicy.class})
class AssetReferenceControllerTest {

    private static final UUID ASSET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssetVisibilityService service;

    /** An integration engineer scoped to MAIN - the ordinary AVAMP write principal. */
    private static MockHttpServletRequestBuilder asManager(MockHttpServletRequestBuilder request) {
        return request.header("X-SFL-User", "operator@sfl.local")
                .header("X-SFL-Roles", "INTEGRATION_ENGINEER")
                .header("X-SFL-Sites", "MAIN");
    }


    private static MockHttpServletRequestBuilder asRole(MockHttpServletRequestBuilder request, String role) {
        return request.header("X-SFL-User", role.toLowerCase() + "@sfl.local")
                .header("X-SFL-Roles", role)
                .header("X-SFL-Sites", "MAIN");
    }

    @Test
    void the_fleet_manager_and_logistics_officer_can_register_an_asset_in_their_own_programme() throws Exception {
        when(service.register(any(RegisterAssetCommand.class)))
                .thenReturn(asset(ASSET_ID, "CAM-001", "MAIN", "ROOM-A"));
        String body = """
                {"assetCode":"cam-001","name":"Camera","category":"EQUIPMENT","siteCode":"MAIN",
                 "locationType":"SITE","locationReference":"main","externalReference":"rfid-1"}
                """;

        for (String role : List.of("FLEET_MANAGER", "FLEET_LOGISTICS_OFFICER")) {
            mockMvc.perform(asRole(post("/api/v1/assets"), role).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void the_fleet_reporting_viewer_holds_no_asset_permission_at_all() throws Exception {
        mockMvc.perform(asRole(get("/api/v1/assets"), "FLEET_REPORTING_VIEWER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.requiredPermission").value("ASSET_REFERENCE_READ"));
    }

    @Test
    void a_tag_is_resolved_to_its_asset() throws Exception {
        when(service.findByTag("RFID-1")).thenReturn(asset(ASSET_ID, "CAM-001", "MAIN", "ROOM-A"));

        mockMvc.perform(asManager(get("/api/v1/assets/by-tag/{tagId}", "RFID-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(ASSET_ID.toString()));
    }

    @Test
    void history_is_returned_for_an_asset_in_scope() throws Exception {
        when(service.findById(ASSET_ID)).thenReturn(asset(ASSET_ID, "CAM-001", "MAIN", "ROOM-A"));
        when(service.history(ASSET_ID)).thenReturn(List.of(new gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetHistoryEntry(
                UUID.randomUUID(), ASSET_ID, gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetChangeType.MOVED,
                "SITE:MAIN", "ROOM:ROOM 12", gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetChangeSource.MANUAL,
                null, "operator@sfl.local", Instant.parse("2026-07-13T08:00:00Z"))));

        mockMvc.perform(asManager(get("/api/v1/assets/{assetId}/history", ASSET_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].changeType").value("MOVED"))
                .andExpect(jsonPath("$.data[0].toValue").value("ROOM:ROOM 12"));
    }

    @Test
    void history_of_an_asset_at_another_site_is_refused() throws Exception {
        when(service.findById(ASSET_ID)).thenReturn(asset(ASSET_ID, "CAM-001", "ELSEWHERE", "ROOM-A"));

        mockMvc.perform(asManager(get("/api/v1/assets/{assetId}/history", ASSET_ID)))
                .andExpect(status().isForbidden());
        verify(service, never()).history(any());
    }

    @Test
    void tagging_an_asset_passes_the_actor_and_reports_a_taken_tag_as_a_conflict() throws Exception {
        when(service.findById(ASSET_ID)).thenReturn(asset(ASSET_ID, "CAM-001", "MAIN", "ROOM-A"));
        when(service.assignTag(any(gh.edu.clet.sfl.fleetlogistics.assets.application.AssignTagCommand.class)))
                .thenThrow(new gh.edu.clet.sfl.fleetlogistics.assets.application.DuplicateAssetTagException("RFID-1", "AST-9"));

        mockMvc.perform(asManager(patch("/api/v1/assets/{assetId}/tag", ASSET_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagId\":\"RFID-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ASSETVIS_DUPLICATE_IDENTIFIER"))
                .andExpect(jsonPath("$.error.message").value("Tag RFID-1 is already assigned to asset AST-9"));
    }

    @Test
    void a_blank_tag_is_refused_before_the_service_is_asked() throws Exception {
        when(service.findById(ASSET_ID)).thenReturn(asset(ASSET_ID, "CAM-001", "MAIN", "ROOM-A"));

        mockMvc.perform(asManager(patch("/api/v1/assets/{assetId}/tag", ASSET_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagId\":\" \"}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).assignTag(any());
    }

    @Test
    void a_reader_sighting_is_applied_by_a_principal_that_may_manage_the_asset_site() throws Exception {
        when(service.findByTag("RFID-1")).thenReturn(asset(ASSET_ID, "CAM-001", "MAIN", "ROOM-A"));
        when(service.recordScan(any(gh.edu.clet.sfl.fleetlogistics.assets.application.RecordScanCommand.class)))
                .thenReturn(asset(ASSET_ID, "CAM-001", "MAIN", "ROOM-A"));

        mockMvc.perform(asRole(post("/api/v1/assets/scans"), "SERVICE_INTEGRATION")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagId\":\"RFID-1\",\"locationType\":\"ROOM\",\"locationReference\":\"Dock 2\",\"readerId\":\"reader-7\"}"))
                .andExpect(status().isOk());
        verify(service).recordScan(any());
    }

    @Test
    void a_read_only_role_cannot_report_a_sighting() throws Exception {
        mockMvc.perform(asRole(post("/api/v1/assets/scans"), "FLEET_REPORTING_VIEWER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagId\":\"RFID-1\",\"locationType\":\"ROOM\",\"locationReference\":\"Dock 2\"}"))
                .andExpect(status().isForbidden());
        verify(service, never()).recordScan(any());
    }

    @Test
    void register_asset_returns_created_contract() throws Exception {
        when(service.register(any(RegisterAssetCommand.class)))
                .thenReturn(asset(ASSET_ID, "CAM-001", "MAIN", "ROOM-A"));

        mockMvc.perform(asManager(post("/api/v1/assets"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-ID", "corr-1")
                        .content("""
                                {
                                  "assetCode": "cam-001",
                                  "name": "Main Gate Camera",
                                  "category": "CCTV_CAMERA",
                                  "siteCode": "MAIN",
                                  "locationType": "ROOM",
                                  "locationReference": "room-a",
                                  "custodianReference": "Security",
                                  "externalReference": "vms-1001"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/assets/" + ASSET_ID))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.id").value(ASSET_ID.toString()))
                .andExpect(jsonPath("$.data.assetCode").value("CAM-001"))
                .andExpect(jsonPath("$.data.category").value("CCTV_CAMERA"))
                .andExpect(jsonPath("$.data.siteCode").value("MAIN"))
                .andExpect(jsonPath("$.data.locationReference").value("ROOM-A"));

        ArgumentCaptor<RegisterAssetCommand> command = ArgumentCaptor.forClass(RegisterAssetCommand.class);
        verify(service).register(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().actor()).isEqualTo("operator@sfl.local");
        org.assertj.core.api.Assertions.assertThat(command.getValue().correlationId()).isEqualTo("corr-1");
    }

    @Test
    void list_assets_by_site_returns_stable_array_contract() throws Exception {
        when(service.findAll("MAIN")).thenReturn(List.of(
                asset(ASSET_ID, "CAM-001", "MAIN", "ROOM-A"),
                asset(UUID.fromString("22222222-2222-2222-2222-222222222222"), "READER-001", "MAIN", "MAIN-GATE")));

        mockMvc.perform(asManager(get("/api/v1/assets")).param("siteCode", "MAIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].assetCode").value("CAM-001"))
                .andExpect(jsonPath("$.data[1].assetCode").value("READER-001"));
    }

    /**
     * No site named, so the answer is the actor's own sites - not the whole estate.
     *
     * <p>This is the path the dashboard takes when it has no site filter set, and before this pass it
     * called {@code findAll(null)} and returned every asset at every site to anybody who asked.
     */
    @Test
    void list_assets_without_a_site_is_narrowed_to_the_actors_scope() throws Exception {
        when(service.findAllInScope(Set.of("MAIN")))
                .thenReturn(List.of(asset(ASSET_ID, "CAM-001", "MAIN", "ROOM-A")));

        mockMvc.perform(asManager(get("/api/v1/assets")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].siteCode").value("MAIN"));

        verify(service).findAllInScope(Set.of("MAIN"));
        verify(service, never()).findAll(null);
    }

    @Test
    void lookup_assets_by_location_returns_matching_assets() throws Exception {
        when(service.findByLocation("MAIN", LocationType.ROOM, "ROOM-A"))
                .thenReturn(List.of(asset(ASSET_ID, "CAM-001", "MAIN", "ROOM-A")));

        mockMvc.perform(asManager(get("/api/v1/assets/by-location"))
                        .param("siteCode", "MAIN")
                        .param("locationType", "ROOM")
                        .param("locationReference", "ROOM-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].locationType").value("ROOM"))
                .andExpect(jsonPath("$.data[0].locationReference").value("ROOM-A"));
    }

    @Test
    void move_asset_returns_updated_location_contract() throws Exception {
        when(service.findById(ASSET_ID)).thenReturn(asset(ASSET_ID, "CAM-001", "MAIN", "ROOM-A"));
        when(service.move(any(MoveAssetCommand.class)))
                .thenReturn(asset(ASSET_ID, "CAM-001", "MAIN", "MAIN-GATE"));

        mockMvc.perform(asManager(patch("/api/v1/assets/{assetId}/location", ASSET_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationType": "ZONE",
                                  "locationReference": "MAIN-GATE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(ASSET_ID.toString()))
                .andExpect(jsonPath("$.data.locationReference").value("MAIN-GATE"));

        ArgumentCaptor<MoveAssetCommand> command = ArgumentCaptor.forClass(MoveAssetCommand.class);
        verify(service).move(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().assetId()).isEqualTo(ASSET_ID);
        org.assertj.core.api.Assertions.assertThat(command.getValue().locationType()).isEqualTo(LocationType.ZONE);
    }

    @Test
    void register_asset_validation_errors_use_error_contract() throws Exception {
        mockMvc.perform(asManager(post("/api/v1/assets"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "assetCode": "",
                                  "name": "Main Gate Camera",
                                  "category": "CCTV_CAMERA",
                                  "siteCode": "MAIN",
                                  "locationType": "ROOM",
                                  "locationReference": "ROOM-A"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ASSETVIS_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").exists())
                .andExpect(jsonPath("$.error.timestamp").exists())
                .andExpect(jsonPath("$.data[0].field").value("assetCode"));
    }

    /**
     * Every endpoint refuses, and refuses for the two distinct reasons.
     *
     * <p>Split deliberately: a caller with no permission and a caller with the permission but not the
     * site fail through different branches of {@code AssetVisibilityAccessPolicy}, and a suite that
     * only exercised the first would leave the site check - the one the register actually needs -
     * unproven.
     */
    @Nested
    class Refusals {

        /** A driver. Holds nothing in AVAMP's matrix, deliberately. */
        private MockHttpServletRequestBuilder asDriver(MockHttpServletRequestBuilder request) {
            return request.header("X-SFL-User", "driver@clet.gh")
                    .header("X-SFL-Roles", "FLEET_DRIVER")
                    .header("X-SFL-Sites", "MAIN");
        }

        /** Holds the permissions, but only at ANNEX. */
        private MockHttpServletRequestBuilder asManagerElsewhere(MockHttpServletRequestBuilder request) {
            return request.header("X-SFL-User", "engineer@clet.gh")
                    .header("X-SFL-Roles", "INTEGRATION_ENGINEER")
                    .header("X-SFL-Sites", "ANNEX");
        }

        private static final String REGISTER_BODY = """
                {
                  "assetCode": "CAM-002",
                  "name": "Annex Camera",
                  "category": "CCTV_CAMERA",
                  "siteCode": "MAIN",
                  "locationType": "ROOM",
                  "locationReference": "ROOM-B"
                }
                """;

        @Test
        void a_driver_cannot_register_an_asset() throws Exception {
            mockMvc.perform(asDriver(post("/api/v1/assets"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REGISTER_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ASSETVIS_UNAUTHORIZED_SCOPE"))
                    .andExpect(jsonPath("$.data.requiredPermission").value("ASSET_REFERENCE_MANAGE"))
                    .andExpect(jsonPath("$.data.resourceType").value("AssetReference"));

            verifyNoInteractions(service);
        }

        @Test
        void a_driver_cannot_read_the_register() throws Exception {
            mockMvc.perform(asDriver(get("/api/v1/assets")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ASSETVIS_UNAUTHORIZED_SCOPE"))
                    .andExpect(jsonPath("$.data.requiredPermission").value("ASSET_REFERENCE_READ"));

            verifyNoInteractions(service);
        }

        @Test
        void a_driver_cannot_read_one_asset_by_id() throws Exception {
            mockMvc.perform(asDriver(get("/api/v1/assets/{assetId}", ASSET_ID)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ASSETVIS_UNAUTHORIZED_SCOPE"));

            // Refused before the load, so a caller cannot use the 403/404 difference to probe for ids.
            verifyNoInteractions(service);
        }

        @Test
        void a_driver_cannot_look_up_by_location() throws Exception {
            mockMvc.perform(asDriver(get("/api/v1/assets/by-location"))
                            .param("siteCode", "MAIN")
                            .param("locationType", "ROOM")
                            .param("locationReference", "ROOM-A"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ASSETVIS_UNAUTHORIZED_SCOPE"));

            verifyNoInteractions(service);
        }

        @Test
        void a_driver_cannot_move_an_asset() throws Exception {
            when(service.findById(ASSET_ID)).thenReturn(asset(ASSET_ID, "CAM-001", "MAIN", "ROOM-A"));

            mockMvc.perform(asDriver(patch("/api/v1/assets/{assetId}/location", ASSET_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"locationType": "ZONE", "locationReference": "MAIN-GATE"}
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ASSETVIS_UNAUTHORIZED_SCOPE"));

            verify(service, never()).move(any());
        }

        @Test
        void a_driver_cannot_reassign_custody() throws Exception {
            when(service.findById(ASSET_ID)).thenReturn(asset(ASSET_ID, "CAM-001", "MAIN", "ROOM-A"));

            mockMvc.perform(asDriver(patch("/api/v1/assets/{assetId}/custody", ASSET_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"custodianReference": "Anybody"}
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ASSETVIS_UNAUTHORIZED_SCOPE"));

            verify(service, never()).assignCustody(any());
        }

        @Test
        void a_driver_cannot_link_evidence() throws Exception {
            when(service.findById(ASSET_ID)).thenReturn(asset(ASSET_ID, "CAM-001", "MAIN", "ROOM-A"));

            mockMvc.perform(asDriver(patch("/api/v1/assets/{assetId}/evidence", ASSET_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"evidenceReference": "DOC-1"}
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ASSETVIS_UNAUTHORIZED_SCOPE"));

            verify(service, never()).linkEvidence(any());
        }

        @Test
        void holding_the_permission_at_another_site_is_still_a_refusal() throws Exception {
            mockMvc.perform(asManagerElsewhere(post("/api/v1/assets"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REGISTER_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ASSETVIS_UNAUTHORIZED_SCOPE"))
                    .andExpect(jsonPath("$.data.siteCode").value("MAIN"));

            verifyNoInteractions(service);
        }

        /**
         * The by-id read that pasting a UUID used to win.
         *
         * <p>The scope check happens against the record's own site after loading it, so an actor scoped
         * to ANNEX is refused an asset that lives at MAIN even though the read permission is held.
         */
        @Test
        void reading_an_out_of_scope_asset_by_id_is_refused() throws Exception {
            when(service.findById(ASSET_ID)).thenReturn(asset(ASSET_ID, "CAM-001", "MAIN", "ROOM-A"));

            mockMvc.perform(asManagerElsewhere(get("/api/v1/assets/{assetId}", ASSET_ID)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ASSETVIS_UNAUTHORIZED_SCOPE"))
                    .andExpect(jsonPath("$.data.siteCode").value("MAIN"));
        }

        /**
         * An actor with no site scopes at all reaches nothing.
         *
         * <p>Fail-closed. An empty scope set meaning "everywhere" is the single most expensive default
         * a site-scoped register can have, and {@code SiteScopedPrincipal.canAccessSite} does not.
         */
        @Test
        void an_actor_with_no_site_scopes_is_refused() throws Exception {
            mockMvc.perform(post("/api/v1/assets")
                            .header("X-SFL-User", "engineer@clet.gh")
                            .header("X-SFL-Roles", "INTEGRATION_ENGINEER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REGISTER_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ASSETVIS_UNAUTHORIZED_SCOPE"));

            verifyNoInteractions(service);
        }

        /**
         * The header default no longer opens the door.
         *
         * <p>Absent {@code X-SFL-Roles} the actor is {@code development-user} with no roles, which the
         * matrix grants nothing. Before this pass that same caller could register assets at any site.
         */
        @Test
        void an_unidentified_caller_is_refused() throws Exception {
            mockMvc.perform(get("/api/v1/assets").param("siteCode", "MAIN"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ASSETVIS_UNAUTHORIZED_SCOPE"));

            verifyNoInteractions(service);
        }
    }

    private static AssetReference asset(UUID id, String assetCode, String siteCode, String locationReference) {
        return new AssetReference(id, assetCode, "Main Gate Camera", AssetCategory.CCTV_CAMERA, AssetStatus.ACTIVE,
                siteCode, locationReference.equals("ROOM-A") ? LocationType.ROOM : LocationType.ZONE,
                locationReference, "Security", "vms-1001", null,
                Instant.parse("2026-07-13T08:00:00Z"), Instant.parse("2026-07-13T08:00:00Z"));
    }
}
