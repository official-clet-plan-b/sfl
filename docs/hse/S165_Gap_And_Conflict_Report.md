# S165 Risk Assessment Library - gap and conflict report

What is built, what is not, and what a reviewer has to decide before S165 is reported as complete.

---

## 1. The Phase 2 SRS is not in this repository

S165 was built against CLET/DTI/CL9/SFL/SRS/2026/002 v1.0 (September 2026), supplied outside the
repository. `docs/srs/` holds neither it nor the Phase 1 `.docx` that `solution.md` cites. **Action:**
check the approved SRS into `docs/srs/` so requirement IDs in code and tests resolve to a file.

## 2. Row-level security covers S165's tables only (ADR 0010)

Every site-scoped S165 table carries the fail-closed policy from V19 (CORR-06, NFR-SEC3). Phase 1 SSEMP
tables - S160 to S163, S174 - still carry none, so ADR 0007 remains partly open for SSEMP. And as in
facilities, no environment yet connects as `sfl_app`: until one does, the policies are defence in depth
that the schema owner bypasses. **Owner:** DTI Platform.

## 3. Coverage sees two sources, not four

S165-03 names S153, S159, S164 and S176 as the activity types to match. Today S165 observes:

- **S176** construction `workTypes`, over the broker (`project-registered`, `project-started`);
- **S163** incident activities, in-process.

S153 work orders and S159 bookings publish no activity or work type in their events, so they cannot be
observed without a facilities change - an additive payload field, out of this pass's scope. S164 is not
built. **Do not report coverage as complete** until S153 and S159 publish a type and S164 exists.

## 4. "Named competent reviewer" is enforced as "not the author"

S165-02: a higher-risk sign-off is "by a named competent reviewer, not the original author acting alone".
The service enforces the second half per person (`SignOffPolicy`) and records the reviewer's name. No
competence register exists anywhere in SFL, so competence itself is not checked - any holder of
`RISK_ASSESSMENT_SIGN_OFF` qualifies. **Owner to decide:** HSE Unit - whether a competence register is
required, and where it lives.

## 5. An incident flags lapsed assessments too

S165-04 says an incident "linked to ... activity with a current risk assessment" flags it. S165 flags
every **published** assessment for the activity, current or lapsed: an assessment that lapsed and was
still relied on is the one most in need of review. A draft that was never published is not flagged.
**Reviewer decision:** accept the broader reading, or narrow it to current only (one filter in
`ReviewFlagService.flagFromIncident`).

## 6. No discard, no withdrawal

- A revision opened by mistake stays open until it is published - there is no discard. It does not
  affect the current version, which stays current and linkable.
- The shared currency rule has a `WITHDRAWN` status; S165 never sets it. The SRS describes no
  withdrawal. An assessment that is no longer needed simply lapses.

## 7. The review reminder reaches the dashboard, not a person

`sfl.ssemp.risk-assessment-review-due.v1` is published, and the dashboard counts "due for review soon".
Nothing delivers the reminder to the HSE owner - that is a notification path (S174 or the enterprise Comms
system) with no consumer built. "The system raises a review reminder" is therefore met as a recorded,
visible reminder, not a sent one.

## 8. Provisional values

- **The risk matrix** is S163's placeholder (score 1-25, bands 4/9/15) pending Q-163-1. Shared on
  purpose, so an incident and its assessment band alike; both change together.
- **Review intervals** (LOW 365, MEDIUM 180, HIGH 90, CRITICAL 30 days) are V19 defaults, runtime
  configurable. HSE sets the real cycle.
- **No templates are seeded.** Writing hazard content on CLET's behalf would be inventing it.

## 9. Delivery has operational preconditions (ADR 0010 §3)

The new drainer delivers every `safety_security` outbox row recorded since V1 on its first tick, and an
SSEMP event with no bound queue is dead-lettered after `max-attempts`. Start facilities against the broker
first, or replay dead letters afterwards.

## 10. Service-wide items noticed, not changed

- A malformed request body (e.g. an unknown enum) returns Jackson's raw message, which names internal
  class paths. Every SSEMP module does this; S165 matches them. Worth one service-wide fix.
- `mvnw verify` fails SpotBugs on SSEMP with two `NM_CLASS_NOT_EXCEPTION` findings in Phase 1 code
  (`accesscontrol.domain.model.AccessException`, `lifesafety.domain.model.LifeSafetyComplianceException` -
  records named like exceptions). CI runs `test`, not `verify`, so nothing reported them. S165, the
  shared kernel and facilities are SpotBugs-clean.
- `docs/frontend/SFL_Role_Portal_Trace_Matrix.md` gains no row: S165 adds screens, not a portal.
