package gh.edu.clet.sfl.facilities.hygiene.domain;

import java.util.Collection;

/**
 * When a finding may be closed - SRS-SFL-S170-04 "closure requires evidence".
 *
 * <p>Two routes, nothing else. <strong>Evidence</strong>: at least one piece of evidence has been
 * accepted by a reviewer, and every corrective action is verified. <strong>Exception</strong>: someone
 * with authority to verify accepts the finding as it stands, on the record, with a reason. Merely
 * submitting evidence, or having every action ticked, closes nothing; and an exception can never be
 * approved by the person who asked for it.
 */
public final class ClosurePolicy {

    private ClosurePolicy() {
    }

    public static boolean closableByEvidence(Collection<HygieneEvidence> evidence, Collection<HygieneAction> actions) {
        boolean accepted = evidence.stream().anyMatch(item -> item.status() == EvidenceStatus.ACCEPTED);
        boolean actionsDone = actions.stream().allMatch(action -> action.status() == ActionStatus.VERIFIED);
        return accepted && actionsDone;
    }

    public static boolean validException(String reason, String requestedBy, String approvedBy) {
        return reason != null && reason.strip().length() >= 10 && approvedBy != null && !approvedBy.equals(requestedBy);
    }
}
