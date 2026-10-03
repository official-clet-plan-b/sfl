package gh.edu.clet.sfl.facilities.lostfound.application;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.lostfound.domain.Claim;
import gh.edu.clet.sfl.facilities.lostfound.domain.EscalationReason;
import gh.edu.clet.sfl.facilities.lostfound.domain.FoundItem;
import gh.edu.clet.sfl.facilities.lostfound.domain.LfEscalation;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Figures, escalations and the retention sweep - SRS-SFL-S179-04 and -05 and the KPIs.
 *
 * <p>The sweep does two things on a timer. An unclaimed item past its retention period is escalated to security
 * - it is not disposed of, because disposal needs an approver and filed authorisation. And a claimant's personal
 * data is purged a configured number of days after their claim closes: name, contact and description are
 * erased, the claim reference, outcome and dates stay, and the purge itself is audited.
 */
@Service
public class LostFoundOpsService {

    private final LostFoundStore store;
    private final LostFoundSupport support;
    private final TransactionTemplate inTransaction;

    public LostFoundOpsService(LostFoundStore store, LostFoundSupport support, PlatformTransactionManager transactions) {
        this.store = store;
        this.support = support;
        this.inTransaction = new TransactionTemplate(transactions);
    }

    public Dashboard dashboard(String siteCode, Caller caller) {
        String site = LostFoundSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_LOSTFOUND_READ, site, "LostFoundDashboard", site);
        return inTransaction.execute(tx -> {
            Instant now = support.now();
            long[] chains = store.custodyCompleteness(site);
            long[] closed = store.withinPolicy(site);
            Double hours = store.meanHoursToRelease(site);
            return new Dashboard(site, store.openByAge(site, now), store.openByLocation(site), hours,
                    percent(chains[1], chains[0]), chains[0], percent(closed[1], closed[0]), closed[0],
                    store.count("SELECT count(*) FROM facilities.lf_items WHERE site_code = ? AND status IN"
                            + " ('REGISTERED', 'STORED') AND retention_until < ?", site, support.today()),
                    store.count("SELECT count(*) FROM facilities.lf_escalations WHERE site_code = ?"
                            + " AND acknowledged_at IS NULL", site),
                    store.count("SELECT count(*) FROM facilities.lf_items WHERE site_code = ? AND status = 'ISOLATED'",
                            site));
        });
    }

    private static BigDecimal percent(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part * 1000 / whole).movePointLeft(1);
    }

    public record Dashboard(String siteCode, LostFoundStore.AgeBuckets openByAge,
            java.util.List<LostFoundStore.LocationCount> openByLocation, Double meanHoursToVerifiedRelease,
            BigDecimal custodyCompletenessPercent, long itemsWithChain, BigDecimal withinPolicyPercent,
            long itemsClosed, long retentionExpired, long openEscalations, long isolatedItems) {
    }

    public LostFoundStore.Page<LfEscalation> escalations(String siteCode, boolean openOnly, int page, int size,
            Caller caller) {
        String site = LostFoundSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_LOSTFOUND_READ, site, "LfEscalation", "list");
        return inTransaction.execute(tx -> store.escalations(site, openOnly, Math.max(0, page),
                Math.min(Math.max(1, size), 100)));
    }

    public LfEscalation acknowledge(UUID id, Caller caller) {
        return inTransaction.execute(tx -> {
            LfEscalation escalation = store.escalation(id).orElseThrow(() -> LostFoundSupport.notFound("Escalation", id));
            support.require(caller, SflPermission.FACILITIES_LOSTFOUND_MANAGE, escalation.siteCode(), "LfEscalation",
                    id.toString());
            store.acknowledge(id, caller.actor().actorId(), support.now(), null);
            return store.escalation(id).orElseThrow();
        });
    }

    public LfEscalation linkIncident(UUID id, String reference, Caller caller) {
        return inTransaction.execute(tx -> {
            LfEscalation escalation = store.escalation(id).orElseThrow(() -> LostFoundSupport.notFound("Escalation", id));
            support.require(caller, SflPermission.FACILITIES_LOSTFOUND_MANAGE, escalation.siteCode(), "LfEscalation",
                    id.toString());
            if ("NOT_REQUIRED".equals(escalation.incidentState())) {
                throw new IllegalArgumentException("This escalation does not need an incident.");
            }
            store.linkIncident(id, LostFoundSupport.required(reference, "incidentReference"));
            return store.escalation(id).orElseThrow();
        });
    }

    public SweepResult sweep(ActorContext actor) {
        Caller caller = new Caller(actor, SourceChannel.SCHEDULER);
        int escalated = 0;
        int purged = 0;
        for (FoundItem item : store.expiredUnclaimed(support.today())) {
            LfEscalation raised = inTransaction.execute(tx -> support.escalateInline(item, null,
                    EscalationReason.RETENTION_EXPIRED, "Unclaimed since " + item.foundAt() + "; retention ended "
                            + item.retentionUntil(), caller));
            if (raised != null) {
                escalated++;
            }
        }
        for (Claim claim : store.claimsDueForPurge(support.now())) {
            inTransaction.executeWithoutResult(tx -> {
                Claim erased = new Claim(claim.id(), claim.reference(), claim.itemId(), claim.siteCode(), null, null,
                        null, claim.status(), claim.identityVerified(), claim.verificationMethod(), null,
                        claim.verifiedBy(), claim.verifiedAt(), claim.decisionReason(), claim.decidedBy(),
                        claim.decidedAt(), claim.releasedBy(), claim.releasedAt(), claim.closedAt(), support.now(),
                        claim.createdBy(), claim.createdAt(), support.now(), claim.version());
                if (store.update(erased, claim.version())) {
                    support.history(claim.siteCode(), "CLAIM", claim.id(), claim.status().name(), claim.status().name(),
                            actor.actorId(), "Personal data purged after the retention period");
                    support.audit(caller, AuditAction.LF_PERSONAL_DATA_PURGED, "Claim", claim.id(), claim.siteCode(),
                            "claimant name, contact and description", "erased");
                }
            });
            purged++;
        }
        return new SweepResult(escalated, purged);
    }

    public record SweepResult(int retentionEscalations, int claimantRecordsPurged) {
    }
}
