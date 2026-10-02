# SFL Operations UI

The shared React front end for the **Safety, Facilities & Logistics Directorate** (CLET). Ten
systems have screens, across three services:

| System | Module | Service | Port |
| --- | --- | --- | --- |
| S152 - Facility management (CAFM/IWMS) | `modules/facilities` | `sfl-facilities-service` | `8091` |
| S153 - Maintenance management (CMMS) | `modules/facilities` | `sfl-facilities-service` | `8091` |
| S159 - Room and resource booking | `modules/booking` | `sfl-facilities-service` | `8091` |
| S160 - Visitor management | `modules/visitor` | `sfl-safety-security-service` | `8092` |
| S163 - HSE incident and near-miss | `modules/incident` | `sfl-safety-security-service` | `8092` |
| S166 - Fleet and vehicle management | `modules/fleet` | `sfl-fleet-logistics-service` | `8093` |
| S168 - Fuel and driver logbooks | `modules/fuel` | `sfl-fleet-logistics-service` | `8093` |
| S171 - Courier and dispatch | `modules/dispatch` | `sfl-fleet-logistics-service` | `8093` |
| S174 - Emergency mass notification | `modules/emergency` | `sfl-safety-security-service` | `8092` |

S166 is the reference module. The dashboard is the **only** interface; service landing pages redirect
into the relevant route here.

Built with React 19, Vite, TypeScript, Tailwind CSS and Recharts. UI primitives come directly from
`@rfdtech/components` v2.4.2, with the library stylesheet and light theme loaded at the application
entry point. Fleet, fuel, dispatch and mailroom follow the Fleet, Transport & Logistics design;
other modules use the same library composition and visual language.

| Token        | Value     | Used for                                             |
| ------------ | --------- | ---------------------------------------------------- |
| Primary navy | `#051B2B` | App bar, side navigation, primary surfaces, headings |
| Gold accent  | `#B8950D` | Active navigation, key actions, highlights, charts   |
| White        | `#FFFFFF` | Work surfaces - tables, cards, dialogs               |

---

## Running it

There is no sign-in step in local development: `sfl.security.enabled` is `false`, so the service
accepts the `X-SFL-*` actor headers this dashboard sends.

### One command - service and dashboard together (recommended)

From the repository root:

```powershell
cd "C:\Users\Daniel Adjei\Documents\CLET\Projects\SFL\SFL"
.\start-fleet.ps1
```

That loads the SFL environment, starts the service databases, builds this dashboard if it has not been
built yet, runs the Fleet service, and opens two browser tabs when it is ready:

| URL                                     | What                |
| --------------------------------------- | ------------------- |
| <http://localhost:8093/ui/>             | Operations dashboard |
| <http://localhost:8093/swagger-ui.html> | Swagger UI          |
| <http://localhost:8093/v3/api-docs>     | OpenAPI JSON        |

`http://localhost:8093/` redirects to the dashboard. One process serves the API and the UI, so the
dashboard calls the API same-origin and CORS never comes into it.

Useful switches:

```powershell
.\start-fleet.ps1 -RebuildUi      # rebuild the dashboard after front-end changes
.\start-fleet.ps1 -SkipDb         # databases already running
.\start-fleet.ps1 -SkipUiBuild    # API only, don't touch the dashboard
.\start-fleet.ps1 -NoBrowser      # don't open browser tabs
```

### Front-end work - hot reload

```powershell
.\scripts\dev\run-fleet-dev.ps1
```

The service runs in its own window on 8093 and Vite serves the dashboard on
<http://localhost:5005> with hot module replacement. The service already allows `localhost:5005`
as a CORS origin.

### Doing it by hand

```powershell
cd "C:\Users\Daniel Adjei\Documents\CLET\Projects\SFL\SFL"
.\use-sfl-env.ps1
docker compose -f compose.service-dbs.yml up -d

cd services
mvn -pl sfl-fleet-logistics-service -am test          # optional
mvn -pl sfl-fleet-logistics-service -Pui spring-boot:run
```

`-Pui` builds this dashboard with a project-local Node install before starting the service. Without
it, Maven copies whatever is already in `frontend/sfl-operations-ui/dist`; if nothing has been
built, the service starts normally and logs how to build the dashboard.

Front-end only:

```powershell
cd frontend\sfl-operations-ui
npm install
npm run lint
npm run typecheck
npm run build
npm run dev
```

### The actor switcher

