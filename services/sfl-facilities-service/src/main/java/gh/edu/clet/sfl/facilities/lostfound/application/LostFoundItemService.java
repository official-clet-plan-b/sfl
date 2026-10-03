package gh.edu.clet.sfl.facilities.lostfound.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.lostfound.domain.Claim;
import gh.edu.clet.sfl.facilities.lostfound.domain.ClaimReference;
import gh.edu.clet.sfl.facilities.lostfound.domain.ClaimStatus;
import gh.edu.clet.sfl.facilities.lostfound.domain.CustodyChain;
import gh.edu.clet.sfl.facilities.lostfound.domain.CustodyEvent;
import gh.edu.clet.sfl.facilities.lostfound.domain.EscalationReason;
import gh.edu.clet.sfl.facilities.lostfound.domain.EvidenceKind;
import gh.edu.clet.sfl.facilities.lostfound.domain.FoundItem;
import gh.edu.clet.sfl.facilities.lostfound.domain.ItemCategory;
import gh.edu.clet.sfl.facilities.lostfound.domain.ItemStatus;
import gh.edu.clet.sfl.facilities.lostfound.domain.LfEvidence;
import gh.edu.clet.sfl.facilities.lostfound.domain.LfHistoryEntry;
import gh.edu.clet.sfl.facilities.lostfound.domain.RetentionPolicy;
import gh.edu.clet.sfl.facilities.lostfound.domain.StorageLocation;
import gh.edu.clet.sfl.facilities.maintenance.domain.RetentionClass;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Found items, their custody and their storage - SRS-SFL-S179-01 to -03 and -05.
 *
 * <h2>What a reader without the private grant sees</h2>
 *
 * A controlled category and a short public description - never the detail that could identify the owner, the
 * finder, the claimant, or a photograph. The masking is applied here, in the service, on every read path, so
 * it does not depend on the screen remembering to hide a field. Reading the private view is itself audited.
 *
 * <h2>The custody chain</h2>
 *
 * Every transfer takes its sender from whoever the chain says holds the item now, and carries a receiver, a
 * place and a time. The rows are protected by a database trigger as well as by the absence of any update path.
 */
@Service
public class LostFoundItemService {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final LostFoundStore store;
    private final LostFoundSupport support;
    private final TransactionTemplate inTransaction;

    public LostFoundItemService(LostFoundStore store, LostFoundSupport support, PlatformTransactionManager transactions) {
        this.store = store;
        this.support = support;
        this.inTransaction = new TransactionTemplate(transactions);
    }

    // ---- configuration

