package gh.edu.clet.sfl.facilities.eventlogistics.infrastructure.integration;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency;
import gh.edu.clet.sfl.facilities.eventlogistics.application.RiskAssessmentProjectionService;
import gh.edu.clet.sfl.facilities.shared.application.integration.InboundIntegrationEvent;
import gh.edu.clet.sfl.facilities.shared.application.integration.IntegrationEventHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SSEMP says something about an S165 risk assessment; S173 keeps what its currency check needs -
 * SRS-SFL-S173-03.
 *
 * <p>Four reserved event names, contracts in {@code docs/facilities/S173_Event_Contracts.md}, published by
 * S165 in {@code sfl-safety-security-service} since Phase 2 (ADR 0010) and proven against this handler by
 * {@code RiskAssessmentEventsContractTest}. Until an assessment's {@code published} event arrives the
 * projection holds nothing for it and every higher-risk event citing it is refused - the intended,
 * fail-closed behaviour, which also covers a deployment on the {@code local} transport, where nothing
 * arrives at all.
 *
 * <p>A payload that can never be handled - no assessment id, an unknown risk level - is logged and
 * dropped rather than thrown, because throwing makes the broker redeliver it forever (see
 * {@link IntegrationEventHandler#handle}).
 */
@Component
public class RiskAssessmentEventsHandler implements IntegrationEventHandler {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentEventsHandler.class);

    private static final Set<String> HANDLED = Set.of(RiskAssessmentProjectionService.PUBLISHED,
            RiskAssessmentProjectionService.SUPERSEDED, RiskAssessmentProjectionService.REVIEW_LAPSED,
            RiskAssessmentProjectionService.SIGNED_OFF);

    private final RiskAssessmentProjectionService projection;
    private final Clock clock;

    public RiskAssessmentEventsHandler(RiskAssessmentProjectionService projection, Clock clock) {
        this.projection = projection;
        this.clock = clock;
    }

    @Override
    public boolean handles(String eventType) {
        return HANDLED.contains(eventType);
    }

    @Override
    public void handle(InboundIntegrationEvent event) {
        String assessmentId = event.text("assessmentId") != null ? event.text("assessmentId") : event.aggregateId();
        Integer version = integer(event.text("version"));
        if (assessmentId == null || assessmentId.isBlank() || version == null) {
            log.error("{} {} lacks an assessmentId or version; the S173 projection cannot be updated",
                    event.eventType(), event.messageId());
            return;
        }
        Instant now = clock.instant();
        try {
            boolean applied = switch (event.eventType()) {
                case RiskAssessmentProjectionService.PUBLISHED -> published(event, assessmentId, version, now);
                case RiskAssessmentProjectionService.SUPERSEDED -> projection.superseded(assessmentId, version, now);
                case RiskAssessmentProjectionService.REVIEW_LAPSED -> projection.reviewLapsed(assessmentId, version,
                        instantOr(event.text("reviewDueAt"), now), now);
                case RiskAssessmentProjectionService.SIGNED_OFF -> signedOff(event, assessmentId, version, now);
                default -> false;
            };
            if (!applied) {
                log.warn("{} for assessment {} v{} was not applied: S173 holds no such version, or the payload is"
                        + " incomplete", event.eventType(), assessmentId, version);
            }
        } catch (IllegalArgumentException | DateTimeParseException malformed) {
            log.error("{} {} is malformed and was dropped: {}", event.eventType(), event.messageId(),
                    malformed.getMessage());
        }
    }

    private boolean published(InboundIntegrationEvent event, String assessmentId, int version, Instant now) {
        String site = event.siteCode() != null ? event.siteCode() : event.text("siteCode");
        String level = event.text("riskLevel");
        Instant due = event.text("reviewDueAt") == null ? null : Instant.parse(event.text("reviewDueAt"));
        if (site == null || level == null || due == null) {
            return false;
        }
        projection.published(assessmentId, version, site.strip().toUpperCase(Locale.ROOT),
                RiskAssessmentCurrency.RiskLevel.valueOf(level.strip().toUpperCase(Locale.ROOT)), due,
                event.text("authorId"), event.text("signedOffBy"), now);
        return true;
    }

    private boolean signedOff(InboundIntegrationEvent event, String assessmentId, int version, Instant now) {
        String reviewer = event.text("signedOffBy");
        if (reviewer == null || reviewer.isBlank()) {
            return false;
        }
        Instant renewed = event.text("reviewDueAt") == null ? null : Instant.parse(event.text("reviewDueAt"));
        return projection.signedOff(assessmentId, version, reviewer.strip(), renewed, now);
    }

    private static Integer integer(String value) {
        try {
            return value == null ? null : Integer.valueOf(value.strip());
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static Instant instantOr(String value, Instant fallback) {
        return value == null ? fallback : Instant.parse(value);
    }
}
