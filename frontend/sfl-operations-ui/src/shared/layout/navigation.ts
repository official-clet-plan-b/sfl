import { servesPlatform } from 'shared/platform';
import { IconName } from 'shared/components/Icon';
import { ProgrammeCode, SystemCode, entitledTo, entitledToSystem } from './programmes';
import { actorIsReviewer, permits, permitsAny } from './actorPermissions';
import type { SflPermission } from './permissions';
import { isPersona, PersonaCode } from './personas';

/**
 * Dashboard navigation.
 *
 * Only destinations that are built and wired to a service appear here. Modules that do not exist
 * yet are not listed at all - a greyed-out "coming soon" entry costs an operator a click to
 * discover nothing, and it makes a working dashboard look half-finished.
 *
 * **Every section declares the programme it belongs to**, and the shell renders only the sections
 * the actor is entitled to. Phase 1 is 13 systems under 4 programme modules delivered as 5
 * services, and those counts do not line up - a programme is a user-facing grouping, a service is
 * a deployment unit. See `programmes.ts` and ADR 0005.
 *
 * The programme is a property of the **system**, not of the service it happens to ship in. S174 is
 * its own deployable (ADR 0004, for availability and blast radius) and is still SSEMP here, sitting
 * beside three FTLMP sections in one bundle. That a user cannot tell which service serves what is
 * the point: deployment topology must not be inferable from a sidebar.
 */

export interface NavItem {
  label: string;
  to: string;
  icon: IconName;
  /** Matches child routes too - `/fleetvehicle/fleet/vehicles/42` still highlights "Vehicle register". */
  matchPrefix?: string;
  description?: string;
  /**
   * The permission this screen's first read requires, when it is more than the section's system.
   *
   * **Absent means the system entitlement is the whole requirement**, which is true of most screens -
   * a role entitled to S171 can read courier items, manifests and exception cases.
   *
   * It is not true of dashboards. A mailroom officer is entitled to S171 and holds no
   * `DISPATCH_REPORT_READ`, so before this existed they were offered the dispatch dashboard as their
   * landing page and met a 403 on arrival. Every code below was read off the service that enforces it,
   * not inferred from the name.
   */
  permission?: SflPermission;
  /**
   * What makes this screen *this person's job*, as opposed to something they may look at.
   *
   * <p>Gating the sidebar on `permission` alone asked "may you read this", and nearly every role may
   * read nearly everything - so a driver was offered the fleet office's registers, a technician the
   * whole estate, and every account looked the same. The distinction the services already draw is
   * finer and it is drawn in verbs: a technician holds `FACILITIES_WORK_ORDER_UPDATE`, a supervisor
   * holds `FACILITIES_WORK_ORDER_CLOSE`. Naming the verb here is what makes the sidebar say it.
   *
   * <p>Any one of them is enough. Omit it for screens where reading genuinely is the work - the
   * audit and evidence views - and see `actorIsReviewer`, which keeps those visible to the roles
   * whose whole job is reading.
   */
  capability?: SflPermission | SflPermission[];
  /**
   * Show this item only to a given persona.
   *
   * **The escape hatch for the one thing a permission cannot express.** `FLEET_DRIVER` holds eight
   * permissions and every one is also held by `FLEET_MANAGER`, so gating "My driving day" on a
   * permission would offer it to the fleet office as their landing page. What makes somebody a
   * driver is what they *cannot* do, and `personas.ts` encodes that using the same
   * narrowest-role rule the services already enforce.
   *
   * Use it only for personal landings. Every operational screen stays permission-gated, because a
   * screen should be offered exactly when the service will answer it.
   */
  persona?: PersonaCode;
}

export interface NavSection {
  heading: string;
  /** Which of the four SFL programmes this section belongs to. Drives what a user sees. */
  programme: ProgrammeCode;
  /**
   * Which system it belongs to - the finer half of the same decision.
   *
   * Programme alone is not enough inside FTLMP, where three systems share one deployable: without
   * this, a mailroom officer sees the fleet register and a driver sees the courier manifests.
   */
  system: SystemCode;
  items: NavItem[];
}

/**
 * S152 CAFM/IWMS routes.
 *
 * The first IFIMP module in this dashboard, and the platform S153 and S159 will attach to - so these
 * paths are `/facilities/...` rather than `/cafm/...`: a user is looking at facilities, and which
 * system inside IFIMP serves a screen is not their problem.
 *
 * Readiness assessments have a register and a detail, because an assessment is a signed record an
 * auditor comes back to. Blockers do not: a blocker is only meaningful beside the space it blocks,
 * so it lives on the space detail screen and on the dashboard drilldown rather than as a third list
 * to cross-reference by hand.
 */
/**
 * The routes that exist outside any platform's screens.
 *
 * <p>Sign-in has no sidebar, no actor and no system guard, but it still has to live at an address -
 * and that address is under `/fleetvehicle` because FTLMP is the deployable that serves the bundle.
 * Kept as a constant rather than the literal it used to be: the route moved once, and the guard that
 * redirects to it was the one place that had to be found by hand when it did.
 */
export const authPaths = {
  login: '/login',
};

export const facilitiesPaths = {
  dashboard: '/facilities',
  sites: '/facilities/estate/sites',
  siteDetail: (siteId: string) => `/facilities/estate/sites/${siteId}`,
  /*
    Buildings have a detail route and no register. A building is only ever reached from the site that
    owns it - nobody searches an estate for a building - and a fourth register would be a sidebar
    entry whose whole content is "choose a site first".
  */
  buildingDetail: (buildingId: string) => `/facilities/estate/buildings/${buildingId}`,
  spaces: '/facilities/estate/spaces',
  spaceDetail: (roomId: string) => `/facilities/estate/spaces/${roomId}`,
  assets: '/facilities/estate/assets',
  assetDetail: (assetId: string) => `/facilities/estate/assets/${assetId}`,
  zones: '/facilities/estate/zones',
  devices: '/facilities/estate/devices',
  assessments: '/facilities/estate/assessments',
  assessmentDetail: (assessmentId: string) => `/facilities/estate/assessments/${assessmentId}`,
  checklists: '/facilities/estate/checklists',
  checklistDetail: (checklistId: string) => `/facilities/estate/checklists/${checklistId}`,
  audit: '/facilities/estate/audit',
  configuration: '/facilities/estate/configuration',

  // S153 CMMS, under /facilities/maintenance. It shares a service and a programme with S152 and now
  // says so *and* stays distinguishable: the two used to share one flat level, so nothing in a URL
  // told you whether a screen was estate registry or maintenance - and the route guard reads a system
  // code the reader cannot see.
  faults: '/facilities/maintenance/faults',
  faultDetail: (faultId: string) => `/facilities/maintenance/faults/${faultId}`,
  workOrders: '/facilities/maintenance/work-orders',
  workOrderDetail: (workOrderId: string) => `/facilities/maintenance/work-orders/${workOrderId}`,
  schedules: '/facilities/maintenance/schedules',
  scheduleDetail: (scheduleId: string) => `/facilities/maintenance/schedules/${scheduleId}`,
  vendors: '/facilities/maintenance/vendors',
  evidenceDetail: (evidenceId: string) => `/facilities/maintenance/evidence/${evidenceId}`,
};

