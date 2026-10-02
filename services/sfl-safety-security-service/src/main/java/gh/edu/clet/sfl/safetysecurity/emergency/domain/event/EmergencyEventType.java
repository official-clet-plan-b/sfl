package gh.edu.clet.sfl.safetysecurity.emergency.domain.event;

/**
 * S174 integration events. Names follow the canonical rule {@code sfl.ssemp.{event-name}.v1}. The two
 * pre-seeded catalog events (activated, status-received) are reused verbatim.
 */
public enum EmergencyEventType {

    EMERGENCY_TEMPLATE_CREATED("sfl.ssemp.emergency-template-created.v1", "NotificationTemplate"),
    EMERGENCY_ACTIVATION_SUBMITTED("sfl.ssemp.emergency-activation-submitted.v1", "NotificationActivation"),
    EMERGENCY_ACTIVATION_APPROVED("sfl.ssemp.emergency-activation-approved.v1", "NotificationActivation"),
    EMERGENCY_ACTIVATION_CANCELLED("sfl.ssemp.emergency-activation-cancelled.v1", "NotificationActivation"),
    EMERGENCY_NOTIFICATION_ACTIVATED("sfl.ssemp.emergency-notification-activated.v1", "NotificationActivation"),
    EMERGENCY_BREAK_GLASS_ACTIVATED("sfl.ssemp.emergency-break-glass-activated.v1", "NotificationActivation"),
    EMERGENCY_DEGRADED_FALLBACK_RECORDED("sfl.ssemp.emergency-degraded-fallback-recorded.v1",
            "NotificationActivation"),
    EMERGENCY_AFTER_ACTION_APPROVED("sfl.ssemp.emergency-after-action-approved.v1", "NotificationActivation"),
    EMERGENCY_ALL_CLEAR_SENT("sfl.ssemp.emergency-all-clear-sent.v1", "NotificationActivation"),
    EMERGENCY_NOTIFICATION_STATUS_RECEIVED("sfl.ssemp.emergency-notification-status-received.v1", "DeliveryReceipt"),
    EMERGENCY_ACKNOWLEDGEMENT_RECEIVED("sfl.ssemp.emergency-acknowledgement-received.v1", "Acknowledgement"),
    EMERGENCY_ACTIVATION_CLOSED("sfl.ssemp.emergency-activation-closed.v1", "NotificationActivation"),
    EMERGENCY_ACTIVATION_REOPENED("sfl.ssemp.emergency-activation-reopened.v1", "NotificationActivation"),
    EMERGENCY_DRILL_COMPLETED("sfl.ssemp.emergency-drill-completed.v1", "DrillRun"),
    // Phase 2 S175: a drill sent through S174's drill mode. Its own event, never
    // emergency-notification-activated, so no consumer can take an exercise for an emergency.
    EMERGENCY_DRILL_NOTIFICATION_SENT("sfl.ssemp.emergency-drill-notification-sent.v1", "NotificationActivation"),
    EMERGENCY_DRILL_NOTIFICATION_CLOSED("sfl.ssemp.emergency-drill-notification-closed.v1", "NotificationActivation");

    private final String eventType;
    private final String defaultAggregateType;

    EmergencyEventType(String eventType, String defaultAggregateType) {
        this.eventType = eventType;
        this.defaultAggregateType = defaultAggregateType;
    }

    public String eventType() {
        return eventType;
    }

    public String defaultAggregateType() {
        return defaultAggregateType;
    }

    public String routingKey() {
        return eventType.substring("sfl.".length());
    }

    public int version() {
        return Integer.parseInt(eventType.substring(eventType.lastIndexOf(".v") + 2));
    }
}
