package gh.edu.clet.sfl.facilities.lease.infrastructure.integration;

import gh.edu.clet.sfl.facilities.lease.application.ports.LeaseOwnerPort;
import org.springframework.stereotype.Component;

/** HRMS (S140) is not integrated yet: this says so every time, and no owner is ever verified. */
@Component
public class UnavailableLeaseOwnerAdapter implements LeaseOwnerPort {

    static final String PROVIDER = "S140-HRMS-NOT-INTEGRATED";

    @Override
    public Verification verifyOwner(String ownerReference) {
        return new Verification(PROVIDER, false, false);
    }
}
