package gh.edu.clet.sfl.fleetlogistics.assets.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import gh.edu.clet.sfl.fleetlogistics.assets.application.AssetVisibilityAccessPolicy;
import gh.edu.clet.sfl.fleetlogistics.assets.application.AssetVisibilityService;
import gh.edu.clet.sfl.fleetlogistics.assets.application.AssignCustodyCommand;
import gh.edu.clet.sfl.fleetlogistics.assets.application.AssignTagCommand;
import gh.edu.clet.sfl.fleetlogistics.assets.application.LinkEvidenceCommand;
import gh.edu.clet.sfl.fleetlogistics.assets.application.MoveAssetCommand;
import gh.edu.clet.sfl.fleetlogistics.assets.application.RecordScanCommand;
import gh.edu.clet.sfl.fleetlogistics.assets.application.RegisterAssetCommand;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetCategory;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetHistoryEntry;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetReference;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.LocationType;
import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AVAMP-Lite asset reference register.
 *
 * <h2>Two things changed here on 1 August 2026, and both were overdue</h2>
 *
 * <p><strong>Authorisation.</strong> Every method below now passes through
 * {@link AssetVisibilityAccessPolicy}. Before, none did - eight endpoints with no permission check
 * and no site-scope check, so any authenticated caller could register an asset, read the whole
 * register and query by location at any site. Reads did not even resolve an actor.
 *
 * <p><strong>The envelope.</strong> These methods returned raw {@code AssetReference} and
 * {@code List<AssetReference>} while every other service in the platform returns
 * {@code {data, error}}. That is a convention break with a cost: a client written against the bare
 * shape breaks the day it is corrected, and the longer it stands the more expensive that day is.
 * Corrected in the same pass rather than left to become somebody's migration.
 *
 * <h2>Site scope on a read is a filter, not a refusal</h2>
 *
 * A caller asking for a site they do not hold is refused, because they asked for something specific
 * and the honest answer is no. A caller asking for *no* site gets the register narrowed to the sites
 * they hold rather than everything - see {@code assets} below, where the distinction is made
 * explicit, because getting it the other way round is how a scoped register quietly leaks.
 */
@RestController
@RequestMapping("/api/v1/assets")
public class AssetReferenceController {

    private static final String RESOURCE = "AssetReference";

    private final AssetVisibilityService service;
    private final AssetVisibilityAccessPolicy access;
    private final AssetVisibilityActorResolver actors;

