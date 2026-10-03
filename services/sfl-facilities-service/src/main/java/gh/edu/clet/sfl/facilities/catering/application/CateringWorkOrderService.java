package gh.edu.clet.sfl.facilities.catering.application;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.catering.application.ports.CateringWorkOrderPort;
import gh.edu.clet.sfl.facilities.catering.domain.CateringException;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Corrective work for catering exceptions (SRS section 5, S153): a shortage, a service exception or a food-safety
 * incident asks S153 for a work order. A substitution is a menu decision, not a repair, so it does not.
 *
 * <p>The request is recorded before S153 is asked and stays PENDING_MANUAL until S153 answers with an order number,
 * so an outage is visible and retried rather than reported as raised. The sweep runs after an exception commits and
 * again on a timer, which also covers exceptions a failed check raises inside another transaction.
 */
@Service
public class CateringWorkOrderService {

    private static final Logger log = LoggerFactory.getLogger(CateringWorkOrderService.class);

    private final CateringStore store;
    private final CateringSupport support;
    private final CateringWorkOrderPort workOrders;
    private final TransactionTemplate requiresNew;

    public CateringWorkOrderService(CateringStore store, CateringSupport support, CateringWorkOrderPort workOrders,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.support = support;
        this.workOrders = workOrders;
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Requests work for every exception that needs it, and retries those S153 has not yet answered. Returns how many it raised. */
    public int sweep(ActorContext actor) {
        Caller caller = new Caller(actor, SourceChannel.SCHEDULER);
        for (CateringException exception : store.exceptionsNeedingWorkOrder()) {
            requiresNew.executeWithoutResult(tx -> store.insertWorkOrderRequest(exception, support.now()));
        }
        int raised = 0;
        for (UUID id : store.pendingWorkOrderRequests()) {
            if (attempt(id, caller)) {
                raised++;
            }
        }
        return raised;
    }

    /** Retry one by hand. */
    public CateringStore.ExceptionWorkOrder retry(UUID exceptionId, Caller caller) {
        CateringException exception = store.exception(exceptionId).orElseThrow(() -> CateringSupport.notFound("Exception", exceptionId));
        support.require(caller, SflPermission.FACILITIES_CATERING_MANAGE, exception.siteCode(), "CateringException", exceptionId.toString());
        if (store.workOrderOf(exceptionId).isEmpty()) {
            requiresNew.executeWithoutResult(tx -> store.insertWorkOrderRequest(exception, support.now()));
        }
        attempt(exceptionId, caller);
        return store.workOrderOf(exceptionId).orElseThrow();
    }

    /** Where each exception at the site stands with S153, for the exceptions list. */
    public java.util.List<CateringStore.ExceptionWorkOrder> at(String siteCode, Caller caller) {
        String site = CateringSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_CATERING_READ, site, "CateringException", "work-orders");
        return store.workOrdersAt(site);
    }

    private boolean attempt(UUID exceptionId, Caller caller) {
        try {
            requiresNew.executeWithoutResult(tx -> {
                CateringException exception = store.exception(exceptionId).orElseThrow();
                UUID roomId = null;
                String location = "SITE " + exception.siteCode();
                if (exception.serviceId() != null) {
                    var venue = store.venue(support.service(exception.serviceId()).venueId()).orElse(null);
                    if (venue != null) {
                        roomId = venue.roomId();
                        location = venue.name();
                    }
                }
                CateringWorkOrderPort.RaisedWorkOrder order = workOrders.raise(exception, roomId, location, caller.actor().actorId(),
                        caller.actor().correlationId());
                store.linkWorkOrder(exceptionId, order.workOrderId(), order.workOrderNumber(), support.now());
                support.audit(caller, AuditAction.CAT_WORK_ORDER_RAISED, "CateringException", exceptionId, exception.siteCode(), "PENDING_MANUAL",
                        order.workOrderNumber());
            });
            return true;
        } catch (RuntimeException failure) {
            log.warn("S153 work order for catering exception {} could not be raised; it stays PENDING_MANUAL and will be retried", exceptionId, failure);
            return false;
        }
    }
}
