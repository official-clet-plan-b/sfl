# S172 Catering & Cafeteria Management

Module `facilities.catering` (domain, application with ports, infrastructure integration, api). Migration `V26__catering.sql`. Suppliers are organisation-wide; every other table is site-scoped with the row-level policy.

## Model
- **Configuration** (SRS-01): suppliers (certificate, expiry, status), venues (capacity), menus with dishes. Each dish declares the allergens it contains from a closed vocabulary (the 14 major allergens) and the diets it suits; `allergensDeclared` is separate from the list, so "none declared" is never mistaken for "none contained".
- **Service**: a planned catering service for a venue, menu and supplier, with its context (event, booking, examination or routine - anything but routine must name it), start, cancellation cut-off, expected guests, planned and delivered portions. Status: draft, pending approval, approved, confirmed, delivered, reconciled, closed, cancelled.
- **Dietary need** (SRS-02): an **opaque person reference** (letters, digits, `. _ -` - a name is refused), the allergen or diet, and who authorised collecting it. Nothing else about the person.
- **Checks, exceptions, variances, evidence** (SRS-03, -05), and an append-only history.

## Rules
- **Allergens are visible and checked before approval** (acceptance criterion): each need is matched against the menu; dishes containing the allergen are flagged, and a need with nothing declared-safe on the menu blocks approval until an approver accepts a substitution that really is safe (declared and free of the allergen, or carrying the diet tag) or waives it with a written reason. An undeclared dish is never counted as safe.
- **A service cannot silently appear compliant** (acceptance criterion): venue in use, menu approved with every dish declared, supplier approved with an unexpired certificate and a passing check in the last 30 days, capacity - recomputed at approval and again at confirmation, and shown on every read of a service being planned.
- **Exceptions an approver may accept, with a reason**: capacity exceeded, supplier certificate expired, supplier check overdue. **Cannot be accepted**: an allergen need with nothing safe, an unapproved menu or undeclared allergens, a suspended supplier, an inactive venue.
- **Controlled changes** (SRS-04): approving a menu needs the approve grant; changing a dish on an approved menu returns it to draft and sends every service still being planned on it back for approval. Changing a service's quantities, venue, supplier or menu, or adding a dietary need, after submission needs a reason and sends it back for approval; after the cancellation cut-off it needs an approver, as does cancelling.
- **Separation of duties**: whoever requested a service cannot approve it; whoever recorded a need cannot approve its substitution or waiver; whoever recorded a variance cannot approve it.
- **Food safety**: a temperature check's result is worked out from the reading (hot food 63 C or above, cold 5 C or below), never typed in. A failing temperature or food-safety check raises a food-safety incident (S163 request recorded, pending until linked). Delivery needs a passing temperature check and no open food-safety incident; only an approver resolves a food-safety incident.
- **Reconciliation** (SRS-05, not the ledger): a quantity difference needs a variance with an owner and a reason before reconciling. Purchase and invoice references are recorded; with no invoice reference the service stays delivered and finance state is pending; with one it is reconciled with state "recorded". There is no finance integration, so nothing is ever reported as matched. Closing needs every variance approved and the delivery note and invoice filed.

## KPIs
Confirmed services delivered, food-safety checks completed (delivered services with a passing temperature check), dietary exception rate (needs that required a substitution or waiver), open variances by age with the net quantity variance, open exceptions, services awaiting finance.

## Permissions
| Permission | Held by |
|---|---|
| `FACILITIES_CATERING_READ` | Facilities director and manager, event-logistics coordinator, HSE manager, compliance officer, DTI admin |
| `FACILITIES_CATERING_MANAGE` | Facilities director and manager, event-logistics coordinator |
| `FACILITIES_CATERING_APPROVE` | Facilities director, HSE manager |
| `FACILITIES_CATERING_DIETARY_READ` | Facilities director and manager, event-logistics coordinator, HSE manager (reads are audited) |

## API (`/api/v1/facilities/catering`)
`GET /dashboard`, `GET /configuration`, `POST /suppliers|venues|menus` and `/{id}/update`, `POST /menus/{id}/items|approve|retire`, `POST /items/{id}/update`, `GET|POST /services`, `GET /services/{id}`, `POST /services/{id}/{change|submit|approve|confirm|deliver|cancel|dietary|variances|evidence|reconcile|close}`, `GET /services/{id}/pack`, `POST /dietary/{id}/{substitute|waive}`, `POST /checks`, `GET|POST /exceptions`, `POST /exceptions/{id}/{resolve|link-incident}`, `POST /variances/{id}/approve`.

## Events
`sfl.ifimp.catering-service-planned.v1`, `-service-approved.v1`, `-service-confirmed.v1`, `-service-delivered.v1`, `-service-cancelled.v1`, `-service-closed.v1`, `-exception-raised.v1`, `-incident-requested.v1`. Payloads carry ids, references, codes, counts and dates - never a person reference or free text.

## Not integrated
S159 and S173 context is held as a reference and not validated against those systems. S170 hygiene audits are not consulted. Finance and procurement: references only, never verified. S163 incidents are requests recorded for a consumer that does not exist yet.
