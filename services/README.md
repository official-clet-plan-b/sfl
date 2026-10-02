# SFL backend services

Java 17 · Spring Boot 4.1 · Maven multi-module. **Three deployables** - one per platform - plus a
shared kernel.

| Module                        | Platform | Systems                          | Schemas                                 | Port   |
| ----------------------------- | -------- | -------------------------------- | --------------------------------------- | ------ |
| `sfl-facilities-service`      | IFIMP    | S152, S153, S159                 | `facilities`                            | `8091` |
| `sfl-safety-security-service` | SSEMP    | S160, S160a, S161, S162, S162a, S163, S174; Phase 2 S165 | `safety_security`, `emergency_notification` | `8092` |
| `sfl-fleet-logistics-service` | FTLMP    | S166, S168_fuel, S171, AVAMP-Lite | `fleet_logistics`, `asset_visibility`   | `8093` |
| `sfl-service-common`          | -        | Shared kernel                    | -                                       | -      |

SSEMP now implements all seven of its systems: S160 Visitor Management, S163 HSE Incident/Near-Miss
Reporting, S174 Emergency Notification, and the four Buy-and-Integrate systems under
`docs/planning/phase-1-system-classification.md` - S160a Physical Access Control Integration, S161
CCTV/VMS Integration, S162 Intrusion Detection & Alarm Monitoring and S162a Fire & Life-Safety
Monitoring. For each of those four, SFL owns the workflow/audit/dashboard layer; the certified vendor
hardware itself is procured separately and is not part of this codebase.
`sfl-fleet-logistics-service` additionally packages and serves the React dashboard at `/ui`.

### Why three, and why five schemas

S174 and AVAMP-Lite were separate deployables (`8095` and `8094`) until 5 August 2026. Consolidating
to three aligns the deployables with the three F&L units the system mapping actually names -
Building & Infrastructure, Health Safety & Security, Transportation & Logistics - and with the SRS's
own module boundaries.

**The schemas did not merge, and must not.** A deployable is a unit of release; a schema is a unit of
ownership, and the SRS makes the second one binding: schema per module (§2.5), no cross-schema
foreign keys (§2.6), architecture tests that enforce it (§23.8). So `emergency_notification` and
`asset_visibility` survive intact inside their host service's database, and no fleet, fuel, dispatch
or safety table may carry a foreign key across that line.

Two properties made this a move rather than a rewrite, and both must be preserved:

- **S174 is plain JDBC with every statement schema-qualified**, so it never depended on Hibernate's
  `default_schema`.
- **AVAMP's entities carry `@Table(schema = "asset_visibility")` explicitly**, so they resolve
  correctly under a host whose default is `fleet_logistics`.

Anything added to either schema must do the same. Relying on the default would place the table in the
host's schema, and `ddl-auto: validate` would then fail at startup rather than at review.

`sfl-service-common` is the shared kernel: the actor principal, RBAC, and the error and event
envelopes. It is a library, not a service.

## Databases

Each deployable owns a separate PostgreSQL database boundary - a runtime database and a separate
end-to-end one, as local Docker containers.

| Service                       | Runtime database              | Port   | E2E database                      | Port    |
| ----------------------------- | ----------------------------- | -----: | --------------------------------- | ------: |
| `sfl-facilities-service`      | `sfl_facilities_service`      | `5441` | `sfl_facilities_service_e2e`      | `55441` |
| `sfl-safety-security-service` | `sfl_safety_security_service` | `5442` | `sfl_safety_security_service_e2e` | `55442` |
| `sfl-fleet-logistics-service` | `sfl__fleet_vehicle_service`  | `5443` | `sfl__fleet_vehicle_service_e2e`  | `55443` |

`5444`/`5445` and `55444`/`55445` are retired along with their deployables. A local volume from an
earlier checkout will still be on disk; `docker volume rm sfl_asset_visibility_postgres_data
sfl_emergency_notification_postgres_data` removes them once you are sure nothing there is wanted.

