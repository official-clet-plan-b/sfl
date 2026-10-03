package gh.edu.clet.sfl.facilities.lease.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Amendment(UUID id, String reference, UUID agreementId, String siteCode, AmendmentKind kind,
        boolean material, AmendmentStatus status, String reason, LocalDate newEndDate, BigDecimal newAnnualRent,
        BigDecimal newDepositAmount, Integer newNoticeDays, LocalDate newRentReviewDate,
        Integer newRenewalTermMonths, LocalDate effectiveOn, int priorVersion, String proposedBy, Instant proposedAt,
        String decidedBy, Instant decidedAt, String decisionReason, String legalReviewNote, String legalClearedBy,
        Instant legalClearedAt, long version) {

    /** The named fields this amendment changes, so two amendments can be tested for overlap. */
    public java.util.Set<String> touched() {
        java.util.Set<String> fields = new java.util.HashSet<>();
        if (kind == AmendmentKind.TERMINATION) {
            fields.add("*");
            return fields;
        }
        if (newEndDate != null) fields.add("endDate");
        if (newAnnualRent != null) fields.add("annualRent");
        if (newDepositAmount != null) fields.add("depositAmount");
        if (newNoticeDays != null) fields.add("noticeDays");
        if (newRentReviewDate != null) fields.add("rentReviewDate");
        if (newRenewalTermMonths != null) fields.add("renewalTermMonths");
        return fields;
    }

    public Amendment withoutFinancials() {
        return new Amendment(id, reference, agreementId, siteCode, kind, material, status, reason, newEndDate, null,
                null, newNoticeDays, newRentReviewDate, newRenewalTermMonths, effectiveOn, priorVersion, proposedBy,
                proposedAt, decidedBy, decidedAt, decisionReason, legalReviewNote, legalClearedBy, legalClearedAt,
                version);
    }
}
