import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * What each role is offered, pinned against the permissions the services actually enforce.
 *
 * <h2>What changed, and why this file had to</h2>
 *
 * <p>The sidebar used to ask "may you read this". Nearly every role may read nearly everything, so
 * the answer was nearly always yes: a driver was offered the fleet office's registers, a technician
 * the whole estate, and every account looked identical regardless of who signed in.
 *
 * <p>It asks "is this your job" now. `offered()` keeps the read as a floor and then requires one of
 * the item's `capability` verbs - the distinction the services already draw, and draw in verbs:
 * `FACILITIES_WORK_ORDER_UPDATE` is a technician, `FACILITIES_WORK_ORDER_CLOSE` is a supervisor.
 *
 * <p>Three things in this file exist only because of that change, and each was a hard failure before
 * it: `actorPermissions` is mocked with all three of its exports rather than `permits` alone;
 * `shared/platform` is mocked, because `entitledSections` now drops anything the serving origin does
 * not own and an unmocked platform is `UNKNOWN`, which owns nothing; and the fail-open contract at
 * the bottom is now a fail-*closed* one.
 *
 * <h2>Why the permission sets are restated here</h2>
 *
 * `permits` is backed by a live call to `/actor/permissions`, which cannot run in a unit test. The
 * sets below are transcribed from the Java matrices - so they can drift, and they have. The
 * mitigation is that each names its source and each assertion states its reason, so a mismatch is
 * visible to whoever changes a matrix. `DashboardPermissionNamesTest` catches the other half: a name
 * here that no service declares.
 */

const permits = vi.hoisted(() => vi.fn<(permission?: string) => boolean>());
const reviewer = vi.hoisted(() => ({ value: false }));
vi.mock('shared/layout/actorPermissions', () => ({
  permits,
  // navigation.ts uses all three. Mocking only `permits` left the other two undefined and the
  // module threw on first call - which is how this file failed before it asserted anything.
  permitsAny: (permissions: string | string[]) =>
    (Array.isArray(permissions) ? permissions : [permissions]).some((p) => permits(p)),
  actorIsReviewer: () => reviewer.value,
}));

// The serving origin owns every platform here unless a test says otherwise; scoping by origin is
// `platformScoping.test.ts`'s subject, not this file's.
vi.mock('shared/platform', () => ({
  servesPlatform: () => true,
  isPortal: () => true,
  servingPlatform: () => 'ALL',
  servingPlatformName: () => 'SFL Operations',
}));

const entitledSystems = vi.hoisted(() => ({ value: [] as string[] }));
vi.mock('shared/layout/programmes', async () => {
  const model = await vi.importActual<typeof import('./programmeModel')>('./programmeModel');
  return {
    ...model,
    entitledTo: () => true,
    entitledToSystem: (code: string) => entitledSystems.value.includes(code),
    portalLabel: () => 'test',
  };
});

const personaValue = vi.hoisted(() => ({ value: '' }));
vi.mock('shared/layout/personas', () => ({
  isPersona: (persona: string) => persona === personaValue.value,
  PersonaCode: {},
}));

const { entitledSections } = await import('./navigation');

/** FLEET_DRIVER, transcribed from `FleetPermissionMatrix` and `FuelPermissionMatrix`. */
const DRIVER = [
  'FLEET_VEHICLE_READ',
  'FLEET_DRIVER_READ',
  'FLEET_TRIP_READ',
  'FLEET_TRIP_ACKNOWLEDGE',
  'FLEET_INSPECTION_RECORD',
  'FLEET_EVIDENCE_REGISTER',
  'FUEL_TRANSACTION_READ',
  'FUEL_LOGBOOK_READ',
  'FUEL_LOGBOOK_CREATE',
  'FUEL_LOGBOOK_SUBMIT',
];

/** IFIMP_TECHNICIAN, transcribed from `FacilitiesPermissionMatrix`. */
const TECHNICIAN = [
  'FACILITIES_WORK_ORDER_READ',
  'FACILITIES_WORK_ORDER_UPDATE',
  'FACILITIES_FAULT_READ',
  'FACILITIES_FAULT_REPORT',
  'FACILITIES_ASSET_READ',
  'FACILITIES_ASSET_MANAGE',
  'FACILITIES_READINESS_READ',
  'FACILITIES_READINESS_ASSESS',
  'FACILITIES_SITE_READ',
  'FACILITIES_SPACE_READ',
  'FACILITIES_EVIDENCE_ATTACH',
  'FACILITIES_SETUP_TASK_MANAGE',
];

const holding = (granted: string[]) =>
  permits.mockImplementation((p) => p === undefined || granted.includes(p));

const labelsFor = (systems: string[]): string[] => {
  entitledSystems.value = systems;
  return entitledSections().flatMap((section) => section.items.map((item) => item.label));
};

