# Estate operations runbook: hygiene, catering, lease, waste, lost & found

For whoever operates and supports S170, S172, S177, S178 and S179. Owners and sign-off at the end are for the operational owners to fill in; nothing here is a sign-off.

## Open items before production
- [ ] **Owners and sign-off:** operational owner, support owner and readiness sign-off for each of S170, S172, S177, S178 and S179 (table at the end of this file).
- [ ] **Retention periods:** the shipped periods are placeholders. Owners confirm the period and basis for each record class (SRS Appendix B), then a migration sets them. Until then the Record retention screen shows "Default pending statutory confirmation".
- [ ] **Integrations:** S136 contract, finance, S140 HR and the provider interfaces are not connected (see below).

## What runs on a timer
| Job | Property | Default | Does |
|---|---|---|---|
| Hygiene control | `sfl.hygiene.scheduling.enabled` | on | Overdue controls, escalations |
| Waste control | `sfl.waste.scheduling.enabled` | on | Missed collections, open chains |
| Lost & found | `sfl.lostfound.scheduling.enabled` | on | Retention expiry, escalations |
| Lease daily control | `sfl.lease.scheduling.enabled`, `sfl.lease.control.interval-ms` | on, hourly | Expiry, due-date alerts, notice dates, S153 review orders for lapsed leases, retries |
| Catering work orders | `sfl.catering.scheduling.enabled`, `sfl.catering.work-orders.interval-ms` | on, 5 min | Asks S153 for work on open exceptions, retries pending |
| Retention | `sfl.retention.scheduling.enabled`, `sfl.retention.interval-ms` | on, daily | Anonymises dietary data past its period |

Every job is idempotent. A failure is logged and the next run retries; a job never stops itself.

## Integrations and what "pending" means
No external system is integrated beyond the platform's own S152 and S153. Everything else is a provider-neutral adapter that says it is unavailable, so nothing is ever shown as verified, matched or confirmed because of it.

| Dependency | Used by | State |
|---|---|---|
| S152 estate | all five | Integrated |
| S153 work orders | S170, S172, S177, S178 | Integrated; a refusal leaves the request PENDING_MANUAL |
| S163 incidents | S170, S172, S178, S179 | Request recorded through the outbox, PENDING_MANUAL until someone links the incident |
| S136 contract / counterparty, finance | S177, S172 | Not integrated: references recorded, never verified or matched |
| S140 HR | S177 | Not integrated: owner shown as recorded, not verified |
| Pest-control, carrier, catering providers | S170, S178, S172 | Not integrated: manual entry or controlled import |

### When S153 is down
Corrective work shows "Pending - not confirmed by S153" (lease agreement, catering exceptions, hygiene findings, waste exceptions). Nothing is lost: the request is stored first. It is retried by the timer; a manager can retry it from the same screen. Do not create the order by hand in S153 and then leave the request pending: retry it so the link is recorded.

## Exports
Registers leave CLET only through `exports/<register>` on each module: hygiene findings, waste collections, lost & found items, catering services, lease agreements. The caller needs `FACILITIES_REGISTER_EXPORT` (director, compliance officer, HSE manager) and a reason of at least 10 characters. The file is watermarked with who, when, site and reason, capped at 5,000 rows (it says so when cut), and each export is audited as `REGISTER_EXPORTED`. Masking follows the caller's own grants: rent without the financial grant, private lost-property detail without the private-read grant. To find who took a register out, search the audit trail for `REGISTER_EXPORTED`.

## Retention
Periods are rows in `facilities.record_retention_policies`, edited in Facilities > Governance > Record retention by the director (`FACILITIES_RETENTION_MANAGE`; the compliance officer can see it with `FACILITIES_RETENTION_READ`), each change audited with its basis. The shipped periods are placeholders: **statutory confirmation is an open decision** (SRS Appendix B). Evidence past its period is listed there for authorised disposal; nothing deletes it. Catering dietary needs are anonymised after their period and each row audited (`RECORD_ANONYMISED`).

## Health and what to watch
- `/actuator/health` for liveness and readiness.
- Log lines to alert on: `could not be raised; it stays PENDING_MANUAL`, `Lease daily control failed`, `Catering work-order sweep failed`, `Retention run failed`.
- Queue age: `facilities.lease_work_orders` and `facilities.cat_exception_work_orders` rows in state `PENDING_MANUAL` for more than a day mean S153 is refusing, not slow.
- Audit chain: the Audit & integrity screen replays the chain.

## Data import
No migration or import is required: all five systems start empty. Existing paper or spreadsheet registers are entered through the screens; there is no bulk import. A controlled import for pest-control, carrier and catering providers is not built and waits on the provider decisions in Appendix B.

## Operational owners and sign-off (to be completed by the owners)
| System | Operational owner | Support owner | Readiness signed (name, date) |
|---|---|---|---|
| S170 Hygiene | | | |
| S172 Catering | | | |
| S177 Lease | | | |
| S178 Waste | | | |
| S179 Lost & found | | | |
