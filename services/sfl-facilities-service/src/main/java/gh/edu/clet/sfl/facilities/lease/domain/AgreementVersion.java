package gh.edu.clet.sfl.facilities.lease.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The terms as they stood at one version, with who approved them - the prior version is never overwritten. */
public record AgreementVersion(UUID id, UUID agreementId, String siteCode, int versionNumber, LocalDate endDate,
        BigDecimal annualRent, BigDecimal depositAmount, Integer noticeDays, LocalDate rentReviewDate,
        Integer renewalTermMonths, String ownerReference, String counterpartyReference, AgreementStatus status,
        UUID amendmentId, String approvedBy, Instant approvedAt, String recordedBy, Instant recordedAt) {

    public AgreementVersion withoutFinancials() {
        return new AgreementVersion(id, agreementId, siteCode, versionNumber, endDate, null, null, noticeDays,
                rentReviewDate, renewalTermMonths, ownerReference, counterpartyReference, status, amendmentId,
                approvedBy, approvedAt, recordedBy, recordedAt);
    }
}