beforeEach(() => {
  permits.mockReset();
  entitledSystems.value = [];
  reviewer.value = false;
  personaValue.value = '';
});

describe('what a driver is offered', () => {
  it('offers their own work and nothing of the fleet office', () => {
    /*
      The whole point of capability gating, in one assertion. A driver reads the vehicle and driver
      registers - the permissions are in DRIVER above - and being able to look up the vehicle they
      are taking is not a reason to be offered the register as a screen. What they can *do* is
      acknowledge a trip and keep a logbook, and both of those are narrowed per record server-side.
    */
    holding(DRIVER);
    personaValue.value = 'driver';
    const labels = labelsFor(['S166', 'S168']);

    expect(labels).toContain('My driving day');
    expect(labels).toContain('Trips & assignments');
    expect(labels).toContain('Driver logbooks');

    expect(labels).not.toContain('Vehicle register');
    expect(labels).not.toContain('Driver register');
    expect(labels).not.toContain('Workflow queue');
    expect(labels).not.toContain('Dashboard');
    expect(labels).not.toContain('Fuel dashboard');
    expect(labels).not.toContain('Reconciliation');
    expect(labels).not.toContain('Anomaly cases');
    expect(labels).not.toContain('Integration health');
  });
});

describe('what a technician is offered', () => {
  it('offers the work in front of them and not the estate', () => {
    /*
      A technician holds FACILITIES_WORK_ORDER_UPDATE and not _CLOSE: they mark work complete and a
      supervisor accepts it. FacilitiesPermissionMatrix says so in prose and has always said so; the
      sidebar could not, because both roles could read the same register.
    */
    holding(TECHNICIAN);
    personaValue.value = 'technician';
    const labels = labelsFor(['S152', 'S153', 'S159']);

    expect(labels).toContain('My work queue');
    expect(labels).toContain('Faults');

    expect(labels).not.toContain('Work orders');
    expect(labels).not.toContain('Preventive schedules');
    expect(labels).not.toContain('Vendors');
    expect(labels).not.toContain('Sites');
    expect(labels).not.toContain('Configuration');
  });
});

describe('what a reviewer is offered', () => {
  it('keeps the read-only screens whose reading is the work', () => {
    /*
      The correction capability gating needed. An auditor changes nothing, so a rule of "offer what
      you can do" would have emptied their sidebar entirely - a worse answer than the read-gating it
      replaced. Holding a reviewing permission is itself a capability; `actorIsReviewer` is where
      that is said.
    */
    holding(['FACILITIES_SITE_READ', 'FACILITIES_ASSET_READ', 'FACILITIES_AUDIT_READ']);
    reviewer.value = true;
    const labels = labelsFor(['S152']);

    expect(labels).toContain('Sites');
    expect(labels).toContain('Facility assets');
  });

  it('offers nothing extra to somebody who merely reads', () => {
    // The same permissions without the reviewing one. Reading alone is not a job.
    holding(['FACILITIES_SITE_READ', 'FACILITIES_ASSET_READ']);
    reviewer.value = false;
    const labels = labelsFor(['S152']);

    expect(labels).not.toContain('Sites');
    expect(labels).not.toContain('Facility assets');
  });
});

describe('the fail-closed contract', () => {
  it('offers nothing when the services could not be asked', () => {
    /*
      This assertion is the exact inverse of the one it replaces, and the inversion is the fix.

      `permits` used to return true for everything when no service answered, on the reasoning that a
      dashboard hiding screens because a request failed reads as a broken build. What it produced
      was worse and invisible: with nothing running, every account saw every screen - a driver
      looking at the whole fleet office - and with one service running, everything belonging to the
      others silently disappeared. Same cause, opposite symptoms, and neither said "a service is
      down". It fails closed now and `permissionFailure()` says why.
    */
    permits.mockReturnValue(false);
    const labels = labelsFor(['S166', 'S168', 'S152']);
    expect(labels).toEqual([]);
  });
});

/**
 * The order of the two fleet operations entries.
 *
 * Pinned because it was asked for explicitly and then reported as not done - the source was correct
 * and a cached bundle was showing the old order, which is exactly where an assertion beats a
 * screenshot. The workflow queue is what a supervisor opens first, so it sits above the register.
 */
describe('fleet navigation order', () => {
  it('puts the workflow queue above trips and assignments', () => {
    holding([
      'FLEET_DASHBOARD_READ',
      'FLEET_DASHBOARD_DRILLDOWN',
      'FLEET_WORKFLOW_READ',
      'FLEET_WORKFLOW_MANAGE',
      'FLEET_TRIP_READ',
      'FLEET_TRIP_MANAGE',
    ]);
    const labels = labelsFor(['S166']);

    const workflow = labels.indexOf('Workflow queue');
    const trips = labels.indexOf('Trips & assignments');

    expect(workflow).toBeGreaterThanOrEqual(0);
    expect(trips).toBeGreaterThanOrEqual(0);
    expect(workflow).toBeLessThan(trips);
  });
});

