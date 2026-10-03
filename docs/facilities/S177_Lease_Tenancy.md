# S177 Lease & Tenancy

Module `facilities.lease` (domain, application with ports, infrastructure integration and scheduling, api). Migration `V27__lease_tenancy.sql`. Replaces the generic register that used to stand in for S177; the generic `register_records` table is dropped by `V28__drop_register_records.sql`.

## Model
- **Agreement**: a lease or tenancy for a property at a site, with CLET as tenant (inbound) or landlord (outbound), term, renewal type (none, option, automatic), notice period, notice date, rent review date, rent, deposit and currency. Status: draft, in review, active, expired, terminated, archived.
- **Version**: a snapshot written when an agreement is approved and again each time an amendment or owner change is applied. Prior versions are never edited.
- **Amendment**: rent change, term change, renewal or termination, proposed against a version. Status: proposed, legal review, approved, rejected, withdrawn.
- **Obligation**: renewal, notice, rent review, insurance, compliance, document expiry, payment, other. Notice, renewal and rent-review obligations are generated at approval; the rest are added by hand.
- **Documents** (by reference, SHA-256, optional expiry), **alerts**, and an append-only history.

## Rules
- **Incomplete agreements are visible and cannot be approved.** Approval needs a counterparty reference, an internal owner, a term, a notice period and approval evidence; every gap is listed on the agreement and in the refusal. A missing contract or finance reference is a warning.
- **Notice dates follow the business calendar.** The notice date is end date minus notice days, moved back to the last business day (weekends and holidays, in the configured timezone, default Africa/Accra). Only an approver changes the calendar.
- **Separation of duties**: whoever requested an agreement or proposed an amendment cannot approve it.
- **Amendments change nothing until approved**, then write a new version and keep the old one. Two open amendments touching the same terms put the later one in legal review; it cannot be approved until a legal reviewer clears it. An amendment proposed against an older version is refused as stale. Termination needs the notice filed.
- **Alerts escalate on a cumulative chain** as a date approaches and nobody acts: owner, manager, director, legal. Acknowledging does not clear a date; completing the obligation does. Waiving an obligation takes an approver and a reason. Each alert is raised once (unique index), so the daily control is safe to repeat.
- **Past end date**: the daily control marks an active agreement expired and tells the director and legal.
- **Rent and deposit are financial.** A role without `FACILITIES_LEASE_FINANCIAL_READ` is shown neither (list, detail, versions, amendments, portfolio exposure) and cannot set them. Financial views are audited.
- **No connected counterparty, contract or finance system**: counterparty state is "unresolved", the contract (S136) and finance references are recorded but never reported as verified or matched.

## KPIs and portfolio
Agreements by status, expiring within 30/60/90 days, obligations due and overdue, incomplete agreements, unresolved counterparties, renewals handled on time, mean amendment cycle time, load by owner, and (financial grant only) remaining rent payable and receivable per currency.

## Permissions
| Permission | Held by |
|---|---|
| `FACILITIES_LEASE_READ` | Facilities director and manager, space planning officer, construction project manager, compliance officer, DTI admin |
| `FACILITIES_LEASE_MANAGE` | Facilities director and manager, space planning officer |
| `FACILITIES_LEASE_APPROVE` | Facilities director |
| `FACILITIES_LEASE_LEGAL_REVIEW` | Facilities director, compliance officer |
| `FACILITIES_LEASE_FINANCIAL_READ` | Facilities director and manager, compliance officer |

## API
`/api/v1/facilities/leases`: `calendar`, `portfolio`, `alerts`, `agreements` (register, update, submit, return-to-draft, approve, reassign, documents, amendments, obligations), `amendments` (decide, clear-legal-review, withdraw), `obligations` (complete, waive).

## Configuration
`sfl.lease.scheduling.enabled`, `sfl.lease.control.interval-ms` for the daily control.

## Corrective work, owner check, export and retention
- When an agreement lapses the daily control raises one S153 review order for whatever depends on it (category `LEASE_EXCEPTION`), once per agreement. A manager can also raise one by hand from the agreement. The request is recorded first and is PENDING_MANUAL until S153 answers; the daily control retries it, and so can a manager.
- The internal owner is checked through a port for HR (S140). No HR system is connected, so the owner is shown as recorded, not verified.
- `GET /api/v1/facilities/leases/exports/agreements?siteCode=&reason=` downloads agreements. Rent and deposit are blank unless the caller holds the financial grant. It needs `FACILITIES_REGISTER_EXPORT` and a reason, is watermarked and audited.
- Lease documents are class LEGAL for retention; the period is set in Record retention.
