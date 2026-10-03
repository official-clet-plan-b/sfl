package gh.edu.clet.sfl.safetysecurity.permit.infrastructure.integration;

import gh.edu.clet.sfl.safetysecurity.emergency.application.contract.EmergencyActivationObserver;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitFlagService;
import org.springframework.stereotype.Component;

/**
 * The one S164 class that knows S174 exists - SRS-SFL-S164-03 "an emergency fast-lane event (S174) affects a zone with active permits,
 * when declared, then all active permits in that zone are flagged for suspension review". Through S174's published
 * {@link EmergencyActivationObserver}, in the activation's own transaction.
 */
@Component
public class EmergencyPermitFlagAdapter implements EmergencyActivationObserver {

    private final PermitFlagService flags;

    public EmergencyPermitFlagAdapter(PermitFlagService flags) {
        this.flags = flags;
    }

    @Override
    public void activated(ActivatedEmergency emergency) {
        flags.flagZoneEmergency(emergency.siteCode(), emergency.zoneIds(), emergency.activationNumber(), emergency.actor());
    }
}