    public Configuration configuration(String siteCode, Caller caller) {
        String site = LostFoundSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_LOSTFOUND_READ, site, "LostFoundConfiguration", site);
        return inTransaction.execute(tx -> new Configuration(store.locations(site), store.policies()));
    }

    public record Configuration(List<StorageLocation> locations, List<RetentionPolicy> policies) {
    }

    public StorageLocation createLocation(String siteCode, String code, String name, boolean secure, Caller caller) {
        return inTransaction.execute(tx -> {
            String site = support.validateSite(siteCode);
            support.require(caller, SflPermission.FACILITIES_LOSTFOUND_MANAGE, site, "StorageLocation", "new");
            Instant now = support.now();
            StorageLocation location = new StorageLocation(UUID.randomUUID(), site,
                    LostFoundSupport.required(code, "code").toUpperCase(), LostFoundSupport.required(name, "name"),
                    secure, true, caller.actor().actorId(), now, now, 0);
            store.insert(location);
            support.audit(caller, AuditAction.LF_CONFIG_CHANGED, "StorageLocation", location.id(), site, null, location);
            return location;
        });
    }

    public StorageLocation updateLocation(UUID id, String name, Boolean secure, Boolean active, Long expectedVersion,
            Caller caller) {
        return inTransaction.execute(tx -> {
            StorageLocation before = store.location(id).orElseThrow(() -> LostFoundSupport.notFound("Storage location", id));
            support.require(caller, SflPermission.FACILITIES_LOSTFOUND_MANAGE, before.siteCode(), "StorageLocation",
                    id.toString());
            LostFoundSupport.checkVersion(expectedVersion, before.version());
            StorageLocation after = new StorageLocation(id, before.siteCode(), before.code(),
                    name == null ? before.name() : name.strip(), secure == null ? before.secure() : secure,
                    active == null ? before.active() : active, before.createdBy(), before.createdAt(), support.now(),
                    before.version());
            if (!store.update(after, before.version())) {
                throw LostFoundSupport.conflict();
            }
            StorageLocation saved = store.location(id).orElseThrow();
            support.audit(caller, AuditAction.LF_CONFIG_CHANGED, "StorageLocation", id, saved.siteCode(), before, saved);
            return saved;
        });
    }

    /** Retention is configuration: changing it is an approval-level act, because it decides when personal data and property are let go. */
    public RetentionPolicy setPolicy(String siteCode, ItemCategory category, int unclaimedDays, int personalDataDays,
            Caller caller) {
        return inTransaction.execute(tx -> {
            String site = support.validateSite(siteCode);
            support.require(caller, SflPermission.FACILITIES_LOSTFOUND_APPROVE, site, "RetentionPolicy", "category");
            if (category == null || unclaimedDays <= 0 || personalDataDays <= 0) {
                throw new IllegalArgumentException("category and positive retention periods are required");
            }
            RetentionPolicy before = store.policy(category);
            RetentionPolicy policy = new RetentionPolicy(category, unclaimedDays, personalDataDays);
            store.save(policy);
            support.audit(caller, AuditAction.LF_CONFIG_CHANGED, "RetentionPolicy",
                    UUID.nameUUIDFromBytes(category.name().getBytes()), site, before, policy);
            return policy;
        });
    }

    // ---- registering and storing

    public record Register(String siteCode, ItemCategory category, String publicDescription, String privateDescription,
            String foundLocation, Instant foundAt, String finderReference, String initialCondition, boolean unsafe,
            String unsafeReason, UUID storageLocationId, Caller caller) {
    }

    public FoundItem register(Register command) {
        return inTransaction.execute(tx -> {
            String site = support.validateSite(command.siteCode());
            Caller caller = command.caller();
            support.require(caller, SflPermission.FACILITIES_LOSTFOUND_MANAGE, site, "FoundItem", "new");
            if (command.category() == null) {
                throw new IllegalArgumentException("category is required");
            }
            Instant now = support.now();
            Instant foundAt = command.foundAt() == null ? now : command.foundAt();
            if (foundAt.isAfter(now)) {
                throw new IllegalArgumentException("foundAt cannot be in the future");
            }
            if (command.unsafe()) {
                LostFoundSupport.required(command.unsafeReason(), "unsafeReason");
            }
            StorageLocation location = null;
            if (command.storageLocationId() != null && !command.unsafe()) {
                location = usable(command.storageLocationId(), site, command.category());
            }
            RetentionPolicy policy = store.policy(command.category());
            ItemStatus status = command.unsafe() ? ItemStatus.ISOLATED : location != null ? ItemStatus.STORED
                    : ItemStatus.REGISTERED;
            FoundItem item = null;
            for (int attempt = 0; attempt < 5 && item == null; attempt++) {
                FoundItem candidate = new FoundItem(UUID.randomUUID(),
                        String.format("LF-I-%06d", store.nextSequence("lf_item_seq")), ClaimReference.generate(), site,
                        command.category(), LostFoundSupport.required(command.publicDescription(), "publicDescription"),
                        LostFoundSupport.blankToNull(command.privateDescription()),
                        LostFoundSupport.required(command.foundLocation(), "foundLocation"), foundAt,
                        LostFoundSupport.required(command.finderReference(), "finderReference"),
                        LostFoundSupport.required(command.initialCondition(), "initialCondition"), status,
                        command.unsafe(), command.unsafe() ? command.unsafeReason().strip() : null,
                        location == null ? null : location.id(), policy.retentionUntil(foundAt.atZone(ZoneOffset.UTC).toLocalDate()),
                        null, caller.actor().actorId(), now, now, 0);
                if (store.insert(candidate)) {
                    item = candidate;
                }
            }
            if (item == null) {
                throw new IllegalStateException("Could not allocate a unique claim reference");
            }
            String desk = "Front desk (" + caller.actor().actorId() + ")";
            custody(item, item.finderReference(), desk, item.foundLocation(), "Received as found property", caller);
            if (location != null) {
                custody(item, desk, "Store: " + location.name(), location.name(), "Placed in storage", caller);
            }
            support.history(site, "ITEM", item.id(), null, status.name(), caller.actor().actorId(), null);
            support.audit(caller, AuditAction.LF_ITEM_REGISTERED, "FoundItem", item.id(), site, null, item.masked());
            support.publish(LostFoundEvents.ITEM_REGISTERED, "FoundItem", item.id(), site, caller.actor(), "itemId",
                    item.id(), "reference", item.reference(), "claimReference", item.claimReference(), "category",
                    item.category(), "retentionUntil", item.retentionUntil());
            if (item.unsafe()) {
                support.publish(LostFoundEvents.ITEM_ISOLATED, "FoundItem", item.id(), site, caller.actor(), "itemId",
                        item.id(), "reference", item.reference());
                support.escalateInline(item, null, EscalationReason.UNSAFE_ITEM, item.unsafeReason(), caller);
            }
            return item;
        });
    }

    public FoundItem store(UUID itemId, UUID storageLocationId, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            FoundItem before = manageable(itemId, caller);
            LostFoundSupport.checkVersion(expectedVersion, before.version());
            requireOpen(before);
            if (before.status() == ItemStatus.ISOLATED) {
                throw new FacilitiesException(FacilitiesErrorCode.LF_ITEM_ISOLATED);
            }
            StorageLocation location = usable(storageLocationId, before.siteCode(), before.category());
            String holder = holder(itemId);
            FoundItem after = copy(before, ItemStatus.STORED, location.id(), before.unsafe(), before.unsafeReason(), null);
            FoundItem saved = save(before, after, caller, "Stored in " + location.name());
            custody(saved, holder, "Store: " + location.name(), location.name(), "Placed in storage", caller);
            return saved;
        });
    }

    /** A transfer to anyone: the sender is whoever the chain says holds the item, so the chain cannot be broken from here. */
    public FoundItem transfer(UUID itemId, String toParty, String location, String reason, Caller caller) {
        return inTransaction.execute(tx -> {
            FoundItem item = manageable(itemId, caller);
            requireOpen(item);
            custody(item, holder(itemId), LostFoundSupport.required(toParty, "toParty"),
                    LostFoundSupport.required(location, "location"), LostFoundSupport.blankToNull(reason), caller);
            return item;
        });
    }

    /** Unsafe or suspicious: isolate it, refuse the claims in play, escalate to security and the emergency procedures. */
    public FoundItem markUnsafe(UUID itemId, String reason, Caller caller) {
        return inTransaction.execute(tx -> {
            FoundItem before = manageable(itemId, caller);
            requireOpen(before);
            LostFoundSupport.required(reason, "reason");
            FoundItem saved = save(before, copy(before, ItemStatus.ISOLATED, before.storageLocationId(), true,
                    reason.strip(), null), caller, "Isolated as unsafe: " + reason.strip());
            for (Claim open : store.claimsOf(itemId).stream().filter(c -> c.status().open()).toList()) {
                store.update(close(open, ClaimStatus.REFUSED, "The item was isolated as unsafe", caller.actor().actorId()),
                        open.version());
                support.history(open.siteCode(), "CLAIM", open.id(), open.status().name(), "REFUSED",
                        caller.actor().actorId(), "The item was isolated as unsafe");
            }
            support.publish(LostFoundEvents.ITEM_ISOLATED, "FoundItem", itemId, saved.siteCode(), caller.actor(),
                    "itemId", itemId, "reference", saved.reference());
            support.escalateInline(saved, null, EscalationReason.UNSAFE_ITEM, reason.strip(), caller);
            return saved;
        });
    }

    // ---- evidence

    public LfEvidence submitEvidence(UUID itemId, UUID claimId, EvidenceKind kind, String reference, String fileName,
            String mediaType, long sizeBytes, String contentHash, Caller caller) {
        return inTransaction.execute(tx -> {
            FoundItem item = manageable(itemId, caller);
            if (kind == null) {
                throw new IllegalArgumentException("kind is required");
            }
            if (claimId != null && !support.claim(claimId).itemId().equals(itemId)) {
                throw new IllegalArgumentException("That claim belongs to a different item.");
            }
            if (sizeBytes <= 0) {
                throw new IllegalArgumentException("sizeBytes must be positive");
            }
            if (contentHash == null || !SHA_256.matcher(contentHash.strip()).matches()) {
                throw new IllegalArgumentException("contentHash must be a SHA-256 digest (64 hex characters)");
            }
            // Photographs and receipts can show faces, documents and signatures: kept for the compliance period, no longer.
            LfEvidence evidence = new LfEvidence(UUID.randomUUID(), itemId, claimId, item.siteCode(), kind,
                    LostFoundSupport.required(reference, "reference"), LostFoundSupport.required(fileName, "fileName"),
                    LostFoundSupport.required(mediaType, "mediaType"), sizeBytes, contentHash.strip().toLowerCase(),
                    RetentionClass.COMPLIANCE.name(), caller.actor().actorId(), support.now());
            store.insert(evidence);
            support.history(item.siteCode(), "ITEM", itemId, item.status().name(), item.status().name(),
                    caller.actor().actorId(), "Evidence filed: " + kind);
            support.audit(caller, AuditAction.LF_EVIDENCE_SUBMITTED, "LfEvidence", evidence.id(), item.siteCode(),
                    null, evidence);
            return evidence;
        });
    }

    /** Photographs and receipts can identify people, so reading them needs the private grant and is audited. */
    public List<LfEvidence> evidence(UUID itemId, Caller caller) {
        return inTransaction.execute(tx -> {
            FoundItem item = support.item(itemId);
            support.require(caller, SflPermission.FACILITIES_LOSTFOUND_PRIVATE_READ, item.siteCode(), "LfEvidence",
                    itemId.toString());
            List<LfEvidence> evidence = store.evidenceOf(itemId);
            support.audit(caller, AuditAction.LF_PRIVATE_VIEWED, "FoundItem", itemId, item.siteCode(), null,
                    evidence.size() + " evidence item(s)");
            return evidence;
        });
    }

    // ---- ending an item's life

    /**
     * Disposal needs the retention period to have run out (an unsafe item excepted), a filed disposal
     * authorisation, no claim in play, and an approver who is not the person who filed the authorisation.
     */
    public FoundItem dispose(UUID itemId, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            FoundItem before = support.item(itemId);
            support.require(caller, SflPermission.FACILITIES_LOSTFOUND_APPROVE, before.siteCode(), "FoundItem",
                    itemId.toString());
            LostFoundSupport.checkVersion(expectedVersion, before.version());
            requireOpen(before);
            if (!before.unsafe() && !before.retentionExpired(support.today())) {
                throw new FacilitiesException(FacilitiesErrorCode.LF_RETENTION_NOT_EXPIRED, "The retention period runs "
                        + "until " + before.retentionUntil() + "; the item cannot be disposed of before then.");
            }
            if (store.claimsOf(itemId).stream().anyMatch(c -> c.status().open())) {
                throw LostFoundSupport.invalid("A claim is still open on this item; resolve it before disposal.");
            }
            LfEvidence authorisation = latest(itemId, EvidenceKind.DISPOSAL_AUTHORISATION, "A disposal authorisation");
            if (authorisation.submittedBy().equals(caller.actor().actorId())) {
                throw new FacilitiesException(FacilitiesErrorCode.LF_SELF_APPROVAL);
            }
            FoundItem saved = closeItem(before, ItemStatus.DISPOSED, "Authorised disposal", authorisation.reference(), caller);
            support.publish(LostFoundEvents.ITEM_DISPOSED, "FoundItem", itemId, saved.siteCode(), caller.actor(),
                    "itemId", itemId, "reference", saved.reference(), "withinRetention", !saved.retentionExpired(support.today()));
            support.audit(caller, AuditAction.LF_ITEM_DISPOSED, "FoundItem", itemId, saved.siteCode(), before.masked(),
                    saved.masked());
            return saved;
        });
    }

    /** Handover to the police or another authority: approved, with the authority's receipt on file. */
    public FoundItem handToAuthorities(UUID itemId, String authority, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            FoundItem before = support.item(itemId);
            support.require(caller, SflPermission.FACILITIES_LOSTFOUND_APPROVE, before.siteCode(), "FoundItem",
                    itemId.toString());
            LostFoundSupport.checkVersion(expectedVersion, before.version());
            requireOpen(before);
            LfEvidence receipt = latest(itemId, EvidenceKind.AUTHORITY_RECEIPT, "The authority's receipt");
            FoundItem saved = closeItem(before, ItemStatus.HANDED_TO_AUTHORITIES, "Handed to "
                    + LostFoundSupport.required(authority, "authority"), receipt.reference(), caller);
            support.publish(LostFoundEvents.ITEM_HANDED_TO_AUTHORITIES, "FoundItem", itemId, saved.siteCode(),
                    caller.actor(), "itemId", itemId, "reference", saved.reference());
            support.audit(caller, AuditAction.LF_ITEM_HANDED_TO_AUTHORITIES, "FoundItem", itemId, saved.siteCode(),
                    before.masked(), saved.masked());
            return saved;
        });
    }

    // ---- reads

    public LostFoundStore.Page<FoundItem> list(String siteCode, String status, String category, boolean openOnly,
            int page, int size, Caller caller) {
        String site = LostFoundSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_LOSTFOUND_READ, site, "FoundItem", "list");
        String statusFilter = enumName(ItemStatus.class, status);
        String categoryFilter = enumName(ItemCategory.class, category);
        boolean privateRead = support.has(caller.actor(), SflPermission.FACILITIES_LOSTFOUND_PRIVATE_READ);
        LostFoundStore.Page<FoundItem> found = inTransaction.execute(tx -> store.items(site, statusFilter,
                categoryFilter, openOnly, Math.max(0, page), Math.min(Math.max(1, size), 100)));
        return privateRead ? found : new LostFoundStore.Page<>(found.items().stream().map(FoundItem::masked).toList(),
                found.total(), found.page(), found.size());
    }

    public ItemDetail get(UUID id, Caller caller) {
        return inTransaction.execute(tx -> {
            FoundItem item = support.item(id);
            support.require(caller, SflPermission.FACILITIES_LOSTFOUND_READ, item.siteCode(), "FoundItem", id.toString());
            boolean privateRead = support.has(caller.actor(), SflPermission.FACILITIES_LOSTFOUND_PRIVATE_READ);
            List<CustodyEvent> custody = store.custody(id);
            List<Claim> claims = store.claimsOf(id);
            StorageLocation location = item.storageLocationId() == null ? null
                    : store.location(item.storageLocationId()).orElse(null);
            if (privateRead && (item.privateDescription() != null || !claims.isEmpty())) {
                support.audit(caller, AuditAction.LF_PRIVATE_VIEWED, "FoundItem", id, item.siteCode(), null, "detail");
            }
            return new ItemDetail(privateRead ? item : item.masked(), location,
                    privateRead ? claims : claims.stream().map(Claim::masked).toList(),
                    privateRead ? custody : custody.stream().map(LostFoundItemService::maskCustody).toList(),
                    CustodyChain.complete(custody), store.evidenceOf(id).size(), store.history(id), privateRead);
        });
    }

    /** What a claimant may be told about an item by its claim reference: the controlled description and where it stands. Nothing private. */
    public PublicView lookup(String claimReference, Caller caller) {
        return inTransaction.execute(tx -> {
            FoundItem item = store.itemByClaimReference(LostFoundSupport.required(claimReference, "claimReference").toUpperCase())
                    .orElseThrow(() -> LostFoundSupport.notFound("Claim reference", claimReference));
            support.require(caller, SflPermission.FACILITIES_LOSTFOUND_READ, item.siteCode(), "FoundItem",
                    item.claimReference());
            return new PublicView(item.claimReference(), item.siteCode(), item.category(), item.publicDescription(),
                    item.foundAt(), item.status());
        });
    }

    public record PublicView(String claimReference, String siteCode, ItemCategory category, String publicDescription,
            Instant foundAt, ItemStatus status) {
    }

    public record ItemDetail(FoundItem item, StorageLocation storageLocation, List<Claim> claims,
            List<CustodyEvent> custody, boolean custodyComplete, int evidenceCount, List<LfHistoryEntry> history,
            boolean privateView) {
    }

    // ---- internals

    private static CustodyEvent maskCustody(CustodyEvent e) {
        boolean finder = !e.fromParty().startsWith("Front desk") && !e.fromParty().startsWith("Store:")
                && !e.fromParty().startsWith("Claimant ");
        return finder ? new CustodyEvent(e.id(), e.itemId(), e.siteCode(), "Finder", e.toParty(), e.location(),
                e.occurredAt(), e.reason(), e.recordedBy()) : e;
    }

    private FoundItem manageable(UUID id, Caller caller) {
        FoundItem item = support.item(id);
        support.require(caller, SflPermission.FACILITIES_LOSTFOUND_MANAGE, item.siteCode(), "FoundItem", id.toString());
        return item;
    }

    private static void requireOpen(FoundItem item) {
        if (!item.status().open()) {
            throw LostFoundSupport.invalid("This item is " + item.status() + " and can no longer be changed.");
        }
    }

    private StorageLocation usable(UUID locationId, String site, ItemCategory category) {
        StorageLocation location = store.location(locationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown storage location"));
        if (!location.siteCode().equals(site) || !location.active()) {
            throw new IllegalArgumentException("Storage location " + location.code() + " is not an active location at " + site + ".");
        }
        if (category.requiresSecureStorage() && !location.secure()) {
            throw new FacilitiesException(FacilitiesErrorCode.LF_STORAGE_NOT_SECURE, category + " items must be kept in secure storage; "
                    + location.name() + " is not.");
        }
        return location;
    }

    private String holder(UUID itemId) {
        String holder = CustodyChain.currentHolder(store.custody(itemId));
        if (holder == null) {
            throw LostFoundSupport.invalid("This item has no custody chain.");
        }
        return holder;
    }

    private LfEvidence latest(UUID itemId, EvidenceKind kind, String what) {
        return store.evidenceOf(itemId).stream().filter(e -> e.kind() == kind).reduce((a, b) -> b)
                .orElseThrow(() -> LostFoundSupport.invalid(what + " must be filed before this can be done."));
    }

    private FoundItem closeItem(FoundItem before, ItemStatus end, String reason, String reference, Caller caller) {
        String holder = holder(before.id());
        FoundItem after = copy(before, end, before.storageLocationId(), before.unsafe(), before.unsafeReason(), support.now());
        FoundItem saved = save(before, after, caller, reason + " (" + reference + ")");
        custody(saved, holder, end == ItemStatus.DISPOSED ? "Disposal" : "Authority", "Authorised handling", reason, caller);
        return saved;
    }

    private FoundItem save(FoundItem before, FoundItem after, Caller caller, String reason) {
        if (!store.update(after, before.version())) {
            throw LostFoundSupport.conflict();
        }
        FoundItem saved = support.item(after.id());
        support.history(saved.siteCode(), "ITEM", saved.id(), before.status().name(), saved.status().name(),
                caller.actor().actorId(), reason);
        support.audit(caller, AuditAction.LF_ITEM_UPDATED, "FoundItem", saved.id(), saved.siteCode(), before.masked(),
                saved.masked());
        return saved;
    }

    private FoundItem copy(FoundItem b, ItemStatus status, UUID locationId, boolean unsafe, String unsafeReason,
            Instant closedAt) {
        return new FoundItem(b.id(), b.reference(), b.claimReference(), b.siteCode(), b.category(),
                b.publicDescription(), b.privateDescription(), b.foundLocation(), b.foundAt(), b.finderReference(),
                b.initialCondition(), status, unsafe, unsafeReason, locationId, b.retentionUntil(),
                closedAt == null ? b.closedAt() : closedAt, b.createdBy(), b.createdAt(), support.now(), b.version());
    }

    static Claim close(Claim c, ClaimStatus status, String reason, String actor) {
        Instant now = Instant.now();
        return new Claim(c.id(), c.reference(), c.itemId(), c.siteCode(), c.claimantName(), c.claimantContact(),
                c.claimantDescription(), status, c.identityVerified(), c.verificationMethod(),
                c.verificationReference(), c.verifiedBy(), c.verifiedAt(), reason, actor, now, c.releasedBy(),
                c.releasedAt(), now, c.personalDataPurgedAt(), c.createdBy(), c.createdAt(), now, c.version());
    }

    private void custody(FoundItem item, String from, String to, String location, String reason, Caller caller) {
        CustodyEvent event = new CustodyEvent(UUID.randomUUID(), item.id(), item.siteCode(), from, to, location,
                support.now(), reason, caller.actor().actorId());
        store.insert(event);
        support.audit(caller, AuditAction.LF_CUSTODY_RECORDED, "FoundItem", item.id(), item.siteCode(), null, event);
    }

    static <E extends Enum<E>> String enumName(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase()).name();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown " + type.getSimpleName() + ": " + value);
        }
    }
}
