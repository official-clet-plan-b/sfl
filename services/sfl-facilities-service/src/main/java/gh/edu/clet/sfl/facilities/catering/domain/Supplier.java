package gh.edu.clet.sfl.facilities.catering.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Supplier(UUID id, String code, String name, String certificateReference, LocalDate certificateExpiresOn,
        String financeReference, SupplierStatus status, String createdBy, Instant createdAt, Instant updatedAt,
        long version) {
}
