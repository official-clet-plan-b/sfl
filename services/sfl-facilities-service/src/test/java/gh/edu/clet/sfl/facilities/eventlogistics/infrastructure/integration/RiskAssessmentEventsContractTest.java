package gh.edu.clet.sfl.facilities.eventlogistics.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency;
import gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport;
import gh.edu.clet.sfl.facilities.eventlogistics.application.ports.EventLogisticsRepository;
import gh.edu.clet.sfl.facilities.eventlogistics.domain.RiskAssessmentProjection;
import gh.edu.clet.sfl.facilities.shared.application.integration.InboundIntegrationEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The S165 -> S173 contract, from the consumer's side: the JSON below is byte-for-byte what
 * {@code sfl-safety-security-service}'s {@code RiskAssessmentEvents} writes to its outbox (and what its
 * {@code RiskAssessmentMandatoryScenariosEndToEndTest} asserts it writes). Parsed the way
 * {@code FacilitiesIntegrationListener} parses a message - so {@code version} arrives as a JSON number -
 * and handed to the real handler and projection.
 *
 * <p>Until S165 existed this handler had never run: nothing published these four events. This is the
 * first proof that what S165 sends is what S173 reads, and that the currency verdict S173 reaches is the
 * one S165 itself reaches.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sfl.facilities.messaging.drainer-enabled=false"
})
@EnabledIf(value = "gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available; see FacilitiesPostgresSupport.unavailableReason()")
class RiskAssessmentEventsContractTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        FacilitiesPostgresSupport.datasource(registry);
    }

    private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() {
    };
    private static final String SITE = "MAIN";

    @Autowired private RiskAssessmentEventsHandler handler;
    @Autowired private EventLogisticsRepository repository;
    @Autowired private ObjectMapper json;

    @Test
    void the_four_s165_events_drive_the_s173_projection_to_the_same_verdict_s165_reaches() {
        String id = UUID.randomUUID().toString();
        Instant due1 = Instant.parse("2026-12-30T09:00:00Z");

        // HIGH, published by its author, not yet independently signed off: not current.
        deliver("sfl.ssemp.risk-assessment-published.v1", id, """
                {"assessmentId":"%s","version":1,"siteCode":"MAIN","riskLevel":"HIGH",
                 "reviewDueAt":"2026-12-30T09:00:00Z","authorId":"hse-officer-1","signedOffBy":null,
                 "reference":"RA-1A2B3C4D","activityType":"CONFINED_SPACE","locationCode":null}
                """.formatted(id));
        RiskAssessmentProjection v1 = version(id, 1);
        assertThat(v1.status()).isEqualTo(RiskAssessmentCurrency.Status.PUBLISHED);
        assertThat(v1.riskLevel()).isEqualTo(RiskAssessmentCurrency.RiskLevel.HIGH);
        assertThat(v1.reviewDueAt()).isEqualTo(due1);
        assertThat(verdict(v1, "2026-10-02T09:00:00Z").reason())
                .isEqualTo(RiskAssessmentCurrency.Reason.NOT_INDEPENDENTLY_SIGNED_OFF);

        // Signed off by someone else, review date renewed: current.
        deliver("sfl.ssemp.risk-assessment-signed-off.v1", id, """
                {"assessmentId":"%s","version":1,"signedOffBy":"hse-director-2","reviewDueAt":"2027-01-01T09:00:00Z"}
                """.formatted(id));
        RiskAssessmentProjection signed = version(id, 1);
        assertThat(signed.signedOffBy()).isEqualTo("hse-director-2");
        assertThat(signed.reviewDueAt()).isEqualTo(Instant.parse("2027-01-01T09:00:00Z"));
        assertThat(verdict(signed, "2026-10-02T09:00:00Z").current()).isTrue();

        // S165 supersedes before it publishes the next version - both arrive, in that order.
        deliver("sfl.ssemp.risk-assessment-superseded.v1", id, """
                {"assessmentId":"%s","version":1}
                """.formatted(id));
        deliver("sfl.ssemp.risk-assessment-published.v1", id, """
                {"assessmentId":"%s","version":2,"siteCode":"MAIN","riskLevel":"MEDIUM",
                 "reviewDueAt":"2027-04-01T09:00:00Z","authorId":"hse-officer-1","signedOffBy":null,
                 "reference":"RA-1A2B3C4D","activityType":"CONFINED_SPACE","locationCode":null}
                """.formatted(id));
        assertThat(version(id, 1).status()).isEqualTo(RiskAssessmentCurrency.Status.SUPERSEDED);
        RiskAssessmentProjection v2 = version(id, 2);
        assertThat(verdict(v2, "2026-10-03T09:00:00Z").current()).as("MEDIUM needs no independent reviewer")
                .isTrue();

        // The review date passes without sign-off: not current, for the reason S165 gives.
        deliver("sfl.ssemp.risk-assessment-review-lapsed.v1", id, """
                {"assessmentId":"%s","version":2,"reviewDueAt":"2027-04-01T09:00:00Z"}
                """.formatted(id));
        assertThat(verdict(version(id, 2), "2027-04-01T09:00:00Z").reason())
                .isEqualTo(RiskAssessmentCurrency.Reason.REVIEW_LAPSED);
    }

    private void deliver(String eventType, String assessmentId, String payload) {
        handler.handle(new InboundIntegrationEvent(UUID.randomUUID(), eventType, "RiskAssessment", assessmentId, SITE,
                "corr-" + assessmentId, null, json.readValue(payload, PAYLOAD)));
    }

    private RiskAssessmentProjection version(String id, int version) {
        return repository.findRiskAssessment(id, version).orElseThrow();
    }

    private static RiskAssessmentCurrency.Verdict verdict(RiskAssessmentProjection projection, String at) {
        return RiskAssessmentCurrency.assess(projection.toSnapshot(), Instant.parse(at));
    }
}
