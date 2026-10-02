# SFL Implementation Solution Log & Architecture Standard

> **Authoritative references**
> - **Specification (the contract):** `docs/srs/CLET_Cluster9_SFL_Phase1_SRS_v1.0.docx` - the 13 Fast-Track systems, all functional/non-functional requirements. Every module, endpoint, event and test traces to an `SRS-SFL-*` ID.
> - **Build plan:** this file - the delivery waves and per-service backlog it once described are complete; this log is now the current build-plan-and-status record. The original workplan is retained for history at `docs/to-delete/release1-demo-cleanup-2026-08-01/SFL_Phase1_Implementation_Workplan.md`.
> - **Reference implementation pattern:** the S074 comms-service (API-first, contract → 202 → fast/deferred processing → transactional audit outbox → runtime-resolved adapter registry → OIDC/JWT + permission checks → emergency fast-lane). We mirror its *shape*, re-expressed in Java/Spring with hexagonal layering.
> - **ADRs:** `docs/adr/0001` (foundation), `0002` (build/buy/hybrid), `0003` (Java/Spring migration),
>   `0004` (S174 as its own deployable), `0005` (programme-scoped portals), `0006` (one dashboard),
>   `0007` (row-level security), `0008` (pluggable authentication), `0009` (Phase 2 IFIMP), `0010`
>   (Phase 2 SSEMP foundation and S165). All ten, so this list cannot quietly fall behind `docs/adr/`.

We implement to the SRS. Where the SRS and any earlier note disagree, **the SRS wins** and this log is corrected.

---

## Active Architecture

The active SFL implementation is the **three-platform Spring Boot** workspace under `services/` -
one deployable per programme, five schemas, three databases:

- `sfl-facilities-service` - **SFL.IFIMP**, port 8091. S152 CAFM/IWMS, S153 CMMS, S159 Room & Resource Booking, hall-readiness, plus the six Phase 2 systems: S156 Building Management System/IoT, S157 Energy & Sustainability Monitoring, S158 Space Planning & Move Management, S169 Cleaning & Janitorial Schedule Management, S173 Event Logistics & Set-Up Workflow, S176 Construction Project Management. Schema `facilities`.
- `sfl-safety-security-service` - **SFL.SSEMP**, port 8092. All of S160 Visitor, S163 HSE Incident/Near-Miss, S160a Physical Access Control Integration, S161 CCTV/VMS Integration, S162 Intrusion Detection & Alarm Monitoring and S162a Fire/Life-Safety Monitoring are now built in `safety_security`; S174 Emergency Notification is built in `emergency_notification`. Phase 2: S165 Risk Assessment Library is built in `safety_security` (package `riskassessment`, ADR 0010), on a Phase 2 foundation S164 and S175 will share - row-level security for Phase 2 tables, a `safety_security` outbox drainer, and an inbound listener bound to `ifimp.#`. S160a governs access policy, provisioning, overrides, SOC exceptions and occupancy over a recorded vendor gateway - see `accesscontrol` - and never controls a door itself. S161 governs camera inventory/health, a governed evidence-request-and-approval workflow, evidence-by-reference with hashing and access logging, live-view authorisation, analytics-alert triage and retention/disclosure governance over a recorded VMS gateway - see `cctv` - and never stores raw video by default. S162 runs the SOC alarm queue, escalation, zone arming/disarm and armed-response coordination over a recorded panel gateway - see `intrusion` - and never sits in the certified intrusion actuation path. S162a is observe-only by design - no outbound command/actuation port exists, unlike its four siblings - and its fast-lane trigger calls S174's break-glass activation in-process, see `lifesafety`. All four SSEMP Buy-and-Integrate systems (S160a, S161, S162, S162a) are now built; none is left as scope only.
- `sfl-fleet-logistics-service` - **SFL.FTLMP**, port 8093. S166 Fleet, S168_fuel Fuel & Logbooks, S171 Mailroom/Courier & Dispatch (schema `fleet_logistics`) and AVAMP-Lite asset/device/location references (schema `asset_visibility`, package `..fleetlogistics.assets`). Serves the dashboard at `/ui`.
- `sfl-service-common` - shared kernel (principal/RBAC, error & event envelopes, outbox/inbox contracts, integration-security primitives). Library, no schema.

**The unit of ownership is the schema; the unit of release is the service.** Each *bounded context*
owns its schema, migrations, API boundary, domain model, outbox and idempotent inbox. Two services
host two contexts each, and the boundary between them is enforced exactly as it is between services:
**no foreign key, join or view crosses a schema, in either direction, even inside one database**
(SRS §2.5, §2.6, §23.8). Cross-context workflows use **APIs, events and sagas** through the
enterprise API gateway, IAM, event broker, audit/evidence, notification, reporting and
document/object-storage services. The root Spring Boot app under `src/main` is migration/reference
material only.

Two properties keep the contexts extractable and must be preserved by anything added to them: S174
is plain JDBC with **every statement schema-qualified**, and AVAMP's entities carry an explicit
**`@Table(schema = ...)`**. Neither depends on its host's Hibernate `default_schema`, which is why
re-extracting either is a `pg_dump -n <schema>` and a connection string rather than a rewrite.

---

## Architecture Standard (Clean / Hexagonal - Ports & Adapters)

Every service, every feature, uses this layering and dependency rule:

```
gh.edu.clet.sfl.<service>.<feature>.
  api/                inbound adapter: controllers, request/response DTOs, Bean Validation,
                      principal mapping, HTTP status + error envelope, OpenAPI
  application/
    command/          write use cases (transaction boundary lives here)
    query/            read use cases / read models
    port/             outbound ports (interfaces the app owns): repositories, EventPublisher,
                      AuditPort, VendorXAdapter, IntegrationInbox, ClockPort, IdGenerator, RuntimeConfigPort
    workflow/         sagas / process managers
  domain/
    model/            aggregates, entities, value objects (NO Spring/JPA/Jackson/HTTP/vendor imports)
    event/            domain events
    policy/           invariants, status transitions, authorization predicates
  infrastructure/
    persistence/      JPA entities + Spring Data repos implementing repository ports
    messaging/        RabbitMQ publisher, outbox drainer, inbound consumers
    integration/      vendor adapters (CCTV/access/alarm/fire/fuel/notification/courier/telematics)
    security/         OIDC/JWT → SflPrincipal mapping, method authorization
    config/           Spring config + runtime-config provider (config-without-code)
```

**Dependency rule (ArchUnit-enforced):** `api → application → domain`; `infrastructure → application/domain`. Nothing points into `infrastructure`. The **domain layer imports no framework**. Modules reference only other modules' contracts + published events - never another module's `domain`/`application`/`infrastructure`. **A DB-lint fails the build on any cross-schema foreign key.**

**Ports own every boundary; one adapter per product.** Identity (OIDC/JWT), messaging (RabbitMQ), cache (Redis), persistence (Postgres) and every vendor device system live behind a port with exactly one adapter, so any product is swappable by configuration - not a rewrite.

---

## API-First Build Recipe (do this for every requirement slice)

Same discipline as comms and as the AVAMP asset API already built. One or a few `SRS-SFL-<system>-NN` per slice, in this fixed order:

1. **Contract** - OpenAPI operation(s) + `api` DTOs with Bean Validation, derived from the requirement's *Requirements* and *Status Matrix*.
2. **Controller (stub)** - correct status (`202 Accepted` + id for async submits; `200/201` sync).
3. **Contract test** - WebMvc slice: happy path + the requirement's *Error States* envelope + 403 authorization. (Executable *Acceptance Criteria*.)
4. **Domain** - aggregate(s), value objects, events, `policy`; unit test each business acceptance criterion.
5. **Application use case** - command/query with the transaction boundary; define new outbound `port`s.
6. **Persistence + Flyway** - JPA entity + repo implementing the port; `V#__<slice>.sql` in the service schema.
7. **Vendor adapter (Buy/Hybrid only)** - implement the vendor port with a **simulator first**; real vendor after contract tests pass.
8. **Async worker/consumer** - outbox drain publishes the slice's events; inbox consumers react idempotently.
9. **Read model / dashboard** - projection + query endpoint feeding dashboards + Analytics (S225).
10. **Authorization + site-scope + audit** - `AuthorizationPolicy` + RLS + audit record on every state change.
11. **Integration + architecture tests** - Testcontainers (Postgres/RabbitMQ/Redis), saga tests, ArchUnit gates.

**Contract conventions:** `/api/v1/<domain>/<resource>`; `202 Accepted` + `{requestId}` + status endpoint (+ signed callback for source systems); `Idempotency-Key` header + dedup on state-creating POSTs; uniform error envelope matching SRS *Error States*; cursor pagination; `X-Correlation-ID` end-to-end.

---

## Eventing, Outbox / Inbox & Data conventions

- **Broker:** RabbitMQ. Exchange `sfl.events` (topic); routing key `{platform}.{event-name}.v{version}`; dead-letter `sfl.events.dlx`; consumer queues `sfl.{consumer}.{purpose}`. A **fast-lane** exchange/queue carries life-safety/emergency events (S162a/S174) and is drained ahead of standard traffic.
- **Envelope (every message):** `eventId, eventType, eventVersion, occurredAt, publishedAt, correlationId, causationId, siteCode, sourceModule, traceparent, payload`.
- **Outbox → publish:** business change **+** outbox row in one local transaction; an `OutboxDrainer` (`@Scheduled`, `FOR UPDATE SKIP LOCKED`, exponential backoff, poison after N) publishes. At-least-once.
- **Inbox → consume:** write `eventId` to `inbox_messages` before processing; consumers are idempotent (Redis may front the dedup, Postgres is the record).
- **Data:** schema per service; Flyway per service; **Postgres Row-Level Security** + repository site-scope filter driven by the principal's `SiteScopes`; **no cross-schema FK**.
- **Evidence by reference:** store references + hashes, not raw video/large files, unless an approved policy exception (S161, §4.2).

---

## Security & Configuration

- **AuthN:** OAuth2 **resource server** validating OIDC/JWT (standards-based, provider-pluggable - swap = config). Runtime validation is pure OIDC/JWKS so it works offline at the edge via cached JWKS. No local credential store.
- **AuthZ:** `AuthorizationPolicy` (in `sfl-service-common`) on every command/query - role **and** site-scope; denials audited. Dev uses the `X-SFL-*` header actor; prod swaps in the JWT/claims-backed `ActorContext` producer (same interface).
- **Inbound integration security:** every vendor webhook - per-vendor HMAC or mTLS, source allowlist, schema validation, store-raw → normalise → publish; reject-and-log otherwise (SRS 0F, CT-19).
- **Audit:** immutable, **hash-chained** (`hash = H(prev_hash || canonical(record))`), insert-only writer role; tamper detectable by replay (PLAT-03, CT-18).
- **Config-without-code:** SLA thresholds, zones, severities, fuel limits, readiness checklists, retention and vendor/provider config are runtime-configurable, versioned and audited (PLAT-05). Secrets are vault references, rotated without redeploy.
- **Edge survivability:** local outbox + offline token validation + permission snapshot keep a centre operating during WAN loss; reconcile on restore (PLAT-04, CT-17).

---

## SRS-Driven Build Sequence (see the Workplan for detail)

- **R0 Foundation** - `sfl-service-common` kernel + per-service foundation (outbox drainer, RabbitMQ topology, resource server, audit hash-chain, ArchUnit/DB-lint, edge skeleton). Prove the spine end-to-end. *(PLAT-01..05)*
- **W1 Facilities core + AVAMP** - S152, S153 spine, readiness v1; extend AVAMP references.
- **W2 Physical security + IntegrationHub** - S160, S160a, S161, S162 + inbound-security (CT-19).
- **W3 Life-safety + Emergency comms + Fast lane** - S162a, S174 (CT-20; SFL never in actuation path).
- **W4 HSE** - S163.
- **W5 Fleet & Logistics** - S166, S168_fuel, S171.
- **W6 Sagas + Edge + Dashboards** - hall-readiness, emergency-incident, secure-dispatch sagas; PLAT-04/06/07 (CT-17).
- **W7 Commissioning & Go-Live** - NFRs §6, CT-17..21, DR drill, sign-off.

**Reference slice (the template every team copies):** `POST /api/v1/facilities/work-requests` → `work-order` → outbox `sfl.ifimp.work-order-created.v1` → audit hash-chain → readiness re-score, built strictly through the API-First recipe above.

---

## Implementation Log (history)

