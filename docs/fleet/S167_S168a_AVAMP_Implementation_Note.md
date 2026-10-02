# Phase 2 logistics: GPS & telematics, VIP trip booking, asset tagging

Three hardware-independent screens in the fleet dashboard, each backed by the fleet service. None of
them needs a tracker, a reader or a vendor SDK to be useful today; each says plainly what it cannot
yet do.

## GPS & telematics (S167)

`GET /api/v1/fleet/vehicles/tracking?siteCode=` lists every **active** vehicle at the site with its
latest position and a tracking state, in one request (one page of vehicles, one lookup for their
latest reports):

| State | Meaning |
|---|---|
| `TRACKED` | Its latest report is no older than `sfl.fleet.tracking.stale-after` (default 15 minutes). |
| `STALE` | It has reported, but not recently. Its last position may be wrong. |
| `UNTRACKED` | It has never reported: no tracker, or one nothing has heard from. |

A silent vehicle has no coordinates, never invented ones. Retired and inactive vehicles are not listed,
so they cannot bury the vehicles that are a real gap. The screen refreshes every minute.

**How positions arrive.** A provider posts a normalised `sfl.ftlmp.vehicle-location-received.v1` message
to the existing fleet integrations inbox (`POST /api/v1/fleet/integrations/{sourceSystem}/messages`);
the service stores it as a location snapshot. There is no second path and no vendor type in the domain.
Connecting a provider means producing that message, and nothing in this screen changes.

## VIP trip & driver booking (S168a)

A request names who is travelling, where and when. It never names a vehicle or a driver: it becomes an
ordinary `PLANNED` trip with no assignment, and the transport office assigns it from *Trips &
assignments* (`FLEET_TRIP_ASSIGN`), so the person asking is not the person choosing the car.

* **Who can raise one.** Creating a trip needs `FLEET_TRIP_MANAGE`, held by the fleet manager and the
  logistics officer. A role without it sees the requests and a note saying who raises them; it is not
  offered a button the service would refuse.
* **How a VIP request is recognised.** The trip record has no "executive request" field, so the request
  is marked in its purpose text (`VIP transport: <principal>`, with a chase-car marker) and
  `GET /api/v1/fleet/trips?purposePrefix=` filters on it. This is a stopgap; a real flag on the trip is
  the proper fix and `modules/phase2/api/vipRequest.ts` is the only place that would change.
* **Not built.** There is no approval step, no priority, and no role for the principals themselves
  (Director-General, Registrar, Board). Today the fleet office raises requests on their behalf. Whether
  principals or their executive offices should raise their own is a decision for the SRS owner.

## Asset tagging & inventory (AVAMP)

The register at `/api/v1/assets`, now with the parts that make tagging and custody trustworthy:

* **One tag identifies one asset.** Compared without regard to case, enforced in the service (409 naming
  the holder) and by a unique index. A tag can be added to an existing asset
  (`PATCH /{id}/tag`) and an asset can be found by it (`GET /by-tag/{tagId}`).
* **History.** Every registration, tag, move and custody change is recorded in `asset_history` and read
  back newest-first from `GET /{id}/history`: what changed, from what, to what, by whom, and whether a
  person or a reader did it. Naming the location or custodian an asset already has writes nothing.
* **Reader connection.** `POST /api/v1/assets/scans` takes a tag, a place and a reader id. It applies
  the same move a person makes, recorded as a reader's doing. An unknown tag is a 404, never an invented
  asset; a read stamped in the future is refused.
* **Who can write.** `ASSET_REFERENCE_MANAGE` is held by the administrators, the integration roles, the
  facilities director and manager, and (since this change) the fleet manager and logistics officer, whose
  programme the screen belongs to. The reporting viewer holds no asset permission and is not offered the
  screen. The facilities roles can write at the service but the dashboard does not offer them this
  register, which lives in the fleet programme.

## Known limits

* Position quality is the provider's: the service trusts the reported coordinates and time.
* Update on the asset screen is up to three calls (tag, location, custody). It sends only what changed
  and says which parts were saved if one fails, but it is not a single transaction.
* The dashboard shows drivers the telematics screen, with every vehicle's position at their site. That
  follows the existing `FLEET_VEHICLE_READ` grant and is worth a deliberate decision.
