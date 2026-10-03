package gh.edu.clet.sfl.facilities.waste.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.api.PageResponse;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import gh.edu.clet.sfl.facilities.waste.application.WasteCollectionService;
import gh.edu.clet.sfl.facilities.waste.application.WasteConfigService;
import gh.edu.clet.sfl.facilities.waste.application.WasteExceptionService;
import gh.edu.clet.sfl.facilities.waste.application.WasteReportService;
import gh.edu.clet.sfl.facilities.waste.application.WasteStore;
import gh.edu.clet.sfl.facilities.waste.domain.ApprovalStatus;
import gh.edu.clet.sfl.facilities.waste.domain.DestinationType;
import gh.edu.clet.sfl.facilities.waste.domain.EvidenceKind;
import gh.edu.clet.sfl.facilities.waste.domain.ExceptionType;
import gh.edu.clet.sfl.facilities.waste.domain.QuantityBasis;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCarrier;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCategory;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCollection;
import gh.edu.clet.sfl.facilities.waste.domain.WasteDestination;
import gh.edu.clet.sfl.facilities.waste.domain.WasteEvidence;
import gh.edu.clet.sfl.facilities.waste.domain.WasteException;
import gh.edu.clet.sfl.facilities.waste.domain.WastePoint;
import gh.edu.clet.sfl.facilities.waste.domain.WasteStream;
import gh.edu.clet.sfl.facilities.waste.domain.WasteUnit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * S178 Waste Management &amp; Recycling Tracking. Creates answer 201; an unknown id answers 404; a transition
 * the record cannot make, or a chain of custody that is not complete, answers 422; and every transition
 * accepts the version the caller last saw, answering 409 if someone else got there first.
 */
@RestController
@RequestMapping("/api/v1/facilities/waste")
public class WasteController {

    private final WasteConfigService config;
    private final WasteCollectionService collections;
    private final WasteExceptionService exceptions;
    private final WasteReportService reports;

    public WasteController(WasteConfigService config, WasteCollectionService collections,
            WasteExceptionService exceptions, WasteReportService reports) {
        this.config = config;
        this.collections = collections;
        this.exceptions = exceptions;
        this.reports = reports;
    }

    // ---- reporting

    @GetMapping("/dashboard")
    public ApiResponse<WasteReportService.Dashboard> dashboard(@RequestParam String siteCode,
            @RequestParam(defaultValue = "90") int periodDays, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(reports.dashboard(siteCode, periodDays, new Caller(actor, channel)));
    }

    @GetMapping("/report")
    public ApiResponse<WasteReportService.Report> report(@RequestParam String siteCode,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(reports.report(siteCode, from, to, new Caller(actor, channel)));
    }

    // ---- configuration

    @GetMapping("/configuration")
    public ApiResponse<WasteConfigService.Configuration> configuration(@RequestParam String siteCode,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(config.configuration(siteCode, new Caller(actor, channel)));
    }

    @PostMapping("/streams")
    public ResponseEntity<ApiResponse<WasteStream>> createStream(@Valid @RequestBody StreamRequest r,
            ActorContext actor, SourceChannel channel) {
        return created(config.createStream(r.code(), r.name(), r.category(), r.hazardous(), r.diverted(),
                r.handlingRules(), new Caller(actor, channel)));
    }

