package gh.edu.clet.sfl.facilities.lease.application;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.lease.domain.Agreement;
import gh.edu.clet.sfl.facilities.lease.domain.AgreementStatus;
import gh.edu.clet.sfl.facilities.lease.domain.AlertLevel;
import gh.edu.clet.sfl.facilities.lease.domain.AlertPolicy;
import gh.edu.clet.sfl.facilities.lease.domain.AlertReason;
import gh.edu.clet.sfl.facilities.lease.domain.BusinessCalendar;
import gh.edu.clet.sfl.facilities.lease.domain.Direction;
import gh.edu.clet.sfl.facilities.lease.domain.ExposurePolicy;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseAlert;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseDocument;
import gh.edu.clet.sfl.facilities.lease.domain.NoticePolicy;
import gh.edu.clet.sfl.facilities.lease.domain.Obligation;
import gh.edu.clet.sfl.facilities.lease.domain.ObligationKind;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily control, the alert queue and the controlled portfolio report - SRS-SFL-S177-03 and -05.
 *
 * <p>The daily control does four things, each safe to repeat: it marks agreements past their end date expired and
 * tells the director and legal (so whatever depends on the lease can be flagged for review); it walks every open
 * obligation on a live agreement and raises the alerts the chain owes - owner at ninety days, manager at sixty,
 * director at thirty, everyone once overdue - each exactly once; it flags expired documents; and it recomputes
 * notice dates against the <em>current</em> calendar, so adding a holiday moves the dates it affects.
 */
@Service
public class LeaseOpsService {

    private final LeaseStore store;
    private final LeaseSupport support;
    private final TransactionTemplate inTransaction;

    public LeaseOpsService(LeaseStore store, LeaseSupport support, PlatformTransactionManager transactions) {
        this.store = store;
        this.support = support;
        this.inTransaction = new TransactionTemplate(transactions);
    }

    public record ControlResult(int expired, int alertsRaised, int noticeDatesMoved) {
    }

    public ControlResult dailyControl(ActorContext actor) {
        Caller caller = new Caller(actor, SourceChannel.SCHEDULER);
        return inTransaction.execute(tx -> {
            LocalDate today = support.today();
            BusinessCalendar calendar = support.calendar();
            int expired = 0;
            int alerts = 0;
            int moved = 0;
            for (Agreement ended : store.endedActive(today)) {
                Agreement after = new Agreement(ended.id(), ended.reference(), ended.siteCode(), ended.roomId(), ended.propertyReference(),
                        ended.kind(), ended.direction(), ended.title(), ended.counterpartyReference(), ended.counterpartyState(),
                        ended.contractReference(), ended.financeReference(), ended.ownerReference(), ended.startDate(), ended.endDate(),
                        ended.renewalType(), ended.renewalTermMonths(), ended.noticeDays(), ended.noticeDate(), ended.rentReviewDate(),
                        ended.annualRent(), ended.depositAmount(), ended.currency(), AgreementStatus.EXPIRED, ended.versionNumber() + 1,
                        ended.requestedBy(), ended.approvedBy(), ended.approvedAt(), ended.terminatedOn(), ended.createdBy(),
                        ended.createdAt(), support.now(), ended.version());
                if (store.update(after, ended.version())) {
                    support.history(ended.siteCode(), "AGREEMENT", ended.id(), "ACTIVE", "EXPIRED", actor.actorId(),
                            "Ended on " + ended.endDate() + " without renewal");
                    support.audit(caller, AuditAction.LEASE_EXPIRED, "Agreement", ended.id(), ended.siteCode(), "ACTIVE", "EXPIRED");
                    support.publish(LeaseEvents.EXPIRED, "Agreement", ended.id(), ended.siteCode(), actor, "agreementId", ended.id(),
                            "reference", ended.reference(), "endDate", ended.endDate());
                    alerts += raise(ended, null, AlertLevel.DIRECTOR, AlertReason.EXPIRED, "Expired on " + ended.endDate(), caller);
                    alerts += raise(ended, null, AlertLevel.LEGAL, AlertReason.EXPIRED, "Expired on " + ended.endDate(), caller);
                    expired++;
                }
            }
            for (Agreement active : store.allActive()) {
                var moved1 = NoticePolicy.noticeDate(active.endDate(), active.noticeDays(), calendar);
                if (moved1 != null && !moved1.equals(active.noticeDate())) {
                    Agreement after = new Agreement(active.id(), active.reference(), active.siteCode(), active.roomId(),
                            active.propertyReference(), active.kind(), active.direction(), active.title(), active.counterpartyReference(),
                            active.counterpartyState(), active.contractReference(), active.financeReference(), active.ownerReference(),
                            active.startDate(), active.endDate(), active.renewalType(), active.renewalTermMonths(), active.noticeDays(),
                            moved1, active.rentReviewDate(), active.annualRent(), active.depositAmount(), active.currency(),
                            active.status(), active.versionNumber(), active.requestedBy(), active.approvedBy(), active.approvedAt(),
                            active.terminatedOn(), active.createdBy(), active.createdAt(), support.now(), active.version());
                    if (store.update(after, active.version())) {
                        for (Obligation o : store.obligationsOf(active.id())) {
                            if (o.open() && o.generated() && (o.kind() == ObligationKind.NOTICE || o.kind() == ObligationKind.RENEWAL)) {
                                store.update(new Obligation(o.id(), o.agreementId(), o.siteCode(), o.kind(), o.title(), moved1,
                                        o.ownerReference(), o.status(), o.completedOn(), o.completedBy(), o.completionNote(), o.generated(),
                                        o.createdBy(), o.createdAt(), support.now(), o.version()), o.version());
                            }
                        }
                        support.history(active.siteCode(), "AGREEMENT", active.id(), "ACTIVE", "ACTIVE", actor.actorId(),
                                "Notice date moved from " + active.noticeDate() + " to " + moved1 + " by the business calendar");
                        moved++;
                    }
                }
            }
            for (Obligation o : store.openObligationsOfActive()) {
                Agreement agreement = support.agreement(o.agreementId());
                for (AlertPolicy.Due due : AlertPolicy.owed(o.daysTo(today))) {
                    alerts += raise(agreement, o.id(), due.level(), due.reason(), o.title() + " - due " + o.dueOn(), caller);
                }
            }
            for (LeaseDocument d : store.expiredDocumentsOfActive(today)) {
                Agreement agreement = support.agreement(d.agreementId());
                alerts += raise(agreement, null, AlertLevel.OWNER, AlertReason.DOCUMENT_EXPIRED, d.kind() + " " + d.reference() + " expired " + d.expiresOn(), caller);
                alerts += raise(agreement, null, AlertLevel.MANAGER, AlertReason.DOCUMENT_EXPIRED, d.kind() + " " + d.reference() + " expired " + d.expiresOn(), caller);
            }
            return new ControlResult(expired, alerts, moved);
        });
    }

