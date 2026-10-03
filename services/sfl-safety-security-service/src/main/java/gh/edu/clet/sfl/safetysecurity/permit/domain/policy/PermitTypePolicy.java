package gh.edu.clet.sfl.safetysecurity.permit.domain.policy;

import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitErrorCode;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitException;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitRiskLevel;

/** A type of higher risk cannot be configured to skip the independent safety sign-off (S164-02). */
public final class PermitTypePolicy {

    private PermitTypePolicy() {
    }

    public static void require(PermitRiskLevel riskLevel, boolean twoStage, int maxValidityHours) {
        if (riskLevel == null) {
            throw PermitException.invalid("riskLevel is required");
        }
        if (riskLevel.requiresTwoStage() && !twoStage) {
            throw new PermitException(PermitErrorCode.PERMIT_TYPE_INVALID);
        }
        if (maxValidityHours < 1 || maxValidityHours > 720) {
            throw PermitException.invalid("maxValidityHours must be between 1 and 720");
        }
    }
}
