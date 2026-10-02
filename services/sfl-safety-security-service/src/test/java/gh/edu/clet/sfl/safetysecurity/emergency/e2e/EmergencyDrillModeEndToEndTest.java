package gh.edu.clet.sfl.safetysecurity.emergency.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport;
import gh.edu.clet.sfl.safetysecurity.emergency.application.contract.EmergencyDrillTrigger;
import gh.edu.clet.sfl.safetysecurity.emergency.application.contract.EmergencyDrillTrigger.DrillNotificationRequest;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.EmergencyRepository;
import gh.edu.clet.sfl.safetysecurity.emergency.application.service.ActivationService;
import gh.edu.clet.sfl.safetysecurity.emergency.application.service.EmergencyRecordsService;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.exception.EmergencyErrorCode;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.exception.EmergencyException;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.AudienceGroup;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.ChannelType;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.NotificationActivation;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.NotificationTemplate;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.Priority;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.SourceChannel;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.policy.DrillSeparationPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S174's drill mode, for Phase 2 SRS-SFL-S175-01, against a real PostgreSQL: a drill goes down the real
 * notification path, and at no step can a drill and a real alert be confused ("Test/Real Ambiguity").
 */
@SpringBootTest(properties = {"sfl.security.enabled=false", "sfl.emergency.scheduling.enabled=false",
        "sfl.emergency.messaging.drainer-enabled=false", "sfl.safety-security.messaging.drainer-enabled=false"})
@EnabledIf(value = "gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available")
class EmergencyDrillModeEndToEndTest extends SafetySecurityPostgresSupport {

    private static final String MARKER = DrillSeparationPolicy.MARKER;

    @Autowired EmergencyRecordsService records;
    @Autowired ActivationService activations;
    @Autowired EmergencyDrillTrigger drills;
    @Autowired EmergencyRepository repository;
    @Autowired JdbcTemplate jdbc;

    private String site;
    private ActorContext director;
    private AudienceGroup audience;

    @BeforeEach
    void fixture() {
        site = "DRM" + System.nanoTime();
        director = new ActorContext(new SiteScopedPrincipal("director", "Director", Set.of(SflRole.SECURITY_DIRECTOR),
                Set.of(site), false), "drill-e2e");
        audience = records.createAudienceGroup(new EmergencyRecordsService.CreateAudienceGroup(site, null, "All staff",
                "dir://all", 40, director, SourceChannel.WEB));
    }

    private NotificationTemplate template(String title, String body, boolean drill, boolean breakGlass) {
        return records.createTemplate(new EmergencyRecordsService.CreateTemplate(site, null, title, body,
                List.of(ChannelType.SMS, ChannelType.EMAIL), breakGlass, director, SourceChannel.WEB, drill));
    }

    private static void ambiguity(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(EmergencyException.class,
                e -> assertThat(e.errorCode()).isEqualTo(EmergencyErrorCode.EMERGENCY_TEST_REAL_AMBIGUITY));
    }

    @Test
    @DisplayName("Test/Real Ambiguity is refused at setup, in both directions")
    void ambiguous_templates_are_refused_when_created() {
        ambiguity(() -> template("Fire drill", "Please assemble", true, false));
        ambiguity(() -> template(MARKER + ": fire drill", "Please assemble", true, false));
        ambiguity(() -> template(MARKER + ": fire drill", MARKER + ". Assemble.", true, true));
        ambiguity(() -> template("Evacuate now", "Real alert. " + MARKER, false, false));
        assertThat(template(MARKER + ": fire drill", MARKER + ". Assemble at point B.", true, false).drill()).isTrue();
    }

    @Test
    @DisplayName("S175-01 AC: an executed drill reaches recipients marked unambiguously as a drill")
    void a_drill_goes_down_the_real_path_marked_as_a_drill() {
        NotificationTemplate drillTemplate = template(MARKER + ": fire drill", MARKER + ". Assemble at point B.", true,
                false);

        var sent = drills.trigger(new DrillNotificationRequest(site, "DRL-TEST-1", drillTemplate.id(),
                List.of(audience.id()), List.of(), List.of()), director);

        NotificationActivation activation = repository.findActivation(sent.activationId()).orElseThrow();
        assertThat(activation.mode()).isEqualTo(NotificationActivation.Mode.DRILL);
        assertThat(activation.status()).isEqualTo(NotificationActivation.Status.ACTIVE);
        assertThat(activation.approvedBy()).as("a drill needs no approval").isNull();
        assertThat(activation.incidentReference()).isEqualTo("DRL-TEST-1");
        assertThat(sent.targetCount()).isEqualTo(80); // 40 recipients on each of the template's two channels
        assertThat(sent.sentCount()).isEqualTo(80);
        assertThat(jdbc.queryForList("SELECT event_type FROM emergency_notification.outbox_messages WHERE aggregate_id = ?",
                String.class, activation.id().toString()))
                .contains("sfl.ssemp.emergency-drill-notification-sent.v1")
                .doesNotContain("sfl.ssemp.emergency-notification-activated.v1");

        var closed = drills.close(sent.activationId(), director);
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(drills.close(sent.activationId(), director).status()).as("idempotent").isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("a drill is refused on a real template, and a real alert on a drill template")
    void the_two_paths_refuse_each_others_templates() {
        NotificationTemplate real = template("Evacuate now", "Leave the building immediately.", false, false);
        NotificationTemplate drill = template(MARKER + ": fire drill", MARKER + ". Assemble.", true, false);

        ambiguity(() -> drills.trigger(new DrillNotificationRequest(site, "DRL-TEST-2", real.id(),
                List.of(audience.id()), List.of(), List.of()), director));
        ambiguity(() -> activations.createDraft(new ActivationService.CreateActivation(site, null, drill.id(),
                List.of(audience.id()), List.of(), List.of(ChannelType.SMS), Priority.HIGH, "INC-1",
                "draft-" + System.nanoTime(), director, SourceChannel.WEB)));
    }

    @Test
    @DisplayName("a drill in progress is never counted as a live emergency nor escalated for missing acknowledgements")
    void a_drill_is_not_a_live_emergency() {
        NotificationTemplate drill = template(MARKER + ": fire drill", MARKER + ". Assemble.", true, false);
        var sent = drills.trigger(new DrillNotificationRequest(site, "DRL-TEST-3", drill.id(), List.of(audience.id()),
                List.of(), List.of()), director);

        Object live = repository.dashboardCounts(List.of(site), site).get("activeActivationCount");
        assertThat(((Number) live).longValue()).isZero();
        assertThat(repository.findActivationsForAckEscalation(site, java.time.Instant.now().plusSeconds(3600), 50))
                .doesNotContain(sent.activationId());
    }
}
