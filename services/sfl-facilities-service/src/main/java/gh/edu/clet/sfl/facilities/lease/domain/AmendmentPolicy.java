package gh.edu.clet.sfl.facilities.lease.domain;

import java.util.Collection;
import java.util.Set;

/** Whether a proposed amendment collides with one already open on the same agreement - SRS error state "conflicting amendment - change blocked pending legal review". */
public final class AmendmentPolicy {

    private AmendmentPolicy() {
    }

    public static boolean conflicts(Amendment proposed, Collection<Amendment> open) {
        Set<String> mine = proposed.touched();
        for (Amendment other : open) {
            if (!other.status().open() || other.id().equals(proposed.id())) {
                continue;
            }
            Set<String> theirs = other.touched();
            if (mine.contains("*") || theirs.contains("*") || mine.stream().anyMatch(theirs::contains)) {
                return true;
            }
        }
        return false;
    }
}