    private int raise(Agreement a, UUID obligationId, AlertLevel level, AlertReason reason, String detail, Caller caller) {
        LeaseAlert alert = new LeaseAlert(UUID.randomUUID(), a.siteCode(), a.id(), obligationId, level, reason, detail, support.now(), null, null);
        if (!store.insert(alert)) {
            return 0;
        }
        support.audit(caller, AuditAction.LEASE_ALERT_RAISED, "Agreement", a.id(), a.siteCode(), null, alert);
        support.publish(LeaseEvents.ALERT_RAISED, "Agreement", a.id(), a.siteCode(), caller.actor(), "agreementId", a.id(), "reference",
                a.reference(), "level", level, "reason", reason, "obligationId", obligationId);
        return 1;
    }

    // ---- alerts

    public LeaseStore.Page<LeaseAlert> alerts(String siteCode, boolean openOnly, int page, int size, Caller caller) {
        String site = LeaseSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_LEASE_READ, site, "LeaseAlert", "list");
        return inTransaction.execute(tx -> store.alerts(site, openOnly, Math.max(0, page), Math.min(Math.max(1, size), 100)));
    }

    public LeaseAlert acknowledge(UUID id, Caller caller) {
        return inTransaction.execute(tx -> {
            LeaseAlert alert = store.alert(id).orElseThrow(() -> LeaseSupport.notFound("Alert", id));
            support.require(caller, SflPermission.FACILITIES_LEASE_MANAGE, alert.siteCode(), "LeaseAlert", id.toString());
            store.acknowledge(id, caller.actor().actorId(), support.now());
            return store.alert(id).orElseThrow();
        });
    }

    // ---- portfolio

    /**
     * The portfolio by site, owner, expiry horizon and unresolved obligation. Counts and dates are open to anyone who
     * can read the register; the money - rent exposure and deposits - only to the financial grant, and viewing it
     * is audited. Without the grant those fields come back null rather than as zeros.
     */
    public Portfolio portfolio(String siteCode, Caller caller) {
        String site = LeaseSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_LEASE_READ, site, "LeasePortfolio", site);
        boolean money = support.has(caller.actor(), SflPermission.FACILITIES_LEASE_FINANCIAL_READ);
        return inTransaction.execute(tx -> {
            LocalDate today = support.today();
            Map<String, Long> byStatus = new TreeMap<>();
            for (AgreementStatus status : AgreementStatus.values()) {
                byStatus.put(status.name(), store.count("SELECT count(*) FROM facilities.lease_agreements WHERE site_code = ? AND status = ?",
                        site, status.name()));
            }
            Map<String, Long> horizons = new java.util.LinkedHashMap<>();
            for (int days : new int[] {30, 60, 90, 180, 365}) {
                horizons.put("within" + days, store.count("SELECT count(*) FROM facilities.lease_agreements WHERE site_code = ?"
                        + " AND status = 'ACTIVE' AND end_date BETWEEN ? AND ?", site, today, today.plusDays(days)));
            }
            Map<String, Long> obligationsDue = new java.util.LinkedHashMap<>();
            for (int days : new int[] {30, 60, 90}) {
                obligationsDue.put("within" + days, store.count("SELECT count(*) FROM facilities.lease_obligations WHERE site_code = ?"
                        + " AND status = 'OPEN' AND due_on BETWEEN ? AND ?", site, today, today.plusDays(days)));
            }
            long overdue = store.count("SELECT count(*) FROM facilities.lease_obligations WHERE site_code = ? AND status = 'OPEN' AND due_on < ?",
                    site, today);
            long incomplete = store.count("SELECT count(*) FROM facilities.lease_agreements WHERE site_code = ?"
                    + " AND status IN ('DRAFT', 'IN_REVIEW', 'ACTIVE') AND (notice_days IS NULL OR counterparty_reference IS NULL)", site);
            long unresolved = store.count("SELECT count(*) FROM facilities.lease_agreements WHERE site_code = ?"
                    + " AND status IN ('DRAFT', 'IN_REVIEW', 'ACTIVE') AND counterparty_state = 'UNRESOLVED'", site);
            long[] renewals = store.renewalTimeliness(site, today);
            Double cycle = store.meanAmendmentCycleHours(site);
            Map<String, Exposure> exposure = null;
            if (money) {
                exposure = new TreeMap<>();
                for (Agreement a : store.activeAgreements(site)) {
                    if (a.currency() == null) {
                        continue;
                    }
                    Exposure e = exposure.computeIfAbsent(a.currency(), c -> new Exposure(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
                    BigDecimal remaining = ExposurePolicy.remainingRent(a, today);
                    BigDecimal deposit = a.depositAmount() == null ? BigDecimal.ZERO : a.depositAmount();
                    exposure.put(a.currency(), a.direction() == Direction.INBOUND
                            ? new Exposure(e.payableRemaining().add(remaining), e.receivableRemaining(), e.depositsHeldByLandlords().add(deposit), e.depositsHeld())
                            : new Exposure(e.payableRemaining(), e.receivableRemaining().add(remaining), e.depositsHeldByLandlords(), e.depositsHeld().add(deposit)));
                }
                support.audit(caller, AuditAction.LEASE_REPORT_VIEWED, "LeasePortfolio", UUID.nameUUIDFromBytes(site.getBytes()), site, null, "financial");
            }
            return new Portfolio(site, today.toString(), support.zone().getId(), byStatus, horizons, obligationsDue, overdue, incomplete, unresolved,
                    store.count("SELECT count(*) FROM facilities.lease_agreements WHERE site_code = ? AND status = 'EXPIRED'", site),
                    percent(renewals[1], renewals[0]), renewals[0], cycle, store.byOwner(site, today), exposure);
        });
    }

    private static BigDecimal percent(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    /** Money still to run, by direction: rent CLET owes as tenant, rent it is owed as landlord, and deposits each way. */
    public record Exposure(BigDecimal payableRemaining, BigDecimal receivableRemaining, BigDecimal depositsHeldByLandlords, BigDecimal depositsHeld) {
    }

    public record Portfolio(String siteCode, String asOf, String timezone, Map<String, Long> agreementsByStatus,
            Map<String, Long> expiringWithin, Map<String, Long> obligationsDueWithin, long overdueObligations,
            long incompleteAgreements, long unresolvedCounterparties, long expiredAgreements,
            BigDecimal renewalsOnTimePercent, long renewalsDueOrDone, Double meanAmendmentCycleHours,
            List<LeaseStore.OwnerRow> byOwner, Map<String, Exposure> financialExposure) {
    }
}