The account avatar in the top bar carries a **Change actor** entry in development builds. It sends
different `X-SFL-*` headers, stores them for the session and reloads.

Use it to check what ADR 0005 promises, at both grains:

| Preset | What should happen |
| --- | --- |
| Fleet manager | All five FTLMP groups. No emergency. |
| **Driver** | Fleet and fuel. **Courier & dispatch disappears** - a driver does not run the mailroom. |
| **Mailroom officer** | **Courier & dispatch only.** Same programme as the driver, the opposite half of it. |
| Emergency coordinator | Emergency notifications only. |
| Security officer | Courier & dispatch *and* emergency - it escalates dispatch exceptions. |
| Facilities manager | The no-programme page, because IFIMP has no screens yet. |

The driver and mailroom officer are the pair worth trying: both are FTLMP, and programme scoping alone
could not tell them apart. The fleet service will also refuse a driver's dashboard read with
`FLEET_UNAUTHORIZED_SCOPE` - navigation scoping is a usability control, and the service is the
enforcement point.

The avatar shows a badge while an override is in force, and **Clear and reload** returns to the
`VITE_SFL_*` values in `.env`. A production build has none of this: the panel is behind
`import.meta.env.DEV` on its dynamic import, so no chunk is emitted.

### How the dashboard is mounted

`npm run build` emits the bundle under the `/ui/` base (see `.env.production`) and Maven copies
`dist/` into the service's `static/ui`. `FleetWebConfiguration` serves it with a single-page
fallback, so refreshing on `/ui/fleet/vehicles` works. React Router takes its basename from
`import.meta.env.BASE_URL`, so the mount point is configured in exactly one place.

---

## Configuration

Copy `.env.example` to `.env` and adjust. Vite reads these at build time.

`.env.production` is loaded by `npm run build` only. It clears `VITE_FLEET_API_BASE_URL` so the
embedded bundle calls the API on its own origin, and sets `VITE_BASE_PATH=/ui/`. Leave both alone
unless you are deploying the dashboard somewhere other than the Fleet service.

| Variable                  | Default                 | Purpose                                            |
| ------------------------- | ----------------------- | -------------------------------------------------- |
| `VITE_FLEET_API_BASE_URL` | `http://localhost:8093` | Base URL of the Fleet & Logistics service          |
| `VITE_SAFETY_SECURITY_API_BASE_URL` | `http://localhost:8092` | Base URL of the SSEMP service - serves S174 today, S160–S163 later |
| `VITE_FACILITIES_API_BASE_URL` | `http://localhost:8091` | Base URL of the Facilities service (S152)       |
| `VITE_SFL_USER`           | `fleet.operator`        | Sent as `X-SFL-User`                               |
| `VITE_SFL_DISPLAY_NAME`   | `Fleet Operator`        | Sent as `X-SFL-Display-Name`                       |
| `VITE_SFL_ROLES`          | `FLEET_MANAGER,…`       | Sent as `X-SFL-Roles` (comma-separated)            |
| `VITE_SFL_SITES`          | `CLET-HQ`               | Sent as `X-SFL-Sites` (comma-separated)            |
| `VITE_FLEET_DEV_FALLBACK` | `false`                 | Reserved switch for clearly-labelled dev fallbacks |
| `VITE_APP_PORT`           | `5005`                  | Dev/preview server port                            |
| `VITE_BASE_PATH`          | `/ui/` in builds        | Mount point of the bundle inside the service       |

### Request headers

Every request made through `shared/api/client.ts` carries:

```
X-SFL-User, X-SFL-Display-Name, X-SFL-Roles, X-SFL-Sites
X-SFL-Source-Channel: WEB
X-Correlation-ID: <uuid v4, one per request>
Idempotency-Key: <uuid v4>   # state-creating POSTs only
```

In production the same `ActorContext` is derived from the OIDC/JWT principal and the `X-SFL-*`
headers are ignored - no change is needed on this side.

### If the facilities sections are missing

`VITE_SFL_ROLES` **overrides** the built-in default role list rather than adding to it. The default
includes `FACILITIES_MANAGER` and `CENTRE_MANAGER`; an `.env` that sets `VITE_SFL_ROLES` without a
facilities role gets no facilities sections at all. That is the entitlement working, not a broken
build - but it looks identical to one, so it is worth checking first. `.env` is gitignored, so this
is per-machine.

The roles worth switching between (the account panel has presets for all of them):