/**
 * S159 room and resource booking routes.
 *
 * Under `/facilities/bookings`, a sibling of `estate` and `maintenance`. S152 and S153 are read by the people who run the estate; the
 * booking diary is read by everybody who ever needs a room, and most of them do not think of
 * themselves as visiting facilities at all. A path a lecturer can be told over the phone is worth
 * more than a URL that mirrors the service topology.
 *
 * Availability is a destination, not a dialog on the diary. It is where a booking begins, it takes
 * eight fields, and its answer is a page of spaces with reasons - none of which fits in a modal, and
 * all of which somebody will want to link to.
 */
export const bookingPaths = {
  diary: '/facilities/bookings',
  /*
    Static siblings of `:bookingId`. React Router ranks a static segment above a dynamic one, so
    `/facilities/bookings/availability` never resolves as a booking whose id is the word "availability" - and
    ids are UUIDs regardless. Keep new static children out of the UUID shape and this stays true.
  */
  availability: '/facilities/bookings/availability',
  resources: '/facilities/bookings/resources',
  setupTasks: '/facilities/bookings/turnaround',
  bookingDetail: (bookingId: string) => `/facilities/bookings/${bookingId}`,
};

/** Phase 2 IFIMP systems share the facilities deployable but remain independently entitled. */
export const ifimpPhase2Paths = {
  buildingSystems: '/facilities/building-systems',
  bmsDevices: '/facilities/building-systems/devices',
  bmsAlerts: '/facilities/building-systems/alerts',
  bmsQuarantine: '/facilities/building-systems/quarantine',
  bmsRules: '/facilities/building-systems/rules',
  energy: '/facilities/energy',
  energyMeters: '/facilities/energy/meters',
  energyReadings: '/facilities/energy/readings',
  energyAlerts: '/facilities/energy/alerts',
  energyBudgets: '/facilities/energy/budgets',
  energyKpis: '/facilities/energy/kpis',
  spacePlanning: '/facilities/space-planning',
  spaceScenarios: '/facilities/space-planning/scenarios',
  spaceRequests: '/facilities/space-planning/requests',
  cleaning: '/facilities/cleaning',
  cleaningTasks: '/facilities/cleaning/tasks',
  cleaningSchedules: '/facilities/cleaning/schedules',
  cleaningVendors: '/facilities/cleaning/vendors',
  eventLogistics: '/facilities/event-logistics',
  eventTemplates: '/facilities/event-logistics/templates',
  eventRisks: '/facilities/event-logistics/risk-criteria',
  eventIntegrations: '/facilities/event-logistics/integration',
  construction: '/facilities/construction',
  constructionProjects: '/facilities/construction/projects',
  constructionContractors: '/facilities/construction/contractors',
  constructionIntegrations: '/facilities/construction/integrations',
};

export const fleetPaths = {
  dashboard: '/fleetvehicle/fleet',
  vehicles: '/fleetvehicle/fleet/vehicles',
  vehicleDetail: (vehicleId: string) => `/fleetvehicle/fleet/vehicles/${vehicleId}`,
  drivers: '/fleetvehicle/fleet/drivers',
  driverDetail: (driverId: string) => `/fleetvehicle/fleet/drivers/${driverId}`,
  trips: '/fleetvehicle/fleet/trips',
  tripDetail: (tripId: string) => `/fleetvehicle/fleet/trips/${tripId}`,
  workflow: '/fleetvehicle/fleet/workflow',
  workflowDetail: (itemId: string) => `/fleetvehicle/fleet/workflow/${itemId}`,
  compliance: '/fleetvehicle/fleet/compliance',
  evidence: '/fleetvehicle/fleet/evidence',
  integrations: '/fleetvehicle/fleet/integrations',
};

/**
 * S168 fuel routes.
 *
 * Policy detail has no endpoint of its own (`GET /policies/{id}` does not exist), so the screen
 * selects out of the site's policy list - the route still exists because a policy is a record an
 * operator links to and comes back to.
 */
export const fuelPaths = {
  dashboard: '/fleetvehicle/fuel',
  transactions: '/fleetvehicle/fuel/transactions',
  transactionDetail: (transactionId: string) => `/fleetvehicle/fuel/transactions/${transactionId}`,
  logbooks: '/fleetvehicle/fuel/logbooks',
  logbookDetail: (logbookId: string) => `/fleetvehicle/fuel/logbooks/${logbookId}`,
  reconciliation: '/fleetvehicle/fuel/reconciliation',
  anomalies: '/fleetvehicle/fuel/anomalies',
  anomalyDetail: (anomalyId: string) => `/fleetvehicle/fuel/anomalies/${anomalyId}`,
  cards: '/fleetvehicle/fuel/cards',
  imports: '/fleetvehicle/fuel/imports',
  policies: '/fleetvehicle/fuel/policies',
  policyDetail: (policyId: string) => `/fleetvehicle/fuel/policies/${policyId}`,
  integrations: '/fleetvehicle/fuel/integrations',
};

/**
 * S171 dispatch routes.
 *
 * Custody, receipts and the return leg have no register of their own: each belongs to one
 * consignment and is only meaningful beside it, so they live on the manifest detail screen rather
 * than as three more sidebar entries an operator would have to cross-reference by hand.
 */
export const dispatchPaths = {
  dashboard: '/fleetvehicle/dispatch',
  items: '/fleetvehicle/dispatch/items',
  itemDetail: (itemId: string) => `/fleetvehicle/dispatch/items/${itemId}`,
  manifests: '/fleetvehicle/dispatch/manifests',
  manifestDetail: (manifestId: string) => `/fleetvehicle/dispatch/manifests/${manifestId}`,
  inbound: '/fleetvehicle/dispatch/inbound',
  exceptions: '/fleetvehicle/dispatch/exceptions',
  exceptionDetail: (caseId: string) => `/fleetvehicle/dispatch/exceptions/${caseId}`,
  scans: '/fleetvehicle/dispatch/scans',
  integrations: '/fleetvehicle/dispatch/integrations',
};

/**
 * S174 emergency notification routes.
 *
 * Break-glass is a destination rather than a mode on the compose dialog. It is a different
 * authorisation, it creates a different obligation, and in a declared emergency it has to be one
 * click from anywhere - a screen that is both a warning and the shortest path is what that needs.
 *
 * Templates and scenarios share a screen, and so do audience groups and recipient zones: each pair
 * answers one question between them and is chosen together on every activation. Only the template
 * has a detail route, because `GET /templates/{id}` is the only detail endpoint this service has.
 */
export const emergencyPaths = {
  dashboard: '/safetysecurity/emergency',
  activations: '/safetysecurity/emergency/activations',
  activationDetail: (activationId: string) => `/safetysecurity/emergency/activations/${activationId}`,
  breakGlass: '/safetysecurity/emergency/break-glass',
  templates: '/safetysecurity/emergency/templates',
  templateDetail: (templateId: string) => `/safetysecurity/emergency/templates/${templateId}`,
  audiences: '/safetysecurity/emergency/audiences',
  drills: '/safetysecurity/emergency/drills',
  integrations: '/safetysecurity/emergency/integrations',
};

