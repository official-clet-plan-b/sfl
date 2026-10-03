package gh.edu.clet.sfl.facilities.lostfound.application;

import java.util.LinkedHashMap;
import java.util.Map;

/** The S179 integration events. Payloads carry references, categories and dates - never a description, a name or a contact. */
public final class LostFoundEvents {

    public static final String ITEM_REGISTERED = "sfl.ifimp.found-item-registered.v1";
    public static final String ITEM_RELEASED = "sfl.ifimp.found-item-released.v1";
    public static final String ITEM_DISPOSED = "sfl.ifimp.found-item-disposed.v1";
    public static final String ITEM_HANDED_TO_AUTHORITIES = "sfl.ifimp.found-item-handed-to-authorities.v1";
    public static final String ITEM_ISOLATED = "sfl.ifimp.found-item-isolated.v1";
    public static final String ESCALATED = "sfl.ifimp.lost-found-escalated.v1";
    public static final String INCIDENT_REQUESTED = "sfl.ifimp.lost-found-incident-requested.v1";

    private LostFoundEvents() {
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
