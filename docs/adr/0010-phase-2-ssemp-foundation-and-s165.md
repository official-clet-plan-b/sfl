# ADR 0010 - Phase 2 SSEMP: one foundation for S165, S164 and S175, and S165 first

- Status: **Accepted and implemented** for S165, 2 October 2026. S164 and S175 build on it.
- Date: 2026-10-02
- Deciders: SFL platform / Health, Safety & Security Unit
- Relates: [0007 row-level security](0007-row-level-security-deferred-with-a-named-mechanism.md);
  [0009 Phase 2 IFIMP](0009-phase-2-ifimp-systems-inside-the-facilities-service.md);
  SRS CLET/DTI/CL9/SFL/SRS/2026/002 §1.7 (CORR-06, -08, -09), §3.2, §6.1 (NFR-SEC3), §6.6 (NFR-MAINT2)

## Context

The Phase 2 SRS adds three systems to SFL.SSEMP, all owned by the Health, Safety & Security Unit: S164
Permit-to-Work, S165 Risk Assessment Library and S175 Crisis & Evacuation Drill Management. S165 comes
first because the other two stand on it - S164-01 refuses a permit without a current S165 assessment -
and because two IFIMP systems already built wait on it: S173 refuses every higher-risk confirmation, and
holds a projection of S165 that nothing had ever fed.

SSEMP lacked three things every Phase 2 SSEMP system needs, and which the IFIMP Phase 2 pass had already
built for facilities:

1. **Row-level security.** ADR 0007 still lists `safety_security` as pending; no SSEMP table carried a
   policy. CORR-06 / NFR-SEC3 require every Phase 2 table to carry one from its first migration.
2. **Event delivery.** `safety_security.outbox_messages` had been written to since V1 and never read.
   S160 to S163 had recorded every event and delivered none. S173's projection could not be fed.
3. **Event consumption.** SSEMP subscribed to nothing. S165-03 needs the activity types IFIMP systems
   actually use.

## Decisions

### 1. Phase 2 SSEMP systems are modules of `sfl-safety-security-service`, in `safety_security`

Same reasoning as ADR 0009: one deployable per owning unit, the module boundary is the package and the
table set. S165 is `gh.edu.clet.sfl.safetysecurity.riskassessment`, tables prefixed `risk_`.

### 2. Row-level security for Phase 2 tables only, by explicit list

V18 ports the facilities mechanism - the `sfl_app` role, `safety_security.site_in_scope()`, and
`safety_security.apply_site_scope_policies()` - with one deliberate difference: the function takes an
**explicit table list** rather than looping over every table with a `site_code`. A catalogue loop would
have switched RLS on for every Phase 1 SSEMP table at once, along with every sweep that reads them, in a
pass that is not about them. Each Phase 2 migration names its own tables and calls the function last.
Naming a table without a `site_code` is an error, not a skip.

`PlatformThreads` comes with it, backing the scheduler and the broker listener, so sweeps, the drainer and
the listener scope to `*` under `sfl_app` rather than reading nothing - the defect facilities found and
fixed in its own Phase 2 pass.

**Consequence:** ADR 0007 stays partly open for SSEMP. Phase 1 tables (S160-S163, S174) are unpoliced
until a pass whose subject they are. `RiskAssessmentRowLevelSecurityTest` asserts the policed set is
exactly the S165 tables, so a Phase 1 table gaining a policy unannounced fails the build.

### 3. A drainer for `safety_security`, defaulting to S174's transport

`SafetySecurityOutboxDrainer` is facilities' drainer unchanged in mechanism - one message per
transaction, `FOR UPDATE SKIP LOCKED`, exponential backoff, dead-letter after N. Its transport property
defaults to S174's (`SFL_EMERGENCY_EVENT_TRANSPORT`), so the variable that already points SSEMP at the
broker points both drainers there; a deployment configured for RabbitMQ that found half the service still
on `local` would be the configured-but-not-connected failure this exists to end.

**Consequences, which an operator must know:**

- **Every row recorded since V1 is delivered once, on the first tick.** Each was written as a promise that
  the event would be published; this keeps the promise late rather than never.
- **An event no queue is bound to is unroutable**, returned by the broker, retried and dead-lettered after
  `max-attempts`. Facilities binds `ssemp.#`, so SSEMP events are routable once facilities has started
  against the broker at least once. Start facilities first, or replay dead letters afterwards
  (`docs/runbooks/dead-letter-recovery.md`).

### 4. An inbound listener, bound to `ifimp.#`

The facilities design, mirrored: one queue (`sfl.ssemp.inbound`), bound by programme, one listener that
claims the message id in V1's `inbox_messages` before dispatching to `IntegrationEventHandler` beans.
Adding a reaction is a handler bean. Declared only with the `rabbitmq` transport.

### 5. S165 decisions a reviewer should know

- **Versioning.** A draft is edited in place; a published version never is. Revising opens the next
  version; publishing it supersedes the last in the same transaction (superseding first - the database
  holds one PUBLISHED version per assessment). This is the reading under which S165-01's "each edit
  creates a new version" and "history is never lost to an overwrite" both hold.
- **Currency is the shared `RiskAssessmentCurrency` rule, everywhere.** S165 stores no "lapsed" status;
  the rule reads the review date. The sweep only records and announces the lapse, once.
- **Risk level is computed** from the highest residual rating, banded with S163's provisional matrix
  (Q-163-1), so an incident and its assessment cannot disagree about "high".
- **No new role.** HSE_MANAGER holds the module. S165-02's independence rule is about people, and
  `SignOffPolicy` enforces it per record.
- **S163 -> S165 is a published contract.** S163 gained two optional fields and an `IncidentRiskObserver`
  contract; S165 implements it with one adapter. The flag commits with the incident.

## Alternatives rejected

- **A catalogue loop for RLS, as facilities V14 did.** Rejected for the reason in decision 2.
- **Record-only events for S165, as SSEMP did until now.** It would have left S173 refusing every
  higher-risk confirmation indefinitely, for want of a class facilities already had.
- **A separate S165 deployable.** No availability, blast-radius or callback argument applies (contrast
  ADR 0004), and S163 -> S165 is in-process precisely because they share one.
