# SFL Operations dashboards - module playbook

What the Fleet & Vehicle build produced, and how to add the next module to it.

Keep this next to the code. It is the contract between modules: anything here is settled, and a new
module inherits it rather than deciding it again.

**Modules built:** `fleet` (S166) and `fuel` (S168). Two modules in, §9 records what the second one
had to add to the shared layer and what it learned that §4 did not already cover.
Since then: `dispatch` (S171), `emergency` (S174), `facilities`, `booking`, `visitor`, `incident`, and
`riskassessment` (S165, the first Phase 2 module - see `docs/hse/S165_UI_Gap_Report.md`).

---

## 1. What exists now

`frontend/sfl-operations-ui` is a single React application that the Fleet & Logistics service serves
from `/ui/`, so one process serves the API, Swagger and the operator screens.

**Stack.** React 19, TypeScript (strict, with `noUnusedLocals` and `noUnusedParameters`), Vite 7,
Tailwind CSS v4, ApexCharts, flatpickr, react-router 7, dayjs. No component library - the kit in
`src/shared` is the component library.

**Structure.**

```
src/
  shared/            the foundation every module uses - do not fork it
    api/             client.ts, config.ts, types.ts
    charts/          BaseChart.tsx, Charts.tsx, palette.ts
    components/      the UI kit (see §3)
    errors/          FleetApiError.ts
    hooks/           useApiQuery.ts
    layout/          AppShell, TopBar, Sidebar, SidebarContext, ScrollToTop, navigation.ts
    pages/           NotFoundPage.tsx
    validation/      useFleetForm.ts, validators.ts
  modules/
    fleet/           the first module - the shape every other one copies
      api/           dto.ts, enums.ts, fleetApi.ts, driverEligibility.ts
      charts/        module-specific chart compositions
      components/    module-specific pieces (drilldown drawer)
      dialogs/        one file per aggregate: vehicle, driver, trip, workflow
      pages/         one file per screen
    fuel/            the second module - same shape
      api/           dto.ts, enums.ts, fuelApi.ts, workflow.ts
      charts/        spend, reconciliation and anomaly-mix compositions
      components/    fleet reference pickers, file field, provenance
      dialogs/       one file per aggregate: policy, transaction, logbook, anomaly, import
      pages/         one file per screen
    dispatch/        the third module - same shape
      api/           dto.ts, enums.ts, dispatchApi.ts, workflow.ts
      charts/        exception-mix composition
      components/    window helpers bound to the module's own limit
      dialogs/       item, manifest, custody, exception
      pages/         one file per screen
    emergency/       the fourth module - same shape, and the first to address a second service
      api/           dto.ts, enums.ts, emergencyApi.ts, workflow.ts
      charts/        drill-performance composition
      components/    site records hook, checkbox group, consequence panels, format helpers
      dialogs/       record, activation, break-glass, drill
      pages/         one file per screen
  App.tsx            routes
  index.css          design tokens - read the header comment before changing anything
```

Imports are absolute from `src` (`shared/components/SectionCard`, `modules/fleet/api/fleetApi`).
Never `@/`, never long relative chains.

**The Fleet module ships eight screens:** operations dashboard, vehicle register and detail, driver
register and detail, trip queue and detail, workflow queue and detail, compliance and service,
evidence and audit, integration health.

**Fuel ships twelve**, **dispatch ten** and **emergency nine**, each under one navigation group. Four
modules, thirty-nine screens, one component kit and one design system.

**Three of those four modules belong to one programme; the fourth does not.** Fleet, fuel and dispatch
are SFL.FTLMP. Emergency notification is SFL.SSEMP - a different programme, a different service and a
different set of users. This bundle is, today, the FTLMP portal: the fleet service serves it, and
`directorate.module` says so. Read §12 before adding a module, because *which portal a module belongs
in* is now a question with an answer, and it is not "whichever one has the component kit".

---

## 2. The design system

Tokens are taken from the CLET UI/UX Design System in Figma - all three variable collections - and
live in `src/index.css`. **Read the header comment in that file before touching colour.** It records
which Figma alias each Tailwind name carries and, where the design system's own alias would have
failed WCAG 2.2, why the theme departs from it.

