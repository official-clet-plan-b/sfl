package gh.edu.clet.sfl.facilities.hygiene.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The S170 integration events, named once. Payloads carry ids, references, codes and dates only - never
 * a finding's description or a note someone typed - so no free text leaves the service on the broker.
 */
public final class HygieneEvents {

    public static final String CONTROL_SCHEDULED = "sfl.ifimp.hygiene-control-scheduled.v1";
    public static final String CONTROL_COMPLETED = "sfl.ifimp.hygiene-control-completed.v1";
    public static final String CONTROL_MISSED = "sfl.ifimp.hygiene-control-missed.v1";
    public static final String FINDING_RAISED = "sfl.ifimp.hygiene-finding-raised.v1";
    public static final String FINDING_CLOSED = "sfl.ifimp.hygiene-finding-closed.v1";
    public static final String ESCALATED = "sfl.ifimp.hygiene-escalated.v1";
    public static final String INCIDENT_REQUESTED = "sfl.ifimp.hygiene-incident-requested.v1";

    private HygieneEvents() {
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
