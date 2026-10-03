package gh.edu.clet.sfl.facilities.waste.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record WasteDestination(UUID id, String code, String name, DestinationType destinationType,
        String permitReference, LocalDate permitExpiresOn, boolean acceptsHazardous, ApprovalStatus status,
        String createdBy, Instant createdAt, Instant updatedAt, long version) {

    /** Why this destination may not receive the waste on {@code day}, or null if it may. */
    public String refusal(boolean hazardous, LocalDate day) {
        if (status != ApprovalStatus.APPROVED) {
            return "Destination " + code + " is suspended.";
        }
        if (permitExpiresOn.isBefore(day)) {
            return "Destination " + code + "'s permit expired on " + permitExpiresOn + ".";
        }
        if (hazardous && !acceptsHazardous) {
            return "Destination " + code + " does not accept hazardous waste.";
        }
        return null;
    }

    /** Landfill is disposal, not diversion. */
    public boolean diverts() {
        return destinationType != DestinationType.LANDFILL;
    }
}
