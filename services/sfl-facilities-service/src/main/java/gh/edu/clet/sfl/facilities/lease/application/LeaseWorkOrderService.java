package gh.edu.clet.sfl.facilities.lease.application;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.lease.application.ports.LeaseWorkOrderPort;
import gh.edu.clet.sfl.facilities.lease.domain.Agreement;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseWorkOrder;
import gh.edu.clet.sfl.facilities.lease.domain.WorkOrderTrigger;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Corrective work for lease exceptions (SRS section 5, S153), and the review a lapsed lease sets off: when an
 * agreement expires, whoever relied on it - a service, a room, access - is asked to review it through a work order.
 *
 * <p>The order is recorded before S153 is asked. If S153 is down or refuses, the row stays PENDING_MANUAL, is shown
 * that way, and is retried by the daily control or by hand; it is never reported as raised without an order number.
 */
@Service
public class LeaseWorkOrderService {

    private static final Logger log = LoggerFactory.getLogger(LeaseWorkOrderService.class);

    private final LeaseStore store;
    private final LeaseSupport support;
    private final LeaseWorkOrderPort workOrders;
    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;

    public LeaseWorkOrderService(LeaseStore store, LeaseSupport support, LeaseWorkOrderPort workOrders,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.support = support;
        this.workOrders = workOrders;
        this.required = new TransactionTemplate(transactions);
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** What the daily control does after expiry commits: a review order for each lapsed agreement, and a retry of any pending. */
    public int reviewExpired(ActorContext actor) {
        Caller caller = new Caller(actor, SourceChannel.SCHEDULER);
        int raised = 0;
        for (Agreement lapsed : store.expiredWithoutReview()) {
            String description = "Lease " + lapsed.reference() + " (" + lapsed.title() + ") ended on " + lapsed.endDate()
                    + " without renewal. Review any service, access or occupation that depends on it.";
            if (record(lapsed, null, WorkOrderTrigger.EXPIRED_REVIEW, description, actor.actorId(), caller) != null) {
                raised++;
            }
        }
        for (LeaseWorkOrder pending : store.pendingWorkOrders()) {
            attempt(pending.id(), caller);
        }
        return raised;
    }

    /** Raise corrective work by hand, from an alert or an overdue obligation. */
    public LeaseWorkOrder raise(UUID agreementId, UUID obligationId, String description, Caller caller) {
        Agreement agreement = required.execute(tx -> support.agreement(agreementId));
        support.require(caller, SflPermission.FACILITIES_LEASE_MANAGE, agreement.siteCode(), "Agreement", agreementId.toString());
        if (obligationId != null) {
            var obligation = store.obligation(obligationId).orElseThrow(() -> LeaseSupport.notFound("Obligation", obligationId));
            if (!obligation.agreementId().equals(agreementId)) {
                throw new IllegalArgumentException("That obligation belongs to a different agreement.");
            }
        }
        String text = LeaseSupport.required(description, "description");
        return record(agreement, obligationId, WorkOrderTrigger.MANUAL, text, caller.actor().actorId(), caller);
    }

    public LeaseWorkOrder retry(UUID id, Caller caller) {
        LeaseWorkOrder order = required.execute(tx -> store.workOrder(id).orElseThrow(() -> LeaseSupport.notFound("WorkOrder", id)));
        support.require(caller, SflPermission.FACILITIES_LEASE_MANAGE, order.siteCode(), "Agreement", order.agreementId().toString());
        return order.raised() ? order : attempt(id, caller);
    }

    public List<LeaseWorkOrder> of(UUID agreementId) {
        return store.workOrdersOf(agreementId);
    }

    private LeaseWorkOrder record(Agreement agreement, UUID obligationId, WorkOrderTrigger trigger, String description, String requestedBy,
            Caller caller) {
        LeaseWorkOrder order = new LeaseWorkOrder(UUID.randomUUID(), agreement.siteCode(), agreement.id(), obligationId, trigger, description,
                "PENDING_MANUAL", null, null, requestedBy, support.now(), support.now(), 0);
        boolean inserted = Boolean.TRUE.equals(requiresNew.execute(tx -> {
            if (!store.insert(order)) {
                return false;
            }
            support.history(agreement.siteCode(), "AGREEMENT", agreement.id(), agreement.status().name(), agreement.status().name(), requestedBy,
                    "Corrective work requested (" + trigger + "): " + description);
            support.audit(caller, AuditAction.LEASE_WORK_ORDER_REQUESTED, "Agreement", agreement.id(), agreement.siteCode(), null, order);
            return true;
        }));
        return inserted ? attempt(order.id(), caller) : null;
    }

    private LeaseWorkOrder attempt(UUID id, Caller caller) {
        try {
            requiresNew.executeWithoutResult(tx -> {
                LeaseWorkOrder order = store.workOrder(id).orElseThrow();
                Agreement agreement = support.agreement(order.agreementId());
                LeaseWorkOrderPort.RaisedWorkOrder raised = workOrders.raise(order, agreement, caller.actor().actorId(),
                        caller.actor().correlationId());
                if (!store.link(order.linked(raised.workOrderId(), raised.workOrderNumber(), support.now()), order.version())) {
                    throw LeaseSupport.conflict();
                }
                support.audit(caller, AuditAction.LEASE_WORK_ORDER_RAISED, "Agreement", agreement.id(), agreement.siteCode(), "PENDING_MANUAL",
                        raised.workOrderNumber());
            });
        } catch (RuntimeException failure) {
            log.warn("S153 work order for lease work request {} could not be raised; it stays PENDING_MANUAL and can be retried", id, failure);
        }
        return store.workOrder(id).orElseThrow();
    }
}
