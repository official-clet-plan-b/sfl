package gh.edu.clet.sfl.safetysecurity.emergency.application.contract;

import gh.edu.clet.sfl.common.security.ActorContext;
import java.util.List;
import java.util.UUID;

/**
 * Told when a real emergency notification goes out - published by S174 in its own {@code contract} package so a sibling module can
 * react without S174 knowing it exists. The implementation today is S164's: SRS-SFL-S164-03 "an emergency fast-lane event (S174) affects
 * a zone with active permits ... all active permits in that zone are flagged for suspension review".
 *
 * <p>Called inside the activation's own transaction, so the flags and the activation commit together. Drills are never announced: an
 * exercise must not flag a real permit. Zero implementations is a valid deployment.
 */
public interface EmergencyActivationObserver {

    void activated(ActivatedEmergency emergency);

    /**
     * @param zoneIds the S160a zones the notification is addressed to; empty means the whole site
     * @param breakGlass true for a break-glass (fast-lane) activation
     */
    record ActivatedEmergency(UUID activationId, String activationNumber, String siteCode, List<UUID> zoneIds, boolean breakGlass,
            ActorContext actor) {
    }
}
