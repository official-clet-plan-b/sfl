# S164 Permit-to-Work: operations

## What runs on a timer
| Job | Property | Default | Does |
|---|---|---|---|
| Expiry sweep | `sfl.permit.scheduling.enabled`, `sfl.permit.scheduling.fixed-delay`, `sfl.permit.scheduling.initial-delay` | on, every 5 minutes | Escalates open permits nearing or past their end, once per level |
| Warning window | `sfl.permit.expiry-warning-minutes` | 60 | How close to its end a permit is "nearing expiry" |

The sweep is idempotent and runs on a platform thread so row-level security sees every site. A failure is logged per permit and never stops the rest.

## Reading it
- **Permit stuck in `SUBMITTED`**: isolation verification is incomplete, or nobody has recorded it. The permit's `blockers` say which.
- **Approval refused**: the audit trail and the permit's history carry `*_REFUSED` entries with the SRS's name for the reason (`PERMIT_ISOLATION_NOT_VERIFIED`, `PERMIT_COMPETENCY_EXCEPTION`, `PERMIT_RISK_ASSESSMENT_NOT_CURRENT`, `PERMIT_SELF_APPROVAL`).
- **A permit S176 does not see as current**: S176 applies the latest `permit-issued`, `-suspended`, `-extended` and `-closed` it received, newest first. Check the outbox (`safety_security.outbox_messages`, `aggregate_id` = the permit id) and the drainer. Until events are delivered S176 refuses the project start, which is the safe outcome.
- **Suspension notifications show `QUEUED`**: nothing delivers them yet. Phone the supervisor on the permit; the record says who was to be told.
- **Overdue permits**: they stay open. The authoriser is told once; close them out, or suspend them.

## Statutory evidence
`GET /api/v1/permits/export?siteCode=&reason=` for a compliance officer or HSE manager. The file is watermarked, audited and says which permits have an incomplete lifecycle. Search the audit trail for `PERMIT_REGISTER_EXPORTED` to see who took one.

## Before production
- [ ] Confirm the role mapping in `docs/hse/S164_Gap_And_Conflict_Report.md`.
- [ ] Confirm the permit types, competences and maximum validity with the HSE unit.
- [ ] Wire a notification channel for suspensions, and the Vendor Master for contractor verification.
- [ ] Statutory retention for permit evidence (SRS Appendix B).
