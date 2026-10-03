package gh.edu.clet.sfl.facilities.lease.application.ports;

/**
 * The authoritative records an agreement points at - the contract (S136), the counterparty, the finance and
 * procurement reference - checked rather than copied: S177 holds references, never the master records.
 *
 * <p>None of those systems is integrated yet, so the one adapter says {@code available=false} every time. The
 * service then leaves the counterparty UNRESOLVED and nothing is ever treated as verified because of it - the SRS
 * error state "counterparty unavailable - reference remains unresolved and cannot be treated as verified".
 */
public interface LeaseReferencePort {

    Verification verifyCounterparty(String counterpartyReference);

    record Verification(String provider, boolean available, boolean verified) {
    }
}
