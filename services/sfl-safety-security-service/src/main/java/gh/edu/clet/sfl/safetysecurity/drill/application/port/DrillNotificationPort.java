package gh.edu.clet.sfl.safetysecurity.drill.application.port;

import gh.edu.clet.sfl.common.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * S175's view of S174's drill mode - SRS-SFL-S175-01. Implemented by the one adapter that knows S174 exists.
 * Every refusal for "this could look like a real alert" arrives as S175's own "Test/Real Ambiguity".
 */
public interface DrillNotificationPort {

    List<Template> drillTemplates(String siteCode);

    /** Throws "Test/Real Ambiguity" when the template cannot be sent as a drill from this site. */
    void requireDrillTemplate(String siteCode, UUID templateId);

    Sent send(String siteCode, String drillReference, UUID templateId, List<UUID> audienceGroupIds,
            List<UUID> recipientZoneIds, ActorContext actor);

    Sent status(UUID activationId);

    void close(UUID activationId, ActorContext actor);

    record Template(UUID templateId, String templateCode, String title, List<String> channels) {
    }

    record Sent(UUID activationId, String activationNumber, String status, Instant sentAt, int targetCount,
            int sentCount, int deliveredCount, int failedCount, long acknowledgedCount) {
    }
}
