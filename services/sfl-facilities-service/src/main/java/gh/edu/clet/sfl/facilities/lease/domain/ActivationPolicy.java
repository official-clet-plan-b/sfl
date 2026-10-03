package gh.edu.clet.sfl.facilities.lease.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What an agreement needs before it can be active - SRS validation "no agreement can be active without an
 * owning site, counterparty reference, term and approval evidence" - and what is merely worth knowing.
 *
 * <p>The notice period is also required: without it no notice date, and so no notice alert, can exist, which is
 * the SRS error state "missing notice date - agreement is flagged incomplete". An unresolved counterparty is a
 * warning, not a blocker: the SRS says only that it cannot be <em>treated as verified</em>.
 */
public final class ActivationPolicy {

    private ActivationPolicy() {
    }

    public record Readiness(List<String> blockers, List<String> warnings) {
        public boolean ready() {
            return blockers.isEmpty();
        }
    }

    public static Readiness assess(Agreement agreement, Collection<LeaseDocument> documents) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (agreement.siteCode() == null || agreement.siteCode().isBlank()) {
            blockers.add("An owning site is required.");
        }
        if (agreement.counterpartyReference() == null || agreement.counterpartyReference().isBlank()) {
            blockers.add("A counterparty reference is required.");
        }
        if (agreement.ownerReference() == null || agreement.ownerReference().isBlank()) {
            blockers.add("An internal owner is required, so there is someone to tell when a date approaches.");
        }
        if (agreement.endDate() == null || agreement.startDate() == null || !agreement.endDate().isAfter(agreement.startDate())) {
            blockers.add("A term with an end date after its start date is required.");
        }
        if (agreement.noticeDays() == null) {
            blockers.add("The notice period is missing, so no notice date can be calculated; the agreement is incomplete.");
        }
        if (documents.stream().noneMatch(d -> d.kind() == DocumentKind.APPROVAL_EVIDENCE)) {
            blockers.add("Approval evidence must be filed.");
        }
        if (agreement.counterpartyState() != CounterpartyState.VERIFIED) {
            warnings.add("The counterparty reference is unresolved: it is recorded but not verified against an authoritative record.");
        }
        if (agreement.contractReference() == null) {
            warnings.add("No contract reference (S136) is linked.");
        }
        if (agreement.financeReference() == null) {
            warnings.add("No finance reference is linked.");
        }
        return new Readiness(blockers, warnings);
    }
}
