# Signing in, and the seeded accounts

## Running it

```powershell
.\start-fleet.ps1
```

`http://localhost:8093/ui/` opens on the sign-in page. Nothing else to start - no Keycloak, no extra
flag. Signing in decides which account's `X-SFL-*` headers the dashboard sends, and the services run
open locally, so the whole flow is self-contained.

## The accounts

One password for all of them: **`Password@Clet1`**

| Email | Role | Sees |
|---|---|---|
| `fleetmanager@clet.gh` | `FLEET_MANAGER` | Fleet, fuel, dispatch |
| `driver@clet.gh` | `FLEET_DRIVER` | My driving day, trips, vehicles |
| `fleetofficer@clet.gh` | `FLEET_LOGISTICS_OFFICER` | Fleet, fuel, dispatch |
| `dispatchcontroller@clet.gh` | `DISPATCH_CONTROLLER` | Dispatch |
| `logisticscoordinator@clet.gh` | `LOGISTICS_COORDINATOR` | Dispatch |
| `mailroomofficer@clet.gh` | `MAILROOM_OFFICER` | Mailroom |
| `centremanager@clet.gh` | `CENTRE_MANAGER` | Centre receipts, facilities, booking |
| `facilitiesmanager@clet.gh` | `FACILITIES_MANAGER` | Facilities, maintenance, booking |
| `facilitiesdirector@clet.gh` | `FACILITIES_DIRECTOR` | All of IFIMP, all sites |
| `maintenancesupervisor@clet.gh` | `IFIMP_MAINTENANCE_SUPERVISOR` | Maintenance, readiness |
| `technician@clet.gh` | `IFIMP_TECHNICIAN` | My work queue, turnaround |
| `vendortechnician@clet.gh` | `VENDOR_TECHNICIAN` | Their assigned work orders only |
| `requester@clet.gh` | `IFIMP_REQUESTER` | My requests, room booking |
| `emergencycoordinator@clet.gh` | `EMERGENCY_COORDINATOR` | Emergency notification |
| `securityofficer@clet.gh` | `SECURITY_OFFICER` | Dispatch exceptions, emergency |
| `socoperator@clet.gh` | `SOC_OPERATOR` | Emergency notification |
| `hsemanager@clet.gh` | `HSE_MANAGER` | Facilities, maintenance, emergency |
| `command@clet.gh` | `COMMAND_ROLE` | Everything, all sites |
| `auditor@clet.gh` | `AUDITOR` | Read and prove, every programme |
| `complianceofficer@clet.gh` | `COMPLIANCE_OFFICER` | Auditor plus export approval |
| `sfladmin@clet.gh` | `SFL_ADMIN` | Everything |

Every account is scoped to `CLET-HQ` except the director, command and admin, which hold `*`.

**These are development credentials in a checked-in realm file.** One shared password across
twenty-two accounts is a seeding convenience, not a security posture, and this realm must not be
imported into any environment reachable from outside a laptop.

## What signing in does

Matches the email against the seeded accounts, checks the shared password, and makes that account the
actor for the browser session - username, display name, roles, site scopes. Those are what every API
call carries, so **the portal that opens is the one that account's roles entitle it to**:

| Signing in as | Lands on |
|---|---|
| `fleetmanager@clet.gh` | `/ui/fleet` - Fleet operations, FTLMP sidebar |
| `driver@clet.gh` | `/ui/me/driving` - My driving day |
| `requester@clet.gh` | `/ui/me/requests` - My requests |
| `technician@clet.gh` | `/ui/me/queue` - My work queue |
| `facilitiesmanager@clet.gh` | `/ui/facilities` - Facilities dashboard |

Both verified in a browser rather than asserted.

**This is a development sign-in and `accounts.ts` says so in its own docblock.** No token is issued,
nothing is verified against an identity provider, and the credentials are in the bundle served to the
browser. That is bounded on purpose: the services run locally with `SFL_SECURITY_ENABLED=false`,
where the actor is whatever the `X-SFL-*` headers claim, so a form here can only decide which headers
to send. Making it look like more would be worse - a form that appears to authenticate while the
service behind it is open.

## The token-issuing path, for when it is wanted

The platform's OIDC provider is now Zitadel (`deploy/idp/README.md`), not Keycloak. Unlike Keycloak's
`--import-realm`, Zitadel has no equivalent single-file realm import, so the same twenty-two accounts
this section used to describe as auto-provisioned (`deploy/idp/sfl-realm.json` is kept only as the
*reference* for what to recreate) are **not currently provisioned** against a running Zitadel instance,
and there is no `shared/auth/zitadel.ts` yet exchanging them for a real token. Bringing this path back
needs both pieces built and verified with the same rigor the old Keycloak path had - not assumed to
carry over unchanged. Until then, local sign-in stays on the `X-SFL-*` header path described above.

## What this closed

A1 turned authentication on across every service - resource server, JWT actor resolvers, realm, the
403-versus-401 distinction. **None of it was reachable from a browser.** The API client sent
`X-SFL-*` and no `Authorization` header at any point, so the dashboard only worked against a service
running with security off, and a service running with the secure default answered 401 to everything
the dashboard did. This is the missing half.

## Owed work

**The password grant is not the flow to ship.** The dashboard handles the user's actual password, so
it cannot support multi-factor, step-up authentication, or an external identity provider - and all
three are plausible CLET requirements. The OAuth working group deprecates this grant for public
clients for exactly that reason. It is used here because the realm already enables it and it makes
authentication reachable today.

Authorization Code with PKCE is the replacement. It needs a redirect URI on the client, a callback
route, PKCE challenge/verifier state, and silent renewal - none of which exists. Until then:

- **No token refresh.** The refresh token is stored but never exchanged, so a session ends when the
  access token expires and the operator signs in again. `readSession` clears an expired token rather
  than sending it, so the failure is a redirect to sign-in and not a wall of 401s.
- **The token is in `sessionStorage`**, which a successful XSS can read. An `HttpOnly` cookie would
  be safer and needs a backend-for-frontend this platform does not have. Recorded rather than glossed.
