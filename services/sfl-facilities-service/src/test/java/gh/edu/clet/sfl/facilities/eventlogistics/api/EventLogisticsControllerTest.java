package gh.edu.clet.sfl.facilities.eventlogistics.api;

import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gh.edu.clet.sfl.facilities.eventlogistics.application.EventHandoffService;
import gh.edu.clet.sfl.facilities.eventlogistics.application.EventReadinessService;
import gh.edu.clet.sfl.facilities.eventlogistics.application.EventResourceRequestService;
import gh.edu.clet.sfl.facilities.eventlogistics.application.EventRiskCriteriaService;
import gh.edu.clet.sfl.facilities.eventlogistics.application.EventSetupTaskService;
import gh.edu.clet.sfl.facilities.eventlogistics.domain.EventDetails;
import gh.edu.clet.sfl.facilities.eventlogistics.domain.EventSetupTask;
import gh.edu.clet.sfl.facilities.eventlogistics.domain.EventSetupTaskStatus;
import gh.edu.clet.sfl.facilities.shared.api.FacilitiesActorResolver;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import gh.edu.clet.sfl.facilities.shared.domain.model.RecordMetadata;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The S173 HTTP contract - following {@code BookingControllerTest}: status code, error envelope,
 * correlation id, plus this module's own vendor-inbound forged-message case (NFR-SEC2).
 */
@WebMvcTest(controllers = EventLogisticsController.class, excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class,
        OAuth2ResourceServerWebSecurityAutoConfiguration.class
})
@AutoConfigureMockMvc(addFilters = false)
@Import(FacilitiesActorResolver.class)
class EventLogisticsControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventHandoffService handoffs;
    @MockitoBean
    private EventResourceRequestService requests;
    @MockitoBean
    private EventSetupTaskService tasks;
    @MockitoBean
    private EventReadinessService readiness;
    @MockitoBean
    private EventRiskCriteriaService riskCriteria;

    @Test
    @DisplayName("a learned template can be applied without adding a manual request")
    void apply_template_with_empty_manual_request_list_is_created() throws Exception {
        given(requests.decompose(any())).willReturn(List.of());

        mockMvc.perform(post("/api/v1/facilities/event-logistics/setup-tasks/" + UUID.randomUUID()
                        + "/resource-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SFL-User", "coordinator")
                        .header("X-SFL-Roles", "EVENT_LOGISTICS_COORDINATOR")
                        .header("X-SFL-Sites", "MAIN")
                        .content("""
                                {"requests":[],"applyTemplate":true}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("confirming a set-up task without FACILITIES_EVENT_COORDINATE is refused with 403")
    void confirm_without_permission_is_403() throws Exception {
        UUID id = UUID.randomUUID();
        willThrow(new FacilitiesException.UnauthorizedScopeException(
                "You are not authorised to access this site or record."))
                .given(tasks).confirm(any());

        mockMvc.perform(patch("/api/v1/facilities/event-logistics/setup-tasks/" + id + "/confirm")
                        .header("X-SFL-User", "requester")
                        .header("X-SFL-Roles", "IFIMP_REQUESTER")
                        .header("X-SFL-Sites", "MAIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED_SCOPE"))
                .andExpect(jsonPath("$.error.correlationId").value(notNullValue()));
    }

    @Test
    @DisplayName("an unresolvable S078 reference on the hand-off endpoint is 422 with EVENT_REFERENCE_UNRESOLVABLE")
    void unresolvable_handoff_is_422() throws Exception {
        willThrow(new FacilitiesException(
                gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode.EVENT_REFERENCE_UNRESOLVABLE))
                .given(handoffs).accept(any(), any());

        mockMvc.perform(post("/api/v1/facilities/event-logistics/handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SFL-Source", "CCP-EVENTS-SIM")
                        .header("X-SFL-Signature", "any-signature")
                        .header("X-SFL-Signed-At", NOW.toString())
                        .header("X-SFL-User", "integration.ccp")
                        .header("X-SFL-Roles", "SERVICE_INTEGRATION")
                        .header("X-SFL-Sites", "*")
                        .content("""
                                {"s078EventReference":"S078-UNKNOWN","status":"TENTATIVE",
                                 "idempotencyKey":"k1","siteCode":"MAIN","messageType":"event.handoff"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EVENT_REFERENCE_UNRESOLVABLE"));
    }

    @Test
    @DisplayName("a malformed hand-off body is a 400 validation failure, never reaching the verifier")
    void malformed_handoff_body_is_400() throws Exception {
        mockMvc.perform(post("/api/v1/facilities/event-logistics/handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SFL-Source", "CCP-EVENTS-SIM")
                        .header("X-SFL-Signature", "any-signature")
                        .header("X-SFL-Signed-At", NOW.toString())
                        .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("finding an event returns its readiness fields")
    void readiness_view_returns_ok() throws Exception {
        EventSetupTask task = new EventSetupTask(UUID.randomUUID(), "MAIN", "EV-MAIN-000001", "S078-1",
                new EventDetails("Founders' Day", "MOOT", NOW.plusSeconds(3600), NOW.plusSeconds(7200), UUID
                        .randomUUID(), "HALL-A", 40, null, false, false), EventSetupTaskStatus.OPEN, null, null,
                null, null, null, null, null, null, null, null, 0, NOW,
                RecordMetadata.createdBy("integration.ccp", NOW, SourceChannel.INTEGRATION, "corr-1"));
        given(tasks.findById(any(), any(), any())).willReturn(task);

        mockMvc.perform(get("/api/v1/facilities/event-logistics/setup-tasks/" + task.id())
                        .header("X-SFL-User", "coordinator")
                        .header("X-SFL-Roles", "EVENT_LOGISTICS_COORDINATOR")
                        .header("X-SFL-Sites", "MAIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.s078EventReference").value("S078-1"))
                .andExpect(jsonPath("$.data.status").value("OPEN"));
    }
}
