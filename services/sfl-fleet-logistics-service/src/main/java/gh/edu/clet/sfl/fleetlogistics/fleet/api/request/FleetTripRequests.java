package gh.edu.clet.sfl.fleetlogistics.fleet.api.request;

import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DefectSeverity;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.InspectionType;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.OperatingMode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.TripAcknowledgementState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request bodies for the trip endpoints (SRS-SFL-S166-02). */
public final class FleetTripRequests {

    private FleetTripRequests() {
    }

    /** {@code POST /api/v1/fleet/trips}. Vehicle and driver are optional: a trip may be planned first. */
    public record CreateTrip(
            UUID vehicleId,
            UUID driverId,
            @NotBlank @Size(max = 40) String siteCode,
            @NotBlank @Size(max = 500) String purpose,
            @NotBlank @Size(max = 200) String origin,
            @NotBlank @Size(max = 200) String destination,
            @NotNull OperatingMode operatingMode,
            @NotNull Instant plannedStart,
            @NotNull Instant plannedEnd) {
    }

    /** {@code PATCH /api/v1/fleet/trips/{tripId}/assignment}. */
    public record AssignTrip(
            @NotNull UUID vehicleId,
            @NotNull UUID driverId,
            @Size(max = 1000) String reason,
            Long expectedVersion) {
    }

    /** {@code PATCH /api/v1/fleet/trips/{tripId}/start}. */
    public record StartTrip(
            @NotNull @PositiveOrZero Long startOdometer,
            Long expectedVersion) {
    }

    /**
     * {@code POST /api/v1/fleet/trips/{tripId}/location}. The device's own reading, unadjusted.
     *
     * <p>Bounded to real coordinates rather than merely non-null: a malformed or spoofed value here
     * would otherwise land silently in the same read a dispatcher trusts for "where is the vehicle".
     */
    public record ReportLocation(
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude) {
    }

    /** {@code PATCH /api/v1/fleet/trips/{tripId}/hold}. */
    public record HoldTrip(
            @NotNull HoldAction action,
            @Size(max = 1000) String reason,
            Long expectedVersion) {

        public enum HoldAction {
            HOLD,
            RESUME
        }
    }

    /**
     * {@code PATCH /api/v1/fleet/trips/{tripId}/acknowledgement}.
     *
     * <p>{@code answer} accepts {@code CONFIRMED} or {@code DEFERRED} only - {@code PENDING} is the
     * state an assignment starts in, not an answer a driver can give, and allowing it would let a
     * driver erase their own deferral and the reason with it.
     *
     * <p>The "a deferral needs a reason" rule is <em>not</em> expressed here as a bean-validation
     * annotation. It is a cross-field rule, and enforcing it at the edge would mean the aggregate
     * still had to enforce it for every other path - two implementations of one rule, which drift.
     * {@code TripAcknowledgement} owns it; this layer only bounds the length.
     */
    public record AcknowledgeTrip(
            @NotNull TripAcknowledgementState answer,
            @Size(max = 1000) String reason,
            Long expectedVersion) {

        public AcknowledgeTrip {
            if (answer == TripAcknowledgementState.PENDING) {
                throw new IllegalArgumentException(
                        "answer must be CONFIRMED or DEFERRED; PENDING is not an answer");
            }
        }
    }

    /** {@code PATCH /api/v1/fleet/trips/{tripId}/cancel}. */
    public record CancelTrip(
            @NotBlank @Size(max = 1000) String reason,
            Long expectedVersion) {
    }

    /** {@code PATCH /api/v1/fleet/trips/{tripId}/closure}. Reason and evidence are both mandatory. */
    public record CloseTrip(
            @NotBlank @Size(max = 1000) String closureReason,
            @NotNull UUID closureEvidenceId,
            @NotNull @PositiveOrZero Long endOdometer,
            Long expectedVersion) {
    }

    /** {@code POST /api/v1/fleet/trips/{tripId}/inspections}. */
    public record RecordInspection(
            @NotNull InspectionType inspectionType,
            @NotNull @PositiveOrZero Long odometerReading,
            UUID evidenceId,
            @Valid List<FindingRequest> findings,
            @Size(max = 2000) String notes) {
    }

    /** One checklist finding submitted with an inspection. */
    public record FindingRequest(
            @NotBlank @Size(max = 80) String checkCode,
            @NotBlank @Size(max = 1000) String description,
            @NotNull DefectSeverity severity) {
    }

    /** {@code POST /api/v1/fleet/vehicles/{vehicleId}/inspections} for a standalone periodic check. */
    public record RecordStandaloneInspection(
            @NotNull InspectionType inspectionType,
            @NotNull @PositiveOrZero Long odometerReading,
            UUID evidenceId,
            @Valid @NotEmpty List<FindingRequest> findings,
            @Size(max = 2000) String notes) {
    }
}