### Pass - Facilities IFIMP vertical slice (migrated to `sfl-facilities-service`)
`sfl-facilities-service` owns S152 facilities master data (sites, buildings, floors, rooms, zones, room readiness, device/location references), the S153 fault-reporting/maintenance-intake foundation, and S159 readiness hooks through room/resource readiness data. It owns the `facilities` schema and reads/writes no other service schema.
Package layout: `masterdata.{domain,application,api,infrastructure.persistence}`, `maintenance.{domain,application,api,infrastructure.persistence}`, `shared.{application,infrastructure.persistence}`.
Persistence/migrations: `V1__service_foundation.sql` (schema, metadata, outbox, inbox/idempotency), `V2__facilities_master_data.sql`, `V3__facility_faults.sql`. Eventing records service-local integration events in `facilities.outbox_messages` via the `ServiceOutbox` port (no writes to old monolith schemas). The service landing page at `/` was retired by ADR 0006 once S152 had dashboard screens; it now redirects to `/ui/facilities`.

### Pass - AVAMP-Lite Asset Visibility slice (`sfl-asset-visibility-service`)
Owns asset/device/location reference records used by other services (not financial accounting, full RFID stocktake, vendor scanner management or depreciation). Capabilities: register asset/device references (category, site, location, custodian, external ref); move to a new site-scoped location; assign/clear custody; link evidence metadata references (no files); query by site and by location; publish service-local outbox rows for registration/location/custody/evidence changes.
API: `POST /api/v1/assets`, `GET /api/v1/assets?siteCode=...`, `GET /api/v1/assets/{assetId}`, `GET /api/v1/assets/by-location`, `PATCH /api/v1/assets/{assetId}/location|custody|evidence`.
Persistence: `V2__asset_references.sql` (`asset_visibility.asset_references`); foundation migration owns `asset_visibility.outbox_messages` and `inbox_messages`.

### Pass - Asset API contract tests + Facilities work orders
Added Spring Boot WebMVC contract tests for `sfl-asset-visibility-service` (register/list/lookup/move + validation error envelope). Added the S153 work-order foundation in `sfl-facilities-service`: `WorkOrder` model, `WorkOrderStatus` lifecycle (`OPEN`, `ASSIGNED`, `CLOSED`), create-from-fault, assign, close with notes, and service-local outbox events `sfl.facilities.work-order-created|assigned|closed`.
API: `POST /api/v1/facilities/work-orders/from-fault`, `PATCH /api/v1/facilities/work-orders/{id}/assignment|closure`, `GET /api/v1/facilities/work-orders[/{id}]`. Persistence: `V4__work_orders.sql` (`facilities.work_orders`, referencing `facilities.facility_faults` inside the same schema only).

### Pass - Role, Actor and Site-Scope foundation (`sfl-service-common`)
Shared security concepts reusable by all four services without coupling to a specific IAM product: `SflRole` (`SFL_ADMIN`, `FACILITIES_DIRECTOR`, `FACILITIES_MANAGER`, `IFIMP_MAINTENANCE_SUPERVISOR`, `IFIMP_TECHNICIAN`, `IFIMP_REQUESTER`, `VENDOR_TECHNICIAN`, `COMMAND_ROLE`, `AUDITOR`, `DTI_ADMIN`, `INTEGRATION_ENGINEER`); `SflPermission`; `SiteScopedPrincipal`; `ActorContext`; `AuthorizationPolicy` (`hasRole`/`hasAnyRole`/`canAccessSite`/`require*`). Workflow authorization stays separate from IAM: the identity provider supplies identity/groups/claims; each service still enforces its own workflow role and site-scope decisions.
Dev header actor resolver reads `X-SFL-User`, `X-SFL-Display-Name`, `X-SFL-Roles`, `X-SFL-Sites`, `X-Correlation-ID`; the future JWT path replaces the header resolver with a JWT/claims-backed adapter producing the same `ActorContext`. S153 work-order commands enforce role + site access (create/assign: `SFL_ADMIN`/`FACILITIES_MANAGER`/`IFIMP_MAINTENANCE_SUPERVISOR`; close: supervisor/technician/vendor; read: facilities + `AUDITOR`/`COMMAND_ROLE`); authorization failures return 403.

### Pass - Bootstrap facilities dashboard
Dev/demo UI served by `sfl-facilities-service` at `/`: Command Center (service health, open work, registry counts, recent records), Facilities Registry (S152 sites/buildings/floors/rooms/readiness), Maintenance (S153 faults → work orders → assign/close), Asset Visibility (AVAMP-Lite register/list), Actor/Services config (dev actor + API base URLs). Thin frontend shell over service APIs (`static/index.html`, `assets/css`, `assets/js`) intended for later replacement by a React app without rewriting backend workflow logic. Local CORS for dev origins (8091/8094/5173/3000).

### Pass - S152 CAFM/IWMS platform (`sfl-facilities-service`)
`SRS-SFL-S152-01..05`, NFR 23.3, NFR 23.8. Built S152 as the **host platform for IFIMP**, because the C9 mapping makes S153 a "sub-system of CAFM (S152)" and puts S158 and S159 under it too - the space and asset registry has to exist before anything can reference it.

**Platform, in `facilities.shared`, inherited by S153 and S159 rather than rebuilt per module:** hash-chained append-only audit (`AuditHashChain`, single-row chain head under a pessimistic lock, DB trigger refusing UPDATE/DELETE, denials audited as `AUTHORIZATION_DENIED`); idempotency store keyed on operation + key + request fingerprint; effective-dated runtime configuration read at evaluation time; `FacilitiesActorResolver` (JWT or `X-SFL-*` headers); correlation-ID filter; uniform error envelope with SRS-worded codes; `FacilitiesPermissionMatrix`; `RecordMetadata` and `RecordLifecycleStatus`.

**Estate:** site → building → floor → space extended with system-managed fields, four-state lifecycle (`ARCHIVED` terminal), `SpaceType` with bookable/examination-capable defaults, area and cost centre; site `OperatingMode` (Routine ⇄ Examination, role-gated and audited per NFR 23.3); zones with nesting and heterogeneous membership; device references with vendor identity and observation time; and the **`FacilityAsset` register** - the fixed plant S153 raises work orders against, distinct from AVAMP-Lite's cross-programme asset identity and linked to it by value.

**Readiness engine:** runtime-configurable versioned checklists applicable by space type and operating mode; append-only assessments; blockers from checklist items, assets and manual raises; the examination readiness lock. The invariant everything serves - *a space cannot be marked READY while a critical blocker is open* - is enforced on both the derived and the manual path through one `ReadinessPolicy`. An asset going out of service raises a blocker at a severity derived from its criticality and blocks the space it serves; its recovery clears it.

**Dashboard (S152-05):** readiness by status, blockers by severity, unavailable spaces, examination-readiness risk, stale readiness with a configured freshness threshold, and maintenance-linked risk through a port the maintenance module implements. Computed live from source records so counts reconcile; snapshot tables exist for go-live reporting.

API: 49 paths under `/api/v1/facilities`. Persistence: `V5__facilities_platform_foundation.sql`, `V6__facilities_estate_model.sql`, `V7__facilities_readiness.sql`, `V8__facilities_dashboard_snapshots.sql`. Tests: 147, covering the ten mandatory S152 scenarios, the readiness rules, the audit chain's tamper detection, the permission matrix, twelve WebMvc contract tests and nine ArchUnit boundary rules.

**Verified by running it, not only by testing it.** 135 tests were green while the service could not start. Dropping the schema and running against a real PostgreSQL found eight defects invisible to in-memory doubles - `CHAR(64)` columns Hibernate rejects, two unannotated constructors that had never let this service boot, blockers written before the row they reference, and - twice over - an audit chain that replayed as tampered because `jsonb` reorders keys and because nanosecond timestamps are stored as microseconds. All fixed; all recorded in `docs/facilities/S152_Gap_And_Conflict_Report.md` §8a, which also flags that `sfl-fleet-logistics-service` likely has the last two. **The lesson for S153 and S159: run the service against a database as part of the build, not after it.**

### Pass - S152 CAFM/IWMS dashboard module (`frontend/sfl-operations-ui`)
Built the S152 UI in the shared operations dashboard: **fifteen screens** in `src/modules/facilities` across three navigation sections - Facility operations (dashboard, readiness assessments), Estate registers (sites, spaces, facility assets, zones, device references), Facility assurance (readiness checklists, audit & integrity, configuration). Every item carries its real service permission, so the sidebar narrows with the actor rather than offering screens the service will refuse.

**Service changes the UI forced.** The facilities service's five S152 controllers returned bare payloads while 27 fleet and 8 emergency controllers returned `ApiResponse<T>`; the five outliers were changed rather than teaching the shared client a per-service policy, and `/actor/permissions` was flattened to `string[]` for the same reason. The two pre-S152 maintenance controllers were finished in the same pass. CORS allowed neither `8093` (bundled dashboard) nor `5005` (`npm run dev`) and did not expose `X-Correlation-ID` - every screen would have failed in a browser while every equivalent `curl` succeeded.

**ADR 0006 decision 3 discharged.** That decision kept `sfl-facilities-service`'s static page *only* until IFIMP had dashboard screens. It now redirects to `/ui/facilities` like the other four, its 861 lines of stylesheet and script are deleted, and its `index.html` is a notice page. Fault reporting and work orders were the one real capability lost - they are S153 and have no screens - which the notice page and the gap report both say plainly rather than gloss.

**Verified by driving it, not only by testing it.** A green build, a green typecheck and 44 green tests hid a module-wide defect: `shared/layout/actorPermissions.ts` asked only fleet and emergency, and its fail-open is per-*set*, so every `FACILITIES_*` permission evaluated **denied** - all S152 controls silently absent, no error anywhere. Found by clicking a row. In the browser against real PostgreSQL: a passing re-assessment closed the critical blocker it superseded; a critical generator set `OUT_OF_SERVICE` blocked its hall while the checklist score stayed at 100%; READY was refused in both layers with a readable message; returning the asset to service returned the space to READY; a `FLEET_MANAGER` saw no facilities sections and an `IFIMP_TECHNICIAN` no operating-mode control and a disabled lock. Recorded in `docs/facilities/S152_UI_Gap_Report.md` and `S152_UI_Screen_Inventory.md`. **The lesson for S153 and S159: adding a module means adding its permissions source - and driving the screens, not only rendering them.**

### Pass - S153 CMMS (`sfl-facilities-service`)
`SRS-SFL-S153-01..05`, NFR 23.1, 23.3, 23.5, 23.8. **A rewrite, not a greenfield build.** The maintenance spine predated S152 and inherited none of its platform, and three things were wrong with it: `FacilityFaultController.findAll()` had **no permission check and no site filter**, so any caller with any role or none received every fault at every site; the controllers read `X-SFL-User` directly through a second actor model that no JWT path touches; and no fault or work-order state change was hash-chained, so the integrity check verified a chain with holes in it and reported `intact: true`.

**Built on the platform rather than beside it:** six aggregates (`FacilityFault`, `WorkOrder`, `PreventiveMaintenanceSchedule`, `MaintenanceVendor`, `MaintenanceEvidence`, `WorkOrderPart`) with `RecordMetadata`, lifecycle, optimistic locking and real state machines; SLA timers computed from effective-dated runtime configuration, site operating mode and a vendor's contracted response time, whichever is tighter; an escalation ladder evaluated against the configuration **active at the moment of evaluation**, as S153-02 requires; preventive schedules that generate work idempotently by cycle and, on closure, write `lastServicedOn` back to the asset - closing the loop S152 left open; closure evidence by reference with a SHA-256, a mandatory retention class, legal hold, and export as a separate authorised act with a recorded reason and recipient.

**The join that puts S153 under S152:** a fault at or above a configurable priority raises a readiness blocker on the space it is in, and resolving it clears the blocker and re-derives the space. The port is declared **by readiness** and implemented by readiness, the opposite of S152's `SpaceReadinessPort`, because readiness is the deeper module - a hall's usability is a fact about the estate, and maintenance is one of several things that can change it. An ArchUnit rule holds that direction, and the `maintenance` exclusion S152 recorded as debt is deleted along with the JPA types the application layer used to name.

**Authorisation, deliberately narrowed.** `VENDOR_TECHNICIAN` was the same permission set as an in-house technician, which let a contractor read the whole estate register and every fault at the site; it is now split, and the real boundary is **assignment**, enforced per record on reads and writes alike because "the ones assigned to me" is not something a matrix can say. A requester reads only the faults they reported. A technician marks work complete and a supervisor accepts it - no technician holds `FACILITIES_WORK_ORDER_CLOSE`.

**Verified by running it.** Four design defects were found by writing the acceptance tests: closure was unreachable from `IN_PROGRESS` (which would have made every migrated row unclosable); a technician could reopen their own completed work; `resolve` and `dismiss` cleared the blocker flag *before* the reconciliation that reads it, leaving a rejected fault's blocker open forever on a hall nobody could book; and the sweep escalated a fault and its work order, notifying two people about one problem. Then a database was built at V8, seeded with rows in the old shape, and V9 run over it: provenance backfilled from the reporter, `HALL-A` linked to its room and `CAR-PARK-B` correctly left unlinked, historic orders left closable, and the whole workflow driven end to end - blocker raised and cleared, evidence gate refused and satisfied, generation and escalation both idempotent, a live configuration change applied to the next triage, and the audit chain verifying intact with 21 records including every S153 action. Tests: **190**, 12 skipped. Docs: `docs/facilities/S153_CMMS_Design.md`, `S153_Gap_And_Conflict_Report.md`, `S153_API_Reference.md`.

