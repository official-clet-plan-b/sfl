package gh.edu.clet.sfl.facilities.lease.application;

import java.util.LinkedHashMap;
import java.util.Map;

/** The S177 integration events. Payloads carry ids, references, codes and dates - never terms, rent or party detail. */
public final class LeaseEvents {

    public static final String REGISTERED = "sfl.ifimp.lease-registered.v1";
    public static final String APPROVED = "sfl.ifimp.lease-approved.v1";
    public static final String AMENDMENT_PROPOSED = "sfl.ifimp.lease-amendment-proposed.v1";
    public static final String AMENDED = "sfl.ifimp.lease-amended.v1";
    public static final String TERMINATED = "sfl.ifimp.lease-terminated.v1";
    public static final String EXPIRED = "sfl.ifimp.lease-expired.v1";
    public static final String ALERT_RAISED = "sfl.ifimp.lease-alert-raised.v1";

    private LeaseEvents() {
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
