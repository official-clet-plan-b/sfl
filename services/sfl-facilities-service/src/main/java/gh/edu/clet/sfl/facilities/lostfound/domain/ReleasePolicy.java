package gh.edu.clet.sfl.facilities.lostfound.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Everything that stands between a claim and the item leaving the building - SRS-SFL-S179 validation "release
 * requires identity verification and recorded acceptance or refusal", and the error states for competing
 * claims, unverified identity and an unsafe item.
 *
 * <p>Returns every blocker, not the first, so the refusal can say all of them and the record of the refusal is
 * complete. An empty list means release may go ahead.
 */
public final class ReleasePolicy {

    private ReleasePolicy() {
    }

    public static List<String> blockers(FoundItem item, Claim claim, Collection<Claim> otherOpenClaims,
            Collection<LfEvidence> evidence) {
        List<String> blockers = new ArrayList<>();
        if (item.status() == ItemStatus.ISOLATED || item.unsafe()) {
            blockers.add("The item is isolated as unsafe.");
        } else if (item.status() != ItemStatus.STORED) {
            blockers.add("The item is " + item.status() + ", not in storage.");
        }
        if (!claim.identityVerified()) {
            blockers.add("The claimant's identity has not been verified.");
        }
        if (claim.status() != ClaimStatus.APPROVED) {
            blockers.add("The release has not been approved.");
        }
        if (!otherOpenClaims.isEmpty()) {
            blockers.add("Another claim is open on this item; release is blocked pending investigation.");
        }
        boolean receipt = evidence.stream().anyMatch(e -> e.kind() == EvidenceKind.RELEASE_RECEIPT
                && claim.id().equals(e.claimId()));
        if (!receipt) {
            blockers.add("No release receipt has been filed for this claim.");
        }
        return blockers;
    }
}
