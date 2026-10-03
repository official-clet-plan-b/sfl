package gh.edu.clet.sfl.facilities.lostfound.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.facilities.lostfound.application.LostFoundClaimService;
import gh.edu.clet.sfl.facilities.lostfound.application.LostFoundItemService;
import gh.edu.clet.sfl.facilities.lostfound.application.LostFoundOpsService;
import gh.edu.clet.sfl.facilities.lostfound.application.LostFoundStore;
import gh.edu.clet.sfl.facilities.lostfound.domain.Claim;
import gh.edu.clet.sfl.facilities.lostfound.domain.EvidenceKind;
import gh.edu.clet.sfl.facilities.lostfound.domain.FoundItem;
import gh.edu.clet.sfl.facilities.lostfound.domain.ItemCategory;
import gh.edu.clet.sfl.facilities.lostfound.domain.LfEscalation;
import gh.edu.clet.sfl.facilities.lostfound.domain.LfEvidence;
import gh.edu.clet.sfl.facilities.lostfound.domain.RetentionPolicy;
import gh.edu.clet.sfl.facilities.lostfound.domain.StorageLocation;
import gh.edu.clet.sfl.facilities.lostfound.domain.VerificationMethod;
import gh.edu.clet.sfl.facilities.shared.api.PageResponse;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S179 Lost-and-Found Register. Creates answer 201; an unknown id 404; a transition the record cannot make, or a
 * release that is blocked, 422 - with every blocker named; and every transition accepts the version the caller
 * last saw, answering 409 if someone else got there first. Without the private grant every response carries the
 * masked view of an item or a claim; the masking happens in the service, not here.
 */
@RestController
@RequestMapping("/api/v1/facilities/lost-found")
public class LostFoundController {

    private final LostFoundItemService items;
    private final LostFoundClaimService claims;
    private final LostFoundOpsService ops;

    public LostFoundController(LostFoundItemService items, LostFoundClaimService claims, LostFoundOpsService ops) {
        this.items = items;
        this.claims = claims;
        this.ops = ops;
    }

    @GetMapping("/dashboard")
    public ApiResponse<LostFoundOpsService.Dashboard> dashboard(@RequestParam String siteCode, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(ops.dashboard(siteCode, new Caller(actor, channel)));
    }

    @GetMapping("/configuration")
    public ApiResponse<LostFoundItemService.Configuration> configuration(@RequestParam String siteCode,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(items.configuration(siteCode, new Caller(actor, channel)));
    }

    @PostMapping("/locations")
    public ResponseEntity<ApiResponse<StorageLocation>> createLocation(@Valid @RequestBody LocationRequest r,
            ActorContext actor, SourceChannel channel) {
        return created(items.createLocation(r.siteCode(), r.code(), r.name(), r.secure(), new Caller(actor, channel)));
    }

    @PostMapping("/locations/{id}/update")
    public ApiResponse<StorageLocation> updateLocation(@PathVariable UUID id, @Valid @RequestBody LocationUpdate r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(items.updateLocation(id, r.name(), r.secure(), r.active(), r.version(),
                new Caller(actor, channel)));
    }

    @PostMapping("/retention")
    public ApiResponse<RetentionPolicy> setPolicy(@Valid @RequestBody PolicyRequest r, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(items.setPolicy(r.siteCode(), r.category(), r.unclaimedDays(), r.personalDataDays(),
                new Caller(actor, channel)));
    }

    // ---- items

