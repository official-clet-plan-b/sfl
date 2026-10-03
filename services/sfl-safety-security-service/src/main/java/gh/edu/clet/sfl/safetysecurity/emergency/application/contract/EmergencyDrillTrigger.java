package gh.edu.clet.sfl.safetysecurity.emergency.application.contract;

import gh.edu.clet.sfl.common.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * S174's drill mode, offered to S175 - Phase 2 SRS-SFL-S175-01: "Execution triggers an S174 notification in
 * explicit test mode, visibly distinguishable to recipients from a real emergency."
 *
 * <p>Published in S174's own {@code contract} package so S175 reaches it through one adapter and never imports
 * S174's services. Same deployable, so a real in-process call: the drill goes down the real notification path
 * - the same gateway, channels and audiences as an emergency - with a drill template and in {@code DRILL} mode.
 *
 * <p>Not permission-checked against the S174 matrix: the caller is S175 acting for an operator it has already
 * authorised to execute the drill. What keeps the send safe is not who asked but what is sent - a drill
 * template carrying the drill marker, refused otherwise with "Test/Real Ambiguity".
 */
public interface EmergencyDrillTrigger {

    /**
     * The site's active drill templates - the only ones a drill plan may name. Each carries the drill marker
     * in its title and body; a real template never appears here.
     */
    List<DrillTemplate> drillTemplates(String siteCode);

    /**
     * Refuses, with "Test/Real Ambiguity", a template that could not be sent as a drill from this site - so a
     * plan naming one is refused when it is scheduled, not when the drill starts.
     */
    void requireDrillTemplate(String siteCode, UUID templateId);

    /** Sends the drill notification now. Throws "Test/Real Ambiguity" unless {@code templateId} is a drill template. */
    DrillNotification trigger(DrillNotificationRequest request, ActorContext actor);

    /** Where delivery and acknowledgement stand for a drill notification already sent. */
    DrillNotification status(UUID activationId);

    /** Closes the drill notification when the drill that sent it is over. Idempotent once closed. */
    DrillNotification close(UUID activationId, ActorContext actor);

    /**
     * @param drillReference the S175 drill's number, recorded on the activation so S174's own screens say which
     *        exercise sent it
     * @param channels channel names; empty means the template's own channels
     */
    record DrillNotificationRequest(String siteCode, String drillReference, UUID templateId,
            List<UUID> audienceGroupIds, List<UUID> recipientZoneIds, List<String> channels) {
    }

    record DrillTemplate(UUID templateId, String templateCode, String title, List<String> channels) {
    }

    /**
     * @param sentAt when the notification went out - the start of S175-03's notification-to-muster time
     */
    record DrillNotification(UUID activationId, String activationNumber, String status, Instant sentAt,
            int targetCount, int sentCount, int deliveredCount, int failedCount, long acknowledgedCount) {
    }
}
