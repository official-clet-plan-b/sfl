# SFL operational runbooks

Written from what the code does, not from what an operations template says it should. Every command
here has been run against this platform, and every threshold quoted is the one the service actually
reads.

| Runbook | Answers |
|---|---|
| [`dead-letter-recovery.md`](dead-letter-recovery.md) | An event did not arrive. Where did it stop, and how do I replay it? |
| [`incident-response.md`](incident-response.md) | A service is down or refusing requests. What do I check, in what order? |
| [`backup-and-restore.md`](backup-and-restore.md) | How is each schema backed up, and how do I prove a restore worked? |
| [`disaster-recovery.md`](disaster-recovery.md) | The site is gone. What comes back, in what order, and what is lost? |
| [`s165-risk-assessment-library.md`](s165-risk-assessment-library.md) | S165: why is an assessment not current, why is S173 still refusing, what does the sweep do? |

## The three services, and what each owns

| Service | Port | Schemas | Local DB | e2e DB |
|---|---|---|---|---|
| `sfl-facilities-service` | 8091 | `facilities` | 5441 | 55441 |
| `sfl-safety-security-service` | 8092 | `safety_security`, `emergency_notification` | 5442 | 55442 |
| `sfl-fleet-logistics-service` | 8093 | `fleet_logistics`, `asset_visibility` | 5443 | 55443 |

Three deployables, five schemas. Two services own two schemas each, because S174 and AVAMP-Lite were
folded in from their own deployables on 5 August 2026 and kept their bounded contexts intact. For a
runbook this distinction is operational, not academic: a restore is **per schema** and always has
been - `pg_dump -n <schema>` - but the recovery *unit* is now the database, so restoring one schema
while its neighbour stays live means stopping a service that also serves the neighbour.

Each service owns its schema, its migrations and its API boundary. **No runbook here may tell you to
write to another service's tables**, and none does - cross-service recovery is always through APIs and
events, because a repair that reaches across a schema boundary re-creates the coupling the
architecture exists to prevent.

## What is not covered, and must be before go-live

- **Authentication is on by default as of 1 August 2026 (A1).** `SFL_SECURITY_ENABLED` now defaults
  to `true`, and the filter chain that opens everything requires the property to be *explicitly*
  `false` - absent no longer means open. The compose stack runs Zitadel (`idp`/`idp-postgres`) as the
  platform's OIDC provider; unlike the Keycloak realm it replaced, its roles/personas are not
  provisioned automatically on startup - see `deploy/idp/README.md`. The local development scripts set
  `SFL_SECURITY_ENABLED` to `false` deliberately and log a warning on every startup when they do.

  Two consequences for these runbooks: a service that will not start may now be failing to reach its
  issuer rather than its database, and any `curl` in anger needs a bearer token. Obtain one against
  the realm:

  ```
  curl -s -d client_id=sfl-operations-ui -d username=<user> -d password=password        -d grant_type=password        http://localhost:8080/realms/sfl/protocol/openid-connect/token | jq -r .access_token
  ```
- **No vendor gateway for S174.** Emergency notification cannot actually deliver a message, so there
  is no "the SMS provider is down" procedure to write yet.
- **The SSEMP cluster does not exist.** S160, S160a, S161, S162, S162a and S163 have no service to
  operate. `sfl-safety-security-service` boots and serves nothing.
