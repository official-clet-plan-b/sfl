package gh.edu.clet.sfl.safetysecurity.drill.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.safetysecurity.drill.domain.event.DrillEventType;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.Drill;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillExecution;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.FrequencyRequirement;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.IntegrationEventPublisher;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The one place S175's event payloads are built. References and counts only - who was missing stays in S175.
 * Every drill payload carries {@code drillId, reference, siteCode, drillType}; instants are ISO-8601 text.
 */
@Component
public class DrillEvents {

    private final IntegrationEventPublisher events;

    public DrillEvents(IntegrationEventPublisher events) {
        this.events = events;
    }

    public void scheduled(Drill drill, ActorContext actor) {
        Map<String, Object> payload = base(drill);
        payload.put("scheduledFor", iso(drill.plan().scheduledFor()));
        publish(DrillEventType.DRILL_SCHEDULED, drill, actor, payload);
    }

    public void started(Drill drill, DrillExecution execution, ActorContext actor) {
        Map<String, Object> payload = base(drill);
        payload.put("startedAt", iso(execution.startedAt()));
        payload.put("notificationActivationId", String.valueOf(execution.notificationActivationId()));
        payload.put("musterSessionId", execution.musterSessionId().toString());
        payload.put("baselineCount", execution.baselineCount());
        payload.put("baselineStale", execution.baselineStale());
        publish(DrillEventType.DRILL_STARTED, drill, actor, payload);
    }

    public void completed(Drill drill, DrillExecution execution, ActorContext actor) {
        Map<String, Object> payload = base(drill);
        payload.put("rollCallClosedAt", iso(execution.rollCallClosedAt()));
        payload.put("baselineCount", execution.baselineCount());
        payload.put("checkedInCount", execution.checkedInCount());
        payload.put("gapCount", execution.gapCount());
        payload.put("notificationToMusterSeconds", execution.notificationToMusterSeconds());
        payload.put("baselineStale", execution.baselineStale());
        publish(DrillEventType.DRILL_COMPLETED, drill, actor, payload);
    }

    public void closed(Drill drill, long openActions, ActorContext actor) {
        Map<String, Object> payload = base(drill);
        payload.put("openCorrectiveActions", openActions);
        payload.put("deferralReason", drill.statusReason());
        publish(DrillEventType.DRILL_CLOSED, drill, actor, payload);
    }

    /** {@code requirementId, siteCode, drillType, intervalDays, dueAt, lastCompletedAt} - S175-04 "Compliance Gap". */
    public void complianceGap(FrequencyRequirement requirement, Instant dueAt, Instant lastCompletedAt,
            ActorContext actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requirementId", requirement.id().toString());
        payload.put("siteCode", requirement.siteCode());
        payload.put("drillType", requirement.drillType().name());
        payload.put("intervalDays", requirement.intervalDays());
        payload.put("dueAt", iso(dueAt));
        payload.put("lastCompletedAt", iso(lastCompletedAt));
        events.publish(DrillEventType.DRILL_COMPLIANCE_GAP.eventType(), DrillEventType.DRILL_COMPLIANCE_GAP.version(),
                DrillEventType.REQUIREMENT_AGGREGATE_TYPE, requirement.id().toString(), requirement.siteCode(), actor,
                payload);
    }

    private void publish(DrillEventType type, Drill drill, ActorContext actor, Map<String, Object> payload) {
        events.publish(type.eventType(), type.version(), DrillEventType.AGGREGATE_TYPE, drill.id().toString(),
                drill.siteCode(), actor, payload);
    }

    private static Map<String, Object> base(Drill drill) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("drillId", drill.id().toString());
        payload.put("reference", drill.reference());
        payload.put("siteCode", drill.siteCode());
        payload.put("drillType", drill.plan().drillType().name());
        return payload;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
