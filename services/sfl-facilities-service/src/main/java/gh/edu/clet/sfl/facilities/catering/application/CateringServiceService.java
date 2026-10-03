package gh.edu.clet.sfl.facilities.catering.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.catering.domain.Allergen;
import gh.edu.clet.sfl.facilities.catering.domain.AllergenPolicy;
import gh.edu.clet.sfl.facilities.catering.domain.CateringCheck;
import gh.edu.clet.sfl.facilities.catering.domain.CateringException;
import gh.edu.clet.sfl.facilities.catering.domain.CateringHistoryEntry;
import gh.edu.clet.sfl.facilities.catering.domain.CateringService;
import gh.edu.clet.sfl.facilities.catering.domain.CheckResult;
import gh.edu.clet.sfl.facilities.catering.domain.CheckType;
import gh.edu.clet.sfl.facilities.catering.domain.ContextType;
import gh.edu.clet.sfl.facilities.catering.domain.DietaryRequest;
import gh.edu.clet.sfl.facilities.catering.domain.DietaryTag;
import gh.edu.clet.sfl.facilities.catering.domain.ExceptionStatus;
import gh.edu.clet.sfl.facilities.catering.domain.ExceptionType;
import gh.edu.clet.sfl.facilities.catering.domain.FinanceState;
import gh.edu.clet.sfl.facilities.catering.domain.Menu;
import gh.edu.clet.sfl.facilities.catering.domain.MenuItem;
import gh.edu.clet.sfl.facilities.catering.domain.NeedStatus;
import gh.edu.clet.sfl.facilities.catering.domain.NeedType;
import gh.edu.clet.sfl.facilities.catering.domain.ReadinessPolicy;
import gh.edu.clet.sfl.facilities.catering.domain.ServiceStatus;
import gh.edu.clet.sfl.facilities.catering.domain.Supplier;
import gh.edu.clet.sfl.facilities.catering.domain.Venue;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A catering service from plan to delivery - SRS-SFL-S172-01, -02 and -04.
 *
 * <p>Create, collect dietary needs, request approval, approve, confirm, deliver. What makes the sequence mean
 * something:
 * <ul>
 *   <li><strong>Allergens are visible and checked before approval.</strong> Each need is matched against the menu,
 *       the dishes it rules out are flagged, and a need with nothing safe to serve blocks approval until an approver
 *       accepts a substitution that really is safe.</li>
 *   <li><strong>A service cannot silently appear compliant.</strong> Venue, menu, supplier status and certificate,
 *       and a recent passing supplier check are recomputed at approval <em>and again at confirmation</em>, and shown
 *       on every read. A lapsed check blocks confirmation even if it lapsed after approval.</li>
 *   <li><strong>Changes after approval are controlled.</strong> Changing quantities, venue, supplier or menu, or
 *       adding a dietary need, sends the service back for approval with a reason; after the cancellation cut-off
 *       it takes an approver.</li>
 *   <li><strong>Whoever requested it cannot approve it</strong>, and whoever recorded a dietary need cannot approve
 *       its substitution.</li>
 * </ul>
 */
@Service
public class CateringServiceService {

    private static final Pattern PERSON_REFERENCE = Pattern.compile("[A-Za-z0-9._-]{1,80}");

    private final CateringStore store;
    private final CateringSupport support;
    private final TransactionTemplate inTransaction;

    public CateringServiceService(CateringStore store, CateringSupport support, PlatformTransactionManager transactions) {
        this.store = store;
        this.support = support;
        this.inTransaction = new TransactionTemplate(transactions);
    }

    public record Create(String siteCode, UUID venueId, UUID menuId, UUID supplierId, ContextType contextType,
            String contextReference, String title, Instant startsAt, int expectedGuests, int plannedPortions,
            Integer cutoffHours, Caller caller) {
    }

