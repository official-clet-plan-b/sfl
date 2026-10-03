# S170 Hygiene & Pest-Control Audit Tracker

Module `facilities.hygiene` (domain, application with ports, infrastructure integration and scheduling, api). Migration `V23__hygiene_controls.sql`. Every table is site-scoped with row-level security.

## Model
- **Control** - an audit, pest visit or statutory check. Frequency ONE_OFF to ANNUAL; the next due date counts from the due date (month-end clamped) and never lands on or before the completion day. Completing or missing a recurring control schedules its successor. `OVERDUE`/`DUE` are derived, never stored. A pest visit with a named provider cannot be completed until the provider's confirmation is recorded.
- **Finding** - LOW to CRITICAL. Critical needs an owner and a target date, gets a corrective action due in 2 days, escalates to HSE at once and to leadership if it repeats a critical finding of the same category and place within 90 days. High and critical raise an S153 work order (category `HYGIENE_FINDING`, idempotent on `hygiene-finding:<id>`).
- **Action** - OPEN, IN_PROGRESS, COMPLETED, VERIFIED or REJECTED. Whoever completed it cannot verify it.
- **Evidence** - by reference: file name, type, size, SHA-256, retention class (never below COMPLIANCE; SAFETY_CRITICAL for high and critical). A different verifier accepts or rejects it.
- **Closure** - only with accepted evidence and every action verified, or by a recorded exception (reason of 10+ characters, approver is not the person who raised the finding). Closed is terminal except an audited reopen with a reason.
- **Escalations** and an append-only **history** are stored in their own tables, besides the audit chain.

## Links that are not enforced
- S153 work order: raised after the finding commits, in its own transaction. A refusal leaves the finding `PENDING_MANUAL`; `POST /findings/{id}/retry-work-order` is safe to repeat.
- S163 incident: S163 runs in another deployable and has no consumer yet. The request is recorded as `sfl.ifimp.hygiene-incident-requested.v1` and the finding stays `PENDING_MANUAL` until `POST /findings/{id}/link-incident` records the reference.

## API (`/api/v1/facilities/hygiene`)
`GET /dashboard`, `GET|POST /controls`, `GET /controls/{id}`, `POST /controls/{id}/{start|complete|missed|cancel|confirm-provider}`, `GET /findings`, `POST /controls/{id}/findings`, `GET /findings/{id}`, `POST /findings/{id}/{update|start|link-incident|retry-work-order|close|reopen|actions}`, `POST /actions/{id}/transition`, `GET|POST /findings/{id}/evidence`, `POST /evidence/{id}/review`, `GET /escalations`, `POST /escalations/{id}/acknowledge`.
Lists are paged and filtered on the server. Creates answer 201, unknown ids 404, a transition the record cannot make 422, a stale `version` 409, malformed input 400 in the standard envelope.

## Permissions
| Permission | Held by |
|---|---|
| `FACILITIES_HYGIENE_READ` | Facilities director and manager, HSE manager, compliance officer, DTI admin |
| `FACILITIES_HYGIENE_MANAGE` | Facilities director and manager, HSE manager |
| `FACILITIES_HYGIENE_EVIDENCE_READ` | Facilities director and manager, HSE manager, compliance officer (every read is audited) |
| `FACILITIES_HYGIENE_VERIFY` | Facilities director, HSE manager |

## Events
`sfl.ifimp.hygiene-control-scheduled.v1`, `-control-completed.v1`, `-control-missed.v1`, `-finding-raised.v1`, `-finding-closed.v1`, `-escalated.v1`, `-incident-requested.v1`. Payloads carry ids, references, codes and dates only.

## Sweep
`sfl.hygiene.scheduling.enabled` (default true), `sfl.hygiene.missed-after-days` (default 7), `sfl.hygiene.sweep.interval-ms` (default 15 minutes). Tells the owner once when a control is overdue, marks it missed after the grace period and tells HSE, escalates overdue actions. Each escalation is unique per subject, level and reason.

## Export and retention
- `GET /api/v1/facilities/hygiene/exports/findings?siteCode=&reason=` downloads findings as a CSV. It needs `FACILITIES_REGISTER_EXPORT` and a reason of at least 10 characters, is watermarked with who took it, when and why, and is audited (`REGISTER_EXPORTED`).
- Evidence keeps its retention class; the period for each class is set in Record retention. Evidence past its period is reported for authorised disposal, never deleted automatically.
