package gh.edu.clet.sfl.fleetlogistics.fleet.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Vendor-neutral vehicle location snapshot for telematics-ready integrations (SRS-SFL-S166-04).
 *
 * <p>A position fed by an integration names the inbox message it came from. One the driver's own phone
 * reported during a trip ({@link #DRIVER_MOBILE}) has no inbox message, and only that kind may omit it.
 */
public record VehicleLocationSnapshot(
        UUID id,
        UUID vehicleId,
        SiteCode siteCode,
        BigDecimal latitude,
        BigDecimal longitude,
        Long odometerValue,
        Instant recordedAt,
        String sourceSystem,
        UUID integrationMessageId,
        String correlationId) {

    /** The source system of a position the assigned driver's phone reported directly to the trip. */
    public static final String DRIVER_MOBILE = "DRIVER_MOBILE";

    public VehicleLocationSnapshot {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(vehicleId, "vehicleId is required");
        Objects.requireNonNull(siteCode, "siteCode is required");
        Objects.requireNonNull(latitude, "latitude is required");
        Objects.requireNonNull(longitude, "longitude is required");
        Objects.requireNonNull(recordedAt, "recordedAt is required");
        sourceSystem = requireText(sourceSystem, "sourceSystem").toUpperCase(java.util.Locale.ROOT);
        if (integrationMessageId == null && !DRIVER_MOBILE.equals(sourceSystem)) {
            throw new NullPointerException("integrationMessageId is required");
        }
    }

    public Map<String, Object> auditImage() {
        return Map.ofEntries(
                Map.entry("locationId", id.toString()),
                Map.entry("vehicleId", vehicleId.toString()),
                Map.entry("siteCode", siteCode.value()),
                Map.entry("latitude", latitude.toPlainString()),
                Map.entry("longitude", longitude.toPlainString()),
                Map.entry("odometerValue", odometerValue == null ? "" : odometerValue),
                Map.entry("recordedAt", recordedAt.toString()),
                Map.entry("sourceSystem", sourceSystem),
                Map.entry("integrationMessageId", integrationMessageId == null ? "" : integrationMessageId.toString()),
                Map.entry("correlationId", correlationId == null ? "" : correlationId));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }
}
