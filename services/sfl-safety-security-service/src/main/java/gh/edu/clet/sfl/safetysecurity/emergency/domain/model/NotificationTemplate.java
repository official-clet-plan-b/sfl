package gh.edu.clet.sfl.safetysecurity.emergency.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A reusable emergency notification template. {@code breakGlassEligible} marks templates that a
 * pre-authorised role may fire during a declared emergency without per-message approval (Arch §0E).
 *
 * <p>{@code drill} marks a template that may only be sent as a drill (Phase 2 SRS-SFL-S175-01). The rule that
 * keeps drill and real templates apart - the marker - is {@code DrillSeparationPolicy}'s, applied when a
 * template is created and whenever one is sent, rather than here: a stored template must always load.
 */
public record NotificationTemplate(UUID id, String templateCode, SiteCode siteCode, String title, String body,
        List<ChannelType> channels, boolean breakGlassEligible, RecordLifecycle lifecycle, RecordMetadata metadata,
        boolean drill) {

    /** A real (non-drill) template - every template that existed before S175. */
    public NotificationTemplate(UUID id, String templateCode, SiteCode siteCode, String title, String body,
            List<ChannelType> channels, boolean breakGlassEligible, RecordLifecycle lifecycle, RecordMetadata metadata) {
        this(id, templateCode, siteCode, title, body, channels, breakGlassEligible, lifecycle, metadata, false);
    }

    public NotificationTemplate {
        Objects.requireNonNull(id);
        Objects.requireNonNull(siteCode);
        Objects.requireNonNull(lifecycle);
        Objects.requireNonNull(metadata);
        templateCode = require(templateCode, "templateCode");
        title = require(title, "title");
        body = require(body, "body");
        channels = channels == null ? List.of() : List.copyOf(channels);
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("A template must declare at least one channel");
        }
    }

    public boolean active() {
        return lifecycle.isActive();
    }

    public NotificationTemplate withLifecycle(RecordLifecycle next, RecordMetadata changed) {
        return new NotificationTemplate(id, templateCode, siteCode, title, body, channels, breakGlassEligible, next,
                changed, drill);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }
}