**One consequence for go-live:** faults migrated open have no SLA and will not escalate until re-triaged. Back-dating deadlines would have produced a wall of instant breaches on day one; the cost is a triage pass at cutover, and it is on the record rather than in the code.

### Pass - S153 CMMS dashboard module (`frontend/sfl-operations-ui`)
Nine screens and ten dialogs extending `src/modules/facilities` - the same service, client and envelope as S152 rather than a parallel module. Fault register and detail, work-order queue and detail, preventive schedules and detail, vendors, evidence detail, and open faults on the S152 space page. One new navigation section, **Maintenance**, each item gated on its real service permission.

**The capability ADR 0006 gave up is back.** Retiring the static facilities page cost CLET its fault register and work-order controls, recorded in `S152_UI_Gap_Report.md` §2.1 as the one real loss. That section is now closed, and what replaced it carries the SLA, escalation, preventive maintenance and closure evidence the old page never had.

**S153 became its own `SystemCode`**, and the reasoning is recorded at the declaration because the obvious reading of two codes that always move together is that one is redundant: entitlement is identical to S152 for every role today, because the permission matrix puts fault and work-order reads in its shared read-only set. It is separate because the C9 mapping treats S153 as a Fast-Track system, the coverage claims count systems, `VITE_SFL_SYSTEMS=S153` makes maintenance viewable in isolation, and a refusal page should name the right thing.

**Nothing the service derives is recomputed.** `overdue`, `minutesOverdue`, `assignable`, `dueForGeneration`, `disposalEligibleFrom` and `supportsClosure` all come down the wire - a browser working out for itself what is late would disagree with the escalation sweep the moment a workstation clock drifted, and the sweep is the one that notifies people. The single piece of client-side arrangement is the queue's overdue-first sort.

**Two defects found by driving it, both invisible to a green build and 73 green tests.** A technician was shown a Close button disabled with "You do not have permission" - permanent, on every job, forever - because the page disabled rather than hid on a permission denial. The fix draws a line the module now follows: *a permission denial hides the control, a state or data shortfall disables it with the reason*. And two empty states claimed "every work order at this site is closed", which is a confident falsehood for a contractor who sees only their own; both now describe what is visible to you rather than what exists.

**Verified end to end against real PostgreSQL, as four actors.** A supervisor reported, triaged, raised, assigned, attached evidence and closed - and watched HALL-A go BLOCKED → READY with the fault resolved and its blocker cleared. Closure was refused first with the service's own sentence and allowed after the evidence. A technician could complete but not close; a vendor saw exactly the one order assigned to them while another vendor saw none; a requester saw only their own fault. Tests: **73**, up from 44. Docs: `docs/facilities/S153_UI_Screen_Inventory.md`, `S153_UI_Gap_Report.md`.

**IFIMP is now complete for S152 and S153.** Six of the thirteen Phase 1 systems have screens; S159 room and resource booking is what remains in this programme.

### Pass - S159 Room and Resource Booking (`sfl-facilities-service`)
`SRS-SFL-S159-01..03`, NFR 23.3, 23.8. The third IFIMP system, built on the S152 platform alongside S153 - same estate register, audit chain, idempotency store, runtime configuration and permission matrix. Six tables, five application services, four controllers, and one database constraint that is the reason the module works.

**The rule that cannot be enforced in Java.** A space cannot be double-booked, and a read-then-write check cannot guarantee it: two requests can both read an empty diary before either writes. The guarantee is a PostgreSQL `GIST` exclusion constraint - `EXCLUDE USING gist (room_id WITH =, tstzrange(occupied_from, occupied_to, '[)') WITH &&) WHERE (status IN ('REQUESTED','CONFIRMED','IN_USE'))`. The application check is kept because it produces the message a requester can act on, naming the booking that has the hall; the constraint is what makes the rule true. `S159MandatoryScenariosTest` reads `V10` off the classpath and asserts the constraint's status list matches `BookingStatus.holdsTheSpace()`, because those are two expressions of one rule in two languages with no compiler between them.

**Half-open intervals, `[start, end)`, decided once and applied in three places.** A booking ending at ten and one starting at ten do not clash. Wrong one way, every back-to-back lecture reports a phantom conflict until people stop trusting the check; wrong the other, the hall is double-booked on the hour - which is exactly when lectures change over. Conflict is tested on the *occupied* window, widened by setup and teardown buffers, so the next booking cannot start while the chairs are still being moved.

**Three decisions that shaped the module.** A **request holds the space** rather than waiting for approval, so the second person to ask is refused now instead of three people planning around one hall and the approver arbitrating a clash. There is **no `APPROVED` state** - approval is an event recorded as a `BookingApproval`, and the absence of one is what records that a booking needed none. A **readiness hold is a flag beside the status, not a state**: a confirmed booking on a hall blocked on Tuesday is still a confirmed booking somebody has in their diary, and moving it to `AT_RISK` would decide on the estate's behalf that Tuesday's leak will still be there on Friday.

**Setup tasks are deliberately not S153 work orders.** The obvious move buys the queue, the SLA and the closure evidence for free; it also puts a twenty-minute chair rearrangement in the same queue as a failed standby generator, and the generator ends up on page four.

**Verified by running it, and it found two defects 290 green tests could not.** Every booking search returned HTTP 500 - `could not determine data type of parameter $11` - because the codebase's `(:p is null or column = :p)` idiom does not work for a null `Instant` on PostgreSQL: `IS NULL` gives the planner no type and pgjdbc sends `UNSPECIFIED`. And sixteen simultaneous requests for one hall produced one booking and **fifteen HTTP 500s**: the constraint held perfectly, but the losers hit a deadlock (`SQLSTATE 40P01`) rather than a constraint violation, because two transactions each insert then each wait on the other's uncommitted row. A per-space transaction-scoped advisory lock taken before the conflict check turns that into one booking and fifteen readable `BOOKING_CONFLICT` refusals; the deadlock translation stays as a backstop.

Also proven against a real database: `V1..V10` on an empty schema, `btree_gist` installable by the application user, Hibernate `validate` passing, both exclusion constraints refusing overlaps and accepting back-to-back pairs at the SQL level, the examination buffer blocking the slot straight after a paper, a requester seeing only their own bookings, an override refused without the permission and recorded with it, the reconciliation sweep placing four holds without changing a status, the no-show sweep releasing a hall and writing the room-time lost, and the audit chain verifying intact.

Tests: **290**, 61 of them S159, 12 skipped. API: 25 paths. Persistence: `V10__room_and_resource_booking.sql`. Docs: `docs/facilities/S159_Booking_Design.md`, `S159_API_Reference.md`, `S159_Gap_And_Conflict_Report.md`.

**IFIMP's backend is complete: S152, S153 and S159.** S159 has no screens yet - that is the next pass, and the two lessons above it still apply: add the module to the permissions source, and drive the screens rather than only rendering them.

### Pass - Record scope in FTLMP, and the mandatory-scenario suites that had never run

`SRS-SFL-S166-01`, `SRS-SFL-S168fuel-01..03`. Two findings, and the second is why the first survived so long.

**101 of 102 skipped tests now execute.** The suites that prove the SRS mandatory scenarios for S166, S168_fuel, S171 and S174 were gated on Testcontainers, and Testcontainers asks whether the **Java** Docker client can reach the daemon. On Windows it cannot - the named-pipe transport fails while `docker ps` works perfectly from a shell - so every one of those tests skipped on every run, in the only environment this platform is developed in. The suites were not failing; they were not running, and a skip reads as a pass in a summary line.

The fleet and emergency suites already had an external-database escape hatch (`SFL_*_TEST_DB_URL`) that nothing was using. Pointing it at the e2e containers already running on 55441–55445 turned 89 skips into passes with no code change at all. `FacilitiesMigrationIntegrationTest` had no such hatch, so it gained one in `FacilitiesPostgresSupport`, modelled on the fleet original - with the constraint that the external database must be **empty**, because that suite asserts absolute facts about a virgin schema and Testcontainers had been supplying the emptiness implicitly. Backend now runs **744 tests, 0 failures, 1 skipped**, where it ran 641 with 102 skipped.

Running them found one order-dependent test, not a product defect: the V7 checklist-seed assertion multiplied the site count *as it stands now*, when V7 seeds only the sites that existed *when it ran* - so it passed or failed on JUnit method order. It now asserts what its own name says: two checklists per seeded site, and no checklist referencing a site that does not exist.

**A record-scope rule that was written down, tested, and enforced nowhere.** `FleetAccessPolicy.requireRecordScope` carries the javadoc "this is what keeps the limited driver/mobile user class to their own trips and inspections", and `FleetAccessPolicyTest` proves it works. It had exactly one production call site, in `TripApplicationService`, under the comment "a driver may only inspect the vehicle on their own trip" - and it was passed `null` as the owner reference, which the policy returns on immediately at its null guard. The control was a no-op at the only place it was invoked, and no read called it at all, so a `FLEET_DRIVER` holding any trip id read that trip in full.

Fuel had the same shape one layer down. `FuelAccessPolicy.isDriverOnly` narrows the logbook **list** in SQL on `created_by`, guards creation against the trip's driver reference, and guards transitions - but `logbook(id, actor)` checked permission and site only. A driver holding a colleague's logbook id read journey, route, purpose and passenger notes through the detail endpoint. **A narrowing the collection obeys and the record does not is decorative:** the row still crosses the boundary, one at a time instead of in a page. Both ownership refusals also threw `IllegalStateException`, reaching the caller as a 500 and leaving no denial in the audit chain; they are now `FleetAuthorizationException`, which is the SRS's 403 envelope and is audited.

The join between the two identity models is the driver's `staffReference` - the value an actor signs in as - which fuel already relied on when refusing a driver a logbook opened for somebody else. Trips carry `driverId`, a register key, so `TripQueryService` and `TripApplicationService` resolve one to the other rather than inventing a second notion of ownership. A supervising `FLEET_TRIP_MANAGE` passes through, so the narrowing binds the driver and not the fleet office.

**Dispatch is deliberately not narrowed, and that is recorded rather than guessed.** `CENTRE_MANAGER` and `MAILROOM_OFFICER` read the whole dispatch register, and the obvious fix does not work: `Dispatch.destinationCentre` and `assignedHandler` are `VARCHAR(200)` free text supplied at creation, with no relationship to a principal. Narrowing on them would produce a rule that holds whenever somebody happened to type an actor id and silently fails otherwise - worse than no rule, because it looks like enforcement. Closing it properly needs a principal-bound centre or handler reference on the dispatch, which is a schema change and an identity decision for the Transportation & Logistics Unit. This is the same shape as S153's recorded choice to narrow vendors per person rather than per firm, and it is left in the same state: written down, owner named, not invented.

Proved by refusal **by id**, never by an absent row: a driver reads their own trip and is refused another's, a driver is refused a colleague's logbook and its transition, and an officer is unaffected by either.

### Pass - The audit chain that never verified, the events that never left, and the runbooks that did not exist

`SRS-SFL-S166-03`, `SRS-SFL-S152-04`, `SRS-SFL-S153-04`, `SRS-SFL-S159-04`, PLAT-01..05. Four pieces of platform work that had one thing in common: each was believed done because nothing was checking.

**The audit chain was permanently reporting tampered, and nobody had looked.** S152 §8a recorded two defects that broke the facilities chain and warned in writing that fleet was likely to carry both - "it should be checked before S166 is relied on for evidence." It was never checked. Checking it took one test and it came back `intact=false`, diverging at sequence 8 of 201.

The cause is S152's D-05 verbatim: `AuditHashChain` hashes `occurredAt.toString()`, the JVM clock yields nanoseconds and `timestamptz` stores microseconds, so the value hashed was never the value read back. The boundary is exactly visible in the data - sequence 7, written by the tests' fixed clock at whole seconds, verifies; sequence 8, the first record written by a real clock, does not. **In production that is every record.** It survived four build passes because every chain-intact assertion in the suite uses an in-memory double, which round-trips nothing and so cannot see a storage-precision defect at all, and because a fixed clock at whole seconds truncates to itself. The one end-to-end check asserted `isNotNull()` rather than `intact()`. `JpaAuditAdapter.storedPrecision()` truncates to microseconds; `FleetAuditChainPostgresTest` replays the whole chain off PostgreSQL and fails on the old code.

S152's other defect, D-04, does **not** apply here: fleet neutralises jsonb's key reordering by re-canonicalising on read where facilities solved it by storing `TEXT`. Both are correct and the difference is recorded so nobody unifies them on sight.

