package gh.edu.clet.sfl.facilities.catering.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CateringService(UUID id, String reference, String siteCode, UUID venueId, UUID menuId, UUID supplierId,
        ContextType contextType, String contextReference, String title, LocalDate serviceDate, Instant startsAt,
        Instant cancellationCutoff, int expectedGuests, int plannedPortions, Integer deliveredPortions,
        ServiceStatus status, String requestedBy, String approvedBy, Instant approvedAt,
        String capacityExceptionReason, String capacityApprovedBy, String supplierExceptionReason,
        String supplierApprovedBy, String purchaseReference, String invoiceReference, FinanceState financeState,
        Instant deliveredAt, Instant closedAt, String cancellationReason, String createdBy, Instant createdAt,
        Instant updatedAt, long version) {

    public boolean afterCutoff(Instant now) {
        return !now.isBefore(cancellationCutoff);
    }
}
