# GSL Cluster 9 - Safety, Facilities & Logistics

The Safety, Facilities & Logistics (SFL) platform for GSL, a directorate under CLET.
Phase 1 covers thirteen Fast-Track systems across four programmes:

| Programme  | What it covers                                   |
| ---------- | ------------------------------------------------ |
| **IFIMP**  | Integrated Facilities & Infrastructure Management |
| **SSEMP**  | Safety, Security & Emergency Management          |
| **FTLMP**  | Fleet, Transport & Logistics Management          |
| **AVAMP**  | Asset Visibility & Asset Management (Lite)       |

**Three deployable Spring Boot services - one per platform - and one React dashboard.** Each service
owns its own schemas, its own migrations and its own API boundary; services talk through APIs and
events, never through each other's tables.

AVAMP is not a fourth service. It is the asset and device reference layer supporting all thirteen
systems, and S168 Asset Tagging is Phase 2 in the parent mapping, so it ships as a schema inside
FTLMP rather than as a deployable of its own.

## Release 1 scope

The repository now contains **all thirteen Phase 1 Fast-Track systems**.

**Built and demoable**

| System | What                                          |
| ------ | --------------------------------------------- |
| S152   | Facility management - CAFM / IWMS              |
| S153   | Maintenance management - CMMS                  |
| S159   | Room & resource booking                        |
| S160   | Visitor management                             |
| S160a  | Physical access control integration            |
| S161   | CCTV / video management integration            |
| S162   | Intrusion detection & alarm monitoring         |
| S162a  | Fire & life-safety monitoring                  |
| S163   | HSE incident and near-miss                     |
| S166   | Fleet & vehicle management                     |
| S168   | Fuel management & driver logbooks              |
| S171   | Mailroom, courier & dispatch                   |
| S174   | Emergency mass notification                    |

S160a, S161, S162 and S162a are Buy-and-Integrate systems: SFL owns the workflow, audit and dashboard
layer over a recorded/simulated vendor gateway; the certified vendor hardware (door readers, cameras,
alarm panels, fire panels) is procured separately and is not part of this codebase. S162a in
particular has no outbound command port at all - it observes, records and notifies only, by design.

S174 uses a recorded outbound adapter in this build. Real notification delivery is deferred to the
integration with the external Comms system. The same is true of every vendor integration point across
S160a/S161/S162/S168/S171: the workflow is built and tested end to end, and connecting to a real
vendor is a procurement and configuration step, not further development.

## Layout

```
services/                            Java 17 · Spring Boot 4.1 · Maven multi-module
  sfl-facilities-service             IFIMP  - S152, S153, S159                    :8091
                                     schema: facilities
  sfl-safety-security-service        SSEMP  - S160, S160a, S161, S162, S162a,      :8092
                                     S163, S174
                                     schemas: safety_security, emergency_notification
  sfl-fleet-logistics-service        FTLMP  - S166, S168, S171, AVAMP; serves /ui :8093
                                     schemas: fleet_logistics, asset_visibility
  sfl-service-common                 Shared kernel - principal, RBAC, error and event envelopes
frontend/sfl-operations-ui           React 19 · TypeScript · Vite - the only user interface
scripts/                             Local development helpers
deploy/                              Deployment assets
```

S174 is a separate deployable rather than a module of another service because emergency
notification needs independent availability, callback, retry, degraded-mode and blast-radius
behaviour.

## Quick start

One command starts the databases, builds the dashboard and runs the Fleet service, which then
serves the API, Swagger and the dashboard together on port 8093:

```powershell
.\start-fleet.ps1
```

| URL                                     | What                 |
| --------------------------------------- | -------------------- |
| <http://localhost:8093/ui/>             | Operations dashboard |
| <http://localhost:8093/swagger-ui.html> | Swagger UI           |
| <http://localhost:8093/v3/api-docs>     | OpenAPI JSON         |

`http://localhost:8093/` redirects to the dashboard. There is no sign-in step locally
(`sfl.security.enabled=false`); the dashboard sends `X-SFL-*` actor headers instead.

Useful switches:

```powershell
.\start-fleet.ps1 -RebuildUi      # rebuild the dashboard after front-end changes
.\start-fleet.ps1 -SkipDb         # databases already running
.\start-fleet.ps1 -SkipUiBuild    # API only
.\start-fleet.ps1 -NoBrowser      # don't open browser tabs
```