**Pre-fix records cannot be repaired**, because a hash chain has no mechanism for amending history - that is the property it exists to provide. The e2e database was truncated and restarted at genesis; a production environment would have to choose that or a documented divergence point, and either is survivable only if it is on the record. No production environment exists yet, so the requirement is simply that the first production record is written by the fixed code.

**IFIMP recorded events and published none of them.** `infrastructure/messaging` was an empty package, so S152, S153 and S159 wrote outbox rows inside the business transaction - correctly - and nothing ever drained them. Three gap reports describe the consequence in different words and they are one gap: no escalation, no booking decision and no readiness hold ever reached a person. `FacilitiesOutboxDrainer` closes it, claiming one message per transaction with `FOR UPDATE SKIP LOCKED`, backing off exponentially through the `next_attempt_at` column V5 added for exactly this and never used, and dead-lettering after N so one poison payload cannot hold up the queue behind it. Driven through a `TransactionTemplate` rather than `@Transactional` on a private method, because Spring proxies do not intercept self-invocation and an annotation there would look like a transaction boundary while being nothing of the kind - the claim and the settle have to be in one transaction or the row lock is released before the message is sent.

**The event names could not be bound.** Facilities published `ifimp.work-order.assigned` - no `sfl.` prefix, no `.v1` suffix, and a dot inside the event name where the catalogue allows hyphens only - and AVAMP published `sfl.asset.*` with the wrong platform token. A consumer binding `sfl.ifimp.*.v1` would have received nothing and had no way to distinguish that from a quiet week. Forty-eight literals across fifty call sites are renamed, and both services now validate the name **at the outbox write path**, because a list of known names has to be remembered and a write path cannot be avoided. The version in the name and the `event_version` column are checked against each other, so a consumer that binds on the routing key and one that reads the column cannot disagree.

**`docs/runbooks/` was an empty directory.** Four runbooks now exist - dead-letter recovery, incident response, backup and restore, disaster recovery - written from what the code does rather than from a template, including the traps this platform has actually hit: the `next_attempt_at` window that makes a healthy drainer look stalled, the `CHAR(n)` validation failure, the two unannotated constructors, and the restore that splits the audit table from its chain head and reports as an attack. The DR runbook states plainly that the procedure has never been rehearsed.

**ADR 0007 takes the RLS decision** that S166 C-09 left open across four passes: the per-request session GUC, failing closed, sequenced deliberately *after* authentication - because RLS enforcing scopes asserted by an unauthenticated caller is theatre.

Backend: **760 tests, 0 failures, 1 skipped**, where before this sequence it ran 641 with 102 skipped.

### Pass - Role-based portals for the stakeholders the SRS names

`SFL_SRS.docx` §2.3 User Classes, `CLET_Comprehensive_Digital_System_Mapping_v2.docx` §30A.6.9. Five modules existed and every one was built for the operator - Facilities Manager, Fleet/Logistics Officer, Emergency Coordinator. Eight roles held real permissions with no view designed for them, landed on somebody else's dashboard, and saw mostly-hidden controls, which reads as a broken build rather than as a role boundary.

**The trace matrix came before the code**, all 26 roles with one row each, and the permission counts were read from the four matrices by asking them rather than by transcribing: counting 145 permissions by hand is how a matrix and a portal drift apart. Eleven roles trace cleanly to a §2.3 class, six derive from one within a single system, and **three are Deviations that are recorded rather than invented** - including `SERVICE_INTEGRATION`, which is a machine principal and is listed precisely so nobody later reads its absence as an oversight.

**The problem a permission cannot solve.** `FLEET_DRIVER` holds eight permissions and **every one of them is also held by `FLEET_MANAGER`**. So no permission distinguishes a driver from the fleet office, and gating "My driving day" on `FUEL_LOGBOOK_CREATE` would have offered it to the manager as their landing page. What makes somebody a driver is not what they can do - it is what they cannot. `shared/layout/personas.ts` encodes that, and it transcribes rather than invents: the narrowest-role rule is exactly the one `FuelAccessPolicy.isDriverOnly` and `FacilityFaultService.requesterFilter` already enforce, down to S153's stated reason that "treating the union of roles as its narrowest member would make adding a role to somebody take capability away". It is **not** an authorisation check and the file says so - nothing there hides data, the services do that per record, and a wrong answer costs a click rather than a disclosure.

**Placement is the whole mechanism.** `landingPath()` returns the first item of the first entitled section, so putting the personal sections first in `navSections` is what makes a driver open on their own day rather than on a fleet dashboard - with no change to the router, the shell or the route guards, and no effect on operators, because every personal item is persona-gated.

**Two portals could not be built honestly, and say so on the page.** `CENTRE_MANAGER` has no way to know which consignments are its own: `destinationCentre` and `assignedHandler` are free text with no principal binding, and a rule built on them would hold whenever somebody happened to type an actor id and fail silently otherwise - worse than no rule, because it looks like enforcement. The screen lists consignments *at this site*, says so twice, and names the owner of the schema decision. And the driver's fuel panel is labelled "recorded at this site" rather than "mine", because `FUEL_TRANSACTION_READ` is not narrowed per record; calling it mine over a list containing a colleague's fill would be a lie the screen tells on the service's behalf.

**Recorded rather than built:** dispatch controller and logistics coordinator get no new portal, because both hold the full controller set and the dispatch module already is their view - duplicating fifteen screens to change a title buys nothing. Command, reporting-viewer and administrator landings are honest today and not designed, which is the next slice rather than a claim. And verification under authentication is owed: A1 was deferred, so the actor still arrives in a header, and the persona rule reads roles from it exactly as it will read them from a JWT claim.

Frontend: **84 tests**, up from 73, with eleven pinning the persona rule including every case where a persona must *not* apply. Docs: `docs/frontend/SFL_Role_Portal_Trace_Matrix.md`, `SFL_Role_Portal_Gap_Report.md`, and the portal pattern added to the module playbook.

### Pass - Authentication on by default

PLAT-01, `solution.md` §Security, ADR 0007. **Every API in this platform was unauthenticated, and an environment that simply forgot a variable stayed that way.**

`sfl.security.enabled` defaulted to `false`, and - the half that mattered more - the filter chain that permits everything carried `matchIfMissing = true`. So two independent things both had to go right for a deployment to be secure, and neither was the default. Both are inverted: the open chain now requires the property to be *explicitly* false, the secure chain is what an absent property selects, and taking the open path logs a warning naming the service on every startup. The local development scripts set the variable deliberately and carry a comment saying the line is now load-bearing.

**Rather less was missing than the gap report implied, and one thing more.** The resource server, both filter chains and the JWT actor resolvers in facilities, fleet and emergency were all already written - the Prompt 1 note that Keycloak was absent from compose was wrong, it was there with the issuer wired and a `depends_on`. What was actually missing was the **realm**: `start-dev` with no import means `/realms/sfl` does not exist, which under the old default degraded quietly and under the new one is a startup failure. `deploy/keycloak/sfl-realm.json` now carries all 26 `SflRole` values, the `site_scopes` mapper the resolvers already read, a public client for the dashboard, a service-account client for signed ingest, and one user per persona so a portal can be signed into rather than only reasoned about. Compose imports it and health-gates the services on the realm answering, not merely on the port opening.

**The thing more.** `sfl-asset-visibility-service` took its actor as `@RequestHeader(name = "X-SFL-User", defaultValue = "development-user")` on every controller method and passed it straight into the command. Harmless while the header *is* the identity; not harmless the moment a verified principal exists, because the service would still have attributed every asset registration, custody change and evidence link to whatever string the caller chose - or, absent one, to a user literally called `development-user`. It now resolves the JWT subject first and falls back to the header only when there is no authenticated principal, which is what the other three have always done.

**The chain that faces every real user had never been executed.** Searching every `src/test/java` in the reactor for `keycloakSecurity`, `sfl.security.enabled=true` or `JwtAuthenticationToken` returned nothing across four build passes, while the suite reported green off the development chain that permits everything - the same shape as the skipped mandatory scenarios and the audit chain that always replayed as tampered. `FacilitiesJwtSecurityTest` runs the real chain in a real context and pins five things: anonymous is refused rather than served as `development-user`; an `X-SFL-User` header cannot assert an identity while the chain is armed; a token is admitted and its realm roles reach the actor; a token whose roles lack the permission gets **403 rather than 401**, because "who are you" and "you may not" are different answers; and the health probe stays reachable, because a load balancer cannot present a token. It had to be a full context rather than a slice - a slice has no `HttpSecurity` for a chain to be built on, which is exactly why the existing controller tests exclude the resource server and disable filters.

**ADR 0007 is unblocked.** It sequenced row-level security behind this deliberately, on the grounds that RLS enforcing scopes asserted by an unauthenticated caller is theatre. The `site_scopes` claim those policies will read is now issued by the realm and consumed by every resolver.

Backend: **772 tests, 0 failures, 0 skipped.**

### Pass - Row-level security (ADR 0007)

Site scope had one layer of enforcement, and it was the layer this platform has twice been observed to get wrong: `FacilityFaultController.findAll()` once returned every fault at every site to any caller, and `FleetAccessPolicy.requireRecordScope` was passed `null` at its only call site and enforced nothing. Both were found by reading code, not by a test failing. RLS is the layer that makes that class of mistake harmless, and A1 unblocked it - enforcing scopes asserted by an unauthenticated caller would have been theatre.

**Built as ADR 0007 decided, plus one thing it did not name.** The per-request session GUC is there: `SiteScopeGuc` in the shared kernel issues `SET LOCAL app.site_scopes` in `afterBegin`. `SET LOCAL` rather than `SET` is the entire safety argument - it rolls back with the transaction either way, so a pooled connection never carries a stranger's scopes to its next borrower, which is the failure this design is usually accused of.

The addition is a **role split**, and it was forced by a real hazard. A table owner bypasses RLS unless `FORCE` is set, and `FORCE` would have applied the policies to Flyway - so a migration that backfills would have silently written nothing, which is worse than the problem and invisible. The owner therefore keeps its bypass and runs migrations, and a separate `sfl_app` role carries the policies. Development and the whole test suite keep connecting as the owner, so nothing that worked stopped working, and adopting RLS in an environment is a connection-string change rather than a deployment that must land in lockstep with a migration.

**The policies fail closed.** `site_in_scope` returns false when the GUC is unset or empty. That is the only setting worth having: a second layer that opens up when the first forgets to speak is not a second layer. `*` is the cross-site scope, matching `SiteScopeFilter.all()`. Two tables are exempt and the reason travels with the rule - the audit chain, because a tamper-evident record that is invisible in parts replays as a break that is really a filter; and runtime configuration, because it is read during evaluation for sites the actor may not hold and narrowing it would make an SLA silently unresolvable rather than refused.

**Proved against the role it applies to.** `FacilitiesRowLevelSecurityTest` opens its own connection as `sfl_app`, because a test running as the owner would have passed while proving nothing at all. Six cases, including that a write outside scope is refused by `WITH CHECK` with SQLSTATE 42501 rather than silently dropped.

Facilities is the reference implementation; the same migration is owed against the other three schemas, and the mechanism is already shared. Backend: **778 tests, 0 failures, 0 skipped.**

### Pass - The S159 booking UI

S159 shipped with twenty-five API paths and no client. This is the client: five screens, seven
dialogs, and the first IFIMP module whose route base does not mirror its service - `/bookings`, not
`/facilities/bookings`, because a lecturer booking a hall does not think of themselves as visiting
facilities and a URL somebody can be told over the phone is worth more than one that mirrors
deployment topology.

**Two mistakes were made in the first draft and both were caught by reading the service rather than
by a failing test**, which is the same way the last four passes found what they found.

`FACILITIES_BOOKING_CANCEL` does not mean "may cancel". `requireMayAct` uses it as the *"may act on
somebody else's booking"* grant and routes cancel, reschedule, start and completion through it
identically - so anything you requested you may move, start, complete and cancel holding nothing
beyond `FACILITIES_BOOKING_REQUEST`, and anything you did not you may touch only holding that one
grant. The first draft gated reschedule and start on `BOOKING_REQUEST`, which is the reading the
names invite and which would have offered a requester the Move button on a hall booked by the
registry. And the turnaround queue was gated on `FACILITIES_SETUP_TASK_MANAGE`; `BookingSetupService.queue`
gates the read on `FACILITIES_BOOKING_READ` and reserves the manage permission for raising and
resolving a task, so shipping it as written would have hidden the queue from everybody who can only
look at it - while the technicians who can resolve tasks saw it fine, so it would have looked correct
to whoever tested it.

**The occupied window is the thing every screen exists to make visible.** A lecture booked 09:00–11:00
with a fifteen-minute teardown refuses a meeting at 11:05, and the refusal names the *booked* window
in its message - so somebody reads 11:00, asked for 11:05, and is refused. The screens do not rewrite
the service's wording; they show the occupied window beside it, on the diary row, as its own stat card
on the detail page, and in the request dialog's description.

