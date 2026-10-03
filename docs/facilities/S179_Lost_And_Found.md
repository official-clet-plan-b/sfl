# S179 Lost-and-Found Register

Module `facilities.lostfound` (domain, application with ports, infrastructure integration and scheduling, api). Migration `V25__lost_and_found.sql`. Site-scoped tables carry the row-level policy; retention periods are configuration, one row per category.

## Model
- **Found item** (SRS-01): controlled category, a short **public description** and a **private description**, where and when it was found, the finder reference, initial condition, storage location, retention date. Status: registered, stored, isolated (unsafe), released, disposed, handed to the authorities.
- **Claim reference** (SRS-02): random, eight characters from an unambiguous alphabet, unrelated to the item - no category, date, site or sequence number.
- **Custody chain** (SRS-03): every transfer has a sender, receiver, place and time. The sender is taken from whoever the chain says holds the item. Rows cannot be updated or deleted: a database trigger refuses both.
- **Claim** (SRS-03/04): received, verified, approved, then released; or refused or withdrawn. Verification records the method and where the check is filed, not ID details.
- **Evidence**: photograph, verification, release receipt, disposal authorisation, authority receipt - by reference with SHA-256.
- **Escalations**: unsafe item (to security and the emergency procedures, S163 incident pending), competing claims, retention expired.

## Rules
- **Masking** (SRS-02, -05): a reader without `FACILITIES_LOSTFOUND_PRIVATE_READ` gets the controlled description and the status only - no private detail, finder, claimant name or contact, and no evidence. Applied in the service on every read path. Reading the private view or evidence is audited. A claimant sees the item's private detail only after their identity is verified.
- **Release** needs: item in storage and not unsafe, claimant verified, release approved, no other open claim, a release receipt filed for the claim, and the claimant's recorded acceptance. Every blocker is listed in the 422 and recorded on the claim's history and in the audit trail in a transaction that survives the refusal. A claimant who declines at the desk closes the claim and the item stays in store.
- **Separation of duties**: whoever verified cannot approve; whoever approved cannot hand the item over; whoever filed a disposal authorisation cannot approve the disposal.
- **Competing claims** block approval and release and escalate to security.
- **Secure storage**: documents, electronics, jewellery, cash and valuables, and medical items must be stored in a secure location.
- **Disposal** needs the retention period to have ended (an unsafe item excepted), a filed authorisation, no open claim, and the approve grant. **Handover to authorities** needs the authority's receipt and the approve grant.
- **Retention sweep** (`sfl.lostfound.scheduling.enabled`, hourly): escalates an unclaimed item past retention once; erases a closed claim's name, contact and description after the category's personal-data period, keeping the reference, outcome and dates. The purge is audited.

## KPIs
Open items by age and by storage location; mean time from claim to verified release; custody-chain completeness; items disposed or returned within policy.

## Permissions
| Permission | Held by |
|---|---|
| `FACILITIES_LOSTFOUND_READ` | Facilities director and manager, security director and officer, reception officer, centre manager, compliance officer, DTI admin |
| `FACILITIES_LOSTFOUND_MANAGE` | Facilities director and manager, security director and officer, reception officer, centre manager |
| `FACILITIES_LOSTFOUND_PRIVATE_READ` | The same roles as manage |
| `FACILITIES_LOSTFOUND_APPROVE` | Facilities director, security director |

## API (`/api/v1/facilities/lost-found`)
`GET /dashboard`, `GET /configuration`, `POST /locations`, `POST /retention`, `GET|POST /items`, `GET /items/{id}`, `GET /lookup`, `POST /items/{id}/{store|transfer|unsafe|dispose|hand-to-authorities|claims|evidence}`, `GET /items/{id}/evidence`, `GET /claims`, `GET /claims/{id}/claimant-view`, `POST /claims/{id}/{verify|approve|refuse|withdraw|release}`, `GET /escalations`, `POST /escalations/{id}/{acknowledge|link-incident}`.

## Events
`sfl.ifimp.found-item-registered.v1`, `-found-item-released.v1`, `-found-item-disposed.v1`, `-found-item-handed-to-authorities.v1`, `-found-item-isolated.v1`, `-lost-found-escalated.v1`, `-lost-found-incident-requested.v1`. Payloads carry references, categories and dates - never a description, name or contact.

## Export and retention
- `GET /api/v1/facilities/lost-found/exports/items?siteCode=&reason=` downloads found items. The private description and finder columns are blank unless the caller holds the private-read grant. It needs `FACILITIES_REGISTER_EXPORT` and a reason, is watermarked and audited.
- Item and personal-data retention keep their own per-category periods (`lf_retention_policies`); evidence classes follow Record retention.
