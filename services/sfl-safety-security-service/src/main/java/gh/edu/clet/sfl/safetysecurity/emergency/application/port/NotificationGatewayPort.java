package gh.edu.clet.sfl.safetysecurity.emergency.application.port;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.ChannelType;
import java.util.UUID;

/**
 * Provider-neutral outbound gateway for one channel of an activation. Vendor SDKs/types live only in
 * adapters; the Phase-1 recorded adapter never fakes vendor success - it reports what it actually did and
 * whether it fell back to a degraded/direct path.
 */
public interface NotificationGatewayPort {

    /**
     * @param drill a drill send (Phase 2 S175-01): a real provider adapter must deliver it so recipients can tell
     *        - the template already opens with the drill marker; this is the structural half of the same rule
     */
    GatewaySendResult send(UUID activationId, ChannelType channel, String siteCode, int targetCount,
            boolean degradedMode, boolean drill, ActorContext actor);

    record GatewaySendResult(String provider, int accepted, boolean degraded) {
    }
}
