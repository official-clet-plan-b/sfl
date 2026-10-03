package gh.edu.clet.sfl.safetysecurity.permit.domain.policy;

import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitErrorCode;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitException;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Isolation;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.IsolationStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitStatus;
import java.util.List;
import java.util.Map;

/**
 * SRS-SFL-S164-04: work completion and isolation removal are separate acts. Completion needs a statement and evidence;
 * closing needs every isolation removed, recorded by someone other than the requester - the two-person rule at issuance,
 * mirrored.
 */
public final class ClosurePolicy {

    private ClosurePolicy() {
    }

    public static void requireCanComplete(Permit permit, String statement, long evidence) {
        if (permit.status() != PermitStatus.ACTIVE && permit.status() != PermitStatus.SUSPENDED) {
            throw PermitException.invalidState(permit.status().name(), "WORK_COMPLETE");
        }
        if (statement == null || statement.isBlank()) {
            throw new PermitException(PermitErrorCode.PERMIT_COMPLETION_STATEMENT_REQUIRED);
        }
        if (evidence < 1) {
            throw new PermitException(PermitErrorCode.PERMIT_EVIDENCE_REQUIRED);
        }
    }

    public static void requireCanRemove(Permit permit, Isolation isolation, String remover) {
        if (permit.status() != PermitStatus.WORK_COMPLETE) {
            throw PermitException.invalidState(permit.status().name(), "ISOLATION_REMOVED");
        }
        if (isolation.status() != IsolationStatus.VERIFIED) {
            throw PermitException.invalidState(isolation.status().name(), IsolationStatus.REMOVED.name());
        }
        if (permit.requestedBy().equals(remover)) {
            throw new PermitException(PermitErrorCode.PERMIT_REMOVER_NOT_INDEPENDENT);
        }
    }

    public static void requireCanClose(Permit permit, List<Isolation> isolations) {
        if (permit.status() != PermitStatus.WORK_COMPLETE) {
            throw PermitException.invalidState(permit.status().name(), "CLOSED");
        }
        long remaining = isolations.stream().filter(i -> i.status() != IsolationStatus.REMOVED).count();
        if (remaining > 0) {
            throw new PermitException(PermitErrorCode.PERMIT_ISOLATION_REMOVAL_NOT_RECORDED, Map.of("remaining", remaining));
        }
    }
}