Start them all:

```powershell
docker compose -f compose.service-dbs.yml up -d
```

For single-service work, use `compose.facilities-db.yml`, `compose.safety-security-db.yml` or
`compose.fleet-db.yml`.

## Build and test

From `services/`, with `JAVA_HOME` pointing at JDK 17:

```powershell
..\mvnw.cmd test                                              # the whole reactor
..\mvnw.cmd -pl sfl-facilities-service test                   # one service
..\mvnw.cmd -pl sfl-service-common,sfl-facilities-service install
..\mvnw.cmd -pl sfl-fleet-logistics-service -am spring-boot:run
```

`-Pui` on the fleet service builds the dashboard with a project-local Node install before starting.
Without it, Maven copies whatever is already in `frontend/sfl-operations-ui/dist`; if nothing has
been built, the service starts normally and logs how to build it.

**Two things about running the tests.**

Testcontainers is skipped in the Windows development environment - the Java Docker client cannot
reach the named pipe - so the end-to-end suites report as skipped rather than failing. A plain
`mvnw test` therefore goes green while whole suites never ran. CI fails the build if anything
skipped; see the root README for the three environment variables that enable them locally.

Unit tests passing is not the same as the service starting. 135 unit tests were green while
`sfl-facilities-service` could not boot at all; dropping the schema and running against real
PostgreSQL found eight defects in minutes. Run it against a real database before calling it done.

## Package layout

Every service follows the same shape:

```
gh.edu.clet.sfl.<service>
├── shared/          the platform each module in this service inherits
│   ├── api/         actor resolution, correlation ID, the error envelope
│   ├── application/ authorisation, governance, the outbox and the ports
│   ├── domain/      audit chain, error codes, record metadata, permission matrix
│   └── infrastructure/persistence/
└── <module>/        one package per system, each with api / application / domain / infrastructure
```

`shared` is the platform, not a junk drawer. Audit, idempotency, runtime configuration, actor
resolution, the error envelope and the permission matrix all live there so later modules inherit
them rather than rebuilding them.

The dependency rule, enforced by an ArchUnit test in each service: `api → application → domain`,
`infrastructure → application/domain`, nothing points into `infrastructure`, and the domain imports
no framework.

## API conventions

- Paths are `/api/v1/<domain>/<resource>`.
- Every response uses the `{data, error}` envelope.
- `Idempotency-Key` is honoured on state-creating POSTs only.
- `X-Correlation-ID` flows end to end, and is **exposed** as a CORS response header - without that
  the browser client reads `null` cross-origin and every error loses the one identifier tying it to
  a service log.
- Error codes carry the SRS's own wording for SRS-defined codes.

There is no authentication locally: `sfl.security.enabled=false` and the actor is asserted through
`X-SFL-*` headers, which Swagger UI declares globally so they can be filled in once. Authorisation
is enforced correctly against whatever actor it is given - but identity is not yet verified. In
production the same actor context is derived from the OIDC/JWT principal and the headers are
ignored.

## Migrations

Flyway, with `ddl-auto: validate`. Two rules:

- Use `VARCHAR(n)` with a length `CHECK`, never `CHAR(n)` - Hibernate rejects the latter at schema
  validation, and the failure does not name the column.
- Alter and backfill rather than drop and recreate.

## Boundary rules

- A service owns its own schemas only, and each module inside it owns exactly one. No cross-schema
  foreign keys in either direction - not between services, and not between two schemas that happen
  to share a database. Cross-context identifiers are held by value.
- Cross-service changes are published through the service outbox and delivered by that service's
  drainer. `safety_security` gained its drainer in Phase 2 (ADR 0010); until then SSEMP recorded every
  event and delivered none.
- External events are consumed idempotently through the service inbox.
- Vendor payloads go through adapters. No vendor model reaches a domain package.
- Store evidence references and hashes only - never large files or CCTV video.