    @GetMapping("/items")
    public ApiResponse<PageResponse<FoundItem>> listItems(@RequestParam String siteCode,
            @RequestParam(required = false) String status, @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "false") boolean openOnly, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(paged(items.list(siteCode, status, category, openOnly, page, size,
                new Caller(actor, channel))));
    }

    @PostMapping("/items")
    public ResponseEntity<ApiResponse<FoundItem>> register(@Valid @RequestBody RegisterRequest r, ActorContext actor,
            SourceChannel channel) {
        return created(items.register(new LostFoundItemService.Register(r.siteCode(), r.category(),
                r.publicDescription(), r.privateDescription(), r.foundLocation(), r.foundAt(), r.finderReference(),
                r.initialCondition(), Boolean.TRUE.equals(r.unsafe()), r.unsafeReason(), r.storageLocationId(),
                new Caller(actor, channel))));
    }

    @GetMapping("/items/{id}")
    public ApiResponse<LostFoundItemService.ItemDetail> item(@PathVariable UUID id, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(items.get(id, new Caller(actor, channel)));
    }

    @GetMapping("/lookup")
    public ApiResponse<LostFoundItemService.PublicView> lookup(@RequestParam String claimReference, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(items.lookup(claimReference, new Caller(actor, channel)));
    }

    @PostMapping("/items/{id}/store")
    public ApiResponse<FoundItem> store(@PathVariable UUID id, @Valid @RequestBody StoreRequest r, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(items.store(id, r.storageLocationId(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/items/{id}/transfer")
    public ApiResponse<FoundItem> transfer(@PathVariable UUID id, @Valid @RequestBody TransferRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(items.transfer(id, r.toParty(), r.location(), r.reason(), new Caller(actor, channel)));
    }

    @PostMapping("/items/{id}/unsafe")
    public ApiResponse<FoundItem> unsafe(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(items.markUnsafe(id, r.reason(), new Caller(actor, channel)));
    }

    @PostMapping("/items/{id}/dispose")
    public ApiResponse<FoundItem> dispose(@PathVariable UUID id, @RequestBody(required = false) VersionRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(items.dispose(id, version(r), new Caller(actor, channel)));
    }

    @PostMapping("/items/{id}/hand-to-authorities")
    public ApiResponse<FoundItem> handToAuthorities(@PathVariable UUID id,
            @Valid @RequestBody AuthorityRequest r, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(items.handToAuthorities(id, r.authority(), r.version(), new Caller(actor, channel)));
    }

    @GetMapping("/items/{id}/evidence")
    public ApiResponse<java.util.List<LfEvidence>> evidence(@PathVariable UUID id, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(items.evidence(id, new Caller(actor, channel)));
    }

    @PostMapping("/items/{id}/evidence")
    public ResponseEntity<ApiResponse<LfEvidence>> submitEvidence(@PathVariable UUID id,
            @Valid @RequestBody EvidenceRequest r, ActorContext actor, SourceChannel channel) {
        return created(items.submitEvidence(id, r.claimId(), r.kind(), r.reference(), r.fileName(), r.mediaType(),
                r.sizeBytes(), r.contentHash(), new Caller(actor, channel)));
    }

    // ---- claims

    @GetMapping("/claims")
    public ApiResponse<PageResponse<Claim>> listClaims(@RequestParam String siteCode,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(paged(claims.list(siteCode, status, page, size, new Caller(actor, channel))));
    }

    @PostMapping("/items/{id}/claims")
    public ResponseEntity<ApiResponse<Claim>> receive(@PathVariable UUID id, @Valid @RequestBody ClaimRequest r,
            ActorContext actor, SourceChannel channel) {
        return created(claims.receive(id, r.claimantName(), r.claimantContact(), r.claimantDescription(),
                new Caller(actor, channel)));
    }

    @GetMapping("/claims/{id}/claimant-view")
    public ApiResponse<LostFoundClaimService.ClaimantView> claimantView(@PathVariable UUID id, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(claims.claimantView(id, new Caller(actor, channel)));
    }

    @PostMapping("/claims/{id}/verify")
    public ApiResponse<Claim> verify(@PathVariable UUID id, @Valid @RequestBody VerifyRequest r, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(claims.verifyIdentity(id, r.method(), r.verificationReference(), r.version(),
                new Caller(actor, channel)));
    }

    @PostMapping("/claims/{id}/approve")
    public ApiResponse<Claim> approve(@PathVariable UUID id, @Valid @RequestBody ApproveRequest r, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(claims.approve(id, r.reason(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/claims/{id}/refuse")
    public ApiResponse<Claim> refuse(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(claims.refuse(id, r.reason(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/claims/{id}/withdraw")
    public ApiResponse<Claim> withdraw(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(claims.withdraw(id, r.reason(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/claims/{id}/release")
    public ApiResponse<Claim> release(@PathVariable UUID id, @Valid @RequestBody ReleaseRequest r, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(claims.release(id, r.accepted(), r.note(), r.version(), new Caller(actor, channel)));
    }

    // ---- escalations

    @GetMapping("/escalations")
    public ApiResponse<PageResponse<LfEscalation>> escalations(@RequestParam String siteCode,
            @RequestParam(defaultValue = "false") boolean openOnly, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(paged(ops.escalations(siteCode, openOnly, page, size, new Caller(actor, channel))));
    }

    @PostMapping("/escalations/{id}/acknowledge")
    public ApiResponse<LfEscalation> acknowledge(@PathVariable UUID id, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(ops.acknowledge(id, new Caller(actor, channel)));
    }

    @PostMapping("/escalations/{id}/link-incident")
    public ApiResponse<LfEscalation> linkIncident(@PathVariable UUID id, @Valid @RequestBody TextRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(ops.linkIncident(id, r.text(), new Caller(actor, channel)));
    }

    // ---- shapes

    private static <T> ResponseEntity<ApiResponse<T>> created(T body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    private static <T> PageResponse<T> paged(LostFoundStore.Page<T> page) {
        int size = Math.max(1, page.size());
        return new PageResponse<>(page.items(), page.total(), (int) Math.ceil(page.total() / (double) size),
                page.page(), page.size());
    }

    private static Long version(VersionRequest body) {
        return body == null ? null : body.version();
    }

    public record VersionRequest(Long version) {
    }

    public record TextRequest(@NotBlank @Size(max = 2000) String text) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 2000) String reason, Long version) {
    }

    public record LocationRequest(@NotBlank @Size(max = 40) String siteCode, @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 160) String name, boolean secure) {
    }

    public record LocationUpdate(@Size(max = 160) String name, Boolean secure, Boolean active, Long version) {
    }

    public record PolicyRequest(@NotBlank @Size(max = 40) String siteCode, @NotNull ItemCategory category,
            @Positive int unclaimedDays, @Positive int personalDataDays) {
    }

    public record RegisterRequest(@NotBlank @Size(max = 40) String siteCode, @NotNull ItemCategory category,
            @NotBlank @Size(max = 240) String publicDescription, @Size(max = 2000) String privateDescription,
            @NotBlank @Size(max = 240) String foundLocation, Instant foundAt,
            @NotBlank @Size(max = 160) String finderReference, @NotBlank @Size(max = 240) String initialCondition,
            Boolean unsafe, @Size(max = 1000) String unsafeReason, UUID storageLocationId) {
    }

    public record StoreRequest(@NotNull UUID storageLocationId, Long version) {
    }

    public record TransferRequest(@NotBlank @Size(max = 160) String toParty, @NotBlank @Size(max = 160) String location,
            @Size(max = 500) String reason) {
    }

    public record AuthorityRequest(@NotBlank @Size(max = 160) String authority, Long version) {
    }

    public record EvidenceRequest(UUID claimId, @NotNull EvidenceKind kind, @NotBlank @Size(max = 240) String reference,
            @NotBlank @Size(max = 240) String fileName, @NotBlank @Size(max = 120) String mediaType,
            @Positive long sizeBytes, @NotBlank @Size(max = 128) String contentHash) {
    }

    public record ClaimRequest(@NotBlank @Size(max = 160) String claimantName,
            @NotBlank @Size(max = 160) String claimantContact, @Size(max = 2000) String claimantDescription) {
    }

    public record VerifyRequest(@NotNull VerificationMethod method, @NotBlank @Size(max = 160) String verificationReference,
            Long version) {
    }

    public record ApproveRequest(@Size(max = 2000) String reason, Long version) {
    }

    public record ReleaseRequest(boolean accepted, @Size(max = 2000) String note, Long version) {
    }
}