**Verified against PostgreSQL, not only against tests**, per the standing rule. A seeded site, two
rooms and one exclusive resource, then thirteen behaviours driven through the same paths the UI calls:
buffers widening the occupied window, the conflict landing on the buffer, setup tasks auto-raised at
the occupied start, completion releasing every allocation, the requester narrowing on both the list
and the by-id read, own-booking cancellation without `BOOKING_CANCEL`, the readiness override with a
recorded reason, and the self-approval refusal. Every field of every response matched the TypeScript
DTOs with no adjustment - they were transcribed from `BookingResponses` rather than inferred.

**One entitlement fact is worth stating because it happened silently.** Adding `FACILITIES_BOOKING_READ`
to the facilities matrix's shared `READ_ONLY` set entitled ten roles to the room diary and left
`VENDOR_TECHNICIAN` out - correctly, and only because a contractor's matrix entry is an explicit
`EnumSet` rather than a union with that set. A test now pins it, so rebuilding `VENDOR_TECHNICIAN` on
`READ_ONLY` cannot hand a contractor the estate's diary by accident.

Recorded rather than built, in `docs/facilities/S159_UI_Gap_Report.md`: the calendar grid (a half-grid
drawing the booked window would actively mislead), post-hoc resource allocation, resource editing, and
manual setup tasks. Each has an endpoint and no control, and each is named with the reason.

Frontend: **115 tests**, up from 84.

### Pass - Evidence digests, and the building screen that was missing from the middle of the estate

Two gaps closed, and neither closed the way its gap report proposed.

**S153 evidence still uploads nothing, and the digest is no longer typed.** Evidence is stored by
reference - the bytes live in the document and object-storage service, this service records where
they landed and what they hashed to - and that standard has not changed. What changed is who computes
the hash. Choosing the file now fills the name, the media type, the size and the SHA-256, because the
browser has the bytes and `crypto.subtle` can hash them. Before this, a technician standing in a plant
room with a photograph had to obtain a digest from somewhere else and type sixty-four hexadecimal
characters into a form. Nobody does that correctly, and a mistyped digest is the one error in this
system that surfaces years later - during an integrity check, on evidence nobody can now re-hash - as
a **false report that the file was tampered with**. Three cases still fall back to typing and each
says why on the field: a file over the 64 MB cap (Web Crypto cannot stream, so the file is read whole,
and the cap is what stops a mis-selected video freezing a site laptop), a browser without Web Crypto,
and a dashboard served over plain HTTP. The storage reference is still typed and still a genuine gap,
because it is what the document service gives back and this dashboard cannot call it - but the half
that was error-prone is closed. Four known-answer SHA-256 vectors pin the helper, so a stub that
merely returns bytes fails the suite.

**The S152 floors gap was real and was not about floors.** Its gap report argued a floor page was not
justified, and that was right about the page and wrong about the gap. A floor has four fields and no
behaviour, so a floor detail route really would have had nothing on it. What was missing was the
**building**: the estate is Site → Building → Floor → Space, the dashboard had screens for the first,
second and fourth, and a building row on the site page led nowhere. `listFloors`, `getFloor`,
`createFloor` and `createBuilding` were written, exported and called by nothing - so the only way to
place a space was to already know a floor id. `BuildingDetailPage` shows a building, its floors, and
what is on the floor being looked at; choosing a floor filters the spaces beside it rather than
navigating away, because what somebody wants from "the second floor" is what is on it.

**The floor filter is a server-side query and a test says so.** The space query is capped at a hundred
rows, so filtering the array in the browser - the obvious implementation - would filter the first page
rather than the floor, and the third floor of a large block would appear empty. And level number is
signed *and* nullable because both cases are real: a basement is `-1`, and a mezzanine has no honest
number at all, so a client sorting `null` as zero would file every mezzanine at ground level.
`floorLabel` renders `B1 · basement 1`, `GF · ground`, `MEZZ · no level` - the last because a blank
cell reads as missing data rather than as the answer.

**One service-side wording inaccuracy found and recorded rather than patched.** A duplicate floor code
is refused with *"An active floor with identifier 'GF' already exists for site CLET-HQ"*, and the
constraint is per **building**, not per site - `GF` was accepted in a second building on the same
site. The message uses the shared `DUPLICATE_IDENTIFIER` wording, which names the site scope rather
than the true key, so it reads as a stricter rule than the one enforced. The wording is the SRS's, so
it is in the gap report rather than rewritten here.

`FileField` moved from `modules/fuel/components` into the shared kit, where its own comment said it
should go once a second module needed it. Three now do, and dispatch had already been importing it
across a module boundary.

Frontend: **132 tests**, up from 115.

### Pass - Cleanup, and the go-live record re-cut

Housekeeping and record-keeping, no new capability. The valuable half was the readiness pack.

**The document carrying the Go-Live recommendation described a build that no longer exists.**
`SFL_Phase1_Workflow_Review_and_GoLive_Readiness_Pack.md` still scored S152 and S153 as *thin*, S159
as *not built*, and totalled **3 built · 3 partial · 7 not built**. The real position is
**7 · 0 · 6**. Its §C.5 described three service-hosted Bootstrap dashboards that ADR 0006 retired -
all three paths now serve a notice page pointing at `/ui/`, and the estate is one application with 78
screens across seven modules. Its §F.1 listed eight cross-cutting blockers of which **six are now
closed**, and each closure in the re-cut names its evidence, because a gap register that marks its
own items done without saying how is the same document that let five of them sit open.

**Two blockers remain and neither is closable by this team**: no external integration has been proven
against a real vendor, and S174 has no notification gateway - an emergency mass-notification system
that composes, approves, records and audits a broadcast it cannot send. Both are stated plainly
rather than softened.

**Verified, not asserted: 778 tests, 0 failures, 0 skipped.** That number needed three runs to
establish honestly. A plain local run reports 778 with **119 skipped**, because the end-to-end suites
are gated on three `SFL_*_TEST_DB_URL` variables. Setting them surfaced **four failures** that looked
like defects and were residue: the facilities migration suite asserts a genesis audit hash of all
zeros, so it needs an empty database, and it had inherited state from the previous run in the same
session. Dropping and recreating that database gave 0/0/0. All three facts are now in the README, so
the next person does not repeat the diagnosis.

**The legacy root application is gone** - 48 Java files under `src/main/java/gh/edu/clet/sfl/ifimp/`,
a second Spring Boot app the root `pom.xml` still compiled. The prompt described it as reading like
live code to a newcomer; it was worse than that. `scripts/dev/run-local.ps1` still launched it, and
had been maintained as recently as the authentication pass - its `SFL_SECURITY_ENABLED=false` line
carries a comment explaining that the line became load-bearing after A1. It pointed at port 8081 and
a database `sfl_java` on 5434 that no longer exists in any compose file. The app, the root pom whose
only job was to build it, and the script that launched it were removed together. History survives on
both `archive/*` branches and on `master`.

**Two documentation defects closed** that had been logged and carried for weeks. `solution.md` and the
readiness pack both cited an SRS filename that does not exist (S166 C-13) - worth chasing for a
filename because both documents name that file as the contract everything traces to. And the
workplan's endpoint table mapped four endpoints to SRS *ordinals* rather than semantics, including one
requirement that does not exist (C-02): S166-03 is Evidence and Audit Trail, not telematics; S166-05
is Dashboards, not the driver register; there is no S166-06. `/fleet/emergency-logistics` now claims
**no** requirement rather than a plausible one, because it is unbuilt and asserting a trace would
manufacture coverage.

**Declined: deleting the merged branches.** Prompt 3 item 5 called for deleting twenty (in fact
thirty-one) branches merged into `main`. They were deleted and then **restored in full** at the
owner's instruction - 31 local, 32 remote, every tip verified as still an ancestor of `main`.
Recovery worked because a `--no-ff` merge stores the branch tip as its second parent, so 29 came back
from the merge commits themselves; two more had no merge commit and came from the reflog, which
returned **pre-history-rewrite SHAs** - the 31 July trailer-strip changed every hash - and had to be
matched by subject to their post-rewrite commits. Recorded as a deliberate decision, not an oversight:
branch deletion buys tidiness and costs a recovery path, and this repository has now demonstrated
exactly how narrow that path is.

Also: gap reports converged on one name (`*_UI_Gap_Report.md`, four renamed, eight inbound links
fixed), all seven ADRs indexed from `solution.md`, `tools/` given a README because
`build_sfl_srs.py` overwrites the contract document, and the strays deleted.

**Item 6 needed no work.** CI already runs `clean test` with a comment naming the phantom-failure
reason, and the stale surefire XML was already gone - verified before assuming, as the prompt asked.

### Pass - Starting all five services at once, which had never been done

The question was whether the platform launches. Four of five did. The fifth had never been started by
anybody, and starting it found two defects that had been invisible for months because nothing had
tried.

**`sfl-safety-security-service` had no `@SpringBootApplication` class.** `spring-boot:run` failed with
*"Unable to find a suitable main class"*. Four documents described this module as a service that
compiles and boots - the go-live readiness pack, `README.md`, `CLAUDE.md` and the developer guide -
and it could not start on any machine. It went unnoticed because nothing ever launched it: it has no
tests, compose does not run it, and CI builds without launching, so a module that could not boot
compiled green while every document asserted the opposite.

**And it had no security configuration at all**, which is the more interesting half. The readiness
pack recorded that absence under G-01 and it was never closed with the rest of the item. The
consequence was the opposite of what "no security block" suggests: with no filter chain declared,
Spring Security's default secured *everything* including `/actuator/health`, so the service answered
**401 to its own probe** and `SFL_SECURITY_ENABLED` had nothing to read. A service whose liveness
probe returns 401 is a service every orchestrator treats as dead. Both chains now match the four
siblings, including the rule that secure is what an absent property selects.

**A third defect, in a service that was working.** `sfl-facilities-service` started cleanly, applied
its migrations and served its API correctly - and reported `503 DOWN`. It carries
`spring-boot-starter-amqp` and defaults its event transport to `local`, so it never talks to RabbitMQ;
but Boot registers the Rabbit health indicator on classpath presence alone, and a failing indicator
drags the **aggregate** status down. In Kubernetes or behind a load balancer, a fully functional
service would have been taken out of rotation. `sfl-fleet-logistics-service` already had the guard for
exactly this; facilities was the only module with AMQP on the classpath and without it, so the fix
existed in the repository and had never propagated.

**Two dead scripts found and rewritten rather than left.** `scripts/dev/verify-local.ps1` called
`/api/health` and `/api/version` on port 8081 - endpoints that existed only on the legacy application
removed earlier in this pass - and reported the failure as a hard error. It now checks all five
actuator probes and distinguishes *not running* from *answering but reporting a failed dependency*,
because those are different problems and looked identical before. `docs/development/run-spring-boot-locally.md`
described the same deleted application end to end and has been rewritten around the five services.

**One claim of mine was wrong within the hour and is corrected.** The rewritten developer guide said
safety-security "starts and answers its health probe, and that is all it does". It did not start at
all. The correction is in the document with the reason, rather than quietly edited.

Final state: **five services up**, each answering `/actuator/health` with `200 UP`, each serving its
API, and the dashboard at `/ui/` returning 200 on both its index and a deep route. `CLAUDE.md` had two
of the five ports wrong - safety-security is 8092 and asset-visibility 8094, not 8094 and 8096 - which
is corrected there too.

### Pass - The sign-in page, and the half of A1 that was never reachable

A1 turned authentication on across every service: resource server, JWT actor resolvers, imported
realm, the 403-versus-401 distinction, a test that runs the real chain. **None of it could be reached
from a browser.** `shared/api/client.ts` sent `X-SFL-User`, `X-SFL-Roles` and `X-SFL-Sites` and no
`Authorization` header at any point in the file. So the dashboard worked only against a service
running with `SFL_SECURITY_ENABLED=false`, and a service running with the secure default answered 401
to everything the dashboard did. This pass is the missing half.

**The roles come out of the token, not out of the form.** The shortcut is to keep sending the
`X-SFL-*` headers and put a login screen in front of them, and it would be theatre of the kind ADR
0007 refuses: the headers are caller-supplied, so a "signed-in driver" could still assert `SFL_ADMIN`
by editing storage. `realm_access.roles` and `site_scopes` are read from the token's own claims - the
same two the services read - so the sidebar and the enforcement point derive from one signed source.
Both are still sent, and the ordering is what makes that safe: with security off the headers are the
only identity there is, with security on the verified principal wins, and there is no mode where a
header overrides a token.

**Twenty-two seeded accounts, one password.** The realm had twelve personas with no email and the
password `password`; it now has twenty-two with `firstname@clet.gh`-style addresses and
`Password@Clet1`, plus `loginWithEmailAllowed` - without which Keycloak accepts only the username and
every credential handed out is an email. The ten added cover roles that had no user at all, including
the dispatch controller. Proved against the running realm: the fleet manager and the driver receive
tokens carrying their role and site scope, and a wrong password is refused with `invalid_grant`.