    public CateringService create(Create c) {
        return inTransaction.execute(tx -> {
            String site = support.validateSite(c.siteCode());
            support.require(c.caller(), SflPermission.FACILITIES_CATERING_MANAGE, site, "CateringService", "new");
            if (c.contextType() == null) {
                throw new IllegalArgumentException("contextType is required");
            }
            if (c.contextType().needsReference() && CateringSupport.blankToNull(c.contextReference()) == null) {
                throw new IllegalArgumentException("A " + c.contextType() + " service must name the "
                        + c.contextType().name().toLowerCase() + " it serves (contextReference).");
            }
            Instant now = support.now();
            if (c.startsAt() == null || !c.startsAt().isAfter(now)) {
                throw new IllegalArgumentException("startsAt must be in the future");
            }
            if (c.expectedGuests() <= 0 || c.plannedPortions() <= 0) {
                throw new IllegalArgumentException("expectedGuests and plannedPortions must be positive");
            }
            int cutoffHours = c.cutoffHours() == null ? 24 : Math.min(Math.max(c.cutoffHours(), 1), 168);
            Venue venue = store.venue(c.venueId()).orElseThrow(() -> new IllegalArgumentException("Unknown venue"));
            Menu menu = store.menu(c.menuId()).orElseThrow(() -> new IllegalArgumentException("Unknown menu"));
            store.supplier(c.supplierId()).orElseThrow(() -> new IllegalArgumentException("Unknown supplier"));
            if (!venue.siteCode().equals(site) || !menu.siteCode().equals(site)) {
                throw new IllegalArgumentException("The venue and the menu must both belong to " + site + ".");
            }
            CateringService service = new CateringService(UUID.randomUUID(),
                    String.format("CAT-S-%06d", store.nextSequence("cat_service_seq")), site, venue.id(), menu.id(),
                    c.supplierId(), c.contextType(), CateringSupport.blankToNull(c.contextReference()),
                    CateringSupport.required(c.title(), "title"), c.startsAt().atZone(ZoneOffset.UTC).toLocalDate(),
                    c.startsAt(), c.startsAt().minus(cutoffHours, ChronoUnit.HOURS), c.expectedGuests(),
                    c.plannedPortions(), null, ServiceStatus.DRAFT, null, null, null, null, null, null, null, null, null,
                    FinanceState.NOT_STARTED, null, null, null, c.caller().actor().actorId(), now, now, 0);
            store.insert(service);
            support.history(site, "SERVICE", service.id(), null, "DRAFT", c.caller().actor().actorId(), null);
            support.audit(c.caller(), AuditAction.CAT_SERVICE_CREATED, "CateringService", service.id(), site, null, service);
            support.publish(CateringEvents.SERVICE_PLANNED, "CateringService", service.id(), site, c.caller().actor(),
                    "serviceId", service.id(), "reference", service.reference(), "contextType", service.contextType(),
                    "contextReference", service.contextReference(), "serviceDate", service.serviceDate());
            return service;
        });
    }

    // ---- reads

