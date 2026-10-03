package gh.edu.clet.sfl.facilities.hygiene.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.AddAction;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.CloseFinding;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.CreateFinding;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.SubmitEvidence;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.UpdateFinding;
import gh.edu.clet.sfl.facilities.hygiene.application.ports.HygieneIncidentPort;
import gh.edu.clet.sfl.facilities.hygiene.application.ports.HygieneWorkOrderPort;
import gh.edu.clet.sfl.facilities.hygiene.domain.ActionStatus;
import gh.edu.clet.sfl.facilities.hygiene.domain.ClosureMode;
import gh.edu.clet.sfl.facilities.hygiene.domain.ClosurePolicy;
import gh.edu.clet.sfl.facilities.hygiene.domain.ControlStatus;
import gh.edu.clet.sfl.facilities.hygiene.domain.EscalationLevel;
import gh.edu.clet.sfl.facilities.hygiene.domain.EscalationPolicy;
import gh.edu.clet.sfl.facilities.hygiene.domain.EscalationReason;
import gh.edu.clet.sfl.facilities.hygiene.domain.EvidenceStatus;
import gh.edu.clet.sfl.facilities.hygiene.domain.FindingStatus;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneAction;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneControl;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneEvidence;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneFinding;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneHistoryEntry;
import gh.edu.clet.sfl.facilities.hygiene.domain.LinkState;
import gh.edu.clet.sfl.facilities.hygiene.domain.Severity;
import gh.edu.clet.sfl.facilities.maintenance.domain.RetentionClass;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Findings, corrective actions and evidence - SRS-SFL-S170-03 to -05.
 *
 * <h2>What a finding obliges</h2>
 *
 * Every finding has a severity, and severity decides what happens without anyone remembering to do it. A
 * CRITICAL finding must name an owner and a target date, gets a corrective action due within the
 * severity's SLA, is escalated to HSE immediately, and goes to leadership as well when it repeats a
 * critical finding of the same category in the same place within {@value EscalationPolicy#REPEAT_WINDOW_DAYS}
 * days. HIGH and CRITICAL findings also raise an S153 work order.
 *
 * <h2>Closing</h2>
 *
 * A finding closes only on accepted evidence with every action verified, or on a recorded exception
 * approved by someone other than the person who raised it (see {@link ClosurePolicy}). Whoever completes
 * an action, or submits evidence, cannot be the one who verifies or accepts it.
 *
 * <h2>Outbound links never fake success</h2>
 *
 * The S153 work order and the S163 incident are made <em>after</em> the finding has committed, in a
 * transaction of their own. The finding is stored with the link {@code PENDING_MANUAL}; only a
 * successful call upgrades it. If S153 refuses, the finding is still there, still pending, and
 * {@link #retryWorkOrder} can be called again - safely, because the raise is idempotent.
 */
@Service
public class HygieneFindingService {

    private static final Logger log = LoggerFactory.getLogger(HygieneFindingService.class);

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final HygieneStore store;
    private final HygieneSupport support;
    private final HygieneWorkOrderPort workOrders;
    private final HygieneIncidentPort incidents;
    private final TransactionTemplate inTransaction;
    private final TransactionTemplate inNewTransaction;

    public HygieneFindingService(HygieneStore store, HygieneSupport support, HygieneWorkOrderPort workOrders,
            HygieneIncidentPort incidents, PlatformTransactionManager transactions) {
        this.store = store;
        this.support = support;
        this.workOrders = workOrders;
        this.incidents = incidents;
        this.inTransaction = new TransactionTemplate(transactions);
        this.inNewTransaction = new TransactionTemplate(transactions);
        this.inNewTransaction.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public HygieneFinding create(CreateFinding command) {
        HygieneFinding created = inTransaction.execute(status -> doCreate(command));
        if (created.workOrderState() == LinkState.PENDING_MANUAL) {
            return attemptWorkOrder(created.id(), command.caller());
        }
        return created;
    }

    public HygieneFinding retryWorkOrder(UUID findingId, Caller caller) {
        HygieneFinding finding = support.finding(findingId);
        support.require(caller, SflPermission.FACILITIES_HYGIENE_MANAGE, finding.siteCode(), "HygieneFinding",
                findingId.toString());
        if (finding.workOrderState() != LinkState.PENDING_MANUAL) {
            return finding;
        }
        return attemptWorkOrder(findingId, caller);
    }

    public HygieneStore.Page<HygieneFinding> list(String siteCode, String status, String severity, UUID controlId,
            boolean overdueOnly, int page, int size, Caller caller) {
        String site = HygieneSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_HYGIENE_READ, site, "HygieneFinding", "list");
        String statusFilter = HygieneControlService.enumOrNull(FindingStatus.class, status);
        String severityFilter = HygieneControlService.enumOrNull(Severity.class, severity);
        return inTransaction.execute(tx -> store.findings(site, statusFilter, severityFilter, controlId, overdueOnly,
                support.today(), Math.max(0, page), Math.min(Math.max(1, size), 100)));
    }

    public FindingDetail get(UUID id, Caller caller) {
        return inTransaction.execute(tx -> {
            HygieneFinding finding = support.finding(id);
            support.require(caller, SflPermission.FACILITIES_HYGIENE_READ, finding.siteCode(), "HygieneFinding",
                    id.toString());
            return new FindingDetail(finding, finding.overdue(support.today()), store.actionsOf(id),
                    store.evidenceOf(id).size(), store.history(id));
        });
    }

    // ---- findings

    public HygieneFinding update(UpdateFinding command) {
        return inTransaction.execute(status -> {
            HygieneFinding before = manageable(command.findingId(), command.caller());
            HygieneSupport.checkVersion(command.expectedVersion(), before.version());
            if (!before.status().open()) {
                throw invalid("A closed finding cannot be edited; reopen it first.");
            }
            String owner = command.ownerReference() != null ? command.ownerReference() : before.ownerReference();
            LocalDate target = command.targetDate() != null ? command.targetDate() : before.targetDate();
            if (before.severity().critical() && (owner == null || target == null)) {
                throw new IllegalArgumentException("A critical finding must keep an owner and a target date.");
            }
            return save(before, copy(before, command.title() != null ? command.title().strip() : before.title(),
                    command.description() != null ? command.description() : before.description(), before.status(),
                    owner, target), command.caller(), null, AuditAction.HYGIENE_FINDING_UPDATED);
        });
    }

    public HygieneFinding start(UUID id, Long expectedVersion, Caller caller) {
        return inTransaction.execute(status -> {
            HygieneFinding before = manageable(id, caller);
            HygieneSupport.checkVersion(expectedVersion, before.version());
            if (before.status() != FindingStatus.OPEN) {
                throw invalid("Only an open finding can be started.");
            }
            return save(before, copy(before, before.title(), before.description(), FindingStatus.IN_PROGRESS,
                    before.ownerReference(), before.targetDate()), caller, null, AuditAction.HYGIENE_FINDING_UPDATED);
        });
    }

    public HygieneFinding linkIncident(UUID id, String reference, Caller caller) {
        return inTransaction.execute(status -> {
            HygieneFinding before = manageable(id, caller);
            if (!before.requiresIncident()) {
                throw new IllegalArgumentException("This finding does not require an incident.");
            }
            HygieneFinding linked = new HygieneFinding(before.id(), before.reference(), before.controlId(),
                    before.siteCode(), before.roomId(), before.category(), before.title(), before.description(),
                    before.severity(), before.status(), before.ownerReference(), before.targetDate(), true,
                    LinkState.LINKED, HygieneSupport.required(reference, "incidentReference"),
                    before.workOrderState(), before.workOrderId(), before.workOrderNumber(), before.repeatOfId(),
                    before.escalationLevel(), before.closureMode(), before.closureReason(), before.closureApprovedBy(),
                    before.closedAt(), before.closedBy(), before.createdBy(), before.createdAt(), support.now(),
                    before.version());
            return save(before, linked, caller, "Incident " + reference + " linked",
                    AuditAction.HYGIENE_FINDING_UPDATED);
        });
    }

    public HygieneFinding close(CloseFinding command) {
        return inTransaction.execute(status -> {
            HygieneFinding before = support.finding(command.findingId());
            support.require(command.caller(), SflPermission.FACILITIES_HYGIENE_VERIFY, before.siteCode(),
                    "HygieneFinding", before.id().toString());
            HygieneSupport.checkVersion(command.expectedVersion(), before.version());
            if (!before.status().open()) {
                throw invalid("This finding is already closed.");
            }
            String approver = command.caller().actor().actorId();
            ClosureMode mode = command.mode() == null ? ClosureMode.EVIDENCE : command.mode();
            if (mode == ClosureMode.EVIDENCE) {
                if (!ClosurePolicy.closableByEvidence(store.evidenceOf(before.id()), store.actionsOf(before.id()))) {
                    throw new FacilitiesException(FacilitiesErrorCode.HYGIENE_CLOSURE_BLOCKED,
                            "Closing needs accepted evidence and every corrective action verified, or an approved exception.");
                }
            } else if (!ClosurePolicy.validException(command.reason(), before.createdBy(), approver)) {
                throw new FacilitiesException(FacilitiesErrorCode.HYGIENE_SELF_VERIFICATION,
                        "An exception needs a written reason (at least 10 characters) and an approver other than "
                                + "the person who raised the finding.");
            }
            Instant now = support.now();
            HygieneFinding closed = new HygieneFinding(before.id(), before.reference(), before.controlId(),
                    before.siteCode(), before.roomId(), before.category(), before.title(), before.description(),
                    before.severity(), FindingStatus.CLOSED, before.ownerReference(), before.targetDate(),
                    before.requiresIncident(), before.incidentState(), before.incidentReference(),
                    before.workOrderState(), before.workOrderId(), before.workOrderNumber(), before.repeatOfId(),
                    before.escalationLevel(), mode, mode == ClosureMode.EXCEPTION ? command.reason().strip()
                            : command.reason(), mode == ClosureMode.EXCEPTION ? approver : null, now, approver,
                    before.createdBy(), before.createdAt(), now, before.version());
            HygieneFinding saved = save(before, closed, command.caller(), command.reason(),
                    AuditAction.HYGIENE_FINDING_CLOSED);
            support.publish(HygieneEvents.FINDING_CLOSED, "HygieneFinding", saved.id(), saved.siteCode(),
                    command.caller().actor(), "findingId", saved.id(), "reference", saved.reference(), "severity",
                    saved.severity(), "closureMode", mode);
            return saved;
        });
    }

    public HygieneFinding reopen(UUID id, String reason, Caller caller) {
        return inTransaction.execute(status -> {
            HygieneFinding before = support.finding(id);
            support.require(caller, SflPermission.FACILITIES_HYGIENE_VERIFY, before.siteCode(), "HygieneFinding",
                    id.toString());
            if (before.status() != FindingStatus.CLOSED) {
                throw invalid("Only a closed finding can be reopened.");
            }
            HygieneSupport.required(reason, "reason");
            HygieneFinding reopened = new HygieneFinding(before.id(), before.reference(), before.controlId(),
                    before.siteCode(), before.roomId(), before.category(), before.title(), before.description(),
                    before.severity(), FindingStatus.IN_PROGRESS, before.ownerReference(), before.targetDate(),
                    before.requiresIncident(), before.incidentState(), before.incidentReference(),
                    before.workOrderState(), before.workOrderId(), before.workOrderNumber(), before.repeatOfId(),
                    before.escalationLevel(), null, null, null, null, null, before.createdBy(), before.createdAt(),
                    support.now(), before.version());
            return save(before, reopened, caller, reason, AuditAction.HYGIENE_FINDING_REOPENED);
        });
    }

    // ---- actions

    public HygieneAction addAction(AddAction command) {
        return inTransaction.execute(status -> {
            HygieneFinding finding = manageable(command.findingId(), command.caller());
            if (!finding.status().open()) {
                throw invalid("Actions cannot be added to a closed finding.");
            }
            if (command.dueOn() == null) {
                throw new IllegalArgumentException("dueOn is required");
            }
            return insertAction(finding, HygieneSupport.required(command.description(), "description"),
                    HygieneSupport.required(command.ownerReference(), "ownerReference"), command.dueOn(),
                    command.caller());
        });
    }

    public HygieneAction moveAction(UUID actionId, ActionStatus next, String reason, Long expectedVersion,
            Caller caller) {
        return inTransaction.execute(status -> {
            HygieneAction before = support.action(actionId);
            HygieneFinding finding = support.finding(before.findingId());
            boolean judging = next == ActionStatus.VERIFIED || next == ActionStatus.REJECTED;
            support.require(caller, judging ? SflPermission.FACILITIES_HYGIENE_VERIFY
                    : SflPermission.FACILITIES_HYGIENE_MANAGE, before.siteCode(), "HygieneAction",
                    actionId.toString());
            HygieneSupport.checkVersion(expectedVersion, before.version());
            if (!finding.status().open()) {
                throw invalid("The finding is closed.");
            }
            if (!before.status().canMoveTo(next)) {
                throw new FacilitiesException(FacilitiesErrorCode.INVALID_STATE_TRANSITION,
                        "A " + before.status() + " action cannot become " + next + ".");
            }
            String actor = caller.actor().actorId();
            if (judging && actor.equals(before.completedBy())) {
                throw new FacilitiesException(FacilitiesErrorCode.HYGIENE_SELF_VERIFICATION);
            }
            if (next == ActionStatus.REJECTED) {
                HygieneSupport.required(reason, "reason");
            }
            Instant now = support.now();
            HygieneAction after = new HygieneAction(before.id(), before.findingId(), before.siteCode(),
                    before.description(), before.ownerReference(), before.dueOn(), next,
                    next == ActionStatus.COMPLETED ? now : before.completedAt(),
                    next == ActionStatus.COMPLETED ? actor : before.completedBy(),
                    next == ActionStatus.VERIFIED ? now : null, next == ActionStatus.VERIFIED ? actor : null,
                    next == ActionStatus.REJECTED ? reason.strip() : null, before.createdBy(), before.createdAt(), now,
                    before.version());
            if (!store.update(after, before.version())) {
                throw HygieneSupport.conflict();
            }
            support.history(before.siteCode(), "ACTION", actionId, before.status().name(), next.name(), actor, reason);
            support.audit(caller, AuditAction.HYGIENE_ACTION_UPDATED, "HygieneAction", actionId, before.siteCode(),
                    before, after);
            if (finding.status() == FindingStatus.OPEN && next != ActionStatus.REJECTED) {
                save(finding, copy(finding, finding.title(), finding.description(), FindingStatus.IN_PROGRESS,
                        finding.ownerReference(), finding.targetDate()), caller, "Corrective action started",
                        AuditAction.HYGIENE_FINDING_UPDATED);
            }
            return support.action(actionId);
        });
    }

    // ---- evidence

    public HygieneEvidence submitEvidence(SubmitEvidence command) {
        return inTransaction.execute(status -> {
            HygieneFinding finding = manageable(command.findingId(), command.caller());
            if (!finding.status().open()) {
                throw invalid("Evidence cannot be added to a closed finding.");
            }
            if (command.actionId() != null && !support.action(command.actionId()).findingId().equals(finding.id())) {
                throw new IllegalArgumentException("That action belongs to a different finding.");
            }
            if (command.sizeBytes() <= 0) {
                throw new IllegalArgumentException("sizeBytes must be positive");
            }
            if (command.contentHash() == null || !SHA_256.matcher(command.contentHash().strip()).matches()) {
                throw new IllegalArgumentException("contentHash must be a SHA-256 digest (64 hex characters)");
            }
            RetentionClass retention = retention(command.retentionClass(), finding.severity());
            HygieneEvidence evidence = new HygieneEvidence(UUID.randomUUID(), finding.id(), command.actionId(),
                    finding.siteCode(), HygieneSupport.required(command.reference(), "reference"),
                    HygieneSupport.required(command.fileName(), "fileName"),
                    HygieneSupport.required(command.mediaType(), "mediaType"), command.sizeBytes(),
                    command.contentHash().strip().toLowerCase(), retention.name(), command.notes(),
                    EvidenceStatus.SUBMITTED, command.caller().actor().actorId(), support.now(), null, null, null);
            store.insert(evidence);
            support.history(finding.siteCode(), "EVIDENCE", evidence.id(), null, "SUBMITTED",
                    command.caller().actor().actorId(), null);
            support.audit(command.caller(), AuditAction.HYGIENE_EVIDENCE_SUBMITTED, "HygieneEvidence", evidence.id(),
                    finding.siteCode(), null, evidence);
            if (finding.status() != FindingStatus.AWAITING_VERIFICATION) {
                save(finding, copy(finding, finding.title(), finding.description(),
                        FindingStatus.AWAITING_VERIFICATION, finding.ownerReference(), finding.targetDate()),
                        command.caller(), "Evidence submitted", AuditAction.HYGIENE_FINDING_UPDATED);
            }
            return evidence;
        });
    }

    public HygieneEvidence reviewEvidence(UUID evidenceId, boolean accept, String reason, Caller caller) {
        return inTransaction.execute(status -> {
            HygieneEvidence before = store.findEvidence(evidenceId)
                    .orElseThrow(() -> HygieneSupport.notFound("Hygiene evidence", evidenceId));
            support.require(caller, SflPermission.FACILITIES_HYGIENE_VERIFY, before.siteCode(), "HygieneEvidence",
                    evidenceId.toString());
            if (before.status() != EvidenceStatus.SUBMITTED) {
                throw invalid("This evidence has already been reviewed.");
            }
            String reviewer = caller.actor().actorId();
            if (reviewer.equals(before.submittedBy())) {
                throw new FacilitiesException(FacilitiesErrorCode.HYGIENE_SELF_VERIFICATION);
            }
            if (!accept) {
                HygieneSupport.required(reason, "reason");
            }
            EvidenceStatus next = accept ? EvidenceStatus.ACCEPTED : EvidenceStatus.REJECTED;
            store.review(evidenceId, next, reviewer, support.now(), reason);
            support.history(before.siteCode(), "EVIDENCE", evidenceId, "SUBMITTED", next.name(), reviewer, reason);
            HygieneEvidence after = store.findEvidence(evidenceId).orElseThrow();
            support.audit(caller, AuditAction.HYGIENE_EVIDENCE_REVIEWED, "HygieneEvidence", evidenceId,
                    before.siteCode(), before, after);
            HygieneFinding finding = support.finding(before.findingId());
            boolean pending = store.evidenceOf(finding.id()).stream()
                    .anyMatch(e -> e.status() != EvidenceStatus.REJECTED);
            if (!accept && !pending && finding.status() == FindingStatus.AWAITING_VERIFICATION) {
                save(finding, copy(finding, finding.title(), finding.description(), FindingStatus.IN_PROGRESS,
                        finding.ownerReference(), finding.targetDate()), caller, "All evidence rejected",
                        AuditAction.HYGIENE_FINDING_UPDATED);
            }
            return after;
        });
    }

    /** Evidence can name people and premises: reading it is its own permission, and every read is audited. */
    public List<HygieneEvidence> evidence(UUID findingId, Caller caller) {
        return inTransaction.execute(status -> {
            HygieneFinding finding = support.finding(findingId);
            support.require(caller, SflPermission.FACILITIES_HYGIENE_EVIDENCE_READ, finding.siteCode(),
                    "HygieneEvidence", findingId.toString());
            List<HygieneEvidence> items = store.evidenceOf(findingId);
            support.audit(caller, AuditAction.HYGIENE_EVIDENCE_VIEWED, "HygieneFinding", findingId,
                    finding.siteCode(), null, items.size() + " item(s)");
            return items;
        });
    }

    // ---- internals

    private HygieneFinding doCreate(CreateFinding command) {
        HygieneControl control = support.control(command.controlId());
        support.require(command.caller(), SflPermission.FACILITIES_HYGIENE_MANAGE, control.siteCode(),
                "HygieneFinding", "new");
        if (control.status() == ControlStatus.CANCELLED) {
            throw invalid("Findings cannot be raised against a cancelled control.");
        }
        if (command.severity() == null) {
            throw new IllegalArgumentException("severity is required");
        }
        Severity severity = command.severity();
        if (severity.atLeast(Severity.HIGH) && blank(command.ownerReference())) {
            throw new IllegalArgumentException("A " + severity + " finding must name an owner.");
        }
        if (severity.critical() && command.targetDate() == null) {
            throw new IllegalArgumentException("A critical finding must have a target date.");
        }
        UUID roomId = command.roomId() != null ? command.roomId() : control.roomId();
        support.validateSiteAndRoom(control.siteCode(), roomId);
        LocalDate today = support.today();
        LocalDate target = command.targetDate() != null ? command.targetDate()
                : today.plusDays(severity.actionSlaDays());
        var category = command.category() != null ? command.category() : control.riskCategory();
        Instant now = support.now();
        HygieneFinding earlier = severity.critical() ? store.earlierCritical(control.siteCode(), category, roomId,
                now.minus(EscalationPolicy.REPEAT_WINDOW_DAYS, ChronoUnit.DAYS)).orElse(null) : null;
        EscalationLevel level = EscalationPolicy.onCreation(severity, earlier != null);
        boolean incident = command.requiresIncident() || severity.critical();
        boolean workOrder = severity.atLeast(Severity.HIGH);
        HygieneFinding finding = new HygieneFinding(UUID.randomUUID(),
                String.format("HYG-F-%06d", store.nextSequence("hygiene_finding_seq")), control.id(),
                control.siteCode(), roomId, category, HygieneSupport.required(command.title(), "title"),
                command.description(), severity, FindingStatus.OPEN, blank(command.ownerReference()) ? null
                        : command.ownerReference().strip(), target, incident,
                incident ? LinkState.PENDING_MANUAL : LinkState.NOT_REQUIRED, null,
                workOrder ? LinkState.PENDING_MANUAL : LinkState.NOT_REQUIRED, null, null,
                earlier == null ? null : earlier.id(), level, null, null, null, null, null,
                command.caller().actor().actorId(), now, now, 0);
        store.insert(finding);
        support.history(finding.siteCode(), "FINDING", finding.id(), null, "OPEN",
                command.caller().actor().actorId(), null);
        support.audit(command.caller(), AuditAction.HYGIENE_FINDING_CREATED, "HygieneFinding", finding.id(),
                finding.siteCode(), null, finding);
        support.publish(HygieneEvents.FINDING_RAISED, "HygieneFinding", finding.id(), finding.siteCode(),
                command.caller().actor(), "findingId", finding.id(), "reference", finding.reference(), "controlId",
                control.id(), "severity", severity, "category", category, "roomId", roomId);
        if (severity.critical()) {
            insertAction(finding, "Correct critical finding " + finding.reference() + ": " + finding.title(),
                    finding.ownerReference(), today.plusDays(severity.actionSlaDays()), command.caller());
            support.escalate(finding.siteCode(), "FINDING", finding.id(), finding.reference(), EscalationLevel.HSE,
                    EscalationReason.CRITICAL_FINDING, "Critical " + category, command.caller().actor(),
                    command.caller().channel());
            if (earlier != null) {
                support.escalate(finding.siteCode(), "FINDING", finding.id(), finding.reference(),
                        EscalationLevel.LEADERSHIP, EscalationReason.REPEAT_CRITICAL,
                        "Repeats " + earlier.reference(), command.caller().actor(), command.caller().channel());
            }
        }
        if (incident) {
            HygieneIncidentPort.Dispatch dispatch = incidents.request(finding, command.caller().actor());
            log.info("Incident for {} dispatched via {} (enforced={})", finding.reference(), dispatch.provider(),
                    dispatch.enforced());
        }
        return finding;
    }

    private HygieneAction insertAction(HygieneFinding finding, String description, String owner, LocalDate dueOn,
            Caller caller) {
        Instant now = support.now();
        HygieneAction action = new HygieneAction(UUID.randomUUID(), finding.id(), finding.siteCode(), description,
                owner, dueOn, ActionStatus.OPEN, null, null, null, null, null, caller.actor().actorId(), now, now, 0);
        store.insert(action);
        support.history(finding.siteCode(), "ACTION", action.id(), null, "OPEN", caller.actor().actorId(), null);
        support.audit(caller, AuditAction.HYGIENE_ACTION_CREATED, "HygieneAction", action.id(), finding.siteCode(),
                null, action);
        return action;
    }

    /** The S153 raise, in a transaction of its own so a refusal cannot take the finding down with it. */
    private HygieneFinding attemptWorkOrder(UUID findingId, Caller caller) {
        try {
            inNewTransaction.executeWithoutResult(status -> {
                HygieneFinding finding = support.finding(findingId);
                HygieneControl control = support.control(finding.controlId());
                HygieneWorkOrderPort.RaisedWorkOrder raised = workOrders.raise(finding, control,
                        caller.actor().actorId(), caller.actor().correlationId());
                HygieneFinding linked = new HygieneFinding(finding.id(), finding.reference(), finding.controlId(),
                        finding.siteCode(), finding.roomId(), finding.category(), finding.title(),
                        finding.description(), finding.severity(), finding.status(), finding.ownerReference(),
                        finding.targetDate(), finding.requiresIncident(), finding.incidentState(),
                        finding.incidentReference(), LinkState.RAISED, raised.workOrderId(),
                        raised.workOrderNumber(), finding.repeatOfId(), finding.escalationLevel(),
                        finding.closureMode(), finding.closureReason(), finding.closureApprovedBy(),
                        finding.closedAt(), finding.closedBy(), finding.createdBy(), finding.createdAt(),
                        support.now(), finding.version());
                save(finding, linked, caller, "Work order " + raised.workOrderNumber() + " raised",
                        AuditAction.HYGIENE_FINDING_UPDATED);
            });
        } catch (RuntimeException failure) {
            log.warn("S153 work order for hygiene finding {} could not be raised; it stays PENDING_MANUAL and can be"
                    + " retried", findingId, failure);
        }
        return support.finding(findingId);
    }

    private HygieneFinding manageable(UUID id, Caller caller) {
        HygieneFinding finding = support.finding(id);
        support.require(caller, SflPermission.FACILITIES_HYGIENE_MANAGE, finding.siteCode(), "HygieneFinding",
                id.toString());
        return finding;
    }

    private HygieneFinding save(HygieneFinding before, HygieneFinding after, Caller caller, String reason,
            AuditAction action) {
        if (!store.update(after, before.version())) {
            throw HygieneSupport.conflict();
        }
        HygieneFinding saved = support.finding(after.id());
        if (before.status() != saved.status() || reason != null) {
            support.history(saved.siteCode(), "FINDING", saved.id(), before.status().name(), saved.status().name(),
                    caller.actor().actorId(), reason);
        }
        support.audit(caller, action, "HygieneFinding", saved.id(), saved.siteCode(), before, saved);
        return saved;
    }

    private HygieneFinding copy(HygieneFinding b, String title, String description, FindingStatus status,
            String owner, LocalDate target) {
        return new HygieneFinding(b.id(), b.reference(), b.controlId(), b.siteCode(), b.roomId(), b.category(), title,
                description, b.severity(), status, owner, target, b.requiresIncident(), b.incidentState(),
                b.incidentReference(), b.workOrderState(), b.workOrderId(), b.workOrderNumber(), b.repeatOfId(),
                b.escalationLevel(), b.closureMode(), b.closureReason(), b.closureApprovedBy(), b.closedAt(),
                b.closedBy(), b.createdBy(), b.createdAt(), support.now(), b.version());
    }

    /** Hygiene evidence is kept at least as long as the finding's severity demands; a caller can lengthen it. */
    private static RetentionClass retention(String requested, Severity severity) {
        RetentionClass floor = severity.atLeast(Severity.HIGH) ? RetentionClass.SAFETY_CRITICAL
                : RetentionClass.COMPLIANCE;
        if (blank(requested)) {
            return floor;
        }
        RetentionClass chosen;
        try {
            chosen = RetentionClass.valueOf(requested.strip().toUpperCase());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown retention class: " + requested);
        }
        return chosen.minimumRetention().toTotalMonths() >= floor.minimumRetention().toTotalMonths() ? chosen : floor;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static FacilitiesException invalid(String message) {
        return new FacilitiesException(FacilitiesErrorCode.INVALID_STATE_TRANSITION, message);
    }

    public record FindingDetail(HygieneFinding finding, boolean overdue, List<HygieneAction> actions,
            int evidenceCount, List<HygieneHistoryEntry> history) {
    }
}
