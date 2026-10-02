package gh.edu.clet.sfl.fleetlogistics.assets.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import gh.edu.clet.sfl.common.security.SiteScopeGuc;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;

import gh.edu.clet.sfl.fleetlogistics.assets.application.ports.AssetHistoryRepository;
import gh.edu.clet.sfl.fleetlogistics.assets.application.ports.AssetReferenceRepository;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetChangeSource;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetChangeType;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetHistoryEntry;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetReference;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.LocationType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetVisibilityService {

    /** A reader's clock may drift a little ahead of ours; a read stamped further out than this is wrong. */
    private static final Duration SCAN_CLOCK_TOLERANCE = Duration.ofMinutes(5);

    private final AssetReferenceRepository assets;
    private final AssetHistoryRepository history;
    private final ServiceOutbox outbox;
    private final Clock clock;

    public AssetVisibilityService(AssetReferenceRepository assets, AssetHistoryRepository history,
            ServiceOutbox outbox, Clock clock) {
        this.assets = assets;
        this.history = history;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public AssetReference register(RegisterAssetCommand command) {
        String assetCode = normalize(command.assetCode(), "assetCode");
        assets.findByAssetCode(assetCode).ifPresent(existing -> {
            throw new IllegalArgumentException("Asset already exists: " + assetCode);
        });
        String tag = normalizeTag(command.externalReference());
        if (tag != null) {
            requireTagFree(tag, null);
        }
        AssetReference asset = AssetReference.register(UUID.randomUUID(), assetCode, command.name(),
                command.category(), command.siteCode(), command.locationType(), command.locationReference(),
                command.custodianReference(), command.externalReference(), clock.instant());
        AssetReference saved = assets.save(asset);
        record("sfl.avamp.asset-registered.v1", "AssetReference", saved, command.actor(), command.correlationId());
        Instant now = clock.instant();
        remember(saved, AssetChangeType.REGISTERED, null, location(saved), AssetChangeSource.MANUAL, null,
                command.actor(), now);
        if (saved.externalReference() != null) {
            remember(saved, AssetChangeType.TAG_ASSIGNED, null, saved.externalReference(), AssetChangeSource.MANUAL,
                    null, command.actor(), now);
        }
        return saved;
    }

    @Transactional
    public AssetReference move(MoveAssetCommand command) {
        AssetReference asset = requireAsset(command.assetId());
        return relocate(asset, command.locationType(), command.locationReference(), AssetChangeSource.MANUAL, null,
                clock.instant(), command.actor(), command.correlationId());
    }

    /**
     * Hands an asset to a custodian, or to nobody when {@code custodianReference} is blank.
     *
     * <p>Naming the custodian it already has changes nothing and writes nothing. The screen sends a
     * custody update alongside every location update, so without this each move would append a
     * custody entry saying the asset passed from somebody to the same person.
     */
    @Transactional
    public AssetReference assignCustody(AssignCustodyCommand command) {
        AssetReference asset = requireAsset(command.assetId());
        AssetReference changed = asset.assignCustodian(command.custodianReference(), clock.instant());
        if (Objects.equals(asset.custodianReference(), changed.custodianReference())) {
            return asset;
        }
        AssetReference saved = assets.save(changed);
        record("sfl.avamp.asset-custody-changed.v1", "AssetReference", saved, command.actor(), command.correlationId());
        remember(saved, AssetChangeType.CUSTODY_CHANGED, asset.custodianReference(), saved.custodianReference(),
                AssetChangeSource.MANUAL, null, command.actor(), clock.instant());
        return saved;
    }

    /**
     * Gives an asset the physical tag it will be read by, replacing any tag it had.
     *
     * <p>A tag identifies exactly one asset, so one already carried by another is refused rather than
     * moved - silently re-pointing a tag would make the first asset invisible to every reader.
     */
    @Transactional
    public AssetReference assignTag(AssignTagCommand command) {
        AssetReference asset = requireAsset(command.assetId());
        String tag = normalizeTag(command.tagId());
        if (tag == null) {
            throw new IllegalArgumentException("tagId is required");
        }
        if (tag.equalsIgnoreCase(asset.externalReference())) {
            return asset;
        }
        requireTagFree(tag, asset.id());
        AssetReference saved = assets.save(asset.withTag(tag, clock.instant()));
        record("sfl.avamp.asset-tagged.v1", "AssetReference", saved, command.actor(), command.correlationId());
        remember(saved, AssetChangeType.TAG_ASSIGNED, asset.externalReference(), tag, AssetChangeSource.MANUAL, null,
                command.actor(), clock.instant());
        return saved;
    }

    /**
     * Applies a reader's sighting of a tag: the asset carrying it is now at the place the reader says.
     *
     * <p>This is the one entry point hardware connects to, and it is deliberately the same move a
     * person makes at the screen - recorded as a {@code READER} change naming the reader, so history
     * shows which of the two put the asset where it is. A tag nobody registered is a 404, never an
     * invented asset. Seeing an asset where it already is changes nothing.
     */
    @Transactional
    public AssetReference recordScan(RecordScanCommand command) {
        AssetReference asset = findByTag(command.tagId());
        Instant now = clock.instant();
        Instant seenAt = command.occurredAt() == null ? now : command.occurredAt();
        if (seenAt.isAfter(now.plus(SCAN_CLOCK_TOLERANCE))) {
            throw new IllegalArgumentException("occurredAt is in the future");
        }
        return relocate(asset, command.locationType(), command.locationReference(), AssetChangeSource.READER,
                command.readerId(), seenAt, command.actor(), command.correlationId());
    }

    @Transactional(readOnly = true)
    public AssetReference findByTag(String tagId) {
        String tag = normalizeTag(tagId);
        return (tag == null ? java.util.Optional.<AssetReference>empty() : assets.findByExternalReference(tag))
                .orElseThrow(() -> new NoSuchElementException("No asset carries tag: " + tagId));
    }

    /** Every change to the asset, newest first. */
    @Transactional(readOnly = true)
    public List<AssetHistoryEntry> history(UUID assetId) {
        requireAsset(assetId);
        return history.findByAssetId(assetId);
    }

    @Transactional
    public AssetReference linkEvidence(LinkEvidenceCommand command) {
        AssetReference asset = requireAsset(command.assetId());
        AssetReference saved = assets.save(asset.linkEvidence(command.evidenceReference(), clock.instant()));
        record("sfl.avamp.asset-evidence-linked.v1", "AssetReference", saved, command.actor(), command.correlationId());
        remember(saved, AssetChangeType.EVIDENCE_LINKED, asset.evidenceReference(), saved.evidenceReference(),
                AssetChangeSource.MANUAL, null, command.actor(), clock.instant());
        return saved;
    }

    @Transactional(readOnly = true)
    public AssetReference findById(UUID id) {
        return requireAsset(id);
    }

    @Transactional(readOnly = true)
    public List<AssetReference> findAll(String siteCode) {
        return assets.findAll(siteCode);
    }

    /**
     * The register narrowed to the sites an actor holds.
     *
     * <p>The cross-site scope is {@code *}, matching {@code SiteScopeGuc.ALL_SITES} and
     * {@code crossProgrammeRoles} - one spelling of "everywhere" across the platform rather than
     * three. Anything else is filtered in SQL.
     */
    @Transactional(readOnly = true)
    public List<AssetReference> findAllInScope(Set<String> siteScopes) {
        if (siteScopes != null && siteScopes.contains(SiteScopeGuc.ALL_SITES)) {
            return assets.findAll(null);
        }
        return assets.findAllInScope(siteScopes);
    }

    @Transactional(readOnly = true)
    public List<AssetReference> findByLocation(String siteCode, LocationType locationType, String locationReference) {
        return assets.findByLocation(siteCode, locationType, locationReference);
    }

    /**
     * An asset that is not there is a 404, not a 400.
     *
     * <p>This threw {@link IllegalArgumentException} and so answered <strong>400</strong> - telling a
     * client its request was malformed when the request was perfectly well-formed and the asset simply
     * does not exist. A client cannot distinguish "you sent nonsense" from "that id is gone", and only
     * one of those is worth retrying.
     */
    private AssetReference requireAsset(UUID id) {
        return assets.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Asset was not found: " + id));
    }

    private void record(String eventType, String aggregateType, AssetReference asset, String actor, String correlationId) {
        outbox.record(eventType, 1, aggregateType, asset.id(), asset.siteCode(), correlationId,
                actorOrDevelopment(actor), asset);
    }

    private String actorOrDevelopment(String actor) {
        return actor == null || actor.isBlank() ? "development-user" : actor;
    }

    private AssetReference relocate(AssetReference asset, LocationType type, String reference,
            AssetChangeSource source, String sourceReference, Instant occurredAt, String actor,
            String correlationId) {
        AssetReference moved = asset.moveTo(type, reference, clock.instant());
        if (moved.locationType() == asset.locationType()
                && moved.locationReference().equals(asset.locationReference())) {
            return asset;
        }
        AssetReference saved = assets.save(moved);
        record("sfl.avamp.asset-location-changed.v1", "AssetReference", saved, actor, correlationId);
        remember(saved, AssetChangeType.MOVED, location(asset), location(saved), source, sourceReference, actor,
                occurredAt);
        return saved;
    }

    private void requireTagFree(String tag, UUID assetBeingTagged) {
        assets.findByExternalReference(tag)
                .filter(holder -> !holder.id().equals(assetBeingTagged))
                .ifPresent(holder -> {
                    throw new DuplicateAssetTagException(tag, holder.assetCode());
                });
    }

    private void remember(AssetReference asset, AssetChangeType type, String from, String to,
            AssetChangeSource source, String sourceReference, String actor, Instant occurredAt) {
        history.save(new AssetHistoryEntry(UUID.randomUUID(), asset.id(), type, from, to, source, sourceReference,
                actorOrDevelopment(actor), occurredAt));
    }

    private static String location(AssetReference asset) {
        return asset.locationType() + ":" + asset.locationReference();
    }

    private static String normalizeTag(String tag) {
        return tag == null || tag.isBlank() ? null : tag.strip();
    }

    private String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip().toUpperCase(Locale.ROOT);
    }
}