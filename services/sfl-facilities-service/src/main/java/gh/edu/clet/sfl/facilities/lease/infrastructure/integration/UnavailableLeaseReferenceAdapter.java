package gh.edu.clet.sfl.facilities.lease.infrastructure.integration;

import gh.edu.clet.sfl.facilities.lease.application.ports.LeaseReferencePort;
import org.springframework.stereotype.Component;

/** No contract, counterparty or finance system is integrated yet: this says so every time, and nothing is ever verified. */
@Component
public class UnavailableLeaseReferenceAdapter implements LeaseReferencePort {

    static final String PROVIDER = "S136-FINANCE-NOT-INTEGRATED";

    @Override
    public Verification verifyCounterparty(String counterpartyReference) {
        return new Verification(PROVIDER, false, false);
    }
}
