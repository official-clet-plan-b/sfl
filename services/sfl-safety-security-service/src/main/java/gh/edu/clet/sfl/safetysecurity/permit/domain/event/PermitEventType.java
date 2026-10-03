package gh.edu.clet.sfl.safetysecurity.permit.domain.event;

/**
 * S164 integration events, canonical {@code sfl.ssemp.{name}.v1}. The first four are the names and payload S176 already
 * consumes (its {@code PermitEventHandler}); the rest are S164's own. Catalogued in {@code docs/hse/S164_Event_Contracts.md}.
 */
public enum PermitEventType {

    PERMIT_ISSUED("sfl.ssemp.permit-issued.v1"),
    PERMIT_SUSPENDED("sfl.ssemp.permit-suspended.v1"),
    PERMIT_EXTENDED("sfl.ssemp.permit-extended.v1"),
    PERMIT_CLOSED("sfl.ssemp.permit-closed.v1"),
    PERMIT_EXPIRING("sfl.ssemp.permit-expiring.v1"),
    PERMIT_OVERDUE("sfl.ssemp.permit-overdue.v1"),
    PERMIT_FLAGGED("sfl.ssemp.permit-flagged.v1");

    public static final String AGGREGATE_TYPE = "Permit";

    private final String eventType;

    PermitEventType(String eventType) {
        this.eventType = eventType;
    }

    public String eventType() {
        return eventType;
    }

    public int version() {
        return Integer.parseInt(eventType.substring(eventType.lastIndexOf(".v") + 2));
    }
}
