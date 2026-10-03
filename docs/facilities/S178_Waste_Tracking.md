# S178 Waste Management & Recycling Tracking

Module `facilities.waste` (domain, application with ports, infrastructure integration and scheduling, api). Migration `V24__waste_tracking.sql`. Site-scoped tables carry the row-level policy; streams, carriers, destinations and units are organisation-wide.

## Model
- **Configuration** (SRS-01): waste streams (hazardous flag, diverted flag, handling rules), collection points per site, approved carriers (licence, expiry, hazardous approval, status), approved destinations (type, permit, expiry, accepts hazardous, status), and units with a kilograms-per-unit factor. A unit is a row, not a release.
- **Collection** (SRS-02): scheduled, collected, handed over, destination confirmed, closed; or missed or cancelled. The quantity is kept as entered (value and unit) beside the normalised kilograms, flagged MEASURED or ESTIMATED.
- **Chain of custody** (SRS-03): an append-only trail - collected, handed over, received at destination, treated - with from, to, location and evidence reference.
- **Evidence**: manifest, receiving, certificate, photo; by reference with SHA-256 and retention (safety-critical for hazardous). Accepted by a person other than the submitter, who must hold the verify grant.
- **Exceptions** (SRS-05): missed collection, contamination, missing certificate, missing receiving evidence, spill, unapproved carrier, unapproved destination. Each is escalated to the facilities owner, has a due date from its type, and is raised once per collection and type while open.

## Rules
- **Approval is checked when waste moves**: at scheduling and again at handover. A suspended or expired carrier or destination, or one not approved for hazardous waste, blocks the handover and raises an exception that commits before the refusal.
- **A hazardous chain cannot close without carrier and destination evidence**: a manifest, accepted receiving evidence and an accepted certificate. A closure attempt with gaps leaves the chain open, raises an exception per gap and answers 422 listing them.
- **Contamination**: the quantity stays unreconciled until restated; the collection cannot close until it is, and the exception cannot be resolved first.
- **Missed collections**: a sweep marks a scheduled collection missed after `sfl.waste.missed-after-days` (default 1) and raises it to the facilities owner; recording the collection late settles it.
- **Measured never mixes with estimated**: every percentage uses measured kilograms only; estimates are totalled apart and labelled.

## Metrics and report (SRS-04)
Diversion (measured kilograms of diverting streams delivered to a non-landfill destination over all measured kilograms), certificate completion, missed-collection ageing, hazardous chain exceptions, open and overdue exceptions. The report lists, per stream, measured, estimated and diverted kilograms and the collection references behind them, and states the estimation and diversion rules. Viewing a report is audited.

## Links that are not enforced
- S153: spill and contamination raise a work order (category `WASTE_EXCEPTION`, idempotent on `waste-exception:<id>`) after the exception commits; a refusal leaves it `PENDING_MANUAL` and retryable.
- S163: a spill records `sfl.ifimp.waste-incident-requested.v1`; no consumer exists, so it stays `PENDING_MANUAL` until the reference is linked by hand.

## Permissions
| Permission | Held by |
|---|---|
| `FACILITIES_WASTE_READ` | Facilities director and manager, sustainability officer, HSE manager, compliance officer, DTI admin |
| `FACILITIES_WASTE_MANAGE` | Facilities director and manager, sustainability officer |
| `FACILITIES_WASTE_VERIFY` | Facilities director, HSE manager |

## API (`/api/v1/facilities/waste`)
`GET /dashboard`, `GET /report`, `GET /configuration`, `POST /streams|points|carriers|destinations|units` and `/{id}/update`, `GET|POST /collections`, `GET /collections/{id}`, `POST /collections/{id}/{record|hand-over|confirm-destination|certificate|contaminated|reconcile|missed|cancel|close|evidence}`, `POST /evidence/{id}/review`, `GET|POST /exceptions`, `POST /exceptions/{id}/{start|assign|link-incident|retry-work-order|resolve}`.

## Events
`sfl.ifimp.waste-collection-scheduled.v1`, `-collection-recorded.v1`, `-collection-handed-over.v1`, `-collection-closed.v1`, `-collection-missed.v1`, `-exception-raised.v1`, `-incident-requested.v1`.

## Export and retention
- `GET /api/v1/facilities/waste/exports/collections?siteCode=&reason=` downloads collections as a CSV, with the quantity basis column so estimates stay distinguishable. It needs `FACILITIES_REGISTER_EXPORT` and a reason, is watermarked and audited.
- Certificate and manifest evidence follow the retention periods in Record retention.