| Tailwind name | Figma ramp | Used for |
| --- | --- | --- |
| `brand-*` | CLET Navy Blue (800 = `#0A1931`) | top bar, primary buttons, headings |
| `gold-*` | CLET Gold (700 = `#B8960C`) | accent only - active nav, KPI plates, one chart series |
| `teal-*` | Deep Teal (700 = `#0C4A6E`) | interactive: links, focus ring, "view records" |
| `gray-*` | Cloud Grey Neutrals | surfaces, text, borders |
| `success-* / warning-* / error-*` | CLET Success Green / Amber / CLET Red | state only |

**The two rules that keep it consistent.** Status colour uses the **700–800** step for anything read
as text and the **500** step only for dots, icons and chart series - the design system's own 500-step
text aliases measure 2.1:1 to 3.8:1 on white and fail SC 1.4.3. And **CLET Gold is never a text
colour**: gold-700 on white is 2.87:1, so a gold surface takes navy text, not white.

**Shape.** Lato throughout, bold sans titles at 30px. Cards and controls at 8px radius, buttons at
6px. Navy top bar full width; white rail beneath it; `bg-gray-50` page.

**Accessibility, already handled centrally - inherit it, do not re-implement:** one global
`:focus-visible` outline, a skip link, `scroll-padding-top` so the sticky bar cannot hide a focused
element, 24×24 minimum targets, table captions and column scopes, live regions on pagination and
notifications, status conveyed by label as well as colour, and `prefers-reduced-motion`.

---

## 3. The component kit

Everything below is in `src/shared`. A new module composes these; it does not rebuild them.

**Page furniture** - `PageHeader` (title, subtitle, crumbs, actions, meta), `SectionCard` (title,
subtitle, actions, `flush` for edge-to-edge tables), `Tabs`, `KeyValueGrid`, `WorkflowTimeline`,
`Alert`, `BlockerList`, `StatCard`, `StatusChip` (+ `toneFor`), `Icon` (+ `IconName` - add new
glyphs to the registry, never inline SVG).

**Data** - `DataTable` with `Column<T>` and `CellStack`: server-paginated, `caption` for screen
readers, `onRowClick` rendered as a real button in the first cell. `DataState` wraps loading, error,
empty and content in one place.

**Forms** - `FormDialog` (submitting, submitDisabled, formError), `Modal`, `FilterBar`, and the
fields in `fields.tsx`: `TextInput`, `NumberInput`, `TextAreaInput`, `SelectInput`, `EnumSelect`,
`Checkbox`, `FieldShell`. `Select` is a custom ARIA listbox - portalled so a scrolling dialog cannot
clip it, full keyboard support, type-ahead. `SiteSelect` restricts site scope to the actor's own
sites. `DateField` / `DateTimeField` wrap flatpickr and keep string form state.

**Charts** - `AreaChart`, `BarChart`, `DonutChart`, `Sparkline` from `shared/charts/Charts`, with
colours from `shared/charts/palette` (`chartColors`, `seriesColors`, `toneColors`). Never a hex in a
page.

**Plumbing** - `apiClient` (headers, correlation id, idempotency key, envelope parsing),
`useApiQuery`, `useFleetForm` + `validators`, `Notifier` (`notifySuccess` / `notifyInfo` /
`notifyError`), `FleetApiError`, `format.ts`.

`apiClient` also has `postForm` for multipart upload and a standalone `downloadFile` for a file
endpoint - both added by the fuel build; see §9.

---

## 4. Conventions that are not negotiable

These were all learned the hard way during the Fleet build. Each one is a defect that shipped once.

1. **Confirm the contract before building.** Read `/v3/api-docs`, the controllers, the request and
   response records, the domain model and the tests. Never assume an endpoint. If one is missing,
   record it in `docs/fleet/S166_UI_Gap_Report.md` and stop - do not mock it.
2. **Client validation mirrors the service, never replaces it.** Every mutation still submits, and
   `FieldErrorResponse` entries map back onto the field that caused them.
3. **Field errors stay on fields.** Everything else goes through `Notifier` with the service's own
   wording and correlation id. No bare `catch {}` around a mutation.
4. **Submit is disabled while in flight**, and blocked outright where a preview has already said the
   service will refuse.
5. **Every write refetches** the queries it invalidates, including siblings on the same page.
6. **Dialogs mount only while open.** A dialog left mounted reads `initialValues` once, so a reopened
   editor offers the previous record back with the new version and silently reverts the save.
7. **Filters reset to page 0.** Site scope uses `SiteSelect`, never free text.
8. **Nothing invented.** No mock data, no decorative chart, no derived figure presented as if the
   service returned it. Where a panel is derived from fetched records, it says so.