    @PostMapping("/streams/{id}/update")
    public ApiResponse<WasteStream> updateStream(@PathVariable UUID id, @Valid @RequestBody StreamUpdate r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(config.updateStream(id, r.name(), r.hazardous(), r.diverted(), r.handlingRules(),
                r.active(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/points")
    public ResponseEntity<ApiResponse<WastePoint>> createPoint(@Valid @RequestBody PointRequest r, ActorContext actor,
            SourceChannel channel) {
        return created(config.createPoint(r.siteCode(), r.code(), r.name(), r.roomId(), r.containerDescription(),
                new Caller(actor, channel)));
    }

    @PostMapping("/points/{id}/update")
    public ApiResponse<WastePoint> updatePoint(@PathVariable UUID id, @Valid @RequestBody PointUpdate r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(config.updatePoint(id, r.name(), r.containerDescription(), r.active(), r.version(),
                new Caller(actor, channel)));
    }

    @PostMapping("/carriers")
    public ResponseEntity<ApiResponse<WasteCarrier>> createCarrier(@Valid @RequestBody CarrierRequest r,
            ActorContext actor, SourceChannel channel) {
        return created(config.createCarrier(r.code(), r.name(), r.licenceReference(), r.licenceExpiresOn(),
                Boolean.TRUE.equals(r.hazardousApproved()), new Caller(actor, channel)));
    }

    @PostMapping("/carriers/{id}/update")
    public ApiResponse<WasteCarrier> updateCarrier(@PathVariable UUID id, @Valid @RequestBody CarrierUpdate r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(config.updateCarrier(id, r.licenceReference(), r.licenceExpiresOn(),
                r.hazardousApproved(), r.status(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/destinations")
    public ResponseEntity<ApiResponse<WasteDestination>> createDestination(
            @Valid @RequestBody DestinationRequest r, ActorContext actor, SourceChannel channel) {
        return created(config.createDestination(r.code(), r.name(), r.destinationType(), r.permitReference(),
                r.permitExpiresOn(), Boolean.TRUE.equals(r.acceptsHazardous()), new Caller(actor, channel)));
    }

    @PostMapping("/destinations/{id}/update")
    public ApiResponse<WasteDestination> updateDestination(@PathVariable UUID id,
            @Valid @RequestBody DestinationUpdate r, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(config.updateDestination(id, r.permitReference(), r.permitExpiresOn(),
                r.acceptsHazardous(), r.status(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/units")
    public ResponseEntity<ApiResponse<WasteUnit>> saveUnit(@Valid @RequestBody UnitRequest r, ActorContext actor,
            SourceChannel channel) {
        return created(config.saveUnit(r.code(), r.name(), r.kilogramsPerUnit(), new Caller(actor, channel)));
    }

    // ---- collections

    @GetMapping("/collections")
    public ApiResponse<PageResponse<WasteCollection>> listCollections(@RequestParam String siteCode,
            @RequestParam(required = false) String status, @RequestParam(required = false) UUID streamId,
            @RequestParam(defaultValue = "false") boolean hazardousOnly, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(paged(collections.list(siteCode, status, streamId, hazardousOnly, page, size,
                new Caller(actor, channel))));
    }

    @PostMapping("/collections")
    public ResponseEntity<ApiResponse<WasteCollection>> schedule(@Valid @RequestBody ScheduleRequest r,
            ActorContext actor, SourceChannel channel) {
        return created(collections.schedule(r.siteCode(), r.streamId(), r.pointId(), r.carrierId(), r.scheduledFor(),
                new Caller(actor, channel)));
    }

    @GetMapping("/collections/{id}")
    public ApiResponse<WasteCollectionService.CollectionDetail> collection(@PathVariable UUID id, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(collections.get(id, new Caller(actor, channel)));
    }

    @PostMapping("/collections/{id}/record")
    public ApiResponse<WasteCollection> record(@PathVariable UUID id, @Valid @RequestBody RecordRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(collections.record(id, r.collectedOn(), r.quantity(), r.unit(), r.basis(),
                r.manifestReference(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/collections/{id}/hand-over")
    public ApiResponse<WasteCollection> handOver(@PathVariable UUID id, @Valid @RequestBody HandOverRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(collections.handOver(id, r.destinationId(), r.location(), r.version(),
                new Caller(actor, channel)));
    }

    @PostMapping("/collections/{id}/confirm-destination")
    public ApiResponse<WasteCollection> confirmDestination(@PathVariable UUID id,
            @RequestBody(required = false) VersionRequest r, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(collections.confirmDestination(id, version(r), new Caller(actor, channel)));
    }

    @PostMapping("/collections/{id}/certificate")
    public ApiResponse<WasteCollection> certificate(@PathVariable UUID id, @Valid @RequestBody CertificateRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(collections.recordCertificate(id, r.certificateReference(), r.receivedOn(), r.version(),
                new Caller(actor, channel)));
    }

    @PostMapping("/collections/{id}/contaminated")
    public ApiResponse<WasteCollection> contaminated(@PathVariable UUID id, @Valid @RequestBody TextRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(collections.markContaminated(id, r.text(), new Caller(actor, channel)));
    }

    @PostMapping("/collections/{id}/reconcile")
    public ApiResponse<WasteCollection> reconcile(@PathVariable UUID id, @Valid @RequestBody ReconcileRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(collections.reconcile(id, r.quantity(), r.unit(), r.basis(), r.version(),
                new Caller(actor, channel)));
    }

    @PostMapping("/collections/{id}/missed")
    public ApiResponse<WasteCollection> missed(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(collections.markMissed(id, r.reason(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/collections/{id}/cancel")
    public ApiResponse<WasteCollection> cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(collections.cancel(id, r.reason(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/collections/{id}/close")
    public ApiResponse<WasteCollection> close(@PathVariable UUID id, @RequestBody(required = false) VersionRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(collections.close(id, version(r), new Caller(actor, channel)));
    }

    @PostMapping("/collections/{id}/evidence")
    public ResponseEntity<ApiResponse<WasteEvidence>> submitEvidence(@PathVariable UUID id,
            @Valid @RequestBody EvidenceRequest r, ActorContext actor, SourceChannel channel) {
        return created(collections.submitEvidence(id, r.kind(), r.reference(), r.fileName(), r.mediaType(),
                r.sizeBytes(), r.contentHash(), new Caller(actor, channel)));
    }

    @PostMapping("/evidence/{id}/review")
    public ApiResponse<WasteEvidence> review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(collections.reviewEvidence(id, r.accept(), r.reason(), new Caller(actor, channel)));
    }

    // ---- exceptions

    @GetMapping("/exceptions")
    public ApiResponse<PageResponse<WasteException>> listExceptions(@RequestParam String siteCode,
            @RequestParam(required = false) String status, @RequestParam(required = false) String exceptionType,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(paged(exceptions.list(siteCode, status, exceptionType, page, size,
                new Caller(actor, channel))));
    }

    @PostMapping("/exceptions")
    public ResponseEntity<ApiResponse<WasteException>> reportException(@Valid @RequestBody ExceptionRequest r,
            ActorContext actor, SourceChannel channel) {
        return created(exceptions.report(r.siteCode(), r.collectionId(), r.exceptionType(), r.description(),
                r.ownerReference(), new Caller(actor, channel)));
    }

    @PostMapping("/exceptions/{id}/start")
    public ApiResponse<WasteException> startException(@PathVariable UUID id,
            @RequestBody(required = false) VersionRequest r, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(exceptions.start(id, version(r), new Caller(actor, channel)));
    }

    @PostMapping("/exceptions/{id}/assign")
    public ApiResponse<WasteException> assign(@PathVariable UUID id, @Valid @RequestBody TextRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(exceptions.assign(id, r.text(), new Caller(actor, channel)));
    }

    @PostMapping("/exceptions/{id}/link-incident")
    public ApiResponse<WasteException> linkIncident(@PathVariable UUID id, @Valid @RequestBody TextRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(exceptions.linkIncident(id, r.text(), new Caller(actor, channel)));
    }

    @PostMapping("/exceptions/{id}/retry-work-order")
    public ApiResponse<WasteException> retryWorkOrder(@PathVariable UUID id, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(exceptions.retryWorkOrder(id, new Caller(actor, channel)));
    }

    @PostMapping("/exceptions/{id}/resolve")
    public ApiResponse<WasteException> resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(exceptions.resolve(id, r.resolution(), r.version(), new Caller(actor, channel)));
    }

    // ---- shapes

    private static <T> ResponseEntity<ApiResponse<T>> created(T body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    private static <T> PageResponse<T> paged(WasteStore.Page<T> page) {
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

    public record StreamRequest(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 160) String name,
            @NotNull WasteCategory category, boolean hazardous, boolean diverted,
            @Size(max = 2000) String handlingRules) {
    }

    public record StreamUpdate(@Size(max = 160) String name, Boolean hazardous, Boolean diverted,
            @Size(max = 2000) String handlingRules, Boolean active, Long version) {
    }

    public record PointRequest(@NotBlank @Size(max = 40) String siteCode, @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 160) String name, UUID roomId, @Size(max = 240) String containerDescription) {
    }

    public record PointUpdate(@Size(max = 160) String name, @Size(max = 240) String containerDescription,
            Boolean active, Long version) {
    }

    public record CarrierRequest(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 120) String licenceReference, @NotNull LocalDate licenceExpiresOn,
            Boolean hazardousApproved) {
    }

    public record CarrierUpdate(@Size(max = 120) String licenceReference, LocalDate licenceExpiresOn,
            Boolean hazardousApproved, ApprovalStatus status, Long version) {
    }

    public record DestinationRequest(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 160) String name,
            @NotNull DestinationType destinationType, @NotBlank @Size(max = 120) String permitReference,
            @NotNull LocalDate permitExpiresOn, Boolean acceptsHazardous) {
    }

    public record DestinationUpdate(@Size(max = 120) String permitReference, LocalDate permitExpiresOn,
            Boolean acceptsHazardous, ApprovalStatus status, Long version) {
    }

    public record UnitRequest(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 60) String name,
            @NotNull @Positive BigDecimal kilogramsPerUnit) {
    }

    public record ScheduleRequest(@NotBlank @Size(max = 40) String siteCode, @NotNull UUID streamId,
            @NotNull UUID pointId, @NotNull UUID carrierId, @NotNull LocalDate scheduledFor) {
    }

    public record RecordRequest(LocalDate collectedOn, @NotNull @Positive BigDecimal quantity,
            @NotBlank @Size(max = 20) String unit, @NotNull QuantityBasis basis,
            @Size(max = 120) String manifestReference, Long version) {
    }

    public record HandOverRequest(@NotNull UUID destinationId, @Size(max = 160) String location, Long version) {
    }

    public record CertificateRequest(@NotBlank @Size(max = 120) String certificateReference, LocalDate receivedOn,
            Long version) {
    }

    public record ReconcileRequest(@NotNull @Positive BigDecimal quantity, @NotBlank @Size(max = 20) String unit,
            @NotNull QuantityBasis basis, Long version) {
    }

    public record EvidenceRequest(@NotNull EvidenceKind kind, @NotBlank @Size(max = 240) String reference,
            @NotBlank @Size(max = 240) String fileName, @NotBlank @Size(max = 120) String mediaType,
            @Positive long sizeBytes, @NotBlank @Size(max = 128) String contentHash) {
    }

    public record ReviewRequest(boolean accept, @Size(max = 2000) String reason) {
    }

    public record ExceptionRequest(@NotBlank @Size(max = 40) String siteCode, UUID collectionId,
            @NotNull ExceptionType exceptionType, @NotBlank @Size(max = 2000) String description,
            @Size(max = 160) String ownerReference) {
    }

    public record ResolveRequest(@NotBlank @Size(max = 2000) String resolution, Long version) {
    }
}
