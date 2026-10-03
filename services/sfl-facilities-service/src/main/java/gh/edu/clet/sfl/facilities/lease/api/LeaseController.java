package gh.edu.clet.sfl.facilities.lease.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.facilities.lease.application.LeaseAgreementService;
import gh.edu.clet.sfl.facilities.lease.application.LeaseConfigService;
import gh.edu.clet.sfl.facilities.lease.application.LeaseOpsService;
import gh.edu.clet.sfl.facilities.lease.application.LeaseStore;
import gh.edu.clet.sfl.facilities.lease.application.LeaseWorkOrderService;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseWorkOrder;
import gh.edu.clet.sfl.facilities.lease.domain.Agreement;
import gh.edu.clet.sfl.facilities.lease.domain.AgreementKind;
import gh.edu.clet.sfl.facilities.lease.domain.Amendment;
import gh.edu.clet.sfl.facilities.lease.domain.AmendmentKind;
import gh.edu.clet.sfl.facilities.lease.domain.Direction;
import gh.edu.clet.sfl.facilities.lease.domain.DocumentKind;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseAlert;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseDocument;
import gh.edu.clet.sfl.facilities.lease.domain.Obligation;
import gh.edu.clet.sfl.facilities.lease.domain.ObligationKind;
import gh.edu.clet.sfl.facilities.lease.domain.RenewalType;
import gh.edu.clet.sfl.facilities.shared.api.PageResponse;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S177 Lease &amp; Tenancy Management. Creates answer 201; an unknown id 404; an agreement that is not complete, an
 * amendment held for legal review, or a transition the record cannot make 422 with the reasons named; an approval
 * by the person who requested it 403; and every transition accepts the version the caller last saw, answering 409
 * if someone else got there first. Without the financial grant rent, deposit and exposure come back null.
 */
@RestController
@RequestMapping("/api/v1/facilities/leases")
public class LeaseController {

    private final LeaseAgreementService agreements;
    private final LeaseConfigService config;
    private final LeaseOpsService ops;
    private final LeaseWorkOrderService workOrders;

    public LeaseController(LeaseAgreementService agreements, LeaseConfigService config, LeaseOpsService ops, LeaseWorkOrderService workOrders) {
        this.workOrders = workOrders;
        this.agreements = agreements;
        this.config = config;
        this.ops = ops;
    }

    // ---- calendar

    @GetMapping("/calendar")
    public ApiResponse<LeaseConfigService.Calendar> calendar(ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(config.calendar(new Caller(actor, channel)));
    }

    @PostMapping("/calendar/settings")
    public ApiResponse<LeaseConfigService.Calendar> settings(@Valid @RequestBody SettingsRequest r, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(config.saveSettings(r.timezone(), r.weekend(), new Caller(actor, channel)));
    }

    @PostMapping("/calendar/holidays")
    public ApiResponse<LeaseConfigService.Calendar> addHoliday(@Valid @RequestBody HolidayRequest r, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(config.addHoliday(r.date(), r.name(), new Caller(actor, channel)));
    }

    @DeleteMapping("/calendar/holidays/{date}")
    public ApiResponse<LeaseConfigService.Calendar> removeHoliday(@PathVariable LocalDate date, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(config.removeHoliday(date, new Caller(actor, channel)));
    }

    // ---- portfolio and alerts

    @GetMapping("/portfolio")
    public ApiResponse<LeaseOpsService.Portfolio> portfolio(@RequestParam String siteCode, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(ops.portfolio(siteCode, new Caller(actor, channel)));
    }

    @GetMapping("/alerts")
    public ApiResponse<PageResponse<LeaseAlert>> alerts(@RequestParam String siteCode, @RequestParam(defaultValue = "false") boolean openOnly,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(paged(ops.alerts(siteCode, openOnly, page, size, new Caller(actor, channel))));
    }

    @PostMapping("/alerts/{id}/acknowledge")
    public ApiResponse<LeaseAlert> acknowledge(@PathVariable UUID id, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(ops.acknowledge(id, new Caller(actor, channel)));
    }

    // ---- agreements

    @GetMapping("/agreements")
    public ApiResponse<PageResponse<Agreement>> list(@RequestParam String siteCode, @RequestParam(required = false) String status,
            @RequestParam(required = false) String owner, @RequestParam(required = false) Integer endsWithinDays,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(paged(agreements.list(siteCode, status, owner, endsWithinDays, page, size, new Caller(actor, channel))));
    }