export const visitorPaths = {
  dashboard: '/safetysecurity/visitors',
  visits: '/safetysecurity/visitors/visits',
  visitDetail: (visitId: string) => `/safetysecurity/visitors/visits/${visitId}`,
  rollCall: '/safetysecurity/visitors/roll-call',
};

export const incidentPaths = {
  dashboard: '/safetysecurity/incidents',
  cases: '/safetysecurity/incidents/cases',
  detail: (incidentId: string) => `/safetysecurity/incidents/cases/${incidentId}`,
};

/**
 * Personal landings - the "what do I have to do today" views.
 *
 * Under `/me/` rather than inside a system's routes because they cross systems: a driver's day is
 * S166 assignments and an S168 logbook, and filing either under the other would be arbitrary. The
 * URL says whose view it is, which is the honest description.
 */
export const mePaths = {
  driverDay: '/me/driving',
  myRequests: '/me/requests',
  myQueue: '/me/queue',
  mailroom: '/me/mailroom',
  centreReceipts: '/me/receipts',
  assurance: '/me/assurance',
} as const;


export const navSections: NavSection[] = [
  // ── Personal landings ───────────────────────────────────────────────────────────────────────
  //
  // First in the list on purpose. `landingPath()` returns the first item of the first entitled
  // section, so putting these ahead of the operator sections is what makes a driver open on their
  // own day rather than on the fleet dashboard - with no change to the router or the shell.
  //
  // Each is `persona`-gated, so an operator never sees them: the sections below are unchanged for
  // everybody who was already served.
  {
    heading: 'My work',
    programme: 'FTLMP',
    system: 'S166',
    items: [
      {
        label: 'My driving day',
        to: mePaths.driverDay,
        icon: 'truck',
        description: 'Today’s assignments, my logbook and pre-trip checks',
        // Enforced by FuelApplicationService.logbooks, narrowed per record on created_by.
        permission: 'FUEL_LOGBOOK_READ',
        persona: 'driver',
      },
    ],
  },
  {
    heading: 'My work',
    programme: 'FTLMP',
    system: 'S171',
    items: [
      {
        label: 'Mailroom',
        to: mePaths.mailroom,
        icon: 'inbox',
        description: 'Register inbound items and today’s distribution',
        // Enforced by DispatchAccessPolicy on the courier item register.
        permission: 'DISPATCH_ITEM_READ',
        persona: 'mailroom',
      },
      {
        label: 'Centre receipts',
        to: mePaths.centreReceipts,
        icon: 'clipboard',
        description: 'Confirm receipt, record a variance, chase returns',
        // Enforced by DispatchAccessPolicy; confirmation needs DISPATCH_RECEIPT_CONFIRM.
        permission: 'DISPATCH_MANIFEST_READ',
        persona: 'centre',
      },
    ],
  },
  {
    heading: 'My work',
    programme: 'IFIMP',
    system: 'S153',
    items: [
      {
        label: 'My requests',
        to: mePaths.myRequests,
        icon: 'clipboard',
        description: 'The bookings and faults I raised',
        // Enforced by FacilityFaultService.requesterFilter and its booking twin.
        permission: 'FACILITIES_FAULT_READ',
        persona: 'requester',
      },
      {
        label: 'My work queue',
        to: mePaths.myQueue,
        icon: 'wrench',
        description: 'The jobs assigned to me',
        // Enforced by WorkOrderApplicationService.assertVisible, per record on assignedTo.
        permission: 'FACILITIES_WORK_ORDER_READ',
        persona: 'technician',
      },
    ],
  },
  {
    heading: 'Assurance',
    programme: 'IFIMP',
    system: 'S152',
    items: [
      {
        label: 'Audit & evidence',
        to: mePaths.assurance,
        icon: 'shield-check',
        description: 'Chain verification, evidence and denials across every system',
        // Enforced by the facilities audit endpoints; FTLMP has its own, read side by side.
        permission: 'FACILITIES_AUDIT_READ',
        persona: 'assurance',
      },
    ],
  },
  /*
    The IFIMP sections below are ordered by the sequence an operator actually works in, not by which
    screen was built first.

    Nothing in this programme can be done out of order: there is no space without a building, no
    assessment without a checklist and a space to assess, no fault against a space that does not
    exist, and nothing to book until all of that is true. The sidebar used to open with the dashboard
    and readiness, then maintenance, then booking, and put the registers everything depends on
    fourth - so somebody setting a centre up read the menu top to bottom and hit the first thing they
    could not yet do. Read in this order it is a sequence:

      operations  - what is the state of the estate           (the landing)
      registers   - build the estate                          sites, spaces, assets, zones, devices
      readiness   - define the standard, then assess against it
      maintenance - fix what the assessment found
      booking     - use what is now ready
      governance  - prove what happened, and tune the thresholds

    `landingPath()` returns the first item of the first entitled section, so the dashboard staying
    first is what keeps an operator opening on the overview rather than on the site register. That
    is the one thing this ordering must not break, and it is why the dashboard sits alone rather than
    being folded into the registers below it.
  */
  {
    // S152 leads the list because IFIMP is the first programme in `allProgrammes`, and an actor
    // entitled to both lands on their facilities dashboard rather than on fleet's.
    heading: 'Facility operations',
    programme: 'IFIMP',
    system: 'S152',
    items: [
      {
        label: 'Facilities dashboard',
        to: facilitiesPaths.dashboard,
        icon: 'dashboard',
        description: 'Readiness, blockers and examination risk',
        // Enforced by FacilityDashboardService.
        permission: 'FACILITIES_DASHBOARD_READ',
        capability: 'FACILITIES_DASHBOARD_DRILLDOWN',
      },
    ],
  },
  {
    // Second, because everything after it needs these records to exist. Within the section the same
    // rule applies: a space needs a site, an asset or a device needs somewhere to be, a zone needs
    // members to cover.
    heading: 'Estate registers',
    programme: 'IFIMP',
    system: 'S152',
    items: [
      {
        label: 'Sites',
        to: facilitiesPaths.sites,
        icon: 'map-pin',
        matchPrefix: facilitiesPaths.sites,
        description: 'Centres, and the operating mode each is in',
        permission: 'FACILITIES_SITE_READ',
        capability: 'FACILITIES_SITE_MANAGE',
      },
      {
        label: 'Spaces',
        to: facilitiesPaths.spaces,
        icon: 'building',
        matchPrefix: facilitiesPaths.spaces,
        description: 'Rooms, halls and courtrooms with their readiness',
        permission: 'FACILITIES_SPACE_READ',
        capability: 'FACILITIES_SPACE_MANAGE',
      },
      {
        label: 'Facility assets',
        to: facilitiesPaths.assets,
        icon: 'wrench',
        matchPrefix: facilitiesPaths.assets,
        description: 'Fixed plant, its condition and what it serves',
        permission: 'FACILITIES_ASSET_READ',
        capability: 'FACILITIES_ASSET_MANAGE',
      },
      {
        label: 'Zones',
        to: facilitiesPaths.zones,
        icon: 'layers',
        description: 'What each zone covers, for safety and emergency',
        permission: 'FACILITIES_ZONE_READ',
        capability: 'FACILITIES_ZONE_MANAGE',
      },
      {
        label: 'Device references',
        to: facilitiesPaths.devices,
        icon: 'activity',
        description: 'Cameras, readers and panels, and where they sit',
        permission: 'FACILITIES_DEVICE_REFERENCE_READ',
        capability: 'FACILITIES_DEVICE_REFERENCE_REGISTER',
      },
    ],
  },
  {
    /*
      Checklists sit above assessments, and that is the whole reason this section exists.

      They were on opposite sides of the sidebar - the checklist register under assurance at the
      bottom, the assessment screen under operations at the top - and an assessment against a site
      with no checklist records no answers and leaves the space UNKNOWN. The assessment screen's own
      empty state says so. Putting the two together, in the order they have to be done, makes the
      dependency something an operator reads rather than something they discover.
    */
    heading: 'Readiness',
    programme: 'IFIMP',
    system: 'S152',
    items: [
      {
        label: 'Readiness checklists',
        to: facilitiesPaths.checklists,
        icon: 'clipboard-list',
        matchPrefix: facilitiesPaths.checklists,
        description: 'The questions an assessment asks, and what a failure costs',
        permission: 'FACILITIES_READINESS_READ',
        capability: 'FACILITIES_READINESS_CHECKLIST_MANAGE',
      },
      {
        label: 'Readiness assessments',
        to: facilitiesPaths.assessments,
        icon: 'clipboard',
        matchPrefix: facilitiesPaths.assessments,
        description: 'Inspect a space against its checklist',
        // Enforced by ReadinessApplicationService.assessments.
        permission: 'FACILITIES_READINESS_READ',
        capability: 'FACILITIES_READINESS_ASSESS',
      },
    ],
  },
  {
    // S153. Its own section rather than items inside 'Facility operations', because maintenance has
    // a different audience: a technician and a contractor live here and never open the estate
    // registers, and a section they can read end to end is easier to trust than three items
    // scattered through one they mostly cannot.
    heading: 'Maintenance',
    programme: 'IFIMP',
    system: 'S153',
    items: [
      {
        label: 'Faults',
        to: facilitiesPaths.faults,
        icon: 'flag',
        matchPrefix: facilitiesPaths.faults,
        description: 'Reported problems, triage and SLA',
        // Enforced by FacilityFaultService. A requester holds this and sees only their own.
        permission: 'FACILITIES_FAULT_READ',
        /*
          Triage *or* report, and the second one was missing.

          Gating on triage alone hid this screen from `IFIMP_TECHNICIAN`, which holds
          FACILITIES_FAULT_REPORT and not FACILITIES_FAULT_TRIAGE - so a technician could raise a
          fault and had nowhere in the dashboard to raise it. FACILITIES_FAULT_REPORT was, before
          this, a capability no navigation item asked for at all: three roles hold it and none of
          them was offered a fault screen. Reporting one is doing something here, which is the test
          this list is supposed to apply.
        */
        capability: ['FACILITIES_FAULT_TRIAGE', 'FACILITIES_FAULT_REPORT'],
      },
      {
        label: 'Work orders',
        to: facilitiesPaths.workOrders,
        icon: 'wrench',
        matchPrefix: facilitiesPaths.workOrders,
        description: 'The queue, its assignees and what is overdue',
        // Enforced by WorkOrderApplicationService, which also narrows a vendor to their own.
        permission: 'FACILITIES_WORK_ORDER_READ',
        capability: [
          'FACILITIES_WORK_ORDER_ASSIGN',
          'FACILITIES_WORK_ORDER_CLOSE',
          'FACILITIES_WORK_ORDER_CREATE',
        ],
      },
      {
        label: 'Preventive schedules',
        to: facilitiesPaths.schedules,
        icon: 'calendar',
        matchPrefix: facilitiesPaths.schedules,
        description: 'Planned servicing, and what it has raised',
        permission: 'FACILITIES_PM_SCHEDULE_READ',
        capability: 'FACILITIES_PM_SCHEDULE_MANAGE',
      },
      {
        label: 'Vendors',
        to: facilitiesPaths.vendors,
        icon: 'users',
        description: 'Contractors, contracts and response times',
        permission: 'FACILITIES_VENDOR_READ',
        capability: 'FACILITIES_VENDOR_MANAGE',
      },
    ],
  },
  {
    // S159. Its own section rather than items inside 'Facility operations', for the same reason
    // maintenance has one: the audience is different. A lecturer or a registry clerk books a room and
    // opens nothing else in this programme, and a section they can read end to end is easier to trust
    // than three items scattered through one they mostly cannot.
    heading: 'Room booking',
    programme: 'IFIMP',
    system: 'S159',
    items: [
      {
        label: 'Booking diary',
        to: bookingPaths.diary,
        icon: 'calendar',
        // Not `matchPrefix`: the diary is the index of `/facilities/bookings`, and a prefix match would keep it
        // highlighted while the operator is on turnaround or the resource register.
        description: 'What is booked, and what the estate thinks of it',
        // Enforced by BookingApplicationService.search. A requester holds this and sees only their own.
        permission: 'FACILITIES_BOOKING_READ',
        capability: [
          'FACILITIES_BOOKING_APPROVE',
          'FACILITIES_BOOKING_REQUEST',
        ],
      },
      {
        label: 'Find a space',
        to: bookingPaths.availability,
        icon: 'search',
        description: 'What can take a window, and what cannot',
        // The availability endpoints are read with BOOKING_READ; the request that follows needs more,
        // and the page hides the control rather than the screen.
        permission: 'FACILITIES_BOOKING_READ',
        capability: 'FACILITIES_BOOKING_REQUEST',
      },
      {
        label: 'Room turnaround',
        to: bookingPaths.setupTasks,
        icon: 'clipboard',
        description: 'What has to happen to a room before its next booking',
        // BOOKING_READ, not SETUP_TASK_MANAGE - read off `BookingSetupService.queue`, which gates the
        // queue on reading bookings and reserves SETUP_TASK_MANAGE for raising and resolving a task.
        // Gating the screen on the write permission would hide the queue from everybody who can only
        // look at it, which is most of the people who need to.
        permission: 'FACILITIES_BOOKING_READ',
      },
      {
        label: 'Bookable resources',
        to: bookingPaths.resources,
        icon: 'package',
        description: 'Projectors, furniture and what else can be booked',
        permission: 'FACILITIES_RESOURCE_READ',
        capability: 'FACILITIES_RESOURCE_MANAGE',
      },
    ],
  },
  {
    heading: 'Building systems',
    programme: 'IFIMP',
    system: 'S156',
    items: [
      { label: 'BMS overview', to: ifimpPhase2Paths.buildingSystems, icon: 'dashboard', description: 'Device health and active alerts', permission: 'FACILITIES_BMS_READ' },
      { label: 'Devices', to: ifimpPhase2Paths.bmsDevices, icon: 'activity', description: 'Registered controllers, sensors and gateways', permission: 'FACILITIES_BMS_READ', capability: 'FACILITIES_BMS_DEVICE_MANAGE' },
      { label: 'BMS alerts', to: ifimpPhase2Paths.bmsAlerts, icon: 'alert-circle', description: 'Threshold and fault-code alerts', permission: 'FACILITIES_BMS_READ' },
      { label: 'Quarantined readings', to: ifimpPhase2Paths.bmsQuarantine, icon: 'shield-lock', description: 'Telemetry awaiting resolution', permission: 'FACILITIES_BMS_READ', capability: 'FACILITIES_BMS_QUARANTINE_RESOLVE' },
      { label: 'Alert rules', to: ifimpPhase2Paths.bmsRules, icon: 'gauge', description: 'Thresholds and fault-code rules', permission: 'FACILITIES_BMS_READ', capability: 'FACILITIES_BMS_RULE_MANAGE' },
    ],
  },
  {
    heading: 'Energy & sustainability',
    programme: 'IFIMP',
    system: 'S157',
    items: [
      { label: 'Energy overview', to: ifimpPhase2Paths.energy, icon: 'dashboard', description: 'Metering health and coverage', permission: 'FACILITIES_ENERGY_READ' },
      { label: 'Meters', to: ifimpPhase2Paths.energyMeters, icon: 'gauge', description: 'Utility meters and acquisition sources', permission: 'FACILITIES_ENERGY_READ', capability: 'FACILITIES_ENERGY_METER_MANAGE' },
      { label: 'Energy readings', to: ifimpPhase2Paths.energyReadings, icon: 'activity', description: 'Posted and held consumption readings', permission: 'FACILITIES_ENERGY_READ', capability: 'FACILITIES_ENERGY_READING_ENTER' },
      { label: 'Energy alerts', to: ifimpPhase2Paths.energyAlerts, icon: 'alert-circle', description: 'Variance and missing-tariff exceptions', permission: 'FACILITIES_ENERGY_READ' },
      { label: 'Budgets', to: ifimpPhase2Paths.energyBudgets, icon: 'clipboard', description: 'Effective-dated utility budgets', permission: 'FACILITIES_ENERGY_READ', capability: 'FACILITIES_ENERGY_BUDGET_MANAGE' },
      { label: 'Sustainability KPIs', to: ifimpPhase2Paths.energyKpis, icon: 'check-circle', description: 'Published indicators and completeness', permission: 'FACILITIES_ENERGY_READ' },
    ],
  },
  {
    heading: 'Space planning',
    programme: 'IFIMP',
    system: 'S158',
    items: [
      { label: 'Planning overview', to: ifimpPhase2Paths.spacePlanning, icon: 'dashboard', description: 'Pipeline, compliance and utilisation', permission: 'FACILITIES_SPACE_PLAN_READ' },
      { label: 'Planning scenarios', to: ifimpPhase2Paths.spaceScenarios, icon: 'layers', description: 'Draft and committed estate plans', permission: 'FACILITIES_SPACE_PLAN_READ', capability: 'FACILITIES_SPACE_PLAN_MANAGE' },
      { label: 'Change requests', to: ifimpPhase2Paths.spaceRequests, icon: 'clipboard', description: 'Move and physical-work requests', permission: 'FACILITIES_SPACE_PLAN_READ', capability: ['FACILITIES_SPACE_CHANGE_REQUEST', 'FACILITIES_SPACE_CHANGE_DECIDE'] },
    ],
  },
  {
    heading: 'Cleaning operations',
    programme: 'IFIMP',
    system: 'S169',
    items: [
      { label: 'Cleaning overview', to: ifimpPhase2Paths.cleaning, icon: 'dashboard', description: 'Workload, SLA and feedback', permission: 'FACILITIES_CLEANING_READ' },
      { label: 'Cleaning tasks', to: ifimpPhase2Paths.cleaningTasks, icon: 'check-circle', description: 'Scheduled and reactive work', permission: 'FACILITIES_CLEANING_READ', capability: ['FACILITIES_CLEANING_TASK_EXECUTE', 'FACILITIES_CLEANING_TASK_SUPERVISE', 'FACILITIES_CLEANING_REQUEST'] },
      { label: 'Cleaning schedules', to: ifimpPhase2Paths.cleaningSchedules, icon: 'calendar', description: 'Recurring room schedules', permission: 'FACILITIES_CLEANING_READ', capability: 'FACILITIES_CLEANING_SCHEDULE_MANAGE' },
      { label: 'Cleaning vendors', to: ifimpPhase2Paths.cleaningVendors, icon: 'users', description: 'Providers and SLA performance', permission: 'FACILITIES_CLEANING_READ', capability: 'FACILITIES_CLEANING_VENDOR_MANAGE' },
    ],
  },
  {
    heading: 'Event logistics',
    programme: 'IFIMP',
    system: 'S173',
    items: [
      { label: 'Set-up tasks', to: ifimpPhase2Paths.eventLogistics, icon: 'calendar', description: 'Event readiness and resources', permission: 'FACILITIES_EVENT_READ', capability: 'FACILITIES_EVENT_COORDINATE' },
      { label: 'Resource templates', to: ifimpPhase2Paths.eventTemplates, icon: 'clipboard-list', description: 'Reusable event resource plans', permission: 'FACILITIES_EVENT_READ', capability: 'FACILITIES_EVENT_COORDINATE' },
      { label: 'Risk criteria', to: ifimpPhase2Paths.eventRisks, icon: 'shield-check', description: 'Event risk categorisation rules', permission: 'FACILITIES_EVENT_READ', capability: 'FACILITIES_EVENT_RISK_CATEGORY_MANAGE' },
      { label: 'Event integrations', to: ifimpPhase2Paths.eventIntegrations, icon: 'workflow', description: 'Upstream event-system status', permission: 'FACILITIES_EVENT_READ' },
    ],
  },
  {
    heading: 'Construction',
    programme: 'IFIMP',
    system: 'S176',
    items: [
      { label: 'Construction overview', to: ifimpPhase2Paths.construction, icon: 'dashboard', description: 'Portfolio, milestones and defects', permission: 'FACILITIES_PROJECT_READ' },
      { label: 'Projects', to: ifimpPhase2Paths.constructionProjects, icon: 'building', description: 'Projects, variations and handover', permission: 'FACILITIES_PROJECT_READ', capability: ['FACILITIES_PROJECT_MANAGE', 'FACILITIES_PROJECT_APPROVE', 'FACILITIES_PROJECT_HANDOVER'] },
      { label: 'Contractors', to: ifimpPhase2Paths.constructionContractors, icon: 'users', description: 'Competency, insurance and site access', permission: 'FACILITIES_PROJECT_READ', capability: 'FACILITIES_CONTRACTOR_MANAGE' },
      { label: 'Construction integrations', to: ifimpPhase2Paths.constructionIntegrations, icon: 'workflow', description: 'Procurement and project-system status', permission: 'FACILITIES_PROJECT_READ' },
    ],
  },
  {
    // Last, because neither answers "what do I do next" - one proves what was already done and the
    // other tunes the thresholds the rest of the programme is measured against.
    heading: 'Governance',
    programme: 'IFIMP',
    system: 'S152',
    items: [
      {
        label: 'Audit & integrity',
        to: facilitiesPaths.audit,
        icon: 'shield-lock',
        description: 'Every state change, and the chain replay that proves it',
        // Enforced by FacilitiesGovernanceService.search.
        permission: 'FACILITIES_AUDIT_READ',
      },
      {
        label: 'Configuration',
        to: facilitiesPaths.configuration,
        icon: 'gauge',
        description: 'Thresholds the rules are read from, and their versions',
        permission: 'FACILITIES_CONFIG_READ',
        capability: 'FACILITIES_CONFIG_MANAGE',
      },
    ],
  },
  {
    heading: 'Operations',
    programme: 'FTLMP',
    system: 'S166',
    items: [
      {
        label: 'Dashboard',
        to: fleetPaths.dashboard,
        icon: 'dashboard',
        description: 'Readiness, activity and exceptions',
        // Enforced by FleetDashboardApplicationService.
        permission: 'FLEET_DASHBOARD_READ',
        capability: 'FLEET_DASHBOARD_DRILLDOWN',
      },
      {
        label: 'Workflow queue',
        to: fleetPaths.workflow,
        icon: 'workflow',
        matchPrefix: fleetPaths.workflow,
        description: 'Inspections, defects and escalations',
        // A driver does NOT hold this. The queue is the supervisor's view of inspections, defects
        // and escalations across the fleet; a driver records an inspection against their own trip
        // and has no business reading everybody else's defects.
        permission: 'FLEET_WORKFLOW_READ',
        capability: [
          'FLEET_WORKFLOW_MANAGE',
          'FLEET_WORKFLOW_APPROVE',
          'FLEET_WORKFLOW_ASSIGN',
        ],
      },
      {
        label: 'Trips & assignments',
        to: fleetPaths.trips,
        icon: 'route',
        matchPrefix: fleetPaths.trips,
        description: 'Plan, assign, start and close movements',
        // Enforced by TripApplicationService. A driver holds this and sees the register; planning,
        // assigning and closing are separate permissions the page gates its controls on.
        permission: 'FLEET_TRIP_READ',
        capability: [
          'FLEET_TRIP_MANAGE',
          'FLEET_TRIP_ASSIGN',
          // A driver confirms or defers the trip assigned to them. TripApplicationService narrows
          // the register to their own records, so this offers them their work, not the fleet's.
          'FLEET_TRIP_ACKNOWLEDGE',
        ],
      },
    ],
  },
  {
    heading: 'Registers',
    programme: 'FTLMP',
    system: 'S166',
    items: [
      {
        label: 'Vehicle register',
        to: fleetPaths.vehicles,
        icon: 'truck',
        matchPrefix: fleetPaths.vehicles,
        description: 'Fleet inventory and readiness',
        // A driver holds this: they need to look up the vehicle they are taking out. Registering,
        // editing and retiring one are FLEET_VEHICLE_MANAGE, which they do not hold - the page
        // hides those controls rather than offering a button the service refuses.
        permission: 'FLEET_VEHICLE_READ',
        capability: 'FLEET_VEHICLE_MANAGE',
      },
      {
        label: 'Driver register',
        to: fleetPaths.drivers,
        icon: 'driver',
        matchPrefix: fleetPaths.drivers,
        description: 'Licence standing and eligibility',
        /*
          A driver holds FLEET_DRIVER_READ and sees the list, the same way they see the vehicle
          register: they work alongside these people and need to look them up. What they do not hold
          is FLEET_DRIVER_MANAGE - so no registering, editing or retiring, including of themselves -
          and no FLEET_DRIVER_SENSITIVE_READ, so licence numbers arrive masked from the service
          rather than being hidden by this screen.

          Medical clearance dates and eligibility standing are still visible to them, which is a
          deliberate accepted trade rather than an oversight: the register is a working document for
          people who cover each other's trips, and eligibility is why a colleague cannot.
        */
        permission: 'FLEET_DRIVER_READ',
        capability: 'FLEET_DRIVER_MANAGE',
      },
    ],
  },
  {
    heading: 'Assurance',
    programme: 'FTLMP',
    system: 'S166',
    items: [
      {
        label: 'Compliance & service',
        to: fleetPaths.compliance,
        icon: 'shield-check',
        description: 'Documents, servicing and expiry',
        // Enforced by the compliance and service-record endpoints. A driver holds neither.
        permission: 'FLEET_COMPLIANCE_MANAGE',
      },
      {
        label: 'Evidence & audit',
        to: fleetPaths.evidence,
        icon: 'document',
        description: 'Closure evidence and audit trail',
        // FLEET_EVIDENCE_READ, not FLEET_EVIDENCE_REGISTER. A driver holds the second - they attach
        // evidence to their own trip closure - and that is deliberately not a licence to read the
        // fleet's evidence library or replay the audit chain.
        permission: 'FLEET_EVIDENCE_READ',
      },
      {
        label: 'Integration health',
        to: fleetPaths.integrations,
        icon: 'cloud',
        description: 'Inbound and outbound message flow',
        // Enforced by FleetIntegrationApplicationService.
        permission: 'FLEET_INTEGRATION_HEALTH_READ',
        capability: 'FLEET_INTEGRATION_REPLAY',
      },
    ],
  },
  {
    heading: 'Fuel & driver logbooks',
    programme: 'FTLMP',
    system: 'S168',
    items: [
      {
        label: 'Fuel dashboard',
        to: fuelPaths.dashboard,
        icon: 'fuel',
        description: 'Spend, volume and reconciliation standing',
        // Enforced by FuelApplicationService.dashboard.
        permission: 'FUEL_REPORT_READ',
        capability: [
          'FUEL_TRANSACTION_CAPTURE',
          'FUEL_RECONCILIATION_RUN',
          'FUEL_POLICY_MANAGE',
          'FUEL_CARD_MANAGE',
          'FUEL_ANOMALY_MANAGE',
        ],
      },
      {
        label: 'Fuel transactions',
        to: fuelPaths.transactions,
        icon: 'coins',
        matchPrefix: fuelPaths.transactions,
        description: 'Captured, imported and provider transactions',
        // Driver-only actors are narrowed by FuelApplicationService.transactions, so this register
        // can be safely shown to drivers without leaking colleagues' fills.
        permission: 'FUEL_TRANSACTION_READ',
        capability: [
          'FUEL_TRANSACTION_CAPTURE',
          'FUEL_TRANSACTION_VOID',
          'FUEL_TRANSACTION_IMPORT',
        ],
      },
      {
        label: 'Driver logbooks',
        to: fuelPaths.logbooks,
        icon: 'book',
        matchPrefix: fuelPaths.logbooks,
        description: 'Journey records through review to approval',
        // Narrowed per record in SQL by FuelApplicationService.logbooks on created_by, so a driver
        // holding this genuinely sees only their own.
        permission: 'FUEL_LOGBOOK_READ',
        capability: [
          'FUEL_LOGBOOK_REVIEW',
          'FUEL_LOGBOOK_REOPEN',
          // The driver's own logbook. FuelApplicationService.logbooks narrows per created_by, so a
          // driver sees theirs and a reviewer sees the site's - one screen, two scopes.
          'FUEL_LOGBOOK_CREATE',
          'FUEL_LOGBOOK_SUBMIT',
        ],
      },
      {
        label: 'Reconciliation',
        to: fuelPaths.reconciliation,
        icon: 'scale',
        description: 'Run the policy rules and read the outcome',
        permission: 'FUEL_RECONCILIATION_RUN',
      },
      {
        label: 'Anomaly cases',
        to: fuelPaths.anomalies,
        icon: 'alert-triangle',
        matchPrefix: fuelPaths.anomalies,
        description: 'Exception queue, explanation and closure',
        permission: 'FUEL_ANOMALY_READ',
        capability: [
          'FUEL_ANOMALY_MANAGE',
          'FUEL_ANOMALY_APPROVE',
          'FUEL_ANOMALY_ESCALATE',
        ],
      },
      {
        label: 'Fuel cards',
        to: fuelPaths.cards,
        icon: 'shield-lock',
        matchPrefix: fuelPaths.cards,
        description: 'Masked card register, assignments and card limits',
        permission: 'FUEL_CARD_READ',
        capability: 'FUEL_CARD_MANAGE',
      },
      {
        label: 'CSV imports',
        to: fuelPaths.imports,
        icon: 'upload',
        description: 'Bulk capture with row-level outcomes',
        permission: 'FUEL_TRANSACTION_IMPORT',
      },
      {
        label: 'Fuel policies',
        to: fuelPaths.policies,
        icon: 'shield-check',
        matchPrefix: fuelPaths.policies,
        description: 'Effective-dated limits the rules are read from',
        // The limits every reconciliation is judged against. A driver being judged by them is not a
        // reason to let them read - still less edit - the thresholds.
        permission: 'FUEL_POLICY_READ',
        capability: 'FUEL_POLICY_MANAGE',
      },
      {
        label: 'Provider integration',
        to: fuelPaths.integrations,
        icon: 'cloud',
        description: 'Provider ingest and outbound publication',
        permission: 'FUEL_INTEGRATION_REPLAY',
      },
    ],
  },
  {
    heading: 'Courier & dispatch',
    programme: 'FTLMP',
    system: 'S171',
    items: [
      {
        label: 'Dispatch dashboard',
        to: dispatchPaths.dashboard,
        icon: 'dashboard',
        description: 'Consignments in transit and open exceptions',
        // Enforced by DispatchDashboardService.
        permission: 'DISPATCH_REPORT_READ',
        capability: [
          'DISPATCH_ITEM_MANAGE',
          'DISPATCH_EXCEPTION_MANAGE',
          'DISPATCH_MANIFEST_CREATE',
        ],
      },
      {
        label: 'Courier items',
        to: dispatchPaths.items,
        icon: 'package',
        matchPrefix: dispatchPaths.items,
        description: 'Every tracked item, inbound and outbound',
        permission: 'DISPATCH_ITEM_READ',
        capability: [
          'DISPATCH_ITEM_MANAGE',
          'DISPATCH_ITEM_REGISTER',
        ],
      },
      {
        label: 'Manifests',
        to: dispatchPaths.manifests,
        icon: 'clipboard-list',
        matchPrefix: dispatchPaths.manifests,
        description: 'Seals, custody, receipt and the return leg',
        permission: 'DISPATCH_MANIFEST_READ',
        capability: [
          'DISPATCH_MANIFEST_CREATE',
          'DISPATCH_CUSTODY_RECORD',
        ],
      },
      {
        label: 'Inbound mail',
        to: dispatchPaths.inbound,
        icon: 'inbox',
        description: 'Registration and acknowledged distribution',
        permission: 'DISPATCH_ITEM_READ',
        capability: [
          'DISPATCH_INBOUND_REGISTER',
          'DISPATCH_INBOUND_DISTRIBUTE',
        ],
      },
      {
        label: 'Exception cases',
        to: dispatchPaths.exceptions,
        icon: 'alert-triangle',
        matchPrefix: dispatchPaths.exceptions,
        description: 'Custody gaps, variances and discrepancies',
        permission: 'DISPATCH_EXCEPTION_READ',
        capability: [
          'DISPATCH_EXCEPTION_MANAGE',
          'DISPATCH_EXCEPTION_APPROVE',
          'DISPATCH_EXCEPTION_ESCALATE',
        ],
      },
      {
        label: 'Scan imports',
        to: dispatchPaths.scans,
        icon: 'upload',
        description: 'Scanner batches and per-row outcomes',
        permission: 'DISPATCH_MANIFEST_READ',
        capability: [
          'DISPATCH_INTEGRATION_INGEST',
          'DISPATCH_MANIFEST_CREATE',
        ],
      },
      {
        label: 'Scanner integration',
        to: dispatchPaths.integrations,
        icon: 'cloud',
        description: 'Scanner and carrier feeds, outbound publication',
        permission: 'DISPATCH_INTEGRATION_REPLAY',
      },
    ],
  },
  {
    heading: 'Visitor management',
    programme: 'SSEMP',
    system: 'S160',
    items: [
      {
        label: 'Visitor dashboard',
        to: visitorPaths.dashboard,
        icon: 'user-plus',
        description: 'Arrivals, approvals and watchlist flags',
        permission: 'VISITOR_VISIT_READ',
        capability: ['VISITOR_VISIT_CREATE', 'VISITOR_VISIT_APPROVE', 'VISITOR_BADGE_ASSIGN', 'VISITOR_REPORT_READ'],
      },
      {
        label: 'Visit register',
        to: visitorPaths.visits,
        icon: 'calendar',
        matchPrefix: visitorPaths.visits,
        description: 'Pre-registration through check-out',
        permission: 'VISITOR_VISIT_READ',
        capability: ['VISITOR_VISIT_CREATE', 'VISITOR_VISIT_APPROVE', 'VISITOR_CHECKIN', 'VISITOR_CHECKOUT', 'VISITOR_REPORT_READ'],
      },
      {
        label: 'Visitor roll call',
        to: visitorPaths.rollCall,
        icon: 'users',
        description: 'People currently checked in by site',
        permission: 'VISITOR_ROLLCALL_READ',
      },
    ],
  },
  {
    heading: 'Incident management',
    programme: 'SSEMP',
    system: 'S163',
    items: [
      {
        label: 'Incident dashboard',
        to: incidentPaths.dashboard,
        icon: 'shield-check',
        description: 'Case standing and severity concentration',
        permission: 'INCIDENT_REPORT_READ',
        capability: ['INCIDENT_TRIAGE', 'INCIDENT_INVESTIGATE', 'INCIDENT_REPORT_EXPORT'],
      },
      {
        label: 'Incidents & near misses',
        to: incidentPaths.cases,
        icon: 'alert-triangle',
        matchPrefix: incidentPaths.cases,
        description: 'Report, investigate, correct and close',
        permission: 'INCIDENT_REPORT_READ',
        capability: ['INCIDENT_REPORT_CREATE', 'INCIDENT_TRIAGE', 'INCIDENT_INVESTIGATE', 'INCIDENT_CAPA_MANAGE'],
      },
    ],
  },
  {
    // SSEMP, not FTLMP. S174 is its own deployable service but it belongs to the safety,
    // security and emergency programme - so a fleet operator does not see it, and a SOC
    // operator or emergency coordinator does. ADR 0005.
    heading: 'Emergency notifications',
    programme: 'SSEMP',
    system: 'S174',
    items: [
      {
        label: 'Emergency dashboard',
        to: emergencyPaths.dashboard,
        icon: 'siren',
        description: 'Live broadcasts and outstanding obligations',
        // Enforced by EmergencyDashboardService.
        permission: 'EMERGENCY_REPORT_READ',
        capability: [
          'EMERGENCY_ACTIVATION_CREATE',
          'EMERGENCY_ACTIVATION_APPROVE',
          'EMERGENCY_ACTIVATION_SEND',
        ],
      },
      {
        label: 'Activations',
        to: emergencyPaths.activations,
        icon: 'megaphone',
        matchPrefix: emergencyPaths.activations,
        description: 'Compose, approve, send, stand down and close',
        permission: 'EMERGENCY_ACTIVATION_READ',
        capability: [
          'EMERGENCY_ACTIVATION_CREATE',
          'EMERGENCY_ACTIVATION_APPROVE',
          'EMERGENCY_ACTIVATION_SEND',
          'EMERGENCY_ALL_CLEAR_SEND',
        ],
      },
      {
        label: 'Break glass',
        to: emergencyPaths.breakGlass,
        icon: 'zap',
        description: 'Declared-emergency send with no approval',
        // The one screen in the platform that sends without approval. It is offered only to an
        // actor who may actually press it - a break-glass page somebody cannot use is worse than
        // absent, because in a declared emergency they will try.
        permission: 'EMERGENCY_BREAK_GLASS_SEND',
      },
      {
        label: 'Templates & scenarios',
        to: emergencyPaths.templates,
        icon: 'document',
        matchPrefix: emergencyPaths.templates,
        description: 'What a broadcast says, and what cites it',
        permission: 'EMERGENCY_TEMPLATE_READ',
        capability: [
          'EMERGENCY_TEMPLATE_MANAGE',
          'EMERGENCY_SCENARIO_MANAGE',
        ],
      },
      {
        label: 'Audiences & zones',
        to: emergencyPaths.audiences,
        icon: 'users',
        description: 'Who a broadcast reaches, and where',
        permission: 'EMERGENCY_AUDIENCE_READ',
        capability: 'EMERGENCY_AUDIENCE_MANAGE',
      },
      {
        label: 'Drills',
        to: emergencyPaths.drills,
        icon: 'target',
        description: 'Rehearsals and notification performance',
        permission: 'EMERGENCY_REPORT_READ',
        capability: [
          'EMERGENCY_ACTIVATION_CREATE',
          'EMERGENCY_AFTER_ACTION_APPROVE',
        ],
      },
      {
        label: 'Provider integration',
        to: emergencyPaths.integrations,
        icon: 'cloud',
        description: 'Outbound publication and the callback path',
        permission: 'EMERGENCY_INTEGRATION_REPLAY',
      },
    ],
  },
];

