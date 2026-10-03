package gh.edu.clet.sfl.facilities.catering.application;

import java.util.LinkedHashMap;
import java.util.Map;

/** The S172 integration events. Payloads carry ids, references, codes, counts and dates - never a person reference or free text. */
public final class CateringEvents {

    public static final String SERVICE_PLANNED = "sfl.ifimp.catering-service-planned.v1";
    public static final String SERVICE_APPROVED = "sfl.ifimp.catering-service-approved.v1";
    public static final String SERVICE_CONFIRMED = "sfl.ifimp.catering-service-confirmed.v1";
    public static final String SERVICE_DELIVERED = "sfl.ifimp.catering-service-delivered.v1";
    public static final String SERVICE_CANCELLED = "sfl.ifimp.catering-service-cancelled.v1";
    public static final String SERVICE_CLOSED = "sfl.ifimp.catering-service-closed.v1";
    public static final String EXCEPTION_RAISED = "sfl.ifimp.catering-exception-raised.v1";
    public static final String INCIDENT_REQUESTED = "sfl.ifimp.catering-incident-requested.v1";

    private CateringEvents() {
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
