package gh.edu.clet.sfl.safetysecurity.permit.application.service;

import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitRepository;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.HistoryEntry;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.AuditPort;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What every S164 change leaves behind: a history row and an audit entry. A <em>refusal</em> is recorded too, in its own
 * transaction - the operation that was refused rolls back, and the fact that it was attempted, by whom and why, must not
 * (SRS-SFL-S164-01: refused with a named reason; S164-05: every permit action written to the audit trail).
 */
@Component
public class PermitRecorder {

    private final PermitRepository repository;
    private final AuditPort audit;
    private final Clock clock;
    private final TransactionTemplate requiresNew;

    public PermitRecorder(PermitRepository repository, AuditPort audit, Clock clock, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Instant now() {
        return clock.instant();
    }

    public void history(Permit permit, String from, String to, String action, String actor, String reason) {
        repository.insertHistory(new HistoryEntry(UUID.randomUUID(), permit.id(), permit.siteCode(), from, to, action, actor, reason, now()));
    }

    public void audit(Caller caller, String action, Permit permit, Object before, Object after, String reason) {
        audit.record(caller.actor(), caller.channel().name(), permit.siteCode(), action, "Permit", permit.id().toString(), before, after, reason);
    }

    /** Records that an action was refused and why, durably, whatever happens to the caller's transaction. */
    public void refusal(Caller caller, Permit permit, String action, String code, String detail) {
        requiresNew.executeWithoutResult(tx -> {
            history(permit, permit.status().name(), permit.status().name(), action + "_REFUSED", caller.id(), code + (detail == null ? "" : ": " + detail));
            audit(caller, action + "_REFUSED", permit, null, null, code + (detail == null ? "" : ": " + detail));
        });
    }
}