9. **The sidebar shows only what works.** No "coming soon" entries.
10. British English, sentence case, no emoji. Comments explain *why*, not *what*.

---

## 5. Build and serve

The service copies an already-built bundle into `static/ui` at `process-resources`, after clearing
the previous one - Vite emits content-hashed names, so without the clean the directory accumulates
every bundle ever built.

```powershell
cd frontend\sfl-operations-ui
npm run build            # tsc -b --force && vite build
cd ..\..
.\start-fleet.ps1        # add -SkipDb if Postgres is already up, -SkipUiBuild if just built
```

`start-fleet.ps1` always rebuilds unless `-SkipUiBuild` is given, and prints the bundle filename and
build time before starting the service. The account menu in the top bar shows the running bundle's
build timestamp - check it before reporting that a change did not take.

---

## 6. The backend, as it actually is

`sfl-fleet-logistics-service` holds **three** modules under `gh.edu.clet.sfl.fleetlogistics`:

- **`fleet`** - vehicles, drivers, trips, inspections, workflow, compliance, evidence, audit,
  integration. This is what the current UI covers.
- **`fuel`** - driver logbooks, fuel transactions and policies, reconciliation and anomaly cases,
  imports, fuel dashboard, provider integration.
- **`dispatch`** - courier items, manifests, custody and receipts, returns and exceptions, scans.

All three now have React modules. So does **S174**, which is *not* in this service at all:

- **`sfl-safety-security-service`** - port 8092, schema `emergency_notification`, its own
  permission matrix. Templates, scenarios, audiences and zones; the activation workflow with
  approval, break-glass and after-action approval; drills; provider callbacks; outbox health.

**The modules are not built alike, and the difference matters.** `fleet` returns `PageResponse<T>`
from every collection, takes `expectedVersion` on its writes and exposes a transition history per
aggregate. `fuel` did none of that until its backend round closed the gaps; `dispatch` and
`emergency` still do not. Do not assume the fleet contract holds; read the controllers.

**Two services, one dashboard.** Since S174 the API client takes a `service: 'fleet' | 'emergency'`
per call, resolved from `shared/api/config.ts`. Adding a third service is adding an entry to
`serviceOrigins`, `serviceNames` and `servicePorts` - not a second client.

Note for whoever reads the source: the file bridge used during the Fleet build rejects `.java` and
`.sql`, so the live `/v3/api-docs` is the practical way to establish a contract. Start the service
first.

---

## 7. Template prompt for the next module

Replace `<MODULE>` and the screen list; leave the rest.

> Build the **<MODULE>** module in the SFL Operations application - its own dashboard, its own
> navigation group and complete workflows, at the same depth as the Fleet & Vehicle module.
> Branch `feat/<module>-ui`, off main.
>
> **Read `docs/frontend/SFL_Operations_UI_Module_Playbook.md` first.** Everything in it is settled:
> the stack, the structure, the design tokens, the component kit and the conventions. Inherit them.
>
> **Step 1 - establish the contract, then stop.** Start the service and read `/v3/api-docs`, plus the
> controllers, request and response records, domain model and tests. Report back: every endpoint with
> its methods, parameters, page sizes and which are idempotent; what the core record is and how it
> relates to the rest of the domain; the full lifecycle - every state, every transition, who may
> perform each, and what refuses them; what the service computes versus what the operator supplies;
> every validation rule including cross-field and domain rules; and whether an aggregate endpoint
> exists to build the dashboard from. Do not write UI until I have seen that summary. Anything
> missing goes in the gap register - do not mock it.
>
> **Step 2 - the module.** A navigation group holding: a dashboard landing page (KPI cards from
> indicators the service really exposes, analytics beneath, then exception lists an operator can act
> on, every card drilling into the records behind it); the register, server-paginated and filtered;
> the detail screen with its audit trail as a timeline; and any queue the lifecycle implies.
>
> **Step 3 - the workflows.** Every transition the contract supports, end to end, not just the create
> form. Each gets a dialog with the service's required fields, its blockers shown before submission,
> and a confirmation naming what happened.
>
> **Step 4 - finish properly.** Run `npm run build` and give me the result. Then walk every screen,
> dialog and transition in the browser yourself and report what you actually saw - not what you
> expect. List every defect found and whether you fixed it.

---

