package gh.edu.clet.sfl.safetysecurity.visitor.application.contract;

import java.time.Instant;
import java.util.List;

/**
 * Who S160 says is on site right now - checked in and not out - published for the muster and drill roll-call
 * (S162a-04, Phase 2 S175-02) that S160a-06 always meant to combine with access occupancy.
 *
 * <p>Not permission-checked: the caller is the platform's muster, acting for an operator it has authorised,
 * and the answer is the same people the roll-call screen already shows.
 */
public interface OnSiteVisitors {

    List<OnSiteVisitor> onSite(String siteCode);

    /**
     * @param personRef the visitor's badge number where one was issued - the reference an access reader would
     *        record for them - and {@code VISIT-<id>} otherwise
     * @param accessZones the zones the visit was granted; empty means unrestricted
     */
    record OnSiteVisitor(String personRef, String displayName, String hostName, Instant checkedInAt,
            List<String> accessZones) {
    }
}