| Role | What it shows |
| --- | --- |
| `FACILITIES_MANAGER` | The estate and readiness. No audit, no configuration, no operating-mode control - the matrix withholds mode from this role deliberately. |
| `CENTRE_MANAGER` | Adds the operating-mode control: declaring and standing down examination mode. |
| `IFIMP_TECHNICIAN` | Assess readiness and change asset condition. No lock, no mode, no configuration. |
| `FACILITIES_DIRECTOR` | Adds audit and integrity verification. |

### CORS

Each service must allow the dev origin. If requests fail with a CORS error, add
`http://localhost:5005` to the allowed origins in `FleetWebConfiguration`,
`EmergencyWebConfiguration` or `FacilitiesWebConfiguration`. Each must also **expose**
`X-Correlation-ID`, or the client reads `null` cross-origin and every error message loses the one
identifier that ties it to a service log.

---

## Architecture

```
src/
  shared/                 # API, domain helpers, shell and intentionally shared domain components
    api/                  # client, headers, correlation, envelope parsing, config
    components/           # DataState, evidence/site fields, formatting and domain helpers
    errors/               # FleetApiError - status, code, fieldErrors, details
    hooks/                # useApiQuery
    layout/               # SflAppShell, navigation
    validation/           # validators + useFleetForm
  modules/
    fleet/                # S166
      api/                # enums.ts, dto.ts, fleetApi.ts
      charts/             # fleet charts composed from the shared chart primitives
      components/         # fleet-specific detail, table and workflow composition
      dialogs/            # vehicle / driver / trip / workflow action dialogs
      pages/              # dashboard, registers, details, queues, governance
    facilities/           # S152
      api/                # enums.ts, dto.ts, facilitiesApi.ts, workflow.ts
      components/         # ReadinessBlockerList, facilitiesFormat
      dialogs/            # assessment / readiness / blocker / asset / operating-mode dialogs
      pages/              # dashboard, estate registers, readiness, audit, configuration
  modules/booking         # S159 room and resource booking
  modules/fuel            # S168 fuel and driver logbooks
  modules/dispatch        # S171 courier, dispatch and mailroom
  modules/emergency       # S174 emergency mass notification
  modules/incident        # S163 HSE incident and near-miss management
  modules/riskassessment  # S165 risk assessment library
  modules/visitor         # S160 visitor management
  modules/ifimp           # Phase 2 IFIMP operations
```

Adding another SFL module means adding `src/modules/<module>` and a navigation section; the API
client, error envelope handling, validation and application shell are already shared.

**One thing is not optional when you do.** If the module is served by a new service, add its
`/actor/permissions` endpoint to `SOURCES` in `shared/layout/actorPermissions.ts`. The fail-open
there is per-*set*, not per-service: once any service answers, anything absent from the merged set is
treated as **denied**, not unknown. A module missing from that list renders with every gated control
silently hidden and no error anywhere. This is documented at the call site and cost an afternoon
during S152.

### Tests

```bash
npm run test          # vitest run
npm run test:watch
```

156 tests today, covering system entitlement per role, the S152 readiness rules, the S153 closure
evidence gate and work-order transition table, the S168 fuel-card masking rules and lifecycle
transitions, the formatting helpers, and the facilities dashboard's loading / error / stale /
restricted-drilldown states.

Two things about writing tests against the shared components. `Select` is an ARIA listbox, not a
native `<select>` - `selectOptions` will not drive it. Click the `combobox` and then the `option`,
and query the option from `screen` rather than from inside the dialog, because the list opens
through a portal. And jsdom has no layout, so `scrollIntoView` is stubbed in `src/test/setup.ts`;
without it every test that opens a listbox fails with an error about scrolling.

### Data-fetching contract

`useApiQuery` gives every screen the same four states - loading, error, empty, content - rendered
through `<DataState>`. Mutations never guess the resulting server state: each dialog refetches the
affected queries on success. There are no optimistic updates on transitions, because the service
owns the state machine and the readiness decision.

### Validation

`useFleetForm` mirrors the service's Bean Validation constraints (required, `@Size`,
`@PositiveOrZero`, year and capacity ranges) and adds the cross-field rules the service enforces
(date ranges, odometer floors, mandatory closure evidence). Client rules exist to answer the
operator sooner - they never replace the server check. On a failed submit the service's
`fieldErrors[]` are mapped back onto the same fields, and the envelope message (which carries the
SRS _Error States_ wording verbatim for SRS-defined codes) is shown as a form-level alert. Submit
buttons disable while a request is in flight.

---

## Screens