## 8. Additions the fuel build made to the shared layer

Four, all additive. Nothing in `shared` was forked or changed in behaviour for the fleet module.

**`apiClient.postForm(path, formData, options)`** - multipart upload. The body is passed to `fetch`
untouched and `Content-Type` is deliberately *not* set, because the browser has to write the
multipart boundary into that header itself; setting it by hand produces a request the server cannot
parse. Same actor headers, same correlation id, same envelope parsing as every other call.

**`downloadFile(path, query, fallbackFileName, accept)`** - a file endpoint saved to disk. It is a
standalone export rather than a method on `apiClient` because it returns a filename rather than a
parsed envelope. Two things it exists to get right: a plain link or `window.open` sends none of the
`X-SFL-*` headers, so the request arrives as an anonymous actor and is refused; and `Accept` must
name the media type the endpoint produces or Spring answers 406 before generating anything. It sends
the produced type *plus* `application/json`, so an authorisation refusal - which comes back as an
envelope - is not itself rejected for the wrong content type.

**`RequestOptions.accept`** - overrides `Accept` for the same reason.

**`nonNegativeNumber`, `positiveNumber`, `integerAtLeast(label, floor)`** in `validators.ts`. The
existing `nonNegativeInteger` rejects `20.5`, which is right for an odometer and wrong for a litre
count: fuel quantities, prices and money are `BigDecimal` on the service and a client rule that
refuses a legal decimal is the client inventing a constraint.

Two things stayed **inside** the fuel module rather than being promoted, on the principle that the
kit gains a component when a second module needs it, not in anticipation: `FileField` (the CSV
import is the only upload in the application) and `useClientWindow` (only fuel lacks pagination).
Promote either the moment dispatch needs it.

*Since resolved:* fuel's collections moved to a real paged envelope and it no longer needs
`useClientWindow` at all - but dispatch and emergency both do, so the hook and its warning banner
are now in `shared` (see §10). `FileField` is still fuel's alone.

---

## 9. What the second module learned

These are additions to §4, not replacements. Everything in §4 still holds.

11. **Read the module's own controllers, not the platform's conventions.** The fuel endpoints look
    like the fleet endpoints and behave differently: no `PageResponse`, no `expectedVersion`, no
    per-aggregate history, `siteCode` required rather than optional, and the domain records returned
    raw instead of mapped to response DTOs - so `siteCode` arrives as `{ value }` and `currency` as
    a whole `Currency` object. Assuming the fleet shapes would have produced a module that compiled
    and broke on first contact.
12. **Transcribe the state guards, do not re-derive them.** `modules/fuel/api/workflow.ts` is a
    line-by-line transcription of the `requireState(...)` calls in the domain records, and every
    entry was then verified against the running service. It is what decides which buttons a detail
    screen offers, so a wrong entry means offering an action that can only be refused. Where the
    state-model *document* and the code disagreed, the code won and the document went in the gap
    register.
13. **Show a multi-condition gate as its conditions.** `FuelAnomalyCase.close` refuses with one
    message naming explanation, decision and evidence together. The screen tracks the three
    separately and shows which are actually missing, so the path to closure is legible instead of
    being discovered one refusal at a time. Same for logbook submission's three preconditions.
14. **Say which figures the service published and which this application counted.** The fuel dashboard
    endpoint returns five numbers. Everything else on that page is counted from fetched records, so
    it sits under its own heading - "Counted from the records this dashboard fetched" - with a note
    naming the limit. A derived figure sitting silently in a KPI row is how a dashboard starts
    lying.
15. **An unpaged endpoint needs a truncation warning, and truncation is a fact about the response.**
    Client-side paging over a returned window is fine; presenting it as the register is not. Compute
    `truncated` from what the *service* returned, never from the client-filtered list - a filter
    that cuts a full window down to twelve rows has not made the window less truncated, and reading
    it off the filtered list switches the warning off exactly when it is most needed.
16. **Offer real references, not identifier fields.** Fuel requests carry bare UUIDs the operator has
    never seen. `FleetReferenceSelect` selects out of the fleet registers for the same site, so the
    dashboard cannot offer a reference the service will refuse. Clear the dependent selects when the
    site changes.
17. **Verify against the running service with the headers the client really sends.** Two defects
    were found this way and by no other means: the CSV report answering 406 to
    `Accept: application/json`, and the audit search returning 500 on every call. Neither is visible
    from the source, and a build that passed `tsc` would have shipped both.