**The specified component was not used verbatim, for two concrete reasons.** It is a shadcn block
whose classes are shadcn's CSS-variable tokens - `bg-background`, `text-muted-foreground`,
`border-input`, `ring-ring` - and this project defines **none of them**; its Tailwind theme is a
bespoke scale, so pasting the block would have produced a transparent, borderless, default-typed
form. And it ships its own `Button`, `Input`, `Label` and `cn`, all four of which already exist in
`shared/components/` where `cn` is the same clsx + tailwind-merge helper and the fields carry the
label/error/helper rhythm every other form uses. Building it on the existing kit added **zero
dependencies** where the prompt called for six. The layout, copy and behaviour are as specified.

**Corrected the same day, after the owner ran it.** The first version put the login behind a
`-WithLogin` switch defaulting to **off**, reasoning that a service with security off issues no tokens
so a Keycloak-backed form there could not succeed. The reasoning was sound and the conclusion was
wrong: the deliverable was a login page, and the default run showed none. It also required starting
Keycloak, which was never asked for.

What replaced it needs nothing external. `shared/auth/accounts.ts` holds the twenty-two seeded
accounts, `signIn.ts` matches the email and the shared password and makes that account the actor, and
the guard is now unconditional - `.\start-fleet.ps1` alone opens on the sign-in page. Verified in a
browser rather than asserted: `fleetmanager@clet.gh` lands on `/ui/fleet` with the FTLMP sidebar,
`driver@clet.gh` on `/ui/me/driving` as Kwame Driver with "My work" at the top.

`accounts.ts` states in its own docblock that this is a **development sign-in** - no token, nothing
verified, credentials in the served bundle - and why that is bounded rather than sloppy: the services
run open locally, where the actor is whatever the headers claim, so a form here can only decide which
headers to send. The token-issuing path sits beside it in `keycloak.ts` against the same twenty-two
accounts, for when a service runs with security on.

Recorded as owed in `docs/frontend/SFL_Sign_In_And_Seeded_Accounts.md`: the resource-owner password
grant is deprecated for public clients and cannot support multi-factor, step-up or an external IdP,
so Authorization Code with PKCE replaces it; there is no token refresh yet; and the token sits in
`sessionStorage`, which a successful XSS can read, because the `HttpOnly` cookie alternative needs a
backend-for-frontend that does not exist.

Frontend: **144 tests**, up from 132.

---

## Pass - Consolidation to three platform services (5 August 2026)

Five deployables became three, one per programme. `sfl-emergency-notification-service` folded into
`sfl-safety-security-service`; `sfl-asset-visibility-service` folded into
`sfl-fleet-logistics-service`. Ports 8094 and 8095 and databases 5444/5445 (and e2e 55444/55445) are
retired.

**Why, in one line each.** The system mapping assigns every Cluster 9 system to one of three F&L
units, and the thirteen Fast-Track systems fall out 3 / 7 / 3 across them - so three deployables
match the three teams that own and release them. And the platform substrate a five-way split assumes
is not available at Phase 1: the enterprise message broker (S217), API gateway (S217a) and container
orchestration with service mesh (S214a) are all Phase 2 in the mapping, while Fast-Track is 1 July
2026.

**No schema merged.** `emergency_notification` and `asset_visibility` moved intact into their host
service's database, with their own migrations, outbox, inbox and audit chains, and no cross-schema
reference in either direction. The SRS asks for schema per module and no cross-schema foreign keys;
it never asked for a database per module.

**No API path changed.** `/api/v1/emergency/**` and `/api/v1/assets` are served exactly as before,
from a different process. The dashboard's only functional edit was one origin.

### What the merge actually cost, and what it caught

Four collisions, each of which would have failed at startup rather than at review:

1. **Duplicate `SecurityFilterChain` beans.** All four services declared `developmentSecurity` and
   `keycloakSecurity` - near-verbatim copies of the same ninety lines. Two in one context is a bean
   name conflict. The emergency pair was deleted and its permit list absorbed into
   `SafetySecurityConfiguration`, including the provider-callback path that must stay open because an
   SMS gateway posting a delivery receipt cannot present a bearer token.
2. **Two `SystemController`s on `/api/v1/system`.** AVAMP's was deleted; the fleet one now reports
   both schemas.
3. **Two Spring Data `OutboxMessageRepository` beans.** AVAMP's outbox is renamed
   `AssetOutboxMessageRepository`/`AssetOutboxMessageRecord`. It stays a separate outbox in a separate
   schema - sharing fleet's would have been a cross-schema write.
4. **Two unscoped `@RestControllerAdvice`s.** `FleetApiExceptionHandler` was declared over the whole
   `fleetlogistics` root, which now contains AVAMP, so a validation failure on an asset endpoint would
   have returned `ASSETVIS_*` or `FLEET_*` depending on classpath scanning order. The two are now
   declared over disjoint package sets, which makes the answer a fact about the source and removes any
   need for `@Order`.

One test earned its keep. `FleetArchitectureTest.jpa_entities_live_only_in_infrastructure` failed the
moment AVAMP's entities arrived - it was the only check in the codebase that noticed the merge at
all. The rule was widened from fleet's infrastructure package to any module's, which is what it
always meant.

`@WebMvcTest` needed one thing that is worth knowing: `basePackages` on an advice governs which
controllers it *applies to*, not whether the bean is *created*, so the AVAMP slice still instantiated
`FleetApiExceptionHandler` and its `FleetAuditService` dependency until an explicit `excludeFilters`
was added.

### Migrations

Renumbered into the host's sequence rather than run as a second Flyway instance: S174's V1–V8 became
SSEMP V2–V9 (after the existing foundation V1), and AVAMP's V1–V2 became FTLMP V27–V28. Every
statement was already schema-qualified, so not one line of SQL changed. Both services declare both
schemas to Flyway and keep history in the host's.

**This requires a clean database.** The renumbering invalidates the existing local Flyway history for
S174 and AVAMP. Drop the two service volumes and let the migrations run from scratch - acceptable
because no production data exists, and stated here rather than discovered at startup.

### Verification

Against real PostgreSQL, not Testcontainers:

- SSEMP - **40 tests, 0 failures, 0 skipped**, including all 24 S174 mandatory end-to-end scenarios.
  Flyway applied 9 migrations across both schemas.
- FTLMP - **445 tests, 0 failures, 0 skipped**. Flyway applied 29 migrations across both schemas.
- Frontend - TypeScript clean, **156 tests**, production build clean.

ADR 0004 is amended rather than deleted: five of its six reasons argued for an independent *process*
and a shared process does not provide them. The amendment states which isolation was traded away,
why the trade is accepted at Phase 1, the four properties that keep re-extraction cheap, and the
named evidence that should reverse it.

---

## Pass - Uploaded evidence, and a fuel claim the claimant cannot write alone (5 August 2026)

Traces `SRS-SFL-S166-01` (compliance documents), `SRS-SFL-S166-03` (evidence and audit),
`SRS-SFL-S168fuel-01/02/04` (capture, reconciliation, fuel cards).

### The question that started it

*"A driver can buy 100 GHS fuel and be one with the fuel attendant and record 120 GHS."*

That is a fair description of what the platform allowed. A fuel claim carried three numbers - litres,
price per litre, total - related by one multiplication, so any two determine the third. All of them
were typed by the person being reimbursed. The only cross-check was `COST_VARIANCE`, which compares a
transaction to the previous one **for the same vehicle**: a change detector, so it fires on a genuine
national price rise and stays silent on a steady twenty-per-cent overstatement.

The evidence field made it worse rather than better. It asked the operator to *register the receipt
under Evidence & audit, then paste its identifier* - and the person filling in the form is a driver,
who has no Evidence & audit screen. The instruction described a workflow its reader could not perform,
so the field stayed empty and fuel was claimed with no receipt at all.

### What the platform now owns

**The bytes.** `fleet_evidence_files` (V29) holds evidence content, behind an `EvidenceFileStore`
port so an object store can replace it without a caller changing. This is a departure from
"metadata only, the file lives in the document store" and it is deliberate: Release 1 has no document
store, every `storage_reference` was a `local-demo://` key resolving nowhere, and three workflows
needed to open a file. Separate table from the metadata, so a metadata read never drags megabytes.

**What is allowed through.** `UploadedFileScanner` refuses anything that is not a PDF or JPEG by
extension **and** magic bytes **and** declared content type, caps at 10 MB, and refuses a PDF
containing `/JavaScript`, `/OpenAction`, `/EmbeddedFile`, `/RichMedia`, `/Launch` or `/XFA`. The
digest is computed from the bytes received and never taken from the client. Every active-content
token is at least seven characters on purpose - a scan for `/JS` or `/AA` across ten megabytes matches
by coincidence about half the time, and a certificate refused for a random byte sequence teaches
operators that the check is noise.

It is not an antivirus, and the code says so. A PDF can hide objects in compressed streams. What it
stops is the realistic range: a renamed executable, a polyglot, HTML with a `.jpg` extension.

**The price.** `fuel_posted_prices` (V30) is effective-dated per site, vendor and product. The
capture form reads it, fills the price in and locks the field; the driver enters the **amount paid**
and litres is derived. That is the mechanism, and it is worth being precise about why it works: it
converts an inflated *amount*, which nothing could detect, into an inflated *volume*, which the tank
capacity, consumption range and daily/monthly litre ceilings already detect. Before, overstating the
money touched none of them.

**A second witness.** `pump_evidence_id` (V30) holds a photograph of the pump meter beside the
receipt. The receipt is what the vendor was willing to write down; the pump is what the pump
dispensed. Collusion lives in the gap between them, and no arithmetic on the recorded figures can
reveal it - only an independent observation can.

Three reconciliation rules follow: `POSTED_PRICE` (deviation from the forecourt price, distinct from
`COST_VARIANCE` and kept separate in the queue), `PUMP_IMAGE` (manual captures only - a provider feed
has no photographs and requiring them would raise an anomaly per row), and `EVIDENCE_UNIQUE` (the
same image filed twice, caught on its digest).

No prices are seeded. An invented reference price would produce confident anomalies about a market
that does not exist, so the rule reports `referencePrice: none` and passes until somebody records the
real ones - visibly, in the stored rule map, so "never actually price-checked" is distinguishable
from "checked and passed".

### Two defects that only a real database could show

**JPA write-behind against a JDBC foreign key.** Evidence metadata is mapped with JPA; content is
written with JDBC; the content table references the metadata table. JPA defers its INSERT to the end
of the transaction, so the JDBC write reached PostgreSQL first and the foreign key failed - every
upload, 500. `EvidenceRepository.saveAndFlush` exists for this one caller and says why. An in-memory
double has no persistence context and cannot reproduce it.

**One manual fuel purchase per site, ever.** `uq_fuel_provider_transaction` was
`UNIQUE NULLS NOT DISTINCT (site_code, source_system, provider_transaction_id)`. A driver at a pump
has no provider reference, so the column is null, and NULLS NOT DISTINCT makes every null equal to
every other - the first manual purchase consumed the key and the second was refused. The defect
predates this pass (any operator leaving the optional field blank hit it) and became certain when the
capture form stopped asking for a field that belonged to the integration. V31 relaxes it to
`NULLS DISTINCT`, which is what `findProviderTransaction` already assumed by returning empty for a
null reference.

### The capture form

Site, then trip from the driver's own queue, which fills in the vehicle and the driver - so the
driver field disappears for a driver, because they are signed in and the trip says who they are. The
trip list is narrowed server-side by `DriverScopeResolver`, which is what makes the trip usable as
identity. Vendor became a select over the policy's approved providers and the station a Places
lookup; as free text, "GOIL", "Goil Tema" and "goil" were three vendors the `APPROVED_VENDOR` rule
could never match. "Occurred at" is now "Time fuel purchased". Both images upload in the form and
attach themselves; nobody copies an identifier.

### Google Places

Suggestions never appeared, and the cause was not in this repository: the key's Google Cloud project
(428834127125) has **Places API (New) disabled**, and the legacy Places API with it. Confirmed by
calling both directly - `SERVICE_DISABLED` from `places.googleapis.com`, and a
`LegacyApiNotActivatedMapError` from the legacy endpoint. Maps JavaScript itself is enabled, which is
why the script loaded and only the lookups failed.

The code was still wrong in one way worth fixing: every failure became an empty suggestion list,
indistinguishable from "no such place", so a project with the API switched off looked exactly like a
working field finding nothing. Lookups now report why they produced nothing, the field says so under
itself, and the full reason is logged once. The bootstrap also gained the `callback` parameter that
`loading=async` requires.

### Verification

Against real PostgreSQL, per the standing rule:

- FTLMP - **455 tests, 0 failures, 0 skipped**, every end-to-end suite included. Flyway applied
  V29-V31.
