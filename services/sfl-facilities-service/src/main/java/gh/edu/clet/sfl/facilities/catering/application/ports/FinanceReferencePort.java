package gh.edu.clet.sfl.facilities.catering.application.ports;

/**
 * The finance and procurement system, as S172 needs it: do these purchase and invoice references match what
 * finance holds. S172 is not the ledger and keeps only the references.
 *
 * <p>No finance integration exists yet, so the one adapter says {@code available=false} and nothing is ever
 * reported as matched. The service records the references and leaves reconciliation visibly pending finance -
 * the SRS error state "finance unavailable - operational delivery remains visible but reconciliation is pending".
 */
public interface FinanceReferencePort {

    Verification verify(String purchaseReference, String invoiceReference);

    record Verification(String provider, boolean available, boolean matched) {
    }
}
