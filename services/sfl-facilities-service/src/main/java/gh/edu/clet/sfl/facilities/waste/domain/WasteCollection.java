package gh.edu.clet.sfl.facilities.waste.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record WasteCollection(UUID id, String reference, String siteCode, UUID streamId, UUID pointId,
        UUID carrierId, UUID destinationId, boolean hazardous, LocalDate scheduledFor, LocalDate collectedOn,
        BigDecimal quantity, String unit, BigDecimal quantityKg, QuantityBasis quantityBasis,
        String manifestReference, String certificateReference, LocalDate certificateReceivedOn,
        boolean contaminated, boolean quantityReconciled, CollectionStatus status, Instant closedAt,
        String createdBy, Instant createdAt, Instant updatedAt, long version) {
}
