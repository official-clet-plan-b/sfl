package gh.edu.clet.sfl.safetysecurity.permit.domain.policy;

import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitErrorCode;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitException;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitType;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** SRS-SFL-S164-03: an extension is checked like a new request - same currency checks - and never extends beyond the type's limit. */
public final class ExtensionPolicy {

    private ExtensionPolicy() {
    }

    public static void require(Permit permit, PermitType type, Instant newEndsAt) {
        if (permit.status() != PermitStatus.ACTIVE) {
            throw PermitException.invalidState(permit.status().name(), "EXTENSION_REQUESTED");
        }
        if (newEndsAt == null || !newEndsAt.isAfter(permit.endsAt())
                || Duration.between(permit.endsAt(), newEndsAt).compareTo(type.maxValidity()) > 0) {
            throw new PermitException(PermitErrorCode.PERMIT_EXTENSION_INVALID, Map.of("currentEnd", permit.endsAt().toString(),
                    "maxHours", type.maxValidityHours()));
        }
    }
}
