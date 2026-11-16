package gh.edu.clet.sfl.fleetlogistics.fuel.domain.model;

import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.RecordMetadata;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.SiteCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A fuel card CLET issued - SRS-SFL-S168fuel-04.
 *
 * <p>Until this existed, {@code FuelTransaction.maskedCardReference} was a string with nothing
 * behind it: the platform could tell you which card was used and could answer no useful question
 * about it. Anti-fraud control is the stated purpose of S168_fuel in the C9 mapping, and the
 * commonest fuel fraud is a card assigned to one vehicle being used to fill another - which is
 * undetectable without a row saying which vehicle the card belongs to.
 *
 * <p><strong>The full card number is never held.</strong> {@link #maskedReference} is the same
 * masked form the provider already sends on every transaction. It is enough to match a transaction
 * to a card and not enough to use one, and the card platform stays outside SFL where the mapping
 * puts it.
 *
 * <p><strong>Limits are nullable and mean "the site policy decides".</strong> A card that overrides
 * its site's ceiling should have done so deliberately; a card with no opinion should follow the
 * policy rather than carry a copy of it that drifts when the policy changes.
 */
public record FuelCard(
        UUID id,
        SiteCode siteCode,
        String maskedReference,
        String provider,
        UUID vehicleId,
        UUID driverId,
        Status status,
        LocalDate issuedOn,
        LocalDate expiresOn,
        BigDecimal dailyLimit,
        BigDecimal monthlyLimit,
        BigDecimal perTransactionLimit,
        String suspensionReason,
        String notes,
        RecordMetadata metadata) {

    public enum Status {
        ACTIVE,
        /** Temporarily stopped - a lost card, a driver under investigation. Reversible. */
        SUSPENDED,
        /** Terminal. The card is gone; the row stays so historic transactions still resolve. */
        CANCELLED
    }

    public FuelCard {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(siteCode, "siteCode is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(metadata, "metadata is required");
        Objects.requireNonNull(issuedOn, "issuedOn is required");
        maskedReference = requireText(maskedReference, "maskedReference");
        provider = requireText(provider, "provider");
        if (expiresOn != null && expiresOn.isBefore(issuedOn)) {
            throw new IllegalArgumentException("a card cannot expire before it was issued");
        }
        if (status == Status.SUSPENDED
                && (suspensionReason == null || suspensionReason.isBlank())) {
            // A suspended card the holder cannot be given a reason for is an argument at a filling
            // station, so the reason is required at the point the state is entered.
            throw new IllegalArgumentException("a suspended card must record why");
        }
        requirePositive(dailyLimit, "dailyLimit");
        requirePositive(monthlyLimit, "monthlyLimit");
        requirePositive(perTransactionLimit, "perTransactionLimit");
    }

    public static FuelCard issue(
            UUID id,
            SiteCode siteCode,
            String maskedReference,
            String provider,
            UUID vehicleId,
            UUID driverId,
            LocalDate issuedOn,
            LocalDate expiresOn,
            BigDecimal dailyLimit,
            BigDecimal monthlyLimit,
            BigDecimal perTransactionLimit,
            String notes,
            RecordMetadata metadata) {
        return new FuelCard(
                id,
                siteCode,
                requireMaskedForm(maskedReference),
                provider,
                vehicleId,
                driverId,
                Status.ACTIVE,
                issuedOn,
                expiresOn,
                dailyLimit,
                monthlyLimit,
                perTransactionLimit,
                null,
                notes,
                metadata);
    }

    /**
     * Reassignment is one move, not an unassign followed by an assign - the card is never unheld.
     */
    public FuelCard assignTo(UUID newVehicleId, UUID newDriverId, RecordMetadata newMetadata) {
        requireNotCancelled("reassigned");
        return new FuelCard(
                id,
                siteCode,
                maskedReference,
                provider,
                newVehicleId,
                newDriverId,
                status,
                issuedOn,
                expiresOn,
                dailyLimit,
                monthlyLimit,
                perTransactionLimit,
                suspensionReason,
                notes,
                newMetadata);
    }

    public FuelCard suspend(String reason, RecordMetadata newMetadata) {
        requireNotCancelled("suspended");
        return new FuelCard(
                id,
                siteCode,
                maskedReference,
                provider,
                vehicleId,
                driverId,
                Status.SUSPENDED,
                issuedOn,
                expiresOn,
                dailyLimit,
                monthlyLimit,
                perTransactionLimit,
                reason,
                notes,
                newMetadata);
    }

    public FuelCard reinstate(RecordMetadata newMetadata) {
        requireNotCancelled("reinstated");
        return new FuelCard(
                id,
                siteCode,
                maskedReference,
                provider,
                vehicleId,
                driverId,
                Status.ACTIVE,
                issuedOn,
                expiresOn,
                dailyLimit,
                monthlyLimit,
                perTransactionLimit,
                null,
                notes,
                newMetadata);
    }

    /**
     * Terminal, and the row survives it.
     *
     * <p>A cancelled card is not deleted because a transaction from three years ago still has to
     * resolve to the card that made it. The partial unique index lets its masked reference be
     * reissued while the history keeps pointing at this row.
     */
    public FuelCard cancel(String reason, RecordMetadata newMetadata) {
        return new FuelCard(
                id,
                siteCode,
                maskedReference,
                provider,
                vehicleId,
                driverId,
                Status.CANCELLED,
                issuedOn,
                expiresOn,
                dailyLimit,
                monthlyLimit,
                perTransactionLimit,
                reason,
                notes,
                newMetadata);
    }

    /**
     * Whether this card may be used on this date.
     *
     * <p>Expiry is checked here rather than by a sweep that flips status, because a card's expiry
     * is a fact about the card and not an event that needs to have happened. A sweep would make
     * "expired" depend on whether the sweep ran.
     */
    public boolean usableOn(LocalDate date) {
        return status == Status.ACTIVE
                && !date.isBefore(issuedOn)
                && (expiresOn == null || !date.isAfter(expiresOn));
    }

    /** {@code true} when the card is held by a different vehicle than the one that was filled. */
    public boolean mismatchesVehicle(UUID filledVehicleId) {
        return vehicleId != null && filledVehicleId != null && !vehicleId.equals(filledVehicleId);
    }

    private void requireNotCancelled(String action) {
        if (status == Status.CANCELLED) {
            throw new IllegalStateException("A cancelled fuel card cannot be " + action);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    private static void requirePositive(BigDecimal value, String field) {
        if (value != null && value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be greater than zero when set");
        }
    }

    /** The provider's masked form: asterisks then the last four digits, such as ****1234. */
    private static final java.util.regex.Pattern MASKED_FORM =
            java.util.regex.Pattern.compile("^\\*{2,}\\d{4}$");

    /**
     * A new card must arrive already masked. A full card number is refused outright (it must never
     * be stored), and anything else is refused because a transaction's masked reference could never
     * match it, so the card-known check would fail every purchase made with it. Checked when a card
     * is issued, not on read, so rows recorded before this rule still load.
     */
    static String requireMaskedForm(String maskedReference) {
        String value = requireText(maskedReference, "maskedReference");
        if (value.replaceAll("\\s+", "").matches(".*\\d{12,}.*")) {
            throw new IllegalArgumentException(
                    "Enter only the masked card reference from the provider, never a full card number");
        }
        if (!MASKED_FORM.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "The card reference must be the provider's masked form, such as ****1234");
        }
        return value;
    }
}
