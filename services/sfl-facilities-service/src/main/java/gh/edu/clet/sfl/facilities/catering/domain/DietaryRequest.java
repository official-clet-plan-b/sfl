package gh.edu.clet.sfl.facilities.catering.domain;

import java.time.Instant;
import java.util.UUID;

/** Who needs what, by opaque reference - never a name - and who authorised collecting it. */
public record DietaryRequest(UUID id, UUID serviceId, String siteCode, String personReference, NeedType needType,
        String needCode, String authorisedBy, NeedStatus status, UUID substituteItemId,
        String substitutionApprovedBy, String waiverReason, String createdBy, Instant createdAt, Instant updatedAt,
        long version) {
}
