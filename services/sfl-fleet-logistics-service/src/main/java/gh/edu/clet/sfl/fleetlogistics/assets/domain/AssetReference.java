package gh.edu.clet.sfl.fleetlogistics.assets.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public record AssetReference(
        UUID id,
        String assetCode,
        String name,
        AssetCategory category,
        AssetStatus status,
        String siteCode,
        LocationType locationType,
        String locationReference,
        String custodianReference,
        String externalReference,
        String evidenceReference,
        Instant createdAt,
        Instant updatedAt) {

    public AssetReference {
        require(id, "id");
        assetCode = normalizeRequired(assetCode, "assetCode");
        name = requireText(name, "name");
        require(category, "category");
        status = status == null ? AssetStatus.UNKNOWN : status;
        siteCode = normalizeRequired(siteCode, "siteCode");
        require(locationType, "locationType");
        locationReference = normalizeRequired(locationReference, "locationReference");
        custodianReference = normalizeOptional(custodianReference);
        externalReference = normalizeOptional(externalReference);
        evidenceReference = normalizeOptional(evidenceReference);
        require(createdAt, "createdAt");
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public static AssetReference register(UUID id, String assetCode, String name, AssetCategory category,
            String siteCode, LocationType locationType, String locationReference, String custodianReference,
            String externalReference, Instant now) {
        return new AssetReference(id, assetCode, name, category, AssetStatus.ACTIVE, siteCode, locationType,
                locationReference, custodianReference, externalReference, null, now, now);
    }

    public AssetReference moveTo(LocationType newLocationType, String newLocationReference, Instant now) {
        require(newLocationType, "locationType");
        return new AssetReference(id, assetCode, name, category, status, siteCode, newLocationType,
                newLocationReference, custodianReference, externalReference, evidenceReference, createdAt, now);
    }

    public AssetReference assignCustodian(String newCustodianReference, Instant now) {
        return new AssetReference(id, assetCode, name, category, status, siteCode, locationType,
                locationReference, newCustodianReference, externalReference, evidenceReference, createdAt, now);
    }

    /** Attaches (or replaces) the physical tag - an RFID, barcode or QR value - this asset is read by. */
    public AssetReference withTag(String newTag, Instant now) {
        return new AssetReference(id, assetCode, name, category, status, siteCode, locationType,
                locationReference, custodianReference, newTag, evidenceReference, createdAt, now);
    }

    public AssetReference linkEvidence(String newEvidenceReference, Instant now) {
        return new AssetReference(id, assetCode, name, category, status, siteCode, locationType,
                locationReference, custodianReference, externalReference, newEvidenceReference, createdAt, now);
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    private static String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip().toUpperCase(Locale.ROOT);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}