package gh.edu.clet.sfl.facilities.catering.infrastructure.integration;

import gh.edu.clet.sfl.facilities.catering.application.ports.FinanceReferencePort;
import org.springframework.stereotype.Component;

/**
 * There is no finance or procurement integration yet. This adapter says so, every time: nothing is matched,
 * nothing is verified, and a reconciliation can never appear finance-approved because of it. When finance is
 * integrated, this is the one class that changes.
 */
@Component
public class UnavailableFinanceAdapter implements FinanceReferencePort {

    static final String PROVIDER = "FINANCE-NOT-INTEGRATED";

    @Override
    public Verification verify(String purchaseReference, String invoiceReference) {
        return new Verification(PROVIDER, false, false);
    }
}
