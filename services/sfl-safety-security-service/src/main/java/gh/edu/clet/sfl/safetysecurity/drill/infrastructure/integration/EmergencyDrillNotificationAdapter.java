package gh.edu.clet.sfl.safetysecurity.drill.infrastructure.integration;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillNotificationPort;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillErrorCode;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import gh.edu.clet.sfl.safetysecurity.emergency.application.contract.EmergencyDrillTrigger;
import gh.edu.clet.sfl.safetysecurity.emergency.application.contract.EmergencyDrillTrigger.DrillNotification;
import gh.edu.clet.sfl.safetysecurity.emergency.application.contract.EmergencyDrillTrigger.DrillNotificationRequest;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.exception.EmergencyErrorCode;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.exception.EmergencyException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The one S175 class that knows S174 exists - SRS-SFL-S175-01's "Execution triggers an S174 notification in explicit
 * test mode". In-process, through S174's published {@link EmergencyDrillTrigger}; S174's refusals become S175's own,
 * so S175's API speaks one error vocabulary.
 */
@Component
public class EmergencyDrillNotificationAdapter implements DrillNotificationPort {

    private final EmergencyDrillTrigger trigger;

    public EmergencyDrillNotificationAdapter(EmergencyDrillTrigger trigger) {
        this.trigger = trigger;
    }

    @Override
    public List<Template> drillTemplates(String siteCode) {
        return translate(() -> trigger.drillTemplates(siteCode).stream()
                .map(t -> new Template(t.templateId(), t.templateCode(), t.title(), t.channels())).toList());
    }

    @Override
    public void requireDrillTemplate(String siteCode, UUID templateId) {
        translate(() -> {
            trigger.requireDrillTemplate(siteCode, templateId);
            return null;
        });
    }

    @Override
    public Sent send(String siteCode, String drillReference, UUID templateId, List<UUID> audienceGroupIds,
            List<UUID> recipientZoneIds, ActorContext actor) {
        return translate(() -> sent(trigger.trigger(new DrillNotificationRequest(siteCode, drillReference, templateId,
                audienceGroupIds, recipientZoneIds, List.of()), actor)));
    }

    @Override
    public Sent status(UUID activationId) {
        return translate(() -> sent(trigger.status(activationId)));
    }

    @Override
    public void close(UUID activationId, ActorContext actor) {
        translate(() -> trigger.close(activationId, actor));
    }

    private static Sent sent(DrillNotification n) {
        return new Sent(n.activationId(), n.activationNumber(), n.status(), n.sentAt(), n.targetCount(), n.sentCount(),
                n.deliveredCount(), n.failedCount(), n.acknowledgedCount());
    }

    private static <T> T translate(Supplier<T> call) {
        try {
            return call.get();
        } catch (EmergencyException e) {
            Map<String, Object> details = new LinkedHashMap<>(e.details());
            details.put("s174Code", e.errorCode().code());
            // if/else rather than a switch: a switch on another module's enum compiles to a synthetic class that
            // the "only this adapter knows S174" architecture rule would rightly catch.
            EmergencyErrorCode code = e.errorCode();
            if (code == EmergencyErrorCode.EMERGENCY_TEST_REAL_AMBIGUITY) {
                throw new DrillException(DrillErrorCode.DRILL_TEST_REAL_AMBIGUITY, details);
            }
            if (code == EmergencyErrorCode.EMERGENCY_VALIDATION_FAILED
                    || code == EmergencyErrorCode.EMERGENCY_RECORD_NOT_FOUND
                    || code == EmergencyErrorCode.EMERGENCY_MISSING_SITE_SCOPE) {
                details.putIfAbsent("reason", e.getMessage());
                throw new DrillException(DrillErrorCode.DRILL_VALIDATION_FAILED, details);
            }
            throw new IllegalStateException("S174 refused the drill notification: " + e.getMessage(), e);
        }
    }
}