/**
 * How the application names itself.
 *
 * `module` is no longer fixed: which programme an operator is looking at depends on what they are
 * entitled to, so the shell reads it from `portalLabel()` rather than from a constant that was only
 * ever true for a fleet user.
 */
export const directorate = {
  name: 'Safety, Facilities & Logistics',
  shortName: 'SFL Operations',
  parentOrganisation: 'CLET',
};

/** The navigation sections this actor is entitled to, in declared order. */
/** No persona named means "everyone who is entitled" - the ordinary case. */
const suitsPersona = (persona?: PersonaCode): boolean => persona === undefined || isPersona(persona);

/**
 * Whether this screen belongs to this actor.
 *
 * Read first - it is still a floor, and a screen you cannot read is not one you can work. Then the
 * capability, which is the part that tells a driver from a fleet manager. A reviewer passes the
 * second test by holding a reviewing permission, because reading is their work rather than a
 * diminished version of somebody else's.
 */
const offered = (item: NavItem): boolean => {
  if (!permits(item.permission)) {
    return false;
  }
  if (!item.capability) {
    return true;
  }
  return permitsAny(item.capability) || actorIsReviewer();
};

export const entitledSections = (): NavSection[] =>
  navSections
    /*
      Scope to the platform this origin serves, before entitlement is considered at all.

      A section can be entitled to the actor and still have no business here: the same bundle is
      served by four jars, and on 8091 the fleet sections have no API behind them. Offering them
      produced "Could not reach the Fleet & Logistics service at http://localhost:8093" from a
      dashboard the operator had opened to look at facilities - a true message about a screen that
      should never have been on that origin. The portal answers ALL and keeps everything.
    */
    .filter((section) => servesPlatform(section.programme))
    .filter((section) => entitledTo(section.programme) && entitledToSystem(section.system))
    // Then drop the items the actor cannot act on, and any section left with none - an empty heading
    // is worse than no heading. `permits` now returns false when the services could not be asked, so
    // a failed lookup hides everything and the shell says why; it no longer shows the whole sidebar.
    .map((section) => ({
      ...section,
      items: section.items.filter((item) => offered(item) && suitsPersona(item.persona)),
    }))
    .filter((section) => section.items.length > 0);

