package gh.edu.clet.sfl.facilities.waste.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record WasteCarrier(UUID id, String code, String name, String licenceReference, LocalDate licenceExpiresOn,
        boolean hazardousApproved, ApprovalStatus status, String createdBy, Instant createdAt, Instant updatedAt,
        long version) {

    /** Why this carrier may not take the waste on {@code day}, or null if it may. */
    public String refusal(boolean hazardous, LocalDate day) {
        if (status != ApprovalStatus.APPROVED) {
            return "Carrier " + code + " is suspended.";
        }
        if (licenceExpiresOn.isBefore(day)) {
            return "Carrier " + code + "'s licence expired on " + licenceExpiresOn + ".";
        }
        if (hazardous && !hazardousApproved) {
            return "Carrier " + code + " is not approved for hazardous waste.";
        }
        return null;
    }
}
