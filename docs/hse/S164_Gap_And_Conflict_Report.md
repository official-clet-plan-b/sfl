# S164 Permit-to-Work: assumptions, gaps and what is held unverified

Requirements: Phase 2 SRS CLET/DTI/CL9/SFL/SRS/2026/002 section 3.2, S164-01 to S164-05.

## What is checked, and what is only recorded
| Reference | State | Why |
|---|---|---|
| S165 risk assessment | **Checked**, at submission, at every approval stage, at extension and at resumption request | In-process, by S165's shared currency rule. The permit stores the assessment as it stood when last checked |
| S160a zone | **Checked** when named: a zone that does not exist at the site is refused | In-process |
| S152 location | Recorded, **not verified** from this service | S152 is in the facilities service; the permit says so |
| S133 contractor | Recorded, **not verified** | Vendor Master is not integrated; shown as unverified on the permit |
| S153 / S176 originating work | Recorded, **not verified** | Held by reference |
| S163 incident | Matched automatically on the assessment the incident names, in force at the time it is logged; otherwise linked by hand | S163 carries no permit reference |
| S174 emergency | Flags live permits in the affected zones, or every live permit at the site when no zone is named | Drills never flag |
| S204 audit | Every action, and every refusal, is on the audit chain | |

## Assumptions to confirm
1. **Roles.** The SRS names a Requester, Permit Authoriser, SOC Operator and HSE Director and no new role. Mapped: issuing authority = HSE manager or facilities director; safety sign-off = HSE manager or security director; isolation verifier and remover = facilities engineer, maintenance supervisor or HSE manager. Confirm the mapping.
2. **Two-stage.** High and critical risk types need both stages (enforced at configuration). Low and medium need one.
3. **Validity.** Each type has a maximum; each extension may add at most that maximum.
4. **Competence.** A worker needs a current, competent check for each competence the type names; the latest check wins; a check that lapses before the permit ends counts as expired. The competence catalogue is free text per type; no HR source is connected.
5. **Notification on suspension.** The supervisor and every worker are queued in the same transaction. "Queued" is what the permit says; delivery is the notification channel's to report. No SMS or push channel is wired for permits.
6. **Overdue.** A permit past its end stays open, escalates to the authoriser once and shows on the dashboard. Nothing closes or suspends it automatically.
7. **Incident match.** "During permitted work" is read as: the permit relies on the assessment the incident names, at the same site, and is in force when the incident is logged. An incident logged later about earlier work needs the manual link.
8. **Readiness gate.** S164's open-permit status feeds the examination-hall readiness gate through S176's start gate today. A direct feed to the S152 readiness gate is not built.

## Not built
- Provider-neutral adapters for the Vendor Master (S133), the notification channel for suspensions, and S153 work-order status. Each would follow the one-adapter pattern used for S165, S160a, S163 and S174.
- A bulk import of existing paper permits.
- Retention periods for permit evidence: filed as class `SAFETY_CRITICAL`, awaiting the statutory confirmation SRS Appendix B asks for.
