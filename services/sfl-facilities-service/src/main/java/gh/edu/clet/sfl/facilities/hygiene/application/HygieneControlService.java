package gh.edu.clet.sfl.facilities.hygiene.application;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.CompleteControl;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.CreateControl;
import gh.edu.clet.sfl.facilities.hygiene.domain.ControlStatus;
import gh.edu.clet.sfl.facilities.hygiene.domain.ControlType;
import gh.edu.clet.sfl.facilities.hygiene.domain.EscalationLevel;
import gh.edu.clet.sfl.facilities.hygiene.domain.EscalationReason;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneAction;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneControl;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneEscalation;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneHistoryEntry;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled controls - audits, pest-control visits and statutory checks - SRS-SFL-S170-01 and -02.
 *
 * <p>Three rules carry the module. A recurring control always has a successor: completing it, or
 * marking it missed, creates the next one, so a missed audit cannot silently end the schedule. A pest
 * visit with a named provider cannot be completed until the provider's confirmation is recorded - the
 * visit is the provider's claim until then. And "overdue" is never stored; it is worked out from the due
 * date, so it cannot be stale.
 */
@Service
public class HygieneControlService {

    private final HygieneStore store;
    private final HygieneSupport support;

    public HygieneControlService(HygieneStore store, HygieneSupport support) {
        this.store = store;
        this.support = support;
    }

    @Transactional
    public HygieneControl create(CreateControl command) {
        String site = support.validateSiteAndRoom(command.siteCode(), command.roomId());
        support.require(command.caller(), SflPermission.FACILITIES_HYGIENE_MANAGE, site, "HygieneControl", "new");
        if (command.controlType() == null || command.riskCategory() == null || command.frequency() == null
                || command.dueOn() == null) {
            throw new IllegalArgumentException("controlType, riskCategory, frequency and dueOn are required");
        }
        if (command.providerReference() != null && command.controlType() != ControlType.PEST_VISIT) {
            throw new IllegalArgumentException("Only a pest-control visit has a provider.");
        }
        UUID id = UUID.randomUUID();
        var now = support.now();
        HygieneControl control = new HygieneControl(id, reference(), site, command.roomId(),
                blankToNull(command.locationLabel()), command.controlType(), command.riskCategory(),
                HygieneSupport.required(command.title(), "title"),
                HygieneSupport.required(command.ownerReference(), "ownerReference"), command.frequency(),
                command.dueOn(), ControlStatus.SCHEDULED, null, null, null, blankToNull(command.providerReference()),
                false, null, null, null, blankToNull(command.notes()), null, command.caller().actor().actorId(), now,
                now, 0);
        store.insert(control);
        recordCreated(control, command.caller());
        return control;
    }

