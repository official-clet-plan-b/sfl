package gh.edu.clet.sfl.facilities.waste.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * When a collection's chain of custody may be closed - SRS-SFL-S178-03 and the validation rule "hazardous
 * waste cannot close without carrier and destination evidence".
 *
 * <p>Returns what is missing rather than a yes/no, so the refusal can say exactly what keeps the chain
 * open and the escalation can name it. Accepting a piece of evidence is a decision by someone other than
 * the person who filed it; merely submitting one counts for nothing here.
 */
public final class ChainPolicy {

    private ChainPolicy() {
    }

    public record Gap(ExceptionType type, String message) {
    }

    public static List<Gap> gaps(WasteCollection collection, Collection<WasteEvidence> evidence,
            Collection<WasteException> openExceptions) {
        List<Gap> gaps = new ArrayList<>();
        boolean receiving = accepted(evidence, EvidenceKind.RECEIVING);
        boolean certificate = accepted(evidence, EvidenceKind.CERTIFICATE);
        if (collection.destinationId() == null) {
            gaps.add(new Gap(ExceptionType.UNAPPROVED_DESTINATION, "No approved destination has been recorded."));
        }
        if (collection.hazardous() && !receiving) {
            gaps.add(new Gap(ExceptionType.MISSING_RECEIVING_EVIDENCE,
                    "Hazardous waste needs accepted receiving evidence from the destination."));
        }
        if (collection.hazardous() && collection.manifestReference() == null) {
            gaps.add(new Gap(ExceptionType.MISSING_RECEIVING_EVIDENCE, "Hazardous waste needs a manifest reference."));
        }
        if (!certificate || collection.certificateReference() == null) {
            gaps.add(new Gap(ExceptionType.MISSING_CERTIFICATE,
                    "The treatment or recycling certificate has not been recorded and accepted."));
        }
        if (collection.contaminated() && !collection.quantityReconciled()) {
            gaps.add(new Gap(ExceptionType.CONTAMINATION,
                    "The stream was contaminated and its quantity is not yet reconciled."));
        }
        boolean otherOpen = openExceptions.stream().anyMatch(e -> e.exceptionType() == ExceptionType.SPILL
                || e.exceptionType() == ExceptionType.CONTAMINATION);
        if (otherOpen && !gaps.stream().anyMatch(g -> g.type() == ExceptionType.CONTAMINATION)) {
            gaps.add(new Gap(ExceptionType.CONTAMINATION, "A spill or contamination exception is still open."));
        }
        return gaps;
    }

    private static boolean accepted(Collection<WasteEvidence> evidence, EvidenceKind kind) {
        return evidence.stream().anyMatch(e -> e.kind() == kind && e.status() == EvidenceStatus.ACCEPTED);
    }
}