- The upload path was exercised by hand against a running service: a renamed executable, a
  JavaScript-bearing PDF and a JPEG declared as `text/html` all refused with their own reasons; a
  clean JPEG and PDF stored, downloaded byte-identical, and served with `nosniff`,
  `default-src 'none'; sandbox` and `private, no-store`.
- Frontend - TypeScript clean, **177 tests**, production build clean, lint at its prior baseline.

### Still open

- No malware scanner. The scanner is structural validation; an ICAP or ClamAV sidecar belongs
  **behind** it, not instead of it, because every check here is free and none can be turned off by a
  scanner being down.
- Bytes live in the service's own database. Fine at Release 1 volumes, wrong at fleet scale - the
  port exists so that is a swap rather than a rewrite.
- Posted prices are entered by hand. `PROVIDER_FEED` and `INVOICE` are defined on the source enum and
  nothing populates them yet.
- The reuse rule is a digest check. A second photograph of the same receipt has a different digest and
  passes it; it catches reuse, not staging, which is why it sits beside the price and volume rules
  rather than in place of them.
- A backdated price cannot be recorded through the API - it would have to close an already-closed
  period, which is a separate job.

## Pass - Routes named after platforms, and three services that say they are up (5 August 2026)

### Every service announces itself

Only the fleet service printed a ready banner, so starting all three gave one block of URLs and two
walls of Spring log lines - with no way to tell whether the other two came up, failed, or were still
starting. Facilities takes around a minute, which is long enough to conclude the wrong thing. All
three now print their Swagger, OpenAPI, health and dashboard addresses on `ApplicationReadyEvent`.

### No port dead-ends

`localhost:8092/ui/login` produced a Whitelabel 404. Nothing was broken - only
`sfl-fleet-logistics-service` packages the bundle - but a stack-trace page is indistinguishable from
a broken service to somebody checking each port came up. `/ui/**` on 8091 and 8092 now redirects,
carrying the rest of the path so the caller lands on the screen they asked for. SSEMP also gained the
landing page at `/` that facilities already had.

### Routes follow platforms, not systems

The dashboard's URLs were named after *systems* (`/fleet`, `/fuel`, `/dispatch`, `/emergency`) while
everything else in Phase 1 - the deployables, the run configurations, the ports, ADR 0005's
navigation model - is organised by *platform*. So a URL was the one place the structure disagreed
with itself.

| Platform | Port | Routes |
|---|---|---|
| IFIMP facilities | 8091 | `/ui/facilities/**`, now including `bookings` |
| SSEMP safety & security | 8092 | `/ui/safetysecurity/**` (was `/emergency`) |
| FTLMP fleet & vehicle | 8093 | `/ui/fleetvehicle/**` - fleet, fuel, dispatch and `login` |

`/me/**` stays where it is: those screens cross systems by design - a driver's day is an S166
assignment and an S168 logbook - and filing them under one platform would misdescribe them.

Cheap to do because the paths were already constants in `navigation.ts`; the only literal was
`/login` in the session guard, which is now `authPaths.login` for the same reason. The redirects
above carry the platform prefix, so `8092/ui/drills` reaches `/ui/safetysecurity/drills`.

### Verification

All three started together and every advertised URL checked: nine endpoints plus Swagger on each
port, five redirects, and five of the new dashboard routes. `8092/ui/login` was walked in a browser
end to end. Frontend: **198 tests**, typecheck and build clean.

One defect found by running it rather than reading it: the first redirect took `substring(4)` of
`/ui/login` and produced `8093/uilogin` - the separating slash belongs to the remainder, not the
prefix.

### The old URLs still work

Renaming every route invalidates every saved link - a bookmarked trip register, a URL in a ticket, a
screenshot in a runbook, a week of browser history. None of that is worth breaking for a rename, and
a 404 on a bookmark reads as a broken deployment to whoever is holding it.

`LegacyRouteRedirect` swaps the leading segment and keeps everything after it, query string and hash
included, so `/fleet/trips/abc-123?status=PLANNED` reaches that trip with that filter rather than
dumping the reader on a register. The redirects are declared **before** the session guard: an
unauthenticated visitor on a bookmarked link is rewritten first and bounced to sign-in second, so
`RequireSession` still carries them to the destination afterwards. Walked in a browser, not inferred.

### One shape for all three

The first pass named the top level after the platform and stopped there, which left the three
services with three different depths - `/fleetvehicle/fleet/trips`, `/facilities/sites`,
`/safetysecurity/drills`. Worse, `/facilities` silently mixed three systems at one level: `sites` was
S152, `faults` was S153, `bookings` was S159, and nothing in the URL said so while each answered to a
different route guard.

Every platform now reads *platform / system / resource*:

| Platform | Systems |
|---|---|
| `/fleetvehicle` | `fleet` S166, `fuel` S168, `dispatch` S171 |
| `/facilities` | `estate` S152, `maintenance` S153, `bookings` S159 |
| `/safetysecurity` | `emergency` S174, with room beside it for S160-S163 |

`/me/**` keeps its own top level: those screens cross systems by design - a driver's day is an S166
assignment and an S168 logbook - and filing them under one platform would misdescribe them.

Programme codes were considered and rejected for the namespace. There are **four** programmes and
**three** services since AVAMP was folded into FTLMP, so they do not map one to one; and `/FTLMP/trips`
names an internal grouping to a reader who has never seen the workplan.

It is a shim with a lifetime, and the file says so - once saved links have aged out, one release being
the usual measure, the block in `App.tsx` and the file go together. Two spellings maintained forever
is worse than the rename was.

---

*Going forward, every new pass follows the API-First Build Recipe, references its `SRS-SFL-*` IDs, and updates the Workplan §15 backlog.*

---

## Pass 8 August 2026 - fleet module defect sweep and fuel policy lifecycle

Fifteen reported items. Three were defects with a shared cause worth naming, the rest were UI
corrections; one turned out not to be a defect at all.

### The transaction-boundary defect, and the rule that now catches it

`Record access` on evidence answered 500 with `IllegalTransactionStateException`.
`JpaAuditAdapter.record` is declared `MANDATORY` deliberately - an audit row must commit or roll back
with the operation it describes - so a public method that writes audit outside a transaction fails
before doing any work. `FleetEvidenceApplicationService.recordAccess` had no `@Transactional`.

The fix belongs on the caller, not the adapter: making the adapter `REQUIRES_NEW` would let audit
writes survive the rollback of the operation they describe, everywhere, to spare one method an
annotation.

Looking for the rest of them found a second: `FleetIntegrationApplicationService.replay`, the Replay
button on the integration health screen, identical cause. `FleetArchitectureTest` now asserts that
**a public method calling `AuditPort.record` is annotated `@Transactional`**. Private helpers are
exempt - they inherit a boundary from the public method that called them; a public method is an entry
point and has no caller to inherit from.

**Why the existing tests were green.** `FleetIntegrationApplicationServiceTest` exercises `replay`
and passed throughout. A unit test constructing the service with doubles calls the target directly,
so the proxy that draws the boundary is never involved and `MANDATORY` has nothing to object to.
`recordAccess` had no test at all. Both now have Spring-context tests against real PostgreSQL, and
the evidence one was confirmed to fail with the annotation removed before being kept.

### Fuel policies became editable

`savePolicy` was INSERT-only, so an edit died on the primary key rather than on any rule; it is now
an upsert that leaves the id and creation stamp alone and lets the database own the version bump.

`PUT /policies/{id}` revises in place. That is safe because every reconciliation run stores the policy
id *and* the `policyVersion` it applied, so a past judgement stays readable as the rules that produced
it - nothing downstream is recomputed from the current row.

`DELETE /policies/{id}` **withdraws**: status to `ARCHIVED`, which `FuelPolicy.appliesAt` already
requires to be `ACTIVE`. A hard delete would strand every reconciliation run that cites the policy -
the audit trail would say a transaction was judged under a policy that no longer exists. Withdrawing
also frees the period, so the replacement can cover the same dates.

### Filter bars aligned on the control line

`FilterBar` used `items-end`, which lines up the bottom of each cell. A cell is label + control +
*helper text*, so a field carrying a two-line hint sat higher than its neighbours and the row of
controls came out ragged - visible on reconciliation, fuel transactions, fuel cards, driver logbooks
and scan imports. Two pages had already worked around it by passing `helperText=" "` to force a blank
line onto the fields that lacked one, which is the workaround that gives the cause away.

Now `items-start`: every label on one line, therefore every control on one line, helper text hanging
below at whatever length. The case `items-end` existed for - a control with no label - is served by
`FieldLabelSpacer`, one line in the two components that need it.

### Date pickers gained a year dropdown

flatpickr draws the year as a number input flanked by arrows. It is typeable and nothing says so, so
reaching a certificate issued four years ago meant clicking towards it. `monthSelectorType` is now
`dropdown` and `flatpickrYearSelect.ts` supplies the year, which flatpickr has no option for. All 87
date fields across 23 files are this one component, so it is one fix.

### Not a defect

"1 blocking issue" was read as a hardcoded number. It is not - `VehicleReadinessPolicy` accumulates
every blocker with no short-circuit, the API returns the whole list, and `BlockerList` counts what it
is given and pluralises. The reported "1" was a real count of one. Left as it is, with tests pinning
the arithmetic so the reading cannot come back.

### Uploads capped at 5 MB

`UploadedFileScanner.MAX_BYTES` was 10 MB; the multipart ceiling follows it at 6/7 MB so an oversized
file is refused by the scanner with an explanation rather than by Tomcat with a bare failure. The
client check moved into `FileField`, which every upload in the dashboard passes through - it had been
applied in the evidence field only, so the CSV import, the dispatch scan batch and the facilities
attachment took a file of any size. **The `V29` CHECK constraint stays at 10 MB**: migrations are
immutable once applied, and tightening it would fail against any existing row above 5 MB.

### Fuel fraud control framework

`docs/fuel/S168_Fuel_Fraud_Control_Framework.md`. The platform already enforces far more than the
brief assumed - 21 reconciliation rules including exact-duplicate evidence detection, price deviation
against the posted price, and money caps per card - so the document is an inventory of what is built,
specifications for what is not (perceptual hashing, EXIF, geofence, OCR, provider feed), and a
priority ordering by impact against effort with provider dependencies flagged.

Its central finding: **the three highest-value actions are configuration, not code.** Posted prices,
per-card money limits and card-to-vehicle bindings are what make the existing rules fire rather than
pass vacuously. The derived-litres design - amount paid ÷ posted price, so inflating money inflates
volume and volume hits physical limits - is inert without the posted-price register.

**463 fleet tests, 0 skipped, against real PostgreSQL.**

---

## Pass 9 August 2026 - the launcher's own defects, and two found reviewing the last pass

Running the work of the previous pass exposed three faults in `scripts/sfl-all-services.sh`, none of
them in product code and all three capable of costing an afternoon. Reviewing that pass then found
two real defects, one of them introduced by it.

### One bug behind every "Unable to rename"

The build failed at packaging with

```
Unable to rename '...sfl-facilities-service-0.1.0-SNAPSHOT.jar'
             to '...sfl-facilities-service-0.1.0-SNAPSHOT.jar.original'
```

which is a message about a file operation that says nothing about its cause. It reads as a corrupt
`target`, and deleting `target` fixes it for exactly as long as it takes the next run to hold the jar
open again. The build is also *green* at that point - every test passed and the failure lands after
them - so the first instinct is to re-read a test report with nothing wrong in it.

The cause was `stop_all`. `launch` runs `java ... | sed` in a subshell, so `$!` is the **subshell**
and java is its grandchild; killing the recorded PID killed the log prefixer and left the service
running. Ctrl+C printed "stopped 12345" four times while four services kept their ports and their
jars. The header of the file claimed "Ctrl+C stops all three" for as long as that was untrue.

So: **every orphaned run in this project traces to one wrong PID**, and the guard added first was
treating the symptom.

Stopping by port fixes it. Java goes first and the subshells after - the other order kills `sed`, and
java then blocks writing to a closed pipe instead of shutting down - and the ports are then *checked*,
because reporting a stop that did not happen is the whole reason this needed rewriting.

### A guard that reported success while doing nothing

The first version of the jar-lock guard asked WMI for java processes and matched their command
lines. It never worked, and it failed silently in two independent ways:

- `-Filter "Name='java.exe'"` cannot survive being embedded in a shell string. Bash removes the inner
  quotes, the WQL becomes invalid, and `Get-CimInstance` errors into a stream nothing reads.
- PowerShell's stdin mode executes line by line, so a multi-line `foreach` never runs as a loop.

Both return nothing, and nothing is indistinguishable from "nothing is running". The guard reported a
clean start while four services held the jars. A `bash -n` check passes on all of this, which is the
lesson: syntax-checking a shell script proves nothing about a subprocess it shells out to.

`netstat -ano` gives the owning PID with one `awk` and no quoting, and a listening port is the fact
that actually matters. It stops whatever holds 8090-8093, a debug session included, and names each
one first - telling a debugged service from a jar-launched one needs the command line again, which is
the road this came off.

