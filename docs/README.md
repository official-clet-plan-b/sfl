# Documentation map

Start here. `solution.md` at the repository root is the current status - read that first for "what's
built." This folder is the supporting detail underneath it.

## Starting a new feature or system

Read [`development/building-a-new-backend-module.md`](development/building-a-new-backend-module.md)
first, every time. It's the checklist for finding the requirement, picking a template module, wiring
roles and permissions into the shared union points, and verifying the build correctly - written from
what actually went wrong building the last four modules.

## Per-system documentation

Each built system that has its own detailed doc suite (API inventory, domain model, event contracts,
gap report, migration plan, operations guide, requirement traceability, test plan) gets one folder,
named after the platform:

| Folder | Systems |
|---|---|
| [`facilities/`](facilities/) | S152 CAFM/IWMS, S153 CMMS, S159 Room & Resource Booking |
| [`fleet/`](fleet/) | S166 Fleet & Vehicle Management |
| [`fuel/`](fuel/) | S168 Fuel Management & Driver Logbooks |
| [`dispatch/`](dispatch/) | S171 Mailroom, Courier & Dispatch |
| [`emergency/`](emergency/) | S174 Emergency Mass Notification |
| [`hse/`](hse/) | S165 Risk Assessment Library (Phase 2 SSEMP) - API reference, event contracts, gap and UI gap reports |

S160 Visitor Management, S163 HSE Incident/Near-Miss, S160a Access Control, S161 CCTV/VMS, S162
Intrusion Detection and S162a Fire & Life-Safety are built (see `solution.md`) but don't yet have a
doc suite in this style - their code is the current reference until one is written.

## Cross-cutting references

| Folder | What |
|---|---|
| [`planning/`](planning/) | The Build/Buy/Hybrid classification decision, and the Phase 1 go-live readiness pack (a 1 August 2026 snapshot - read its status banner before trusting anything in it) |
| [`srs/`](srs/) | The SRS itself (the requirements contract) and the system architecture implementation guide |
| [`adr/`](adr/) | Architecture decision records |
| [`architecture/`](architecture/) | Evergreen architecture guides (clean architecture, microservices realignment) |
| [`integration/`](integration/) | The event catalog, vendor adapter guide, vendor procurement checklist |
| [`frontend/`](frontend/) | Operations UI module playbook, role/portal trace and gap reports, sign-in and seeded accounts |
| [`development/`](development/) | Migrations, running the stack locally, building a new backend module |
| [`runbooks/`](runbooks/) | Operational runbooks (backup/restore, incident response, disaster recovery, dead-letter recovery) |
| [`status-updates/`](status-updates/) | Leadership-facing status update deliverables (SFL's own, plus two sibling-directorate samples used as formatting references) |
| [`ROLE_MATRIX_DRAFT.md`](ROLE_MATRIX_DRAFT.md) | Generated - do not hand-edit. Regenerate per `development/building-a-new-backend-module.md`. Stays directly under `docs/`; three test files hardcode this exact path. |

## Pending deletion

[`to-delete/`](to-delete/) holds stale, superseded documents - point-in-time architecture/security
reviews from before the current builds, and the old Release 1 cleanup archive. See its own README for
why each item is there. Nothing in this repository references anything under `to-delete/`; it's safe
to delete the whole folder once you've confirmed you agree with what's in it.
