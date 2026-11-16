# SFL UAT deployment handoff

Prepared for Infra from the Repo Deployment Readiness Checklist dated 3 October 2026.

## Scope

SFL is a restricted workforce operations portal for permanent staff, temporary personnel, approved contractors and approved vendor users. It is deployed as four images from this repository:

| System | Image | Container port | Purpose |
| --- | --- | ---: | --- |
| Portal | `ghcr.io/official-clet-plan-b/sfl-portal-service:<release-tag>` | 8080 | Serves the compiled React dashboard at `/home/` |
| Facilities | `ghcr.io/official-clet-plan-b/sfl-facilities-service:<release-tag>` | 8080 | S152, S153, S156-S159, S169, S173, S176-S179 APIs |
| Safety & Security | `ghcr.io/official-clet-plan-b/sfl-safety-security-service:<release-tag>` | 8080 | S160-S165 and S174-S175 APIs |
| Fleet & Logistics | `ghcr.io/official-clet-plan-b/sfl-fleet-logistics-service:<release-tag>` | 8080 | S166, S167, S168, S168a, AVAMP and S171 APIs |

All four images are built for `linux/amd64`. The release workflow publishes them only from a `v*.*.*` tag; manual workflow runs are for non-production image verification.

## Handoff table

| Item | Answer |
| --- | --- |
| System code and name | Cluster 9 SFL Operations Portal; 30 operational systems |
| Repo(s) and image each builds | This repository; four images listed above |
| Release tag to deploy | Infra selects an immutable `vX.Y.Z` tag from the GitHub Packages list |
| Public hostname(s) | Restricted workforce UAT hostname to be supplied by Infra; portal path is `/home/` |
| Access model | Restricted workforce application behind the organisation's TLS and identity gate: permanent staff, temporary/seconded personnel, approved contractors and approved vendor users may be onboarded as named IAM users. It is not public-facing or anonymous. |
| Port the web process listens on | 8080 in each container; portal and APIs are separate services |
| Processes and start commands | One Spring Boot web process per image: `java -jar app.jar`. Spring scheduled jobs and outbox drainers currently run inside their owning service process; there are no separate worker, websocket or beat images. Infra should run one replica per service until scheduler leadership/locking is confirmed. |
| Database type and existing data | PostgreSQL 16; separate database per Facilities, Safety & Security and Fleet & Logistics service. Fresh UAT is expected to be empty unless Infra imports an existing snapshot. |
| PostgreSQL extensions | None required by the application migrations; Flyway creates the service schemas and tables. |
| Redis / cache | Not required by the application runtime. Redis is not included in the current UAT Compose topology. |
| Object storage | No S3 adapter is currently wired. Fleet evidence uploads are stored in PostgreSQL `fleet_logistics.fleet_evidence_files.content` (`BYTEA`) and must therefore be included in the Fleet PostgreSQL backup/retention plan. Moving bytes to the approved S3-compatible store is a follow-up before high-volume production use. |
| Paths that must persist on disk | None. Logs go to stdout/stderr and no uploads are written to container-local disk. Uploaded evidence currently persists in PostgreSQL, not the filesystem. |
| Environment variables | `deploy/env/.env.example` lists service, database, broker, IAM, scheduler, feature and tuning variables. `frontend/sfl-operations-ui/.env.example` lists public build-time `VITE_*` values. Secrets are marked in comments. |
| First admin user | Infra provisions the Keycloak realm admin and named application users through the approved UAT IAM procedure. The committed `deploy/idp/sfl-realm.json` is a local bootstrap/demo seed only; do not reuse its example passwords or service secret in UAT. Zitadel remains available only through the optional `zitadel` Compose profile. |
| Expected memory | Start with 768 MiB limit per stateful API service and 384 MiB for the portal; observe `docker stats`/cluster metrics during UAT and tune from evidence. |
| External services | Keycloak/OIDC for workforce identity; PostgreSQL; RabbitMQ for event transport; approved object storage is a future evidence-storage target. Zitadel is retained as a secondary provider option. Vendor integrations and SMS/email providers remain adapter-based and are not required for the first UI/API smoke test. |
| Technical contact | CLET SFL application team; Infra adds the named on-call contact to the deployment tracker. |

## Repository readiness actions

1. **Container images:** four service Dockerfiles build from the repository root with multi-stage Maven builds, non-root runtime users, mirror-based base images and no local runtime files.
2. **Configuration and secrets:** runtime variables are documented in `deploy/env/.env.example`; frontend build-time variables are documented separately; UAT must use OIDC and `SFL_SECURITY_ENABLED=true`.
3. **Database and migrations:** services use PostgreSQL and Flyway migrations. Startup migrations are safe to re-run; no application upload bytes are stored on pod-local disk. The current fleet evidence store is PostgreSQL `BYTEA`, so Infra must include that database data in UAT backups.
4. **Health and runtime:** Spring Actuator health/readiness endpoints are exposed without login and include the database health indicator. Logs are emitted to stdout/stderr. The portal health check is `/api/v1/system/info`.
5. **Build pipeline:** `.github/workflows/build-docker-image.yml` publishes all four `linux/amd64` images to GHCR on immutable `v*.*.*` tags and supports manual test builds. It requires the repository `GHCR_TOKEN` secret.
6. **Infra handoff:** this document supplies the service/image topology, ports, dependencies, persistence rules, runtime processes and configuration references needed for the UAT deployment record.