describe('who is offered the hygiene register (S170)', () => {
  /*
    S170 has its own permission pair, because the one it was first built on - FACILITIES_CONFIG_MANAGE - is a
    platform-configuration grant held by the administrator and the integration engineer. The owners the SRS
    names, Facilities and HSE, held neither, so the register was hidden from the people it exists for, and
    from every read-only role too, because the entry demanded a write verb as well as the read.
  */
  const OWNER = ['FACILITIES_SITE_READ', 'FACILITIES_HYGIENE_READ', 'FACILITIES_HYGIENE_MANAGE'];
  const AUDITOR = ['FACILITIES_SITE_READ', 'FACILITIES_HYGIENE_READ'];
  const INTEGRATION_ENGINEER = ['FACILITIES_SITE_READ', 'FACILITIES_CONFIG_MANAGE'];

  it('offers the register to a facilities or HSE owner', () => {
    holding(OWNER);
    expect(labelsFor(['S170'])).toContain('Hygiene & pest control');
  });

  it('offers it to a read-only auditor too - reading is their whole job', () => {
    holding(AUDITOR);
    expect(labelsFor(['S170'])).toContain('Hygiene & pest control');
  });

  it('does not offer it to a role that can read the estate but holds no hygiene permission', () => {
    holding(['FACILITIES_SITE_READ', 'FACILITIES_WORK_ORDER_READ']);
    expect(labelsFor(['S170'])).not.toContain('Hygiene & pest control');
  });

  it('does not offer it on the configuration grant alone - that is not what owns hygiene', () => {
    holding(INTEGRATION_ENGINEER);
    expect(labelsFor(['S170'])).not.toContain('Hygiene & pest control');
  });

  it('does not offer it to an actor who is not entitled to S170, however much they hold', () => {
    holding(OWNER);
    expect(labelsFor(['S172'])).not.toContain('Hygiene & pest control');
  });

  it('keeps the other estate registers on the grants they were built with', () => {
    holding(['FACILITIES_SITE_READ', 'FACILITIES_CONFIG_MANAGE']);
    expect(labelsFor(['S172'])).toEqual(
      expect.arrayContaining(['Catering & cafeteria', 'Lease & tenancy']),
    );
    holding(OWNER);
    expect(labelsFor(['S172'])).not.toContain('Catering & cafeteria');
  });
});

describe('who is offered waste & recycling (S178)', () => {
  const OWNER = ['FACILITIES_SITE_READ', 'FACILITIES_WASTE_READ', 'FACILITIES_WASTE_MANAGE'];
  const AUDITOR = ['FACILITIES_SITE_READ', 'FACILITIES_WASTE_READ'];

  it('offers it to a role that runs waste, and to a read-only auditor', () => {
    holding(OWNER);
    expect(labelsFor(['S178'])).toContain('Waste & recycling');
    holding(AUDITOR);
    expect(labelsFor(['S178'])).toContain('Waste & recycling');
  });

  it('does not offer it on the platform-configuration grant alone', () => {
    holding(['FACILITIES_SITE_READ', 'FACILITIES_CONFIG_MANAGE']);
    expect(labelsFor(['S178'])).not.toContain('Waste & recycling');
  });

  it('does not offer it to an actor who is not entitled to S178', () => {
    holding(OWNER);
    expect(labelsFor(['S172'])).not.toContain('Waste & recycling');
  });
});

describe('who is offered lost & found (S179)', () => {
  const FRONT_DESK = ['FACILITIES_SITE_READ', 'FACILITIES_LOSTFOUND_READ', 'FACILITIES_LOSTFOUND_MANAGE', 'FACILITIES_LOSTFOUND_PRIVATE_READ'];

  it('offers it to a role that runs the register, and to a read-only auditor', () => {
    holding(FRONT_DESK);
    expect(labelsFor(['S179'])).toContain('Lost & found');
    holding(['FACILITIES_SITE_READ', 'FACILITIES_LOSTFOUND_READ']);
    expect(labelsFor(['S179'])).toContain('Lost & found');
  });

  it('does not offer it on the platform-configuration grant alone', () => {
    holding(['FACILITIES_SITE_READ', 'FACILITIES_CONFIG_MANAGE']);
    expect(labelsFor(['S179'])).not.toContain('Lost & found');
  });

  it('does not offer it to an actor who is not entitled to S179', () => {
    holding(FRONT_DESK);
    expect(labelsFor(['S172'])).not.toContain('Lost & found');
  });
});
