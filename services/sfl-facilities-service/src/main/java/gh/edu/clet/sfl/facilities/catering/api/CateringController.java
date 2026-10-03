package gh.edu.clet.sfl.facilities.catering.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.facilities.catering.application.CateringConfigService;
import gh.edu.clet.sfl.facilities.catering.application.CateringOpsService;
import gh.edu.clet.sfl.facilities.catering.application.CateringServiceService;
import gh.edu.clet.sfl.facilities.catering.application.CateringStore;
import gh.edu.clet.sfl.facilities.catering.domain.Allergen;
import gh.edu.clet.sfl.facilities.catering.domain.CateringCheck;
import gh.edu.clet.sfl.facilities.catering.domain.CateringEvidence;
import gh.edu.clet.sfl.facilities.catering.domain.CateringException;
import gh.edu.clet.sfl.facilities.catering.domain.CateringService;
import gh.edu.clet.sfl.facilities.catering.domain.CheckType;
import gh.edu.clet.sfl.facilities.catering.domain.ContextType;
import gh.edu.clet.sfl.facilities.catering.domain.DietaryRequest;
import gh.edu.clet.sfl.facilities.catering.domain.DietaryTag;
import gh.edu.clet.sfl.facilities.catering.domain.EvidenceKind;
import gh.edu.clet.sfl.facilities.catering.domain.ExceptionType;
import gh.edu.clet.sfl.facilities.catering.domain.HoldType;
import gh.edu.clet.sfl.facilities.catering.domain.Menu;
import gh.edu.clet.sfl.facilities.catering.domain.MenuItem;
import gh.edu.clet.sfl.facilities.catering.domain.NeedType;
import gh.edu.clet.sfl.facilities.catering.domain.Supplier;
import gh.edu.clet.sfl.facilities.catering.domain.SupplierStatus;
import gh.edu.clet.sfl.facilities.catering.domain.Variance;
import gh.edu.clet.sfl.facilities.catering.domain.VarianceKind;
import gh.edu.clet.sfl.facilities.catering.domain.Venue;
import gh.edu.clet.sfl.facilities.shared.api.PageResponse;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
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
 * S172 Catering &amp; Cafeteria Management. Creates answer 201; an unknown id 404; a transition the record cannot make,
 * or a service that is not valid yet, 422 with every reason named; an approval by the person who requested it 403;
 * and every transition accepts the version the caller last saw, answering 409 if someone else got there first.
 */
@RestController
@RequestMapping("/api/v1/facilities/catering")
public class CateringController {

    private final CateringConfigService config;
    private final CateringServiceService services;
    private final CateringOpsService ops;
    private final gh.edu.clet.sfl.facilities.catering.application.CateringWorkOrderService workOrders;

    public CateringController(CateringConfigService config, CateringServiceService services, CateringOpsService ops,
            gh.edu.clet.sfl.facilities.catering.application.CateringWorkOrderService workOrders) {
        this.workOrders = workOrders;
        this.config = config;
        this.services = services;
        this.ops = ops;
    }

    @GetMapping("/dashboard")
    public ApiResponse<CateringOpsService.Dashboard> dashboard(@RequestParam String siteCode,
            @RequestParam(defaultValue = "90") int periodDays, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(ops.dashboard(siteCode, periodDays, new Caller(actor, channel)));
    }

    // ---- configuration

    @GetMapping("/configuration")
    public ApiResponse<CateringConfigService.Configuration> configuration(@RequestParam String siteCode, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(config.configuration(siteCode, new Caller(actor, channel)));
    }

    @PostMapping("/suppliers")
    public ResponseEntity<ApiResponse<Supplier>> createSupplier(@Valid @RequestBody SupplierRequest r, ActorContext actor,
            SourceChannel channel) {
        return created(config.createSupplier(r.code(), r.name(), r.certificateReference(), r.certificateExpiresOn(),
                r.financeReference(), new Caller(actor, channel)));
    }

