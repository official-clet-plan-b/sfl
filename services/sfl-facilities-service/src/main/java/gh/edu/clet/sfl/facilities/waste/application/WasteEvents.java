package gh.edu.clet.sfl.facilities.waste.application;

import java.util.LinkedHashMap;
import java.util.Map;

/** The S178 integration events. Payloads carry ids, references, codes, quantities and dates - never free text. */
public final class WasteEvents {

    public static final String COLLECTION_SCHEDULED = "sfl.ifimp.waste-collection-scheduled.v1";
    public static final String COLLECTION_RECORDED = "sfl.ifimp.waste-collection-recorded.v1";
    public static final String COLLECTION_HANDED_OVER = "sfl.ifimp.waste-collection-handed-over.v1";
    public static final String COLLECTION_CLOSED = "sfl.ifimp.waste-collection-closed.v1";
    public static final String COLLECTION_MISSED = "sfl.ifimp.waste-collection-missed.v1";
    public static final String EXCEPTION_RAISED = "sfl.ifimp.waste-exception-raised.v1";
    public static final String INCIDENT_REQUESTED = "sfl.ifimp.waste-incident-requested.v1";

    private WasteEvents() {
    }

    public static Map<String, Object> payload(Object... keysAndValues) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            Object value = keysAndValues[i + 1];
            if (value != null) {
                payload.put(String.valueOf(keysAndValues[i]),
                        value instanceof Enum<?> e ? e.name() : value instanceof java.util.UUID
                                || value instanceof java.time.temporal.Temporal ? value.toString() : value);
            }
        }
        return payload;
    }
}