    @Transactional(readOnly = true)
    public HygieneStore.Page<HygieneControl> list(String siteCode, String status, String type, boolean overdueOnly,
            int page, int size, Caller caller) {
        String site = HygieneSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_HYGIENE_READ, site, "HygieneControl", "list");
        return store.controls(site, enumOrNull(ControlStatus.class, status), enumOrNull(ControlType.class, type),
                overdueOnly, support.today(), Math.max(0, page), Math.min(Math.max(1, size), 100));
    }

    @Transactional(readOnly = true)
    public ControlDetail get(UUID id, Caller caller) {
        HygieneControl control = support.control(id);
        support.require(caller, SflPermission.FACILITIES_HYGIENE_READ, control.siteCode(), "HygieneControl",
                id.toString());
        return new ControlDetail(control, control.effectiveStatus(support.today()), store.history(id));
    }

    @Transactional
    public HygieneControl start(UUID id, Long expectedVersion, Caller caller) {
        HygieneControl before = manageable(id, caller);
        HygieneSupport.checkVersion(expectedVersion, before.version());
        HygieneControl after = save(before, move(before, ControlStatus.IN_PROGRESS, caller, null,
                support.now(), before.completedOn(), before.completedBy()), caller, null);
        return after;
    }

    /** Completes a control and, if it recurs, schedules its successor in the same transaction. */
    @Transactional
    public Completion complete(CompleteControl command) {
        HygieneControl before = manageable(command.controlId(), command.caller());
        HygieneSupport.checkVersion(command.expectedVersion(), before.version());
        if (before.needsProviderConfirmation()) {
            throw new FacilitiesException(FacilitiesErrorCode.HYGIENE_PROVIDER_UNCONFIRMED);
        }
        LocalDate completedOn = command.completedOn() == null ? support.today() : command.completedOn();
        if (completedOn.isAfter(support.today())) {
            throw new IllegalArgumentException("completedOn cannot be in the future");
        }
        HygieneControl moved = move(before, ControlStatus.COMPLETED, command.caller(), command.notes(),
                before.startedAt(), completedOn, command.caller().actor().actorId());
        HygieneControl after = save(before, moved, command.caller(), command.notes());
        support.publish(HygieneEvents.CONTROL_COMPLETED, "HygieneControl", after.id(), after.siteCode(),
                command.caller().actor(), "controlId", after.id(), "reference", after.reference(), "controlType",
                after.controlType(), "completedOn", completedOn);
        return new Completion(after, successor(after, completedOn, command.caller()));
    }

    @Transactional
    public Completion markMissed(UUID id, String reason, Long expectedVersion, Caller caller) {
        HygieneControl before = manageable(id, caller);
        HygieneSupport.checkVersion(expectedVersion, before.version());
        HygieneControl after = save(before, move(before, ControlStatus.MISSED, caller, reason, before.startedAt(),
                before.completedOn(), before.completedBy()), caller, HygieneSupport.required(reason, "reason"));
        support.escalate(after.siteCode(), "CONTROL", after.id(), after.reference(), EscalationLevel.HSE,
                EscalationReason.OVERDUE_CONTROL, "Control missed", caller.actor(), caller.channel());
        support.publish(HygieneEvents.CONTROL_MISSED, "HygieneControl", after.id(), after.siteCode(), caller.actor(),
                "controlId", after.id(), "reference", after.reference(), "dueOn", after.dueOn());
        return new Completion(after, successor(after, support.today(), caller));
    }

    @Transactional
    public HygieneControl cancel(UUID id, String reason, Long expectedVersion, Caller caller) {
        HygieneControl before = manageable(id, caller);
        HygieneSupport.checkVersion(expectedVersion, before.version());
        return save(before, move(before, ControlStatus.CANCELLED, caller, reason, before.startedAt(),
                before.completedOn(), before.completedBy()), caller, HygieneSupport.required(reason, "reason"));
    }

    @Transactional
    public HygieneControl confirmProvider(UUID id, Long expectedVersion, Caller caller) {
        HygieneControl before = manageable(id, caller);
        HygieneSupport.checkVersion(expectedVersion, before.version());
        if (before.providerReference() == null) {
            throw new IllegalArgumentException("This control has no provider to confirm.");
        }
        HygieneControl confirmed = new HygieneControl(before.id(), before.reference(), before.siteCode(),
                before.roomId(), before.locationLabel(), before.controlType(), before.riskCategory(), before.title(),
                before.ownerReference(), before.frequency(), before.dueOn(), before.status(), before.startedAt(),
                before.completedOn(), before.completedBy(), before.providerReference(), true,
                caller.actor().actorId(), support.now(), before.previousControlId(), before.notes(),
                before.overdueNotifiedAt(), before.createdBy(), before.createdAt(), support.now(), before.version());
        return save(before, confirmed, caller, "Provider visit confirmed");
    }

    @Transactional(readOnly = true)
    public HygieneStore.Page<HygieneEscalation> escalations(String siteCode, boolean openOnly, int page, int size,
            Caller caller) {
        String site = HygieneSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_HYGIENE_READ, site, "HygieneEscalation", "list");
        return store.escalations(site, openOnly, Math.max(0, page), Math.min(Math.max(1, size), 100));
    }

    @Transactional
    public HygieneEscalation acknowledge(UUID id, Caller caller) {
        HygieneEscalation escalation = store.findEscalation(id)
                .orElseThrow(() -> HygieneSupport.notFound("Hygiene escalation", id));
        support.require(caller, SflPermission.FACILITIES_HYGIENE_MANAGE, escalation.siteCode(), "HygieneEscalation",
                id.toString());
        store.acknowledge(id, caller.actor().actorId(), support.now());
        return store.findEscalation(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(String siteCode, int periodDays, Caller caller) {
        String site = HygieneSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_HYGIENE_READ, site, "HygieneDashboard", site);
        int days = Math.min(Math.max(7, periodDays), 366);
        LocalDate today = support.today();
        HygieneStore.Kpis kpis = store.kpis(site, today, today.minusDays(days));
        Double rate = kpis.controlsDue() == 0 ? null
                : Math.round(1000.0 * kpis.controlsCompleted() / kpis.controlsDue()) / 10.0;
        return new Dashboard(site, days, kpis, rate);
    }

    /**
     * The periodic sweep - SRS-SFL-S170-02/05. For every open control past its due date: tell the owner
     * once; after {@code missedAfterDays} mark it missed, schedule its successor and tell HSE; and for an
     * overdue corrective action tell HSE. Each notification is an escalation row raised once per subject,
     * level and reason, so running the sweep every few minutes is safe.
     */
    @Transactional
    public SweepResult sweep(int missedAfterDays, ActorContext actor) {
        LocalDate today = support.today();
        Caller caller = new Caller(actor, SourceChannel.SCHEDULER);
        int notified = 0;
        int missed = 0;
        int actions = 0;
        for (HygieneControl control : store.openControlsDueBefore(today)) {
            if (support.escalate(control.siteCode(), "CONTROL", control.id(), control.reference(),
                    EscalationLevel.OWNER, control.needsProviderConfirmation() ? EscalationReason.UNCONFIRMED_PROVIDER
                            : EscalationReason.OVERDUE_CONTROL,
                    "Due " + control.dueOn(), actor, SourceChannel.SCHEDULER)) {
                notified++;
            }
            if (control.dueOn().isBefore(today.minusDays(missedAfterDays))) {
                HygieneControl after = save(control, move(control, ControlStatus.MISSED, caller,
                        "Not carried out within " + missedAfterDays + " days of its due date", control.startedAt(),
                        control.completedOn(), control.completedBy()), caller,
                        "Not carried out within " + missedAfterDays + " days of its due date");
                support.escalate(after.siteCode(), "CONTROL", after.id(), after.reference(), EscalationLevel.HSE,
                        EscalationReason.OVERDUE_CONTROL, "Missed", actor, SourceChannel.SCHEDULER);
                support.publish(HygieneEvents.CONTROL_MISSED, "HygieneControl", after.id(), after.siteCode(), actor,
                        "controlId", after.id(), "reference", after.reference(), "dueOn", after.dueOn());
                successor(after, today, caller);
                missed++;
            }
        }
        for (HygieneAction action : store.openActionsDueBefore(today)) {
            if (support.escalate(action.siteCode(), "ACTION", action.id(), action.id().toString().substring(0, 8),
                    EscalationLevel.HSE, EscalationReason.OVERDUE_ACTION, "Due " + action.dueOn(), actor,
                    SourceChannel.SCHEDULER)) {
                actions++;
            }
        }
        return new SweepResult(notified, missed, actions);
    }

    // ---- internals

    private HygieneControl manageable(UUID id, Caller caller) {
        HygieneControl control = support.control(id);
        support.require(caller, SflPermission.FACILITIES_HYGIENE_MANAGE, control.siteCode(), "HygieneControl",
                id.toString());
        return control;
    }

    private HygieneControl move(HygieneControl before, ControlStatus next, Caller caller, String reason,
            java.time.Instant startedAt, LocalDate completedOn, String completedBy) {
        if (!before.status().canMoveTo(next)) {
            throw new FacilitiesException(FacilitiesErrorCode.INVALID_STATE_TRANSITION,
                    "A " + before.status() + " control cannot become " + next + ".");
        }
        return new HygieneControl(before.id(), before.reference(), before.siteCode(), before.roomId(),
                before.locationLabel(), before.controlType(), before.riskCategory(), before.title(),
                before.ownerReference(), before.frequency(), before.dueOn(), next, startedAt, completedOn, completedBy,
                before.providerReference(), before.providerConfirmed(), before.providerConfirmedBy(),
                before.providerConfirmedAt(), before.previousControlId(),
                reason != null && next == ControlStatus.COMPLETED ? reason : before.notes(),
                before.overdueNotifiedAt(), before.createdBy(), before.createdAt(), support.now(), before.version());
    }

    private HygieneControl save(HygieneControl before, HygieneControl after, Caller caller, String reason) {
        if (!store.update(after, before.version())) {
            throw HygieneSupport.conflict();
        }
        HygieneControl saved = support.control(after.id());
        support.history(saved.siteCode(), "CONTROL", saved.id(), before.status().name(), saved.status().name(),
                caller.actor().actorId(), reason);
        support.audit(caller, AuditAction.HYGIENE_CONTROL_UPDATED, "HygieneControl", saved.id(), saved.siteCode(),
                before, saved);
        return saved;
    }

    private HygieneControl successor(HygieneControl done, LocalDate fromDay, Caller caller) {
        if (!done.frequency().recurs() || store.nextControlExists(done.id())) {
            return null;
        }
        var now = support.now();
        HygieneControl next = new HygieneControl(UUID.randomUUID(), reference(), done.siteCode(), done.roomId(),
                done.locationLabel(), done.controlType(), done.riskCategory(), done.title(), done.ownerReference(),
                done.frequency(), done.frequency().next(done.dueOn(), fromDay), ControlStatus.SCHEDULED, null, null,
                null, done.providerReference(), false, null, null, done.id(), null, null, done.createdBy(), now, now,
                0);
        store.insert(next);
        recordCreated(next, caller);
        return next;
    }

    private void recordCreated(HygieneControl control, Caller caller) {
        support.history(control.siteCode(), "CONTROL", control.id(), null, control.status().name(),
                caller.actor().actorId(), null);
        support.audit(caller, AuditAction.HYGIENE_CONTROL_CREATED, "HygieneControl", control.id(),
                control.siteCode(), null, control);
        support.publish(HygieneEvents.CONTROL_SCHEDULED, "HygieneControl", control.id(), control.siteCode(),
                caller.actor(), "controlId", control.id(), "reference", control.reference(), "controlType",
                control.controlType(), "frequency", control.frequency(), "dueOn", control.dueOn());
    }

    private String reference() {
        return String.format("HYG-C-%06d", store.nextSequence("hygiene_control_seq"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static <E extends Enum<E>> String enumOrNull(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase()).name();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown " + type.getSimpleName() + ": " + value);
        }
    }

    public record ControlDetail(HygieneControl control, String effectiveStatus, List<HygieneHistoryEntry> history) {
    }

    public record Completion(HygieneControl control, HygieneControl next) {
    }

    public record SweepResult(int ownersNotified, int controlsMissed, int actionsEscalated) {
    }

    public record Dashboard(String siteCode, int periodDays, HygieneStore.Kpis kpis, Double completionRatePercent) {
    }
}