    @PostMapping("/suppliers/{id}/update")
    public ApiResponse<Supplier> updateSupplier(@PathVariable UUID id, @Valid @RequestBody SupplierUpdate r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(config.updateSupplier(id, r.certificateReference(), r.certificateExpiresOn(), r.status(),
                r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/venues")
    public ResponseEntity<ApiResponse<Venue>> createVenue(@Valid @RequestBody VenueRequest r, ActorContext actor,
            SourceChannel channel) {
        return created(config.createVenue(r.siteCode(), r.code(), r.name(), r.roomId(), r.capacity(), new Caller(actor, channel)));
    }

    @PostMapping("/venues/{id}/update")
    public ApiResponse<Venue> updateVenue(@PathVariable UUID id, @Valid @RequestBody VenueUpdate r, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(config.updateVenue(id, r.name(), r.capacity(), r.active(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/menus")
    public ResponseEntity<ApiResponse<Menu>> createMenu(@Valid @RequestBody MenuRequest r, ActorContext actor,
            SourceChannel channel) {
        return created(config.createMenu(r.siteCode(), r.code(), r.name(), r.description(), new Caller(actor, channel)));
    }

    @PostMapping("/menus/{id}/items")
    public ResponseEntity<ApiResponse<MenuItem>> addItem(@PathVariable UUID id, @Valid @RequestBody ItemRequest r,
            ActorContext actor, SourceChannel channel) {
        return created(config.addItem(id, r.name(), r.allergens(), r.allergensDeclared(), r.dietaryTags(),
                new Caller(actor, channel)));
    }

    @PostMapping("/items/{id}/update")
    public ApiResponse<MenuItem> updateItem(@PathVariable UUID id, @Valid @RequestBody ItemUpdate r, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(config.updateItem(id, r.allergens(), r.allergensDeclared(), r.dietaryTags(),
                new Caller(actor, channel)));
    }

    @PostMapping("/menus/{id}/approve")
    public ApiResponse<Menu> approveMenu(@PathVariable UUID id, @RequestBody(required = false) VersionRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(config.approveMenu(id, version(r), new Caller(actor, channel)));
    }

    @PostMapping("/menus/{id}/retire")
    public ApiResponse<Menu> retireMenu(@PathVariable UUID id, @RequestBody(required = false) VersionRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(config.retireMenu(id, version(r), new Caller(actor, channel)));
    }

    // ---- services

    @GetMapping("/services")
    public ApiResponse<PageResponse<CateringService>> listServices(@RequestParam String siteCode,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(paged(services.list(siteCode, status, page, size, new Caller(actor, channel))));
    }

    @PostMapping("/services")
    public ResponseEntity<ApiResponse<CateringService>> createService(@Valid @RequestBody ServiceRequest r,
            ActorContext actor, SourceChannel channel) {
        return created(services.create(new CateringServiceService.Create(r.siteCode(), r.venueId(), r.menuId(),
                r.supplierId(), r.contextType(), r.contextReference(), r.title(), r.startsAt(), r.expectedGuests(),
                r.plannedPortions(), r.cutoffHours(), new Caller(actor, channel))));
    }

    @GetMapping("/services/{id}")
    public ApiResponse<CateringServiceService.ServiceDetail> service(@PathVariable UUID id, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(services.get(id, new Caller(actor, channel)));
    }

    @PostMapping("/services/{id}/change")
    public ApiResponse<CateringService> change(@PathVariable UUID id, @Valid @RequestBody ChangeRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(services.change(new CateringServiceService.Change(id, r.expectedGuests(), r.plannedPortions(),
                r.venueId(), r.menuId(), r.supplierId(), r.reason(), r.version(), new Caller(actor, channel))));
    }

    @PostMapping("/services/{id}/submit")
    public ApiResponse<CateringService> submit(@PathVariable UUID id, @RequestBody(required = false) VersionRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(services.submit(id, version(r), new Caller(actor, channel)));
    }

    @PostMapping("/services/{id}/approve")
    public ApiResponse<CateringService> approve(@PathVariable UUID id, @RequestBody(required = false) ApproveRequest r,
            ActorContext actor, SourceChannel channel) {
        ApproveRequest request = r == null ? new ApproveRequest(null, null, null) : r;
        return ApiResponse.ok(services.approve(id, request.capacityReason(), request.supplierReason(), request.version(),
                new Caller(actor, channel)));
    }

    @PostMapping("/services/{id}/confirm")
    public ApiResponse<CateringService> confirm(@PathVariable UUID id, @RequestBody(required = false) VersionRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(services.confirm(id, version(r), new Caller(actor, channel)));
    }

    @PostMapping("/services/{id}/deliver")
    public ApiResponse<CateringService> deliver(@PathVariable UUID id, @Valid @RequestBody DeliverRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(services.deliver(id, r.deliveredPortions(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/services/{id}/cancel")
    public ApiResponse<CateringService> cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(services.cancel(id, r.reason(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/services/{id}/dietary")
    public ResponseEntity<ApiResponse<DietaryRequest>> addDietary(@PathVariable UUID id,
            @Valid @RequestBody DietaryRequestBody r, ActorContext actor, SourceChannel channel) {
        return created(services.addDietary(id, r.personReference(), r.needType(), r.needCode(), r.authorisedBy(),
                new Caller(actor, channel)));
    }

    @PostMapping("/dietary/{id}/substitute")
    public ApiResponse<DietaryRequest> substitute(@PathVariable UUID id, @Valid @RequestBody SubstituteRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(services.substitute(id, r.itemId(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/dietary/{id}/waive")
    public ApiResponse<DietaryRequest> waive(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(services.waive(id, r.reason(), r.version(), new Caller(actor, channel)));
    }

    // ---- checks, exceptions, variances, evidence, reconciliation

    @PostMapping("/checks")
    public ResponseEntity<ApiResponse<CateringCheck>> recordCheck(@Valid @RequestBody CheckRequest r, ActorContext actor,
            SourceChannel channel) {
        return created(ops.recordCheck(new CateringOpsService.Check(r.siteCode(), r.serviceId(), r.supplierId(),
                r.checkType(), r.holdType(), r.temperatureC(), r.passed(), r.notes(), new Caller(actor, channel))));
    }

    @GetMapping("/exceptions")
    public ApiResponse<PageResponse<CateringException>> exceptions(@RequestParam String siteCode,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(paged(ops.exceptions(siteCode, status, page, size, new Caller(actor, channel))));
    }

    @PostMapping("/exceptions")
    public ResponseEntity<ApiResponse<CateringException>> raiseException(@Valid @RequestBody ExceptionRequest r,
            ActorContext actor, SourceChannel channel) {
        return created(ops.raiseException(r.siteCode(), r.serviceId(), r.exceptionType(), r.description(),
                r.ownerReference(), new Caller(actor, channel)));
    }

    @GetMapping("/exceptions/work-orders")
    public ApiResponse<java.util.List<CateringStore.ExceptionWorkOrder>> exceptionWorkOrders(@RequestParam String siteCode, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(workOrders.at(siteCode, new Caller(actor, channel)));
    }

    @PostMapping("/exceptions/{id}/work-order/retry")
    public ApiResponse<CateringStore.ExceptionWorkOrder> retryWorkOrder(@PathVariable UUID id, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(workOrders.retry(id, new Caller(actor, channel)));
    }

    @PostMapping("/exceptions/{id}/resolve")
    public ApiResponse<CateringException> resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(ops.resolveException(id, r.resolution(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/exceptions/{id}/link-incident")
    public ApiResponse<CateringException> linkIncident(@PathVariable UUID id, @Valid @RequestBody TextRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(ops.linkIncident(id, r.text(), new Caller(actor, channel)));
    }

    @PostMapping("/services/{id}/variances")
    public ResponseEntity<ApiResponse<Variance>> recordVariance(@PathVariable UUID id,
            @Valid @RequestBody VarianceRequest r, ActorContext actor, SourceChannel channel) {
        return created(ops.recordVariance(id, r.kind(), r.planned(), r.actual(), r.ownerReference(), r.reason(),
                new Caller(actor, channel)));
    }

    @PostMapping("/variances/{id}/approve")
    public ApiResponse<Variance> approveVariance(@PathVariable UUID id, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(ops.approveVariance(id, new Caller(actor, channel)));
    }

    @PostMapping("/services/{id}/evidence")
    public ResponseEntity<ApiResponse<CateringEvidence>> submitEvidence(@PathVariable UUID id,
            @Valid @RequestBody EvidenceRequest r, ActorContext actor, SourceChannel channel) {
        return created(ops.submitEvidence(id, r.kind(), r.reference(), r.fileName(), r.mediaType(), r.sizeBytes(),
                r.contentHash(), new Caller(actor, channel)));
    }

    @PostMapping("/services/{id}/reconcile")
    public ApiResponse<CateringOpsService.Reconciliation> reconcile(@PathVariable UUID id,
            @Valid @RequestBody ReconcileRequest r, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(ops.reconcile(id, r.purchaseReference(), r.invoiceReference(), r.version(),
                new Caller(actor, channel)));
    }

    @PostMapping("/services/{id}/close")
    public ApiResponse<CateringService> close(@PathVariable UUID id, @RequestBody(required = false) VersionRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(ops.close(id, version(r), new Caller(actor, channel)));
    }

    @GetMapping("/services/{id}/pack")
    public ApiResponse<CateringOpsService.Pack> pack(@PathVariable UUID id, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(ops.pack(id, new Caller(actor, channel)));
    }

    // ---- shapes

    private static <T> ResponseEntity<ApiResponse<T>> created(T body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    private static <T> PageResponse<T> paged(CateringStore.Page<T> page) {
        int size = Math.max(1, page.size());
        return new PageResponse<>(page.items(), page.total(), (int) Math.ceil(page.total() / (double) size), page.page(),
                page.size());
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

    public record SupplierRequest(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 120) String certificateReference, @NotNull LocalDate certificateExpiresOn,
            @Size(max = 120) String financeReference) {
    }

    public record SupplierUpdate(@Size(max = 120) String certificateReference, LocalDate certificateExpiresOn,
            SupplierStatus status, Long version) {
    }

    public record VenueRequest(@NotBlank @Size(max = 40) String siteCode, @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 160) String name, UUID roomId, @Positive int capacity) {
    }

    public record VenueUpdate(@Size(max = 160) String name, @Positive Integer capacity, Boolean active, Long version) {
    }

    public record MenuRequest(@NotBlank @Size(max = 40) String siteCode, @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 160) String name, @Size(max = 1000) String description) {
    }

    public record ItemRequest(@NotBlank @Size(max = 160) String name, Set<Allergen> allergens, boolean allergensDeclared,
            Set<DietaryTag> dietaryTags) {
    }

    public record ItemUpdate(Set<Allergen> allergens, boolean allergensDeclared, Set<DietaryTag> dietaryTags) {
    }

    public record ServiceRequest(@NotBlank @Size(max = 40) String siteCode, @NotNull UUID venueId, @NotNull UUID menuId,
            @NotNull UUID supplierId, @NotNull ContextType contextType, @Size(max = 160) String contextReference,
            @NotBlank @Size(max = 240) String title, @NotNull Instant startsAt, @Positive int expectedGuests,
            @Positive int plannedPortions, Integer cutoffHours) {
    }

    public record ChangeRequest(@Positive Integer expectedGuests, @Positive Integer plannedPortions, UUID venueId, UUID menuId,
            UUID supplierId, @Size(max = 2000) String reason, Long version) {
    }

    public record ApproveRequest(@Size(max = 1000) String capacityReason, @Size(max = 1000) String supplierReason, Long version) {
    }

    public record DeliverRequest(int deliveredPortions, Long version) {
    }

    public record DietaryRequestBody(@NotBlank @Size(max = 80) String personReference, @NotNull NeedType needType,
            @NotBlank @Size(max = 30) String needCode, @NotBlank @Size(max = 160) String authorisedBy) {
    }

    public record SubstituteRequest(@NotNull UUID itemId, Long version) {
    }

    public record CheckRequest(@Size(max = 40) String siteCode, UUID serviceId, UUID supplierId, @NotNull CheckType checkType,
            HoldType holdType, BigDecimal temperatureC, Boolean passed, @Size(max = 1000) String notes) {
    }

    public record ExceptionRequest(@Size(max = 40) String siteCode, UUID serviceId, @NotNull ExceptionType exceptionType,
            @NotBlank @Size(max = 2000) String description, @NotBlank @Size(max = 160) String ownerReference) {
    }

    public record ResolveRequest(@NotBlank @Size(max = 2000) String resolution, Long version) {
    }

    public record VarianceRequest(@NotNull VarianceKind kind, @NotNull BigDecimal planned, @NotNull BigDecimal actual,
            @NotBlank @Size(max = 160) String ownerReference, @NotBlank @Size(max = 1000) String reason) {
    }

    public record EvidenceRequest(@NotNull EvidenceKind kind, @NotBlank @Size(max = 240) String reference,
            @NotBlank @Size(max = 240) String fileName, @NotBlank @Size(max = 120) String mediaType,
            @Positive long sizeBytes, @NotBlank @Size(max = 128) String contentHash) {
    }

    public record ReconcileRequest(@NotBlank @Size(max = 120) String purchaseReference,
            @Size(max = 120) String invoiceReference, Long version) {
    }
}