For front-end work with hot reload, `.\scripts\dev\run-fleet-dev.ps1` runs the service on 8093 and
Vite on 5005.

## Feature flags

Each system can be switched on or off per deployment, at runtime - no rebuild of the images or the
dashboard. Set these on the backend services (the same values on all three is fine; each service only
reads the codes it hosts):

| Variable | Meaning |
| --- | --- |
| `SFL_FEATURES_ENABLED_SYSTEMS` | Allow-list, e.g. `S166,S167,S168,S168a`. Blank = every system on. |
| `SFL_FEATURES_DISABLED_SYSTEMS` | Deny-list applied after the allow-list, e.g. `S171`. |

A switched-off system's API answers `404 SFL_SYSTEM_DISABLED`, and the dashboard hides its menu
entries and pages. The dashboard learns what is on from `GET /api/v1/{fleet|facilities|emergency}/features`
when it loads. A system that another switched-on system depends on keeps its API reachable (fuel reads
the vehicle register; every facilities module reads the estate) but its own screens stay hidden. The
system lists live in `FleetLogisticsFeatures`, `FacilitiesFeatures` and `SafetySecurityFeatures`.

## Build and test

```powershell
docker compose -f compose.service-dbs.yml up -d
cd services
..\mvnw.cmd test
```

Details for each half are in [`services/README.md`](services/README.md) and
[`frontend/sfl-operations-ui/README.md`](frontend/sfl-operations-ui/README.md).

## Environment

Three things that have each cost somebody an afternoon.

**Java 17, and the machine default may not be it.** The reactor needs 17+. This machine's `PATH`
has carried `C:\Program Files\Zulu\zulu-11\bin`, and Maven picks that up silently and fails with
errors that never mention the JDK:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
```

**A plain `mvnw test` skips the end-to-end suites and still reports green.** The suites are gated on
three environment variables, and CI fails the build if anything skipped. To reproduce CI locally,
start the databases and set:

```powershell
$env:SFL_FACILITIES_TEST_DB_URL = 'jdbc:postgresql://localhost:55441/sfl_facilities_migration_test'
$env:SFL_FLEET_LOGISTICS_TEST_DB_URL = 'jdbc:postgresql://localhost:55443/sfl__fleet_vehicle_service_e2e'
$env:SFL_SAFETY_SECURITY_TEST_DB_URL = 'jdbc:postgresql://localhost:55442/sfl_safety_security_service_e2e'
```

The facilities one points at a **dedicated, empty** database on purpose: the migration suite asserts
a genesis audit hash of all zeros, so running it against a database with any history fails with four
errors that look like defects and are residue. Drop and recreate it if that happens.

**The "Invalid VCS root mapping" warning on project open.** `.idea/vcs.xml` acquires a Git root
mapping for `frontend/sfl-operations-ui`, which is neither a repository nor a submodule - it is a
directory inside this one. Remove the second `<mapping>` line, leaving only the project-root
mapping. `.idea/` is gitignored, so this recurs for everyone who opens the project.

## Build vs buy

Build CLET-owned workflow, governance, records, APIs, dashboards, audit and evidence references, and
integration adapters.

Buy or integrate specialist platforms and hardware: CCTV/VMS, access-control devices, biometric
readers, fire and life-safety panels, intrusion panels, SMS/voice/signage providers, fuel provider
feeds, GPS/telematics, and RFID/barcode devices.

Shared ecosystem services - IAM, API gateway, notification, audit and evidence, integration gateway
and event broker, reporting, document and object storage - are integrated through adapters and are
not duplicated inside SFL services.

## Commit conventions

**Do not add AI-attribution trailers to anything.** No `Co-Authored-By: Claude …`, no
`🤖 Generated with [Claude Code]`, no equivalent line in a commit message, a PR body, or a
changelog entry. This applies whatever the default instruction says - the repository's convention
wins.

History was rewritten on 31 July 2026 to strip 21 such trailers from 21 commits across every
branch. Re-adding them means doing that again, and a second rewrite invalidates every commit SHA a
second time.

Commits are authored by the person running the session. Nothing else is attributed.

## A note on documentation

The SRS, the architecture decision records, the per-system design notes, gap reports and runbooks
are maintained outside this repository. The requirement source of truth is the Cluster 9 SFL Phase 1
SRS; the parent CLET digital system mapping remains authoritative for system IDs, Cluster 9 scope
and phase classification.
