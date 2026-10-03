package gh.edu.clet.sfl.facilities.lease.application.ports;

/**
 * Whether an internal owner is a person HR knows (S140) - checked, never copied: S177 holds the reference and nothing
 * else about them. HRMS is not integrated yet, so the one adapter answers {@code available=false} and an owner is never
 * shown as verified because of it.
 */
public interface LeaseOwnerPort {

    Verification verifyOwner(String ownerReference);

    record Verification(String provider, boolean available, boolean verified) {
    }
}
