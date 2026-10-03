package gh.edu.clet.sfl.facilities.lostfound.domain;

import java.util.List;

/**
 * Whether a custody chain is complete: it starts somewhere and every transfer picks up from where the last one
 * left off. The service always takes the sender from the current holder, so a break can only come from outside
 * the service - which is exactly what the completeness figure is there to show.
 */
public final class CustodyChain {

    private CustodyChain() {
    }

    public static boolean complete(List<CustodyEvent> events) {
        if (events.isEmpty()) {
            return false;
        }
        for (int i = 1; i < events.size(); i++) {
            if (!events.get(i).fromParty().equals(events.get(i - 1).toParty())
                    || events.get(i).occurredAt().isBefore(events.get(i - 1).occurredAt())) {
                return false;
            }
        }
        return true;
    }

    public static String currentHolder(List<CustodyEvent> events) {
        return events.isEmpty() ? null : events.get(events.size() - 1).toParty();
    }
}