    public AssetReferenceController(AssetVisibilityService service, AssetVisibilityAccessPolicy access,
            AssetVisibilityActorResolver actors) {
        this.service = service;
        this.access = access;
        this.actors = actors;
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Registers a new asset reference at a site")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request failed bean validation")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks ASSET_REFERENCE_MANAGE for the site")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "An asset with this code is already registered")
    @PostMapping
    public ResponseEntity<ApiResponse<AssetReference>> register(@Valid @RequestBody RegisterAssetRequest request,
            HttpServletRequest http) {
        ActorContext actor = actors.resolve(http);
        access.require(actor, SflPermission.ASSET_REFERENCE_MANAGE, request.siteCode(), RESOURCE);

        AssetReference result = service.register(new RegisterAssetCommand(request.assetCode(), request.name(),
                request.category(), request.siteCode(), request.locationType(), request.locationReference(),
                request.custodianReference(), request.externalReference(), actor.actorId(),
                actor.correlationId()));
        return ResponseEntity.created(URI.create("/api/v1/assets/" + result.id()))
                .body(ApiResponse.ok(result));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Lists assets, narrowed to a named site or to the actor's site scope")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "A named site is outside the actor's scope, or the actor lacks ASSET_REFERENCE_READ")
    @GetMapping
    public ApiResponse<List<AssetReference>> assets(@RequestParam(required = false) String siteCode,
            HttpServletRequest http) {
        ActorContext actor = actors.resolve(http);

        if (siteCode != null && !siteCode.isBlank()) {
            // They named a site. Refuse if it is not theirs - answering an explicit question with a
            // silently empty list would let a caller conclude the site has no assets.
            access.require(actor, SflPermission.ASSET_REFERENCE_READ, siteCode, RESOURCE);
            return ApiResponse.ok(service.findAll(siteCode));
        }

        access.require(actor, SflPermission.ASSET_REFERENCE_READ, RESOURCE);
        /*
          No site named, so the register is narrowed to the sites this actor holds rather than
          returned whole. This is the line that mattered most: `findAll(null)` returned every asset
          at every site to anybody, and it was the default path because the dashboard does not always
          send a site.
        */
        return ApiResponse.ok(service.findAllInScope(actor.principal().siteScopes()));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Reads one asset reference by id")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks ASSET_REFERENCE_READ, or the asset's site is outside their scope")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No asset exists with this id")
    @GetMapping("/{assetId}")
    public ApiResponse<AssetReference> asset(@PathVariable UUID assetId, HttpServletRequest http) {
        ActorContext actor = actors.resolve(http);
        access.require(actor, SflPermission.ASSET_REFERENCE_READ, RESOURCE);

        AssetReference asset = service.findById(assetId);
        // Scope is checked against the record's own site, after loading it. A by-id read that skipped
        // this is how an out-of-scope asset is read by pasting a UUID - the defect A0 found in fuel.
        access.require(actor, SflPermission.ASSET_REFERENCE_READ, asset.siteCode(), RESOURCE);
        return ApiResponse.ok(asset);
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Lists assets at a specific location within a site")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks ASSET_REFERENCE_READ for the site")
    @GetMapping("/by-location")
    public ApiResponse<List<AssetReference>> byLocation(@RequestParam String siteCode,
            @RequestParam LocationType locationType, @RequestParam String locationReference,
            HttpServletRequest http) {
        ActorContext actor = actors.resolve(http);
        access.require(actor, SflPermission.ASSET_REFERENCE_READ, siteCode, RESOURCE);
        return ApiResponse.ok(service.findByLocation(siteCode, locationType, locationReference));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Reads the asset that carries a physical tag")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks ASSET_REFERENCE_READ, or the asset's site is outside their scope")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No asset carries this tag")
    @GetMapping("/by-tag/{tagId}")
    public ApiResponse<AssetReference> byTag(@PathVariable String tagId, HttpServletRequest http) {
        ActorContext actor = actors.resolve(http);
        access.require(actor, SflPermission.ASSET_REFERENCE_READ, RESOURCE);

        AssetReference asset = service.findByTag(tagId);
        access.require(actor, SflPermission.ASSET_REFERENCE_READ, asset.siteCode(), RESOURCE);
        return ApiResponse.ok(asset);
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Lists every change to an asset - location, custody and tag - newest first")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks ASSET_REFERENCE_READ, or the asset's site is outside their scope")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No asset exists with this id")
    @GetMapping("/{assetId}/history")
    public ApiResponse<List<AssetHistoryEntry>> history(@PathVariable UUID assetId, HttpServletRequest http) {
        ActorContext actor = actors.resolve(http);
        access.require(actor, SflPermission.ASSET_REFERENCE_READ, RESOURCE);

        AssetReference asset = service.findById(assetId);
        access.require(actor, SflPermission.ASSET_REFERENCE_READ, asset.siteCode(), RESOURCE);
        return ApiResponse.ok(service.history(assetId));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Gives an asset the physical tag it is read by")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request failed bean validation")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks ASSET_REFERENCE_MANAGE for the asset's site")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No asset exists with this id")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The tag already identifies a different asset")
    @PatchMapping("/{assetId}/tag")
    public ApiResponse<AssetReference> assignTag(@PathVariable UUID assetId,
            @Valid @RequestBody AssignTagRequest request, HttpServletRequest http) {
        ActorContext actor = actors.resolve(http);
        AssetReference existing = service.findById(assetId);
        access.require(actor, SflPermission.ASSET_REFERENCE_MANAGE, existing.siteCode(), RESOURCE);

        return ApiResponse.ok(service.assignTag(new AssignTagCommand(assetId, request.tagId(), actor.actorId(),
                actor.correlationId())));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Records a reader's sighting of a tag, moving the asset that carries it")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request failed bean validation, or the read is stamped in the future")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks ASSET_REFERENCE_MANAGE for the asset's site")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No asset carries this tag")
    @PostMapping("/scans")
    public ApiResponse<AssetReference> recordScan(@Valid @RequestBody RecordScanRequest request,
            HttpServletRequest http) {
        ActorContext actor = actors.resolve(http);
        access.require(actor, SflPermission.ASSET_REFERENCE_MANAGE, RESOURCE);

        AssetReference existing = service.findByTag(request.tagId());
        access.require(actor, SflPermission.ASSET_REFERENCE_MANAGE, existing.siteCode(), RESOURCE);

        return ApiResponse.ok(service.recordScan(new RecordScanCommand(request.tagId(), request.locationType(),
                request.locationReference(), request.readerId(), request.occurredAt(), actor.actorId(),
                actor.correlationId())));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Moves an asset to a new location")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request failed bean validation")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks ASSET_REFERENCE_MANAGE for the asset's site")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No asset exists with this id")
    @PatchMapping("/{assetId}/location")
    public ApiResponse<AssetReference> move(@PathVariable UUID assetId,
            @Valid @RequestBody MoveAssetRequest request, HttpServletRequest http) {
        ActorContext actor = actors.resolve(http);
        AssetReference existing = service.findById(assetId);
        access.require(actor, SflPermission.ASSET_REFERENCE_MANAGE, existing.siteCode(), RESOURCE);

        return ApiResponse.ok(service.move(new MoveAssetCommand(assetId, request.locationType(),
                request.locationReference(), actor.actorId(), actor.correlationId())));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Reassigns which custodian is responsible for an asset")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks ASSET_REFERENCE_MANAGE for the asset's site")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No asset exists with this id")
    @PatchMapping("/{assetId}/custody")
    public ApiResponse<AssetReference> assignCustody(@PathVariable UUID assetId,
            @Valid @RequestBody AssignCustodyRequest request, HttpServletRequest http) {
        ActorContext actor = actors.resolve(http);
        AssetReference existing = service.findById(assetId);
        access.require(actor, SflPermission.ASSET_REFERENCE_MANAGE, existing.siteCode(), RESOURCE);

        return ApiResponse.ok(service.assignCustody(new AssignCustodyCommand(assetId,
                request.custodianReference(), actor.actorId(), actor.correlationId())));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Links an external evidence reference to an asset")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request failed bean validation")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks ASSET_REFERENCE_MANAGE for the asset's site")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No asset exists with this id")
    @PatchMapping("/{assetId}/evidence")
    public ApiResponse<AssetReference> linkEvidence(@PathVariable UUID assetId,
            @Valid @RequestBody LinkEvidenceRequest request, HttpServletRequest http) {
        ActorContext actor = actors.resolve(http);
        AssetReference existing = service.findById(assetId);
        access.require(actor, SflPermission.ASSET_REFERENCE_MANAGE, existing.siteCode(), RESOURCE);

        return ApiResponse.ok(service.linkEvidence(new LinkEvidenceCommand(assetId,
                request.evidenceReference(), actor.actorId(), actor.correlationId())));
    }

    public record RegisterAssetRequest(@NotBlank @Size(max = 80) String assetCode,
            @NotBlank @Size(max = 160) String name, @NotNull AssetCategory category,
            @NotBlank @Size(max = 40) String siteCode, @NotNull LocationType locationType,
            @NotBlank @Size(max = 120) String locationReference, @Size(max = 160) String custodianReference,
            @Size(max = 160) String externalReference) {
    }

    public record MoveAssetRequest(@NotNull LocationType locationType,
            @NotBlank @Size(max = 120) String locationReference) {
    }

    public record AssignTagRequest(@NotBlank @Size(max = 160) String tagId) {
    }

    public record RecordScanRequest(@NotBlank @Size(max = 160) String tagId, @NotNull LocationType locationType,
            @NotBlank @Size(max = 120) String locationReference, @Size(max = 160) String readerId,
            Instant occurredAt) {
    }

    public record AssignCustodyRequest(@Size(max = 160) String custodianReference) {
    }

    public record LinkEvidenceRequest(@NotBlank @Size(max = 180) String evidenceReference) {
    }
}