    public CateringStore.Page<CateringService> list(String siteCode, String status, int page, int size, Caller caller) {
        String site = CateringSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_CATERING_READ, site, "CateringService", "list");
        String statusFilter = enumName(ServiceStatus.class, status);
        return inTransaction.execute(tx -> store.services(site, statusFilter, Math.max(0, page),
                Math.min(Math.max(1, size), 100)));
    }

    public ServiceDetail get(UUID id, Caller caller) {
        return inTransaction.execute(tx -> {
            CateringService service = support.service(id);
            support.require(caller, SflPermission.FACILITIES_CATERING_READ, service.siteCode(), "CateringService", id.toString());
            Venue venue = store.venue(service.venueId()).orElseThrow();
            Menu menu = store.menu(service.menuId()).orElseThrow();
            Supplier supplier = store.supplier(service.supplierId()).orElseThrow();
            List<MenuItem> items = store.itemsOf(menu.id());
            List<DietaryRequest> requests = store.requestsOf(id);
            List<AllergenPolicy.Finding> findings = AllergenPolicy.assess(requests, items);
            boolean dietaryRead = support.has(caller.actor(), SflPermission.FACILITIES_CATERING_DIETARY_READ);
            if (dietaryRead && !requests.isEmpty()) {
                support.audit(caller, AuditAction.CAT_DIETARY_VIEWED, "CateringService", id, service.siteCode(), null,
                        requests.size() + " dietary request(s)");
            }
            List<ReadinessPolicy.Blocker> readiness = service.status().planning()
                    ? ReadinessPolicy.blockers(service, venue, supplier, menu, items, findings, currentCheck(supplier.id()))
                    : List.of();
            return new ServiceDetail(service, venue, menu, items, supplier, readiness,
                    dietaryRead ? findings.stream().map(f -> new NeedView(f.request(), f.flagged().stream().map(MenuItem::name).toList(),
                            f.blocked(), f.message())).toList() : List.of(), requests.size(),
                    store.checksOf(id), store.exceptionsOf(id), store.variancesOf(id), store.evidenceOf(id).size(),
                    store.history(id), dietaryRead);
        });
    }

    public record NeedView(DietaryRequest request, List<String> flaggedItems, boolean blocked, String message) {
    }

    public record ServiceDetail(CateringService service, Venue venue, Menu menu, List<MenuItem> items, Supplier supplier,
            List<ReadinessPolicy.Blocker> readiness, List<NeedView> needs, int dietaryCount, List<CateringCheck> checks,
            List<CateringException> exceptions, List<gh.edu.clet.sfl.facilities.catering.domain.Variance> variances,
            int evidenceCount, List<CateringHistoryEntry> history, boolean dietaryView) {
    }

    // ---- dietary needs

    public DietaryRequest addDietary(UUID serviceId, String personReference, NeedType needType, String needCode,
            String authorisedBy, Caller caller) {
        return inTransaction.execute(tx -> {
            CateringService service = manageable(serviceId, caller);
            requirePlanning(service);
            if (needType == null) {
                throw new IllegalArgumentException("needType is required");
            }
            String reference = CateringSupport.required(personReference, "personReference");
            if (!PERSON_REFERENCE.matcher(reference).matches()) {
                throw new IllegalArgumentException("personReference must be an opaque reference (letters, digits, . _ -), not a name.");
            }
            String code = CateringSupport.required(needCode, "needCode").toUpperCase();
            try {
                if (needType == NeedType.ALLERGY) {
                    Allergen.valueOf(code);
                } else {
                    DietaryTag.valueOf(code);
                }
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("Unknown " + needType + " need: " + needCode);
            }
            Instant now = support.now();
            DietaryRequest request = new DietaryRequest(UUID.randomUUID(), serviceId, service.siteCode(), reference,
                    needType, code, CateringSupport.required(authorisedBy, "authorisedBy"), NeedStatus.OPEN, null, null,
                    null, caller.actor().actorId(), now, now, 0);
            store.insert(request);
            support.audit(caller, AuditAction.CAT_DIETARY_RECORDED, "CateringService", serviceId, service.siteCode(), null,
                    needType + " " + code);
            reopenIfApproved(service, "A dietary need was added", caller);
            return request;
        });
    }

    /** An approver accepts a different dish for a need with nothing safe on the menu; it must really be safe, and not be approved by whoever recorded the need. */
    public DietaryRequest substitute(UUID requestId, UUID itemId, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            DietaryRequest before = store.request(requestId).orElseThrow(() -> CateringSupport.notFound("Dietary request", requestId));
            support.require(caller, SflPermission.FACILITIES_CATERING_APPROVE, before.siteCode(), "DietaryRequest", requestId.toString());
            CateringSupport.checkVersion(expectedVersion, before.version());
            if (before.status() != NeedStatus.OPEN) {
                throw CateringSupport.invalid("This need is already " + before.status() + ".");
            }
            if (caller.actor().actorId().equals(before.createdBy())) {
                throw new FacilitiesException(FacilitiesErrorCode.CAT_SELF_APPROVAL);
            }
            CateringService service = support.service(before.serviceId());
            MenuItem item = store.item(itemId).orElseThrow(() -> new IllegalArgumentException("Unknown menu item"));
            if (!item.menuId().equals(service.menuId())) {
                throw new IllegalArgumentException("That dish is not on this service's menu.");
            }
            if (!AllergenPolicy.suitable(before, item)) {
                throw new IllegalArgumentException(item.name() + " is not safe for this need.");
            }
            DietaryRequest after = new DietaryRequest(requestId, before.serviceId(), before.siteCode(), before.personReference(),
                    before.needType(), before.needCode(), before.authorisedBy(), NeedStatus.SUBSTITUTED, itemId,
                    caller.actor().actorId(), null, before.createdBy(), before.createdAt(), support.now(), before.version());
            return saveRequest(before, after, caller, "Substitution approved: " + item.name());
        });
    }

    public DietaryRequest waive(UUID requestId, String reason, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            DietaryRequest before = store.request(requestId).orElseThrow(() -> CateringSupport.notFound("Dietary request", requestId));
            support.require(caller, SflPermission.FACILITIES_CATERING_APPROVE, before.siteCode(), "DietaryRequest", requestId.toString());
            CateringSupport.checkVersion(expectedVersion, before.version());
            if (before.status() != NeedStatus.OPEN) {
                throw CateringSupport.invalid("This need is already " + before.status() + ".");
            }
            if (caller.actor().actorId().equals(before.createdBy())) {
                throw new FacilitiesException(FacilitiesErrorCode.CAT_SELF_APPROVAL);
            }
            String why = CateringSupport.required(reason, "reason");
            if (why.length() < 10) {
                throw new IllegalArgumentException("A waiver needs a written reason of at least 10 characters.");
            }
            DietaryRequest after = new DietaryRequest(requestId, before.serviceId(), before.siteCode(), before.personReference(),
                    before.needType(), before.needCode(), before.authorisedBy(), NeedStatus.WAIVED, null, null, why,
                    before.createdBy(), before.createdAt(), support.now(), before.version());
            return saveRequest(before, after, caller, "Waived: " + why);
        });
    }

    // ---- the workflow

    public CateringService submit(UUID serviceId, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            CateringService before = manageable(serviceId, caller);
            CateringSupport.checkVersion(expectedVersion, before.version());
            requireMove(before, ServiceStatus.PENDING_APPROVAL);
            CateringService after = with(before, ServiceStatus.PENDING_APPROVAL, caller.actor().actorId(), null, null,
                    before.capacityExceptionReason(), before.capacityApprovedBy(), before.supplierExceptionReason(),
                    before.supplierApprovedBy(), before.deliveredPortions(), before.deliveredAt(), before.closedAt());
            return save(before, after, caller, "Submitted for approval", AuditAction.CAT_SERVICE_UPDATED);
        });
    }

    /**
     * Approves the service. Blockers an approver may accept - capacity, an expired certificate, an overdue supplier
     * check - are accepted by supplying the reason; the others cannot be, and the refusal lists every one.
     */
    public CateringService approve(UUID serviceId, String capacityReason, String supplierReason, Long expectedVersion,
            Caller caller) {
        return inTransaction.execute(tx -> {
            CateringService before = support.service(serviceId);
            support.require(caller, SflPermission.FACILITIES_CATERING_APPROVE, before.siteCode(), "CateringService",
                    serviceId.toString());
            CateringSupport.checkVersion(expectedVersion, before.version());
            requireMove(before, ServiceStatus.APPROVED);
            if (caller.actor().actorId().equals(before.requestedBy())) {
                throw new FacilitiesException(FacilitiesErrorCode.CAT_SELF_APPROVAL);
            }
            CateringService working = before;
            List<ReadinessPolicy.Blocker> blockers = blockers(working);
            String me = caller.actor().actorId();
            if (CateringSupport.blankToNull(capacityReason) != null && has(blockers, "CAPACITY_EXCEEDED")) {
                working = withExceptions(working, capacityReason.strip(), me, working.supplierExceptionReason(), working.supplierApprovedBy());
            }
            if (CateringSupport.blankToNull(supplierReason) != null
                    && (has(blockers, "SUPPLIER_CERTIFICATE_EXPIRED") || has(blockers, "SUPPLIER_CHECK_OVERDUE"))) {
                working = withExceptions(working, working.capacityExceptionReason(), working.capacityApprovedBy(),
                        supplierReason.strip(), me);
            }
            blockers = blockers(working);
            if (!blockers.isEmpty()) {
                throw refusal(blockers);
            }
            Instant now = support.now();
            CateringService after = with(working, ServiceStatus.APPROVED, working.requestedBy(), me, now,
                    working.capacityExceptionReason(), working.capacityApprovedBy(), working.supplierExceptionReason(),
                    working.supplierApprovedBy(), working.deliveredPortions(), working.deliveredAt(), working.closedAt());
            CateringService saved = save(before, after, caller, "Approved", AuditAction.CAT_SERVICE_APPROVED);
            support.publish(CateringEvents.SERVICE_APPROVED, "CateringService", serviceId, saved.siteCode(), caller.actor(),
                    "serviceId", serviceId, "reference", saved.reference());
            return saved;
        });
    }

    /** Confirmation recomputes every control: an approved service whose supplier check has since lapsed cannot be confirmed. */
    public CateringService confirm(UUID serviceId, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            CateringService before = manageable(serviceId, caller);
            CateringSupport.checkVersion(expectedVersion, before.version());
            requireMove(before, ServiceStatus.CONFIRMED);
            List<ReadinessPolicy.Blocker> blockers = blockers(before);
            if (!blockers.isEmpty()) {
                throw refusal(blockers);
            }
            CateringService saved = save(before, with(before, ServiceStatus.CONFIRMED, before.requestedBy(),
                    before.approvedBy(), before.approvedAt(), before.capacityExceptionReason(), before.capacityApprovedBy(),
                    before.supplierExceptionReason(), before.supplierApprovedBy(), before.deliveredPortions(),
                    before.deliveredAt(), before.closedAt()), caller, "Confirmed", AuditAction.CAT_SERVICE_CONFIRMED);
            support.publish(CateringEvents.SERVICE_CONFIRMED, "CateringService", serviceId, saved.siteCode(), caller.actor(),
                    "serviceId", serviceId, "reference", saved.reference(), "serviceDate", saved.serviceDate(),
                    "plannedPortions", saved.plannedPortions());
            return saved;
        });
    }

    /** Delivery needs a passing food-safety temperature check recorded for the service and no open food-safety incident. */
    public CateringService deliver(UUID serviceId, int deliveredPortions, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            CateringService before = manageable(serviceId, caller);
            CateringSupport.checkVersion(expectedVersion, before.version());
            requireMove(before, ServiceStatus.DELIVERED);
            if (deliveredPortions < 0) {
                throw new IllegalArgumentException("deliveredPortions cannot be negative");
            }
            if (store.checksOf(serviceId).stream().noneMatch(c -> c.checkType() == CheckType.TEMPERATURE
                    && c.result() == CheckResult.PASS)) {
                throw new FacilitiesException(FacilitiesErrorCode.CAT_SERVICE_NOT_VALID,
                        "No passing temperature check has been recorded for this service, so it cannot be marked delivered.");
            }
            if (store.exceptionsOf(serviceId).stream().anyMatch(e -> e.exceptionType() == ExceptionType.FOOD_SAFETY_INCIDENT
                    && e.status() == ExceptionStatus.OPEN)) {
                throw new FacilitiesException(FacilitiesErrorCode.CAT_SERVICE_NOT_VALID,
                        "A food-safety incident is open on this service; resolve it before delivery is recorded.");
            }
            Instant now = support.now();
            CateringService saved = save(before, with(before, ServiceStatus.DELIVERED, before.requestedBy(), before.approvedBy(),
                    before.approvedAt(), before.capacityExceptionReason(), before.capacityApprovedBy(),
                    before.supplierExceptionReason(), before.supplierApprovedBy(), deliveredPortions, now, before.closedAt()),
                    caller, "Delivered " + deliveredPortions + " of " + before.plannedPortions() + " portions",
                    AuditAction.CAT_SERVICE_DELIVERED);
            support.publish(CateringEvents.SERVICE_DELIVERED, "CateringService", serviceId, saved.siteCode(), caller.actor(),
                    "serviceId", serviceId, "reference", saved.reference(), "plannedPortions", saved.plannedPortions(),
                    "deliveredPortions", deliveredPortions);
            return saved;
        });
    }

    /** Free before the cut-off; after it, an approver and a reason. */
    public CateringService cancel(UUID serviceId, String reason, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            CateringService before = support.service(serviceId);
            CateringSupport.checkVersion(expectedVersion, before.version());
            requireMove(before, ServiceStatus.CANCELLED);
            boolean late = before.status() != ServiceStatus.DRAFT && before.afterCutoff(support.now());
            support.require(caller, late ? SflPermission.FACILITIES_CATERING_APPROVE : SflPermission.FACILITIES_CATERING_MANAGE,
                    before.siteCode(), "CateringService", serviceId.toString());
            String why = CateringSupport.required(reason, "reason");
            CateringService saved = save(before, with(before, ServiceStatus.CANCELLED, before.requestedBy(), before.approvedBy(),
                    before.approvedAt(), before.capacityExceptionReason(), before.capacityApprovedBy(),
                    before.supplierExceptionReason(), before.supplierApprovedBy(), before.deliveredPortions(),
                    before.deliveredAt(), before.closedAt(), why), caller, (late ? "Cancelled after the cut-off: " : "Cancelled: ") + why,
                    AuditAction.CAT_SERVICE_CANCELLED);
            support.publish(CateringEvents.SERVICE_CANCELLED, "CateringService", serviceId, saved.siteCode(), caller.actor(),
                    "serviceId", serviceId, "reference", saved.reference(), "afterCutoff", late);
            return saved;
        });
    }

    public record Change(UUID serviceId, Integer expectedGuests, Integer plannedPortions, UUID venueId, UUID menuId,
            UUID supplierId, String reason, Long expectedVersion, Caller caller) {
    }

    /**
     * A controlled change - SRS-SFL-S172-04. A draft changes freely. Once submitted, any change needs a reason and
     * sends the service back for approval; after the cancellation cut-off it needs an approver as well.
     */
    public CateringService change(Change c) {
        return inTransaction.execute(tx -> {
            CateringService before = support.service(c.serviceId());
            CateringSupport.checkVersion(c.expectedVersion(), before.version());
            requirePlanning(before);
            boolean controlled = before.status() != ServiceStatus.DRAFT;
            boolean late = controlled && before.afterCutoff(support.now());
            support.require(c.caller(), late ? SflPermission.FACILITIES_CATERING_APPROVE : SflPermission.FACILITIES_CATERING_MANAGE,
                    before.siteCode(), "CateringService", c.serviceId().toString());
            if (controlled) {
                CateringSupport.required(c.reason(), "reason");
            }
            int guests = c.expectedGuests() == null ? before.expectedGuests() : c.expectedGuests();
            int portions = c.plannedPortions() == null ? before.plannedPortions() : c.plannedPortions();
            if (guests <= 0 || portions <= 0) {
                throw new IllegalArgumentException("expectedGuests and plannedPortions must be positive");
            }
            UUID venueId = c.venueId() == null ? before.venueId() : c.venueId();
            UUID menuId = c.menuId() == null ? before.menuId() : c.menuId();
            UUID supplierId = c.supplierId() == null ? before.supplierId() : c.supplierId();
            if (!venueId.equals(before.venueId()) && !store.venue(venueId).map(v -> v.siteCode().equals(before.siteCode())).orElse(false)) {
                throw new IllegalArgumentException("Unknown venue, or not at this site");
            }
            if (!menuId.equals(before.menuId()) && !store.menu(menuId).map(m -> m.siteCode().equals(before.siteCode())).orElse(false)) {
                throw new IllegalArgumentException("Unknown menu, or not at this site");
            }
            if (!supplierId.equals(before.supplierId())) {
                store.supplier(supplierId).orElseThrow(() -> new IllegalArgumentException("Unknown supplier"));
            }
            CateringService changed = new CateringService(before.id(), before.reference(), before.siteCode(), venueId, menuId,
                    supplierId, before.contextType(), before.contextReference(), before.title(), before.serviceDate(),
                    before.startsAt(), before.cancellationCutoff(), guests, portions, before.deliveredPortions(),
                    controlled ? ServiceStatus.PENDING_APPROVAL : before.status(), before.requestedBy(),
                    controlled ? null : before.approvedBy(), controlled ? null : before.approvedAt(),
                    controlled ? null : before.capacityExceptionReason(), controlled ? null : before.capacityApprovedBy(),
                    controlled ? null : before.supplierExceptionReason(), controlled ? null : before.supplierApprovedBy(),
                    before.purchaseReference(), before.invoiceReference(), before.financeState(), before.deliveredAt(),
                    before.closedAt(), before.cancellationReason(), before.createdBy(), before.createdAt(), support.now(),
                    before.version());
            return save(before, changed, c.caller(), controlled ? (late ? "Changed after the cut-off: " : "Changed: ")
                    + c.reason().strip() : "Draft changed", AuditAction.CAT_SERVICE_UPDATED);
        });
    }

    // ---- internals

    List<ReadinessPolicy.Blocker> blockers(CateringService service) {
        Venue venue = store.venue(service.venueId()).orElseThrow();
        Menu menu = store.menu(service.menuId()).orElseThrow();
        Supplier supplier = store.supplier(service.supplierId()).orElseThrow();
        List<MenuItem> items = store.itemsOf(menu.id());
        return ReadinessPolicy.blockers(service, venue, supplier, menu, items,
                AllergenPolicy.assess(store.requestsOf(service.id()), items), currentCheck(supplier.id()));
    }

    private boolean currentCheck(UUID supplierId) {
        return store.passingSupplierCheckSince(supplierId,
                support.now().minus(ReadinessPolicy.SUPPLIER_CHECK_VALID_DAYS, ChronoUnit.DAYS));
    }

    private static boolean has(List<ReadinessPolicy.Blocker> blockers, String code) {
        return blockers.stream().anyMatch(b -> b.code().equals(code));
    }

    private static FacilitiesException refusal(List<ReadinessPolicy.Blocker> blockers) {
        String message = blockers.stream().map(ReadinessPolicy.Blocker::message).collect(Collectors.joining(" "));
        boolean onlyAllergens = blockers.stream().allMatch(b -> b.code().equals("ALLERGEN_UNRESOLVED"));
        return new FacilitiesException(onlyAllergens ? FacilitiesErrorCode.CAT_ALLERGEN_CONFLICT
                : FacilitiesErrorCode.CAT_SERVICE_NOT_VALID, message);
    }

    private CateringService manageable(UUID id, Caller caller) {
        CateringService service = support.service(id);
        support.require(caller, SflPermission.FACILITIES_CATERING_MANAGE, service.siteCode(), "CateringService", id.toString());
        return service;
    }

    private static void requirePlanning(CateringService service) {
        if (!service.status().planning()) {
            throw CateringSupport.invalid("This service is " + service.status() + " and its plan can no longer change.");
        }
    }

    private static void requireMove(CateringService service, ServiceStatus next) {
        if (!service.status().canMoveTo(next)) {
            throw new FacilitiesException(FacilitiesErrorCode.INVALID_STATE_TRANSITION,
                    "A " + service.status() + " service cannot become " + next + ".");
        }
    }

    /** A planned service whose plan has changed under its approval goes back for approval. */
    private void reopenIfApproved(CateringService service, String reason, Caller caller) {
        if (service.status() == ServiceStatus.APPROVED || service.status() == ServiceStatus.CONFIRMED) {
            CateringService back = reopened(service, support.now());
            if (store.update(back, service.version())) {
                support.history(service.siteCode(), "SERVICE", service.id(), service.status().name(),
                        ServiceStatus.PENDING_APPROVAL.name(), caller.actor().actorId(), reason);
            }
        }
    }

    /** The reopened form of a service: back to pending approval, approval and exceptions cleared. */
    static CateringService reopened(CateringService s, Instant now) {
        return new CateringService(s.id(), s.reference(), s.siteCode(), s.venueId(), s.menuId(), s.supplierId(),
                s.contextType(), s.contextReference(), s.title(), s.serviceDate(), s.startsAt(), s.cancellationCutoff(),
                s.expectedGuests(), s.plannedPortions(), s.deliveredPortions(), ServiceStatus.PENDING_APPROVAL,
                s.requestedBy(), null, null, null, null, null, null, s.purchaseReference(), s.invoiceReference(),
                s.financeState(), s.deliveredAt(), s.closedAt(), s.cancellationReason(), s.createdBy(), s.createdAt(), now,
                s.version());
    }

    private CateringService withExceptions(CateringService s, String capacityReason, String capacityBy, String supplierReason,
            String supplierBy) {
        return new CateringService(s.id(), s.reference(), s.siteCode(), s.venueId(), s.menuId(), s.supplierId(),
                s.contextType(), s.contextReference(), s.title(), s.serviceDate(), s.startsAt(), s.cancellationCutoff(),
                s.expectedGuests(), s.plannedPortions(), s.deliveredPortions(), s.status(), s.requestedBy(), s.approvedBy(),
                s.approvedAt(), capacityReason, capacityBy, supplierReason, supplierBy, s.purchaseReference(),
                s.invoiceReference(), s.financeState(), s.deliveredAt(), s.closedAt(), s.cancellationReason(),
                s.createdBy(), s.createdAt(), support.now(), s.version());
    }

    private CateringService with(CateringService s, ServiceStatus status, String requestedBy, String approvedBy,
            Instant approvedAt, String capacityReason, String capacityBy, String supplierReason, String supplierBy,
            Integer delivered, Instant deliveredAt, Instant closedAt) {
        return with(s, status, requestedBy, approvedBy, approvedAt, capacityReason, capacityBy, supplierReason, supplierBy,
                delivered, deliveredAt, closedAt, s.cancellationReason());
    }

    private CateringService with(CateringService s, ServiceStatus status, String requestedBy, String approvedBy,
            Instant approvedAt, String capacityReason, String capacityBy, String supplierReason, String supplierBy,
            Integer delivered, Instant deliveredAt, Instant closedAt, String cancellationReason) {
        return new CateringService(s.id(), s.reference(), s.siteCode(), s.venueId(), s.menuId(), s.supplierId(),
                s.contextType(), s.contextReference(), s.title(), s.serviceDate(), s.startsAt(), s.cancellationCutoff(),
                s.expectedGuests(), s.plannedPortions(), delivered, status, requestedBy, approvedBy, approvedAt,
                capacityReason, capacityBy, supplierReason, supplierBy, s.purchaseReference(), s.invoiceReference(),
                s.financeState(), deliveredAt, closedAt, cancellationReason, s.createdBy(), s.createdAt(), support.now(),
                s.version());
    }

    private CateringService save(CateringService before, CateringService after, Caller caller, String reason, AuditAction action) {
        if (!store.update(after, before.version())) {
            throw CateringSupport.conflict();
        }
        CateringService saved = support.service(after.id());
        support.history(saved.siteCode(), "SERVICE", saved.id(), before.status().name(), saved.status().name(),
                caller.actor().actorId(), reason);
        support.audit(caller, action, "CateringService", saved.id(), saved.siteCode(), before, saved);
        return saved;
    }

    private DietaryRequest saveRequest(DietaryRequest before, DietaryRequest after, Caller caller, String reason) {
        if (!store.update(after, before.version())) {
            throw CateringSupport.conflict();
        }
        support.history(before.siteCode(), "NEED", before.id(), before.status().name(), after.status().name(),
                caller.actor().actorId(), reason);
        support.audit(caller, AuditAction.CAT_DIETARY_RECORDED, "CateringService", before.serviceId(), before.siteCode(),
                before.status(), after.status());
        return store.request(before.id()).orElseThrow();
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
