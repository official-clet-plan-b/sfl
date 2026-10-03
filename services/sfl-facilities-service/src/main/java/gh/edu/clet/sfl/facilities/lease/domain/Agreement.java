package gh.edu.clet.sfl.facilities.lease.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Agreement(UUID id, String reference, String siteCode, UUID roomId, String propertyReference,
        AgreementKind kind, Direction direction, String title, String counterpartyReference,
        CounterpartyState counterpartyState, String contractReference, String financeReference, String ownerReference,
        LocalDate startDate, LocalDate endDate, RenewalType renewalType, Integer renewalTermMonths, Integer noticeDays,
        LocalDate noticeDate, LocalDate rentReviewDate, BigDecimal annualRent, BigDecimal depositAmount,
        String currency, AgreementStatus status, int versionNumber, String requestedBy, String approvedBy,
        Instant approvedAt, LocalDate terminatedOn, String createdBy, Instant createdAt, Instant updatedAt,
        long version) {

    /** The view of an agreement without the money: for a reader who may see the register but not the figures. */
    public Agreement withoutFinancials() {
        return new Agreement(id, reference, siteCode, roomId, propertyReference, kind, direction, title,
                counterpartyReference, counterpartyState, contractReference, null, ownerReference, startDate, endDate,
                renewalType, renewalTermMonths, noticeDays, noticeDate, rentReviewDate, null, null, null, status,
                versionNumber, requestedBy, approvedBy, approvedAt, terminatedOn, createdBy, createdAt, updatedAt,
                version);
    }
}
