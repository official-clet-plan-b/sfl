package gh.edu.clet.sfl.facilities.hygiene.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A scheduled audit, pest-control visit or statutory check. */
public record HygieneControl(UUID id, String reference, String siteCode, UUID roomId, String locationLabel,
        ControlType controlType, RiskCategory riskCategory, String title, String ownerReference, Frequency frequency,
        LocalDate dueOn, ControlStatus status, Instant startedAt, LocalDate completedOn, String completedBy,
        String providerReference, boolean providerConfirmed, String providerConfirmedBy, Instant providerConfirmedAt,
        UUID previousControlId, String notes, Instant overdueNotifiedAt, String createdBy, Instant createdAt,
        Instant updatedAt, long version) {

    /** A pest visit with a named provider is only as good as the provider's confirmation that it happened. */
    public boolean needsProviderConfirmation() {
        return controlType == ControlType.PEST_VISIT && providerReference != null && !providerConfirmed;
    }

    public String effectiveStatus(LocalDate today) {
        return ControlStatus.effective(status, dueOn, today);
    }
}