    @PostMapping("/agreements")
    public ResponseEntity<ApiResponse<Agreement>> register(@Valid @RequestBody RegisterRequest r, ActorContext actor, SourceChannel channel) {
        return created(agreements.register(new LeaseAgreementService.Register(r.siteCode(), r.roomId(), r.propertyReference(), r.kind(),
                r.direction(), r.title(), r.counterpartyReference(), r.contractReference(), r.financeReference(), r.ownerReference(),
                r.startDate(), r.endDate(), r.renewalType(), r.renewalTermMonths(), r.noticeDays(), r.rentReviewDate(), r.annualRent(),
                r.depositAmount(), r.currency(), new Caller(actor, channel))));
    }

    @GetMapping("/agreements/{id}")
    public ApiResponse<LeaseAgreementService.Detail> agreement(@PathVariable UUID id, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(agreements.get(id, new Caller(actor, channel)));
    }

    @PostMapping("/agreements/{id}/update")
    public ApiResponse<Agreement> update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest r, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(agreements.update(new LeaseAgreementService.Update(id, r.propertyReference(), r.title(), r.counterpartyReference(),
                r.contractReference(), r.financeReference(), r.ownerReference(), r.startDate(), r.endDate(), r.renewalType(),
                r.renewalTermMonths(), r.noticeDays(), r.rentReviewDate(), r.annualRent(), r.depositAmount(), r.currency(), r.version(),
                new Caller(actor, channel))));
    }

    @PostMapping("/agreements/{id}/submit")
    public ApiResponse<Agreement> submit(@PathVariable UUID id, @RequestBody(required = false) VersionRequest r, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(agreements.submit(id, version(r), new Caller(actor, channel)));
    }

    @PostMapping("/agreements/{id}/return-to-draft")
    public ApiResponse<Agreement> returnToDraft(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(agreements.returnToDraft(id, r.reason(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/agreements/{id}/approve")
    public ApiResponse<Agreement> approve(@PathVariable UUID id, @RequestBody(required = false) VersionRequest r, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(agreements.approve(id, version(r), new Caller(actor, channel)));
    }

    @PostMapping("/agreements/{id}/reassign")
    public ApiResponse<Agreement> reassign(@PathVariable UUID id, @Valid @RequestBody ReassignRequest r, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(agreements.reassign(id, r.ownerReference(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/agreements/{id}/documents")
    public ResponseEntity<ApiResponse<LeaseDocument>> fileDocument(@PathVariable UUID id, @Valid @RequestBody DocumentRequest r, ActorContext actor,
            SourceChannel channel) {
        return created(agreements.fileDocument(id, r.kind(), r.reference(), r.fileName(), r.mediaType(), r.sizeBytes(), r.contentHash(),
                r.expiresOn(), new Caller(actor, channel)));
    }

    // ---- amendments

    @GetMapping("/amendments")
    public ApiResponse<PageResponse<Amendment>> amendments(@RequestParam String siteCode, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(paged(agreements.amendments(siteCode, status, page, size, new Caller(actor, channel))));
    }

    @PostMapping("/agreements/{id}/amendments")
    public ResponseEntity<ApiResponse<Amendment>> propose(@PathVariable UUID id, @Valid @RequestBody AmendmentRequest r, ActorContext actor,
            SourceChannel channel) {
        return created(agreements.propose(new LeaseAgreementService.Propose(id, r.kind(), r.newEndDate(), r.newAnnualRent(), r.newDepositAmount(),
                r.newNoticeDays(), r.newRentReviewDate(), r.newRenewalTermMonths(), r.effectiveOn(), r.reason(), new Caller(actor, channel))));
    }

    @PostMapping("/amendments/{id}/decide")
    public ApiResponse<Amendment> decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest r, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(agreements.decide(id, r.approve(), r.reason(), r.version(), new Caller(actor, channel)));
    }

    @PostMapping("/amendments/{id}/clear-legal-review")
    public ApiResponse<Amendment> clearLegal(@PathVariable UUID id, @Valid @RequestBody NoteRequest r, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(agreements.clearLegalReview(id, r.note(), new Caller(actor, channel)));
    }

    @PostMapping("/amendments/{id}/withdraw")
    public ApiResponse<Amendment> withdraw(@PathVariable UUID id, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(agreements.withdraw(id, new Caller(actor, channel)));
    }

    // ---- obligations

    @GetMapping("/obligations")
    public ApiResponse<PageResponse<Obligation>> obligations(@RequestParam String siteCode, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer dueWithinDays, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(paged(agreements.obligations(siteCode, status, dueWithinDays, page, size, new Caller(actor, channel))));
    }

    @PostMapping("/agreements/{id}/obligations")
    public ResponseEntity<ApiResponse<Obligation>> addObligation(@PathVariable UUID id, @Valid @RequestBody ObligationRequest r, ActorContext actor,
            SourceChannel channel) {
        return created(agreements.addObligation(id, r.kind(), r.title(), r.dueOn(), r.ownerReference(), new Caller(actor, channel)));
    }

    @PostMapping("/obligations/{id}/complete")
    public ApiResponse<Obligation> complete(@PathVariable UUID id, @RequestBody(required = false) NoteVersionRequest r, ActorContext actor,
            SourceChannel channel) {
        NoteVersionRequest request = r == null ? new NoteVersionRequest(null, null) : r;
        return ApiResponse.ok(agreements.completeObligation(id, request.note(), request.version(), new Caller(actor, channel)));
    }

    @PostMapping("/obligations/{id}/waive")
    public ApiResponse<Obligation> waive(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(agreements.waiveObligation(id, r.reason(), r.version(), new Caller(actor, channel)));
    }

    // ---- corrective work (S153)

    public record WorkOrderRequest(UUID obligationId, @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 2000) String description) {
    }

    @PostMapping("/agreements/{id}/work-orders")
    public ResponseEntity<ApiResponse<LeaseWorkOrder>> raiseWorkOrder(@PathVariable UUID id, @Valid @RequestBody WorkOrderRequest r, ActorContext actor,
            SourceChannel channel) {
        return created(workOrders.raise(id, r.obligationId(), r.description(), new Caller(actor, channel)));
    }

    @PostMapping("/work-orders/{id}/retry")
    public ApiResponse<LeaseWorkOrder> retryWorkOrder(@PathVariable UUID id, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(workOrders.retry(id, new Caller(actor, channel)));
    }

    // ---- shapes

    private static <T> ResponseEntity<ApiResponse<T>> created(T body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    private static <T> PageResponse<T> paged(LeaseStore.Page<T> page) {
        int size = Math.max(1, page.size());
        return new PageResponse<>(page.items(), page.total(), (int) Math.ceil(page.total() / (double) size), page.page(), page.size());
    }

    private static Long version(VersionRequest body) {
        return body == null ? null : body.version();
    }

    public record VersionRequest(Long version) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 2000) String reason, Long version) {
    }

    public record NoteRequest(@NotBlank @Size(max = 2000) String note) {
    }

    public record NoteVersionRequest(@Size(max = 1000) String note, Long version) {
    }

    public record SettingsRequest(@NotBlank @Size(max = 60) String timezone, Set<DayOfWeek> weekend) {
    }

    public record HolidayRequest(@NotNull LocalDate date, @NotBlank @Size(max = 160) String name) {
    }

    public record RegisterRequest(@NotBlank @Size(max = 40) String siteCode, UUID roomId, @NotBlank @Size(max = 240) String propertyReference,
            @NotNull AgreementKind kind, @NotNull Direction direction, @NotBlank @Size(max = 240) String title,
            @Size(max = 160) String counterpartyReference, @Size(max = 160) String contractReference, @Size(max = 160) String financeReference,
            @Size(max = 160) String ownerReference, @NotNull LocalDate startDate, @NotNull LocalDate endDate, @NotNull RenewalType renewalType,
            @Positive Integer renewalTermMonths, Integer noticeDays, LocalDate rentReviewDate, BigDecimal annualRent, BigDecimal depositAmount,
            @Size(max = 3) String currency) {
    }

    public record UpdateRequest(@Size(max = 240) String propertyReference, @Size(max = 240) String title, @Size(max = 160) String counterpartyReference,
            @Size(max = 160) String contractReference, @Size(max = 160) String financeReference, @Size(max = 160) String ownerReference,
            LocalDate startDate, LocalDate endDate, RenewalType renewalType, @Positive Integer renewalTermMonths, Integer noticeDays,
            LocalDate rentReviewDate, BigDecimal annualRent, BigDecimal depositAmount, @Size(max = 3) String currency, Long version) {
    }

    public record ReassignRequest(@NotBlank @Size(max = 160) String ownerReference, Long version) {
    }

    public record DocumentRequest(@NotNull DocumentKind kind, @NotBlank @Size(max = 240) String reference, @NotBlank @Size(max = 240) String fileName,
            @NotBlank @Size(max = 120) String mediaType, @Positive long sizeBytes, @NotBlank @Size(max = 128) String contentHash, LocalDate expiresOn) {
    }

    public record AmendmentRequest(@NotNull AmendmentKind kind, LocalDate newEndDate, BigDecimal newAnnualRent, BigDecimal newDepositAmount,
            Integer newNoticeDays, LocalDate newRentReviewDate, @Positive Integer newRenewalTermMonths, LocalDate effectiveOn,
            @NotBlank @Size(max = 2000) String reason) {
    }

    public record DecisionRequest(boolean approve, @Size(max = 2000) String reason, Long version) {
    }

    public record ObligationRequest(@NotNull ObligationKind kind, @NotBlank @Size(max = 240) String title, @NotNull LocalDate dueOn,
            @Size(max = 160) String ownerReference) {
    }
}