| Route                 | Screen                                                                      |
| --------------------- | --------------------------------------------------------------------------- |
| `/fleet`              | Operations dashboard - readiness, exceptions, active trips, escalations     |
| `/fleet/vehicles`     | Vehicle register (server-side filter, sort, page)                           |
| `/fleet/vehicles/:id` | Vehicle detail - readiness, compliance, service history, trips              |
| `/fleet/drivers`      | Driver register                                                             |
| `/fleet/drivers/:id`  | Driver detail - eligibility by vehicle category, assignments                |
| `/fleet/trips`        | Trip queue                                                                  |
| `/fleet/trips/:id`    | Trip workflow - assign, inspect, start, hold, close, cancel                 |
| `/fleet/workflow`     | Workflow queue with SLA standing                                            |
| `/fleet/workflow/:id` | Workflow item with immutable transition and comment history                 |
| `/fleet/compliance`   | Compliance and service exposure                                             |
| `/fleet/governance`   | Evidence lookup and registration, export requests, audit chain verification |
| `/fleet/integrations` | Telematics intake health and dead-letter replay                             |

---

## Fleet endpoints integrated

Taken from the controllers, which are the authority - where the API inventory note and the code
disagree, the code is what the dashboard was built against.

| Area         | Endpoints                                                                                                                                                                                                                                                   |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Vehicles     | `GET/POST /vehicles`, `GET/PATCH /vehicles/{id}`, `PATCH /vehicles/{id}/lifecycle`, `GET/POST /vehicles/{id}/compliance-documents`, `GET /vehicles/{id}/service-history`, `POST /vehicles/{id}/service-records`, `POST /vehicles/{id}/odometer-corrections` |
| Drivers      | `GET/POST /drivers`, `GET/PATCH /drivers/{id}`, `GET /drivers/{id}/eligibility`                                                                                                                                                                             |
| Trips        | `GET/POST /trips`, `GET /trips/{id}`, `PATCH /trips/{id}/assignment` `/start` `/hold` `/cancel` `/closure`, `GET/POST /trips/{id}/inspections`, `GET /trips/assignment-preview`                                                                             |
| Workflow     | `GET/POST /workflow-items`, `GET /workflow-items/{id}`, `PATCH …/assignment` `/progress` `/hold` `/escalation` `/cancel` `/closure` `/reopen`, `POST …/comments`, `GET …/transitions`                                                                       |
| Evidence     | `POST /evidence`, `GET /evidence/{id}`, `POST /evidence/{id}/access`, `POST /evidence/{id}/export-requests`, `PATCH /evidence/export-requests/{id}/decision`, `POST /evidence/export-requests/{id}/export`                                                  |
| Audit        | `GET /audit/records`, `GET /audit/chain/verification`                                                                                                                                                                                                       |
| Integrations | `GET /integrations/health`, `POST /integrations/messages/{id}/replay`                                                                                                                                                                                       |
| Dashboards   | `GET /dashboards/operations`, `GET /dashboards/operations/drilldowns/{indicator}`, `GET /reports/go-live-readiness`                                                                                                                                         |

---

## Scripts

| Command             | Does                                              |
| ------------------- | ------------------------------------------------- |
| `npm run dev`       | Vite dev server on `VITE_APP_PORT` (default 5005) |
| `npm run build`     | Type-checked production build into `dist/`        |
| `npm run typecheck` | `tsc --noEmit`                                    |
| `npm run lint`      | ESLint with Prettier, zero warnings tolerated     |
| `npm run pretty`    | Prettier write across the project                 |
| `npm run preview`   | Serve the production build locally                |

## Manual verification checklist

1. Run `.\start-fleet.ps1` from the repository root. Swagger and the dashboard open by themselves.
2. `http://localhost:8093/` redirects to `/ui/` and the dashboard renders indicators, charts and
   the snapshot freshness chip.
3. Vehicle and driver registers load, filter and page against the server.
4. Vehicle, driver, trip and workflow detail screens open from their registers.
5. Create and transition dialogs show inline errors before submit - try an empty registration
   number, an end date before the start date, and a negative odometer.
6. A backend rejection surfaces its own wording - try closing a trip with an unknown evidence ID
   and confirm the `FLEET_CLOSURE_EVIDENCE_MISSING` message appears with its correlation ID.
7. The SFL logo appears in the sidenav and the navy/gold palette is applied throughout.
8. Resize to tablet and phone widths - the sidenav becomes a drawer and tables stay scrollable.