### Two runs at once destroy each other

The `_e2e` databases are one set on fixed ports, shared by everything on the machine, and a second
run begins by dropping the databases the first is using. Two failures follow, neither pointing at its
cause:

- `DROP DATABASE ... WITH (FORCE)` terminates the other run's connections mid-transaction, and its
  next test fails with `JpaSystemException: Unable to rollback against JDBC Connection` - a message
  about a connection, in a test about authorisation, masking the exception the test was correctly
  about to receive.
- Two JVMs appending to one hash-chained audit table interleave, and `FleetAuditChainPostgresTest`
  reports a broken previous-hash link at sequence 1. **Tamper detection working exactly as designed,
  on tampering that was two writers.**

Both were observed in one session, and the second is alarming enough to send somebody hunting a
corrupted audit trail. The launcher now refuses to start when anything is connected to an `_e2e`
database. It refuses rather than waits: the other run owns them until it finishes.

### The policy version had stopped meaning anything

Introduced by the previous pass. A reconciliation records the policy id and `policyVersion` and
**nothing else about the rules** - the run does not copy the limits it applied - so that pair is the
entire account of why a transaction was judged as it was. `updatePolicy` took the version straight
from the request, and the edit form prefills the current one, so the default path was *change a
limit, keep version 1*. Every past run recording "version 1" then described rules it was not produced
by, and nothing in the record distinguished them.

The previous pass's javadoc asserted that past judgements stay readable as the rules that produced
them. True only if the operator remembered to bump the number, and nothing asked them to.

`FuelPolicy.hasSameRulesAs` compares the outcome-bearing fields - by `compareTo`, so 50 and 50.00 are
one ceiling - and a revision changing any of them under the same version is refused with
`FUEL_POLICY_VERSION_NOT_ADVANCED`. **Refused, not auto-incremented:** the version is the operator's
own numbering, it appears on their paperwork, and the service is not entitled to change it behind
them. A rename or a corrected date still needs no new version.

The exhaustive `switch` in `FleetHttpStatusMapper` refused to compile until the new code had a status,
which is the check earning its keep.

### Closing an anomaly asked for a UUID nobody has

Pre-existing. Closure is the one anomaly transition the domain refuses without evidence, and the
dialog satisfied that with a bare text field: *register the closure evidence under Evidence & audit,
then paste its identifier*. The identifier is a UUID on no paperwork, so the real workflow was to
leave the case, find the record, copy the id and come back - on the mandatory field standing between
an operator and finishing the case. The compliance form and the fuel capture form were both rewritten
to remove that exact instruction; this one was missed.

It now takes the document. The upload happens **before** the transition, so a refused file cannot
leave a case closed citing evidence that was never stored, and a document already filed is still
selectable. Filed against the case rather than the vehicle: it documents this decision, and an
anomaly raised from a logbook has no vehicle to file under at all.

### Still open

- The 5 MB cap now governs bulk CSV and scan imports as well as evidence photographs, and the
  container ceiling fell from 11 MB to 6 MB. Per instruction, but it merges two different judgements
  and is worth taking deliberately.
- `V29`'s `CHECK (byte_size <= 10485760)` stands - migrations are immutable once applied - so its
  comment now describes a limit that is not the policy.
- The static copy never cleans: 24 index bundles and their chunk trees ship in every jar. Harmless to
  correctness, and it misleads anyone grepping the packaged assets to check what is deployed.
- Whether the `REQUIRES_NEW` denial path has enough connection-pool headroom. Raised on evidence that
  turned out to be the concurrent-run collision above, so it is a question, not a finding.

**464 fleet, 341 facilities, 41 SSEMP tests and 239 front-end tests, 0 skipped, against real
PostgreSQL.**

### Pass - Phase 2 IFIMP: S156, S157, S158, S169, S173, S176 (`sfl-facilities-service`)

Built the six Phase 2 IFIMP systems named in SRS CLET/DTI/CL9/SFL/SRS/2026/002 §3.1, all owned by the
Building & Infrastructure Unit, all landed as modules of the existing `sfl-facilities-service` /
`facilities` schema alongside S152/S153/S159 - see ADR 0009 for why, and CORR-06 for why RLS could not
wait for a second pass this time. Six modules were built in parallel worktrees against one foundation
commit and merged in with zero conflicts, because the foundation pre-seeded each system's own delimited
block in the shared enums.

**Foundation, built once, ahead of the six modules:**

- `facilities.apply_site_scope_policies()` (V15) turns V14's one-time RLS catalogue loop into a
  function every Phase 2 migration calls last, so a table has its policy from the migration that
  creates it - the ADR 0007 deferral Phase 1 paid for, closed structurally this time.
  `Phase2RowLevelSecurityCoverageTest` fails the build on any site-scoped table left uncovered.
- A real defect, found and fixed: outside an HTTP request the site-scope supplier returned nothing,
  so under `sfl_app` every scheduled sweep, the outbox drainer and the broker listener would have read
  zero rows. `PlatformThreads` marks scheduler/listener threads and scopes them to `*`.
- One authenticated vendor inbox (`VendorMessageVerifier`) for every inbound vendor feed - S156
  telemetry, S157 meter readings, the S078 event hand-off - source allowlist, channel, HMAC-SHA256
  timestamp window, site, schema, idempotency. A rejection is recorded to the inbox, the hash chain,
  the SIEM port and the outbox in its own transaction before the refusal is thrown.
- `gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency` in `sfl-service-common`, so S173's risk-gated
  confirmation and the future S164/S165 (SSEMP) share one currency rule rather than each inventing one.
- `AutomatedWorkOrderIntake` on S153 (the one door S156/S173/S176 raise work through), a
  `BookingLifecycleObserver` + `CleaningRequirement` on S159 (what S169 hooks into), and
  `BookingUtilisationReader` (S158's read-only view of S159).
- Cross-module contracts published in each provider's own `application/contract` package - S156's
  `BuildingDeviceDirectory`/`BuildingTelemetryObserver`, S169's `EventCleaningCapacity`, S158's
  `ScenarioHandover`, S176's `ConstructionProjectIntake` - each consumed through the consumer's own
  port, so no Phase 2 module depends on another's internals, and S158/S176 (each the other's provider
  and consumer) merged with zero wiring needed beyond Spring picking up the real bean over the scaffold.

**S156 Building Management System / IoT** (V16, `buildingsystems`) - authenticated telemetry behind a
vendor-translator port with two shipped adapters (a simulator and a differently-shaped BACnet/MQTT
bridge, proving replaceability); location resolved against S152, unresolvable readings quarantined;
every device an AVAMP asset, fed by a local projection off `sfl.avamp.asset-registered.v1`;
versioned/debounced threshold rules raising correlated S153 work orders; a health rollup that shows
`UNKNOWN` rather than defaulting green; critical faults escalate immediately to the S162a/S174 fast
lane - **recorded and drained, not delivered: SSEMP has no inbound event consumer anywhere in this
codebase**, so the fast-lane latency target (NFR-PERF1) cannot be measured until one exists.

**S157 Energy & Sustainability Monitoring** (V17, `energy`) - meters per utility/source; a meter whose
device is already an S156 asset must stream through S156, not open a second vendor connection
(`ENERGY_DEVICE_DOUBLE_REGISTERED`); manual readings outside a plausibility band held for a different
person's verification; budgets/tariffs versioned so a later revision never rewrites a closed period's
variance; sustainability KPIs always carry a completeness indicator and are never withheld for being
partial.

**S158 Space Planning & Move Management** (V18, `spaceplanning`, plus a new `space_allocations` table
added to S152's own `masterdata` module - S152 had no allocation register, which the requirement's
"never a parallel register" forced into scope) - versioned draft scenarios that never touch the S152
register until an explicit, audited commit; occupancy compliance computed per allocation, never
compliant by default; utilisation pulled read-only from S159 on a schedule (architecture-tested: no
class outside one named adapter may depend on `booking`).

**S169 Cleaning & Janitorial Schedule Management** (V19, `cleaning`) - routine schedules plus
booking-triggered tasks raised automatically when a confirmed S159 booking carries a cleaning
requirement; checklists with photo-evidence references (never bytes); SLA compliance computed from the
task's own timestamps, never a vendor's self-report; capacity reservations for S173 that name the
competing commitment on conflict rather than a bare refusal.

**S173 Event Logistics & Set-Up Workflow** (V20, `eventlogistics`) - a confirmed S078 hand-off is the
only way a set-up task can exist; resource requests route to S159/S153/S169, with S172 catering
(Phase 3, unbuilt) recorded as an explicit manual-coordination item rather than dropped or shown
fulfilled; higher-risk events require a current risk assessment via the shared `RiskAssessmentCurrency`
- the S165 projection that would feed it has no publisher yet, so every higher-risk confirmation is
correctly refused until S165 ships.

**S176 Construction Project Management** (V21, `construction`) - the full PROPOSED→CLOSED lifecycle
with its own approval-sign-off and permit gates (S164 permits held in a fail-closed local projection,
same reason as S173's S165 gap); contractor compliance auto-suspends site access on expiry (recorded,
not enforced - S160a/S160 have no consumer either); variations blocked past an escalation threshold
until an escalated approver acts; handover applies the real S152 register update in the same
transaction and refuses to complete without it.

**Verification.** `784 tests, 0 failures, 0 errors, 0 skipped` for `sfl-service-common` +
`sfl-facilities-service` together, against real PostgreSQL (RLS and migration suites included).
SpotBugs clean on both modules. Migrations apply cleanly V1 through V21 on a virgin schema and on a
populated one. Every module's own gap-and-conflict report is under `docs/facilities/S1xx_*`; the
recurring theme is the same one S156 states plainly: SSEMP has nothing built yet that consumes an
IFIMP event, so every cross-programme signal this pass adds is published, audited and SIEM-forwarded,
and none of them is delivered.

### Pass - Phase 2 SSEMP: foundation and S165 Risk Assessment Library (`sfl-safety-security-service`)

The first of the three Phase 2 SSEMP systems (SRS CLET/DTI/CL9/SFL/SRS/2026/002 §3.2), built first because
S164 refuses a permit without a current S165 assessment and S173 had been refusing every higher-risk
event for want of one. Decisions in ADR 0010.

**Foundation (V18), once, for S165, S164 and S175.** Row-level security ported from facilities, applied by
explicit table list so Phase 1 SSEMP tables are untouched; `PlatformThreads` behind the scheduler and the
listener. `SafetySecurityOutboxDrainer`: `safety_security.outbox_messages` had been written since V1 and
never read - every SSEMP event was recorded and none delivered. It now drains, defaulting to S174's
transport. An inbound listener bound to `ifimp.#`, claiming V1's never-used `inbox_messages`.

**S165 (V19, `riskassessment`).** Versioned assessments scoped to an activity type and/or S152 location;
hazards rated before and after controls, level computed from the highest residual; publish refused with
"Hazard Without Control" naming every one; revision opens the next version and publishing supersedes the
last in one transaction. Review intervals per level as runtime configuration; independent sign-off at
HIGH/CRITICAL; the shared `RiskAssessmentCurrency` rule decides currency everywhere, and a sweep reminds and
records the lapse once each. Coverage gaps against activity types actually observed (S176 work types over
the broker, S163 incident activities in-process). S163 incidents gained an optional assessment and
activity; S163 publishes `IncidentRiskObserver` and S165 implements it, so the review flag commits with the
incident - deferred with a reason and date, or cleared by findings, never dismissed. Create honours
`Idempotency-Key`. The four reserved `sfl.ssemp.risk-assessment-*` events carry exactly S173's contract,
proven from both sides. HSE_MANAGER holds the module; no new role.

**UI.** `modules/riskassessment`: dashboard, register, detail with every version, review-flag queue,
coverage and hazards, templates and review cycle; risk-context fields on the incident screens. Driving the
live API first found three contract gaps (computed scores and `linkable` not on the wire; generic wording
where the SRS has its own), fixed in the service.

**Found on the way.** Publishing superseded *after* the new version, which the one-PUBLISHED index refused
- caught by the real-database suite. The Backend workflow had been red on `main` since the IFIMP Phase 2
merge: facilities' distinct test contexts exhausted PostgreSQL's 100 connections ("too many clients
already" x125); `spring.test.context.cache.maxSize=6` fixes it. The SSEMP role matrix doc was stale on
`main`.

**Verification.** Against real PostgreSQL and RabbitMQ: shared kernel + SSEMP 281 tests, facilities 785,
0 failures, 0 skipped. Frontend: typecheck clean, 366 tests, production build green. SSEMP boots through
V19 on an empty and a populated database; a pending row drained to a queue bound to `ssemp.#`. Gaps in
`docs/hse/S165_Gap_And_Conflict_Report.md`, runbook `docs/runbooks/s165-risk-assessment-library.md`.
