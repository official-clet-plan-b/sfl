package gh.edu.clet.sfl.safetysecurity.emergency.infrastructure.integration;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.NotificationGatewayPort;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.ChannelType;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase-1 recorded notification gateway. Provider-neutral: it records what it dispatched to the channel
 * simulator and reports it honestly - it never fabricates vendor success. A real SMS/email/voice/siren
 * adapter replaces this without any domain change; vendor DTOs would live only inside such an adapter.
 */
@Component
public class RecordedNotificationGateway implements NotificationGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(RecordedNotificationGateway.class);

    @Override
    public GatewaySendResult send(UUID activationId, ChannelType channel, String siteCode, int targetCount,
            boolean degradedMode, boolean drill, ActorContext actor) {
        String provider = channel.name() + (degradedMode ? "-DIRECT" : "-SIMULATOR") + (drill ? "-DRILL" : "");
        log.info("Recorded emergency dispatch activation={} channel={} site={} target={} degraded={} drill={} provider={}",
                activationId, channel, siteCode, targetCount, degradedMode, drill, provider);
        return new GatewaySendResult(provider, Math.max(targetCount, 0), degradedMode);
    }
}