---

## 10. Additions the third and fourth modules made to the shared layer

Still additive. Nothing in `shared` has been forked or had its behaviour changed for an earlier
module.

**`SflService` and per-call service routing in `client.ts`.** `apiClient.get/post/patch` and
`downloadFile` take `service: 'fleet' | 'emergency'`, resolved through `serviceOrigins`. A **name**
rather than a raw base URL, so a module cannot quietly point at something that is not an SFL
service, and so the transport error can say *"Could not reach the Emergency Notification service at
… port 8092"* instead of always naming the fleet service. Default is `fleet`, so the three modules
that predate this changed nothing.

**`emergencyApiBaseUrl` in `config.ts`**, from `VITE_EMERGENCY_API_BASE_URL`. Note that
`.env.production` cannot leave it empty the way the fleet one is: an empty base means same origin,
and the bundle is served by the *fleet* service.

**`shared/hooks/useClientWindow.ts` and `shared/components/WindowNotice.tsx`.** Promoted out of
dispatch when emergency turned out to need the same thing for the same reason. `modules/dispatch`
keeps two thin files that bind the module's own default window size and name its own service, so
eleven call sites were untouched - that is the shape to copy when promoting anything else.

**Five icons** - `megaphone`, `siren`, `zap`, `users`, `target` - and the S174 status tones in
`StatusChip`, all appended.

One thing was deliberately **not** put in the shared table: `ACTIVE`. It is already mapped to
`ready`, which is right for a vehicle, a driver and a master-data record, and wrong for an
activation where it means a live emergency broadcast is out. `modules/emergency/api/workflow.ts`
declares `activationTone` for the whole activation enum instead, and `ActivationStatusChip` is the
only chip that module uses for a status. **Where a shared reading is wrong for one module, state
that module's reading locally - do not change the shared one out from under three others.**

---

## 11. What the third and fourth modules learned

Additions to §4 and §9. Everything there still holds.

18. **Searching one package is not searching the service.** The S166 review concluded no driver
    logbook system of record existed. `DriverLogbook` is in `gh.edu.clet.sfl.fleetlogistics.fuel` -
    same service, different package. One package read as the whole service produced a gap register
    entry that was simply wrong. Enumerate the service's packages before concluding something is
    absent, and when a module lives in a *different service* entirely (S174 does), say so where
    somebody will trip over it.
19. **Two services means two `RecordMetadata`s.** Fleet-logistics names the correlation field
    `auditCorrelationId`; emergency names it `correlationId`, and its fields are non-null where
    fleet's are optional. Re-using the fuel type type-checked and would have rendered an empty
    correlation id on every screen. Re-use a wire type across services only after diffing the two
    records - `SiteCode` really is identical, the provenance block really is not.
20. **Preview what the service will decide, never send it.** Dispatch derives the receipt outcome,
    the return outcome and `chainOfCustodyRequired`; emergency derives the delivery and
    acknowledgement summaries at closure and the break-glass eligibility verdict. Each dialog runs
    the same comparison the service will and names the result *before* submission, as a consequence
    of what was entered - with no field to override it. The operator learns what is about to happen
    rather than what happened.
21. **An irreversible action with no second approver earns deliberate friction.** Break-glass is the
    only send in the dashboard with no approver, no draft and no recall. Its confirmation states the
    reach, the channel count, the message text and the obligation it leaves behind, and requires the
    word BROADCAST to be typed. That is not ceremony - it is the difference between meaning it and
    clicking a red button on a page reached in a hurry. Nothing else in four modules needs this.
22. **Group registers by the question they answer, not by the aggregate count.** Dispatch put
    custody, receipts and returns on the manifest detail because each belongs to one consignment and
    that placement is what lets the closure blockers be stated in one place. Emergency paired
    templates with scenarios, and audiences with zones, because each pair is chosen together on
    every activation. Eleven aggregates, nine screens - a sidebar entry per aggregate would have
    made an operator cross-reference by hand what belongs side by side.
23. **Name what the absence of a read costs.** S174 publishes no inbound integration health at all,
    and its inbound feed is the only thing that ever writes `delivered`, `failed` and
    `acknowledged`. So a screen showing 480 sent and 0 delivered cannot distinguish "no provider
    configured" from "every callback is being rejected". The integration screen says exactly that,
    on the screen, rather than leaving an empty panel and a gap-register entry nobody reads.