/**
 * Where an actor lands when they open the application.
 *
 * The first destination of the first programme they are entitled to - **not** the fleet dashboard,
 * which is only the right answer for a fleet user. `null` when they are entitled to nothing, which
 * the router turns into an explanation rather than a redirect loop.
 */
export const landingPath = (): string | null =>
  entitledSections()[0]?.items[0]?.to ?? null;


/**
 * Whether the screen at this path is one the actor may work, by the same rule the sidebar uses.
 *
 * <p>Matched by longest prefix rather than exactly, so a detail page inherits its register's answer:
 * `/fleetvehicle/fleet/vehicles/42` is the vehicle register as far as entitlement is concerned, and
 * declaring the rule twice is how the two drift apart.
 *
 * <p>`null` when no navigation item owns the path - a screen reached only from inside another, with
 * no rule of its own to apply. The service still authorises it; this returns "no opinion" rather
 * than inventing one.
 */
export const navItemFor = (pathname: string): NavItem | null => {
  let best: NavItem | null = null;
  for (const section of navSections) {
    for (const item of section.items) {
      if (pathname === item.to || pathname.startsWith(`${item.to}/`)) {
        if (!best || item.to.length > best.to.length) {
          best = item;
        }
      }
    }
  }
  return best;
};

/** `true` when a navigation item owns this path and the actor may not work it. */
export const capabilityRefusedFor = (pathname: string): boolean => {
  const item = navItemFor(pathname);
  return item ? !offered(item) : false;
};