## Infra values and decisions required before UAT

Please return these values in the deployment record. They are intentionally not committed to this repository:

1. **Release:** the exact immutable image tag from GHCR, cut from `main`; do not reuse tags.
2. **Ingress:** the UAT portal hostname, TLS certificate/termination policy, and the public API routing or same-origin gateway paths. The portal is served at `/home/`.
3. **Keycloak:** the UAT issuer URL for the `sfl` realm, client ID, redirect/logout URIs for the final hostname, allowed web origins, admin bootstrap owner, MFA/SSO policy, and user-provisioning process. The application expects roles in `realm_access.roles` and site scopes in `site_scopes` (list or comma-separated value).
4. **Users and access:** named accounts for staff, temporary/seconded personnel, approved contractors and approved vendors; role, programme and site scope for each; expiry/deprovisioning process. Shared accounts are not supported.
5. **PostgreSQL:** connection details for three databases (`sfl_facilities_service`, `sfl_safety_security_service`, `sfl__fleet_vehicle_service`), credentials, schema ownership, migration permissions and backup/restore retention. No PostgreSQL extensions are required.
6. **RabbitMQ:** broker host/port, vhost, username/password, TLS requirements, and the durable exchange/routing configuration for `sfl.events`. RabbitMQ is required by the current event/outbox transport; Redis is not.
7. **Secrets:** populate all secret values from the Infra secret store, including database credentials, RabbitMQ credentials, IAM configuration and vendor simulator/signature secrets. Do not copy values from the committed examples.
8. **Runtime policy:** one replica per backend service while in-process scheduled jobs and outbox drainers are enabled, or an approved scheduler-leadership/locking arrangement before scaling horizontally.
9. **Storage decision:** confirm the UAT backup treatment for Fleet evidence in PostgreSQL `fleet_logistics.fleet_evidence_files.content` (`BYTEA`) and the plan/timeline for moving high-volume evidence to approved S3-compatible object storage.
10. **Operations:** CPU/memory limits, log aggregation, metrics/alert destinations, database/RabbitMQ alerting, and the named Infra/application on-call contacts.

## Answers to the deployment questions

- **Is Redis required?** No. It is not used by the application runtime and is not part of the current Compose stack.
- **Is Kafka required?** No. The current implementation uses RabbitMQ for event transport. Kafka would require a separate adapter, topic/consumer configuration and an approved broker before it can replace RabbitMQ.
- **Are test/E2E databases required for UAT?** No. Test and E2E databases are CI/test resources only. UAT needs the three application PostgreSQL databases and Keycloak's database.
- **Does the application need local persistent disk?** No. Logs use stdout/stderr and uploaded Fleet evidence is currently stored in PostgreSQL. PostgreSQL and RabbitMQ must use durable managed storage.
- **Does the application run separate workers?** No. The deployables are four web images. Scheduled jobs, event listeners and outbox drainers run inside their owning Spring Boot process; keep one replica per service unless Infra approves coordination.
- **Does the application require a vendor integration before the first smoke test?** No. Vendor and SMS/email integrations are adapter-based and can remain configured as simulators or disabled for the first UAT pass.

## UAT conditions to acknowledge

- Configure exactly one active scheduler replica for each service, or obtain approval for the current in-process scheduler model before scaling replicas.
- Treat the Fleet PostgreSQL evidence table as persistent business data. Do not deploy it with an ephemeral database volume.
- Confirm the approved object-storage migration plan before production; it is not silently represented as complete by this handoff.
- Onboard contractors and vendors as named, time-bounded IAM users with only the role, programme and site scope required for their assignment. Do not issue shared contractor accounts.

## Release procedure

1. Merge the approved code into `main`.
2. Create a new, never-before-used tag, for example `v1.0.0`.
3. Push the tag and wait for **Build and publish UAT images** to finish.
4. Confirm the four images appear in GitHub Packages.
5. Give Infra the exact tag and this handoff document. Infra changes the deployment configuration; this repository does not SSH to hosts or run `docker run` remotely.

## UAT smoke checks

```text
GET https://<uat-host>/home/
GET https://<service-host>/actuator/health
GET https://<uat-host>/api/v1/system/info
```

The health endpoint must return HTTP 200 after the database and broker dependencies are ready. Complete one authenticated login, one read on each platform, one create/update workflow, one evidence-reference workflow and one event/outbox check before accepting UAT.