24. **Check what the domain can do against what the API exposes.** `NotificationActivation`
    implements `cancel`, `escalate`, `reopen` and `withDegradedFallback`. No controller calls any of
    them. Four statuses are therefore unreachable and one is set by nothing at all - which the
    filter still has to be able to find, because a stored record may hold it. Read the domain record
    and the controller side by side; the gap between them is a gap register entry every time.

---

## 12. Which portal a module belongs in

Phase 1 is **13 systems under 4 programme modules, delivered as 5 services.** Those three counts do not
line up, and the mapping is in
[`docs/architecture/microservices-realignment.md`](../architecture/microservices-realignment.md) and
[ADR 0005](../adr/0005-programme-scoped-portals-and-navigation-entitlement.md). Read one of them before
starting a module.

**The rule.** A user sees the programmes they are entitled to, and within a programme, the systems their
roles grant. A driver or a head of fleet sees fleet, fuel and dispatch; they do **not** see CCTV access
management, intrusion detection or visitor badges. A manager or superadmin sees everything - that
exception is what makes the rule worth having.

**Two corollaries that are easy to get wrong.**

- **Programme membership is a property of the system, not of the service it ships in.** S174 is its own
  deployable (ADR 0004, for availability and blast radius) and it is still SSEMP everywhere a user can
  see. Do not let a deployment decision become a navigation decision.
- **Deployment topology must not be inferable from a sidebar.** Three systems in one service appear as
  three systems; two services in one programme appear as one programme.

**How it works.** `shared/layout/programmeModel.ts` maps role → programme and imports nothing, so the
decision can be checked directly. `programmes.ts` reads the actor's roles and exposes `entitledTo`.
Every `NavSection` declares a `programme`; the sidebar renders only entitled sections,
`RequireEntitlement` refuses the routes of an unentitled programme **or system**, and the landing route is the actor's
**first entitled destination** rather than the fleet dashboard.

Entitlement is **derived from roles**, not from a second list, because that is what IAM will do - a
role already implies a programme. Drop `EMERGENCY_COORDINATOR` and `COMMAND_ROLE` from
`VITE_SFL_ROLES` and the emergency section disappears; that is the fleet-operator view.

**IAM is not integrated.** No centralised auth, no Zitadel. Roles arrive in `X-SFL-*` headers the
client sets for itself, so this is a **usability** control and not a security control - and it would
stay one even with IAM, because a hidden link protects nothing. Every service authorises every call
independently. **Build as though the nav filter does not exist and the service is the enforcement
point, because that is the truth.**

**What to do when you add the next module.** Declare its programme on the section, and wrap its route
subtree in `RequireEntitlement`. Two lines, and they are the same two lines whether the portal is later
split per programme or left as one bundle.


---

## The portal pattern (added 31 July 2026)

A **portal** is a landing that answers *what do I have to do today*, where a module answers *what is
the state of the estate*. Same services, same shell, same components - a different first paragraph.

Build one when a role holds real permissions and the module built for its system was designed for
somebody else. Six exist, under `/me/`, in `src/modules/me/pages`.

**Three rules, and the second is the one that is easy to get wrong.**

1. **Compose, do not fork.** A portal is `PageHeader` + `DataState` + `DataTable` over an existing
   API client. If it needs a component the modules do not have, add it to `shared/components` rather
   than to the portal - a second design system starts with one exception.

2. **Gate on a persona only when a permission cannot do the job, and never for anything else.**
   Every operational screen stays permission-gated, because a screen should be offered exactly when
   the service will answer it. The exception exists because `FLEET_DRIVER`'s eight permissions are
   all held by `FLEET_MANAGER` too, so no permission distinguishes them. `shared/layout/personas.ts`
   encodes the *narrowest-role* rule the services already enforce - it does not invent one - and it
   is not an authorisation check: nothing there hides data.

3. **A portal may only claim what the service enforces.** If the backend narrows per record, say
   "mine". If it does not, say where the data actually comes from - on the page, not in a report.
   The centre-manager portal carries an `Alert` saying it cannot narrow to a centre, because a user
   who assumes otherwise would be wrong and no screen should let somebody be wrong quietly.

**Placement.** Personal sections go first in `navSections`, because `landingPath()` returns the first
item of the first entitled section. That ordering is the whole mechanism - no router change, no shell
change, and operators are unaffected because every personal item is persona-gated.
