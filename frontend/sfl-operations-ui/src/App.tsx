import { Suspense, lazy } from 'react';
import { BrowserRouter, Navigate, Outlet, Route, Routes } from 'react-router';
import { Spinner } from 'shared/components/DataState';
import { NotifierProvider } from 'shared/components/Notifier';
import AppShell from 'shared/layout/AppShell';
import RequireSession from 'shared/auth/RequireSession';
import RequireEntitlement, { NoProgrammePage } from 'shared/layout/RequireEntitlement';
import type { SystemCode } from 'shared/layout/programmes';
import { landingPath } from 'shared/layout/navigation';
import NotFoundPage from 'shared/pages/NotFoundPage';
import ScrollToTop from 'shared/layout/ScrollToTop';
import LegacyRouteRedirect, { LEGACY_ROUTES } from 'shared/layout/LegacyRouteRedirect';

const LoginPage = lazy(() => import('shared/pages/LoginPage'));
const DriverDayPage = lazy(() => import('modules/me/pages/DriverDayPage'));
const MyRequestsPage = lazy(() => import('modules/me/pages/MyRequestsPage'));
const MyQueuePage = lazy(() => import('modules/me/pages/MyQueuePage'));
const MailroomPage = lazy(() => import('modules/me/pages/MailroomPage'));
const CentreReceiptsPage = lazy(() => import('modules/me/pages/CentreReceiptsPage'));
const AssurancePage = lazy(() => import('modules/me/pages/AssurancePage'));
const FacilitiesDashboardPage = lazy(() => import('modules/facilities/pages/FacilitiesDashboardPage'));
// S153 CMMS
const FaultRegisterPage = lazy(() => import('modules/facilities/pages/FaultRegisterPage'));
const FaultDetailPage = lazy(() => import('modules/facilities/pages/FaultDetailPage'));
const WorkOrderQueuePage = lazy(() => import('modules/facilities/pages/WorkOrderQueuePage'));
const WorkOrderDetailPage = lazy(() => import('modules/facilities/pages/WorkOrderDetailPage'));
const PreventiveSchedulesPage = lazy(() => import('modules/facilities/pages/PreventiveSchedulesPage'));
const ScheduleDetailPage = lazy(() => import('modules/facilities/pages/ScheduleDetailPage'));
const MaintenanceVendorsPage = lazy(() => import('modules/facilities/pages/MaintenanceVendorsPage'));
const EvidenceDetailPage = lazy(() => import('modules/facilities/pages/EvidenceDetailPage'));
const SiteRegisterPage = lazy(() => import('modules/facilities/pages/SiteRegisterPage'));
const SiteDetailPage = lazy(() => import('modules/facilities/pages/SiteDetailPage'));
const BuildingDetailPage = lazy(() => import('modules/facilities/pages/BuildingDetailPage'));
const SpaceRegisterPage = lazy(() => import('modules/facilities/pages/SpaceRegisterPage'));
const SpaceDetailPage = lazy(() => import('modules/facilities/pages/SpaceDetailPage'));
const AssetRegisterPage = lazy(() => import('modules/facilities/pages/AssetRegisterPage'));
const AssetDetailPage = lazy(() => import('modules/facilities/pages/AssetDetailPage'));
const ZonesPage = lazy(() => import('modules/facilities/pages/ZonesPage'));
const DeviceReferencesPage = lazy(() => import('modules/facilities/pages/DeviceReferencesPage'));
const ReadinessAssessmentsPage = lazy(() => import('modules/facilities/pages/ReadinessAssessmentsPage'));
const ReadinessAssessmentDetailPage = lazy(
  () => import('modules/facilities/pages/ReadinessAssessmentDetailPage'),
);
const ReadinessChecklistsPage = lazy(() => import('modules/facilities/pages/ReadinessChecklistsPage'));
const ReadinessChecklistDetailPage = lazy(
  () => import('modules/facilities/pages/ReadinessChecklistDetailPage'),
);
const FacilitiesAuditPage = lazy(() => import('modules/facilities/pages/FacilitiesAuditPage'));
const FacilitiesConfigurationPage = lazy(
  () => import('modules/facilities/pages/FacilitiesConfigurationPage'),
);

// S159 room and resource booking
const BookingDiaryPage = lazy(() => import('modules/booking/pages/BookingDiaryPage'));
const BookingDetailPage = lazy(() => import('modules/booking/pages/BookingDetailPage'));
const AvailabilitySearchPage = lazy(() => import('modules/booking/pages/AvailabilitySearchPage'));
const BookableResourcesPage = lazy(() => import('modules/booking/pages/BookableResourcesPage'));
const SetupTaskQueuePage = lazy(() => import('modules/booking/pages/SetupTaskQueuePage'));

// Phase 2 IFIMP
const BuildingSystemsPage = lazy(() => import('modules/ifimp/pages/phase2Pages').then((module) => ({ default: module.BuildingSystemsPage })));
const EnergyPage = lazy(() => import('modules/ifimp/pages/phase2Pages').then((module) => ({ default: module.EnergyPage })));
const SpacePlanningPage = lazy(() => import('modules/ifimp/pages/phase2Pages').then((module) => ({ default: module.SpacePlanningPage })));
const CleaningPage = lazy(() => import('modules/ifimp/pages/phase2Pages').then((module) => ({ default: module.CleaningPage })));
const EventLogisticsPage = lazy(() => import('modules/ifimp/pages/phase2Pages').then((module) => ({ default: module.EventLogisticsPage })));
const ConstructionPage = lazy(() => import('modules/ifimp/pages/phase2Pages').then((module) => ({ default: module.ConstructionPage })));

const FleetDashboardPage = lazy(() => import('modules/fleet/pages/FleetDashboardPage'));
const VehicleRegisterPage = lazy(() => import('modules/fleet/pages/VehicleRegisterPage'));
const VehicleDetailPage = lazy(() => import('modules/fleet/pages/VehicleDetailPage'));
const DriverRegisterPage = lazy(() => import('modules/fleet/pages/DriverRegisterPage'));
const DriverDetailPage = lazy(() => import('modules/fleet/pages/DriverDetailPage'));
const TripQueuePage = lazy(() => import('modules/fleet/pages/TripQueuePage'));
const TripDetailPage = lazy(() => import('modules/fleet/pages/TripDetailPage'));
const WorkflowQueuePage = lazy(() => import('modules/fleet/pages/WorkflowQueuePage'));
const WorkflowDetailPage = lazy(() => import('modules/fleet/pages/WorkflowDetailPage'));
const CompliancePage = lazy(() => import('modules/fleet/pages/CompliancePage'));
const EvidenceAuditPage = lazy(() => import('modules/fleet/pages/EvidenceAuditPage'));
const IntegrationHealthPage = lazy(() => import('modules/fleet/pages/IntegrationHealthPage'));

const FuelDashboardPage = lazy(() => import('modules/fuel/pages/FuelDashboardPage'));
const FuelTransactionsPage = lazy(() => import('modules/fuel/pages/FuelTransactionsPage'));
const FuelTransactionDetailPage = lazy(
  () => import('modules/fuel/pages/FuelTransactionDetailPage'),
);
const DriverLogbooksPage = lazy(() => import('modules/fuel/pages/DriverLogbooksPage'));
const DriverLogbookDetailPage = lazy(() => import('modules/fuel/pages/DriverLogbookDetailPage'));
const FuelReconciliationPage = lazy(() => import('modules/fuel/pages/FuelReconciliationPage'));
const FuelAnomaliesPage = lazy(() => import('modules/fuel/pages/FuelAnomaliesPage'));
const FuelAnomalyDetailPage = lazy(() => import('modules/fuel/pages/FuelAnomalyDetailPage'));
const FuelCardsPage = lazy(() => import('modules/fuel/pages/FuelCardsPage'));
const FuelImportsPage = lazy(() => import('modules/fuel/pages/FuelImportsPage'));
const FuelPoliciesPage = lazy(() => import('modules/fuel/pages/FuelPoliciesPage'));
const FuelPolicyDetailPage = lazy(() => import('modules/fuel/pages/FuelPolicyDetailPage'));
const FuelIntegrationPage = lazy(() => import('modules/fuel/pages/FuelIntegrationPage'));

const DispatchDashboardPage = lazy(() => import('modules/dispatch/pages/DispatchDashboardPage'));
const CourierItemsPage = lazy(() => import('modules/dispatch/pages/CourierItemsPage'));
const CourierItemDetailPage = lazy(() => import('modules/dispatch/pages/CourierItemDetailPage'));
const ManifestsPage = lazy(() => import('modules/dispatch/pages/ManifestsPage'));
const ManifestDetailPage = lazy(() => import('modules/dispatch/pages/ManifestDetailPage'));
const InboundMailPage = lazy(() => import('modules/dispatch/pages/InboundMailPage'));
const DispatchExceptionsPage = lazy(() => import('modules/dispatch/pages/DispatchExceptionsPage'));
const DispatchExceptionDetailPage = lazy(
  () => import('modules/dispatch/pages/DispatchExceptionDetailPage'),
);
const ScanImportsPage = lazy(() => import('modules/dispatch/pages/ScanImportsPage'));
const DispatchIntegrationPage = lazy(
  () => import('modules/dispatch/pages/DispatchIntegrationPage'),
);

const EmergencyDashboardPage = lazy(
  () => import('modules/emergency/pages/EmergencyDashboardPage'),
);
const ActivationsPage = lazy(() => import('modules/emergency/pages/ActivationsPage'));
const ActivationDetailPage = lazy(() => import('modules/emergency/pages/ActivationDetailPage'));
const BreakGlassPage = lazy(() => import('modules/emergency/pages/BreakGlassPage'));
const EmergencyTemplatesPage = lazy(
  () => import('modules/emergency/pages/EmergencyTemplatesPage'),
);
const EmergencyTemplateDetailPage = lazy(
  () => import('modules/emergency/pages/EmergencyTemplateDetailPage'),
);
const EmergencyAudiencesPage = lazy(
  () => import('modules/emergency/pages/EmergencyAudiencesPage'),
);
const EmergencyDrillsPage = lazy(() => import('modules/emergency/pages/EmergencyDrillsPage'));
const EmergencyIntegrationPage = lazy(
  () => import('modules/emergency/pages/EmergencyIntegrationPage'),
);

const VisitorDashboardPage = lazy(() => import('modules/visitor/pages/VisitorDashboardPage'));
const VisitorVisitsPage = lazy(() => import('modules/visitor/pages/VisitorVisitsPage'));
const VisitorVisitDetailPage = lazy(() => import('modules/visitor/pages/VisitorVisitDetailPage'));
const VisitorRollCallPage = lazy(() => import('modules/visitor/pages/VisitorRollCallPage'));

const IncidentDashboardPage = lazy(() => import('modules/incident/pages/IncidentDashboardPage'));
const IncidentsPage = lazy(() => import('modules/incident/pages/IncidentsPage'));
const IncidentDetailPage = lazy(() => import('modules/incident/pages/IncidentDetailPage'));

/**
 * The router basename comes from Vite's `BASE_URL`, which is set by `base` in `vite.config.ts`.
 * Keeping it derived means the mount point is stated once: move the bundle and the routes follow.
 */
const basename = import.meta.env.BASE_URL.replace(/\/$/, '');

const PageFallback = () => (
  <div className="flex min-h-[60vh] items-center justify-center">
    <Spinner size={30} />
  </div>
);

/**
 * A system's routes, refused when the actor is not entitled to it.
 *
 * The wrapper sits on the parent route so every child inherits it - there is no way to add a screen
 * under `fleet` or `emergency` and forget the check.
 *
 * It takes the **system**, not the programme, because the system is the more specific fact and the
 * programme follows from it. Passing both would let a route claim `dispatch` belongs to SSEMP; the
 * model owns that mapping instead. It is a usability control, not the enforcement point: the services
 * authorise every call regardless. See `RequireEntitlement` and ADR 0005.
 */
const SystemRoutes = ({ system }: { system: SystemCode }) => (
  <RequireEntitlement system={system}>
    <Outlet />
  </RequireEntitlement>
);

const App = () => {
  // Where this actor lands, which is their first entitled destination rather than the fleet
  // dashboard - that is only the right answer for a fleet user.
  const home = landingPath();

  return (
  <BrowserRouter basename={basename}>
    <ScrollToTop />
    <NotifierProvider>
      <Suspense fallback={<PageFallback />}>
        <Routes>
          {/*
            Outside the shell on purpose: the sign-in page has no sidebar, no top bar and no actor to
            build them from, and it is the one route that must render when nothing else can.

            It used to sit under /fleetvehicle, which was true when one deployable served the bundle
            and became a lie when four did: the safety-security service sent people to
            /home/fleetvehicle/login to sign in to safety-security. The path names no platform now,
            because sign-in belongs to none of them. LEGACY_ROUTES keeps the old address working.
          */}
          <Route path="login" element={<LoginPage />} />

          {/*
            The pre-platform URLs, kept working.

            Declared before the shell so they redirect without needing a session first: an
            unauthenticated visitor arriving on a bookmarked /fleet/trips lands on the new path and is
            then bounced to sign-in by RequireSession, which returns them there afterwards. Sending
            them through the guard first would lose the destination.

            This is a shim with a lifetime - see LegacyRouteRedirect for when to delete it.
          */}
          {LEGACY_ROUTES.map((route) => (
            <Route
              key={route.from}
              path={`${route.from.slice(1)}/*`}
              element={<LegacyRouteRedirect {...route} />}
            />
          ))}
          {LEGACY_ROUTES.map((route) => (
            <Route
              key={`${route.from}-exact`}
              path={route.from.slice(1)}
              element={<LegacyRouteRedirect {...route} />}
            />
          ))}
          <Route element={<RequireSession><AppShell /></RequireSession>}>
            <Route
              index
              element={home ? <Navigate to={home} replace /> : <NoProgrammePage />}
            />
            {/*
              Personal landings. Outside the SystemRoutes guards on purpose: these cross systems - a
              driver's day is an S166 assignment and an S168 logbook - and every one of them is
              already gated in the sidebar by persona plus permission, with the services enforcing
              per record underneath. A system guard here would have to pick one system arbitrarily
              and would refuse the other half of the page.
            */}
            <Route path="me">
              <Route path="driving" element={<DriverDayPage />} />
              <Route path="requests" element={<MyRequestsPage />} />
              <Route path="queue" element={<MyQueuePage />} />
              <Route path="mailroom" element={<MailroomPage />} />
              <Route path="receipts" element={<CentreReceiptsPage />} />
              <Route path="assurance" element={<AssurancePage />} />
            </Route>
            <Route path="facilities" element={<SystemRoutes system="S152" />}>
              <Route index element={<FacilitiesDashboardPage />} />
              {/*
                S152's registers under `estate`, a sibling of `maintenance` and `bookings`.

                They shared one flat level with S153 before, so a URL could not tell an estate
                register from a maintenance queue - `/facilities/spaces` and `/facilities/faults`
                looked like peers and answered to different systems and different guards. The three
                IFIMP systems now each own a segment, which is the shape FTLMP already had.
              */}
              <Route path="estate">
              <Route path="sites">
                <Route index element={<SiteRegisterPage />} />
                <Route path=":siteId" element={<SiteDetailPage />} />
              </Route>
              {/*
                Buildings have a detail route and no register: a building is only ever reached from
                the site that owns it, and a register would be a screen whose whole content is
                "choose a site first".
              */}
              <Route path="buildings/:buildingId" element={<BuildingDetailPage />} />
              <Route path="spaces">
                <Route index element={<SpaceRegisterPage />} />
                <Route path=":roomId" element={<SpaceDetailPage />} />
              </Route>
              <Route path="assets">
                <Route index element={<AssetRegisterPage />} />
                <Route path=":assetId" element={<AssetDetailPage />} />
              </Route>
              <Route path="zones" element={<ZonesPage />} />
              <Route path="devices" element={<DeviceReferencesPage />} />
              <Route path="assessments">
                <Route index element={<ReadinessAssessmentsPage />} />
                <Route path=":assessmentId" element={<ReadinessAssessmentDetailPage />} />
              </Route>
              <Route path="checklists">
                <Route index element={<ReadinessChecklistsPage />} />
                <Route path=":checklistId" element={<ReadinessChecklistDetailPage />} />
              </Route>
              <Route path="audit" element={<FacilitiesAuditPage />} />
              <Route path="configuration" element={<FacilitiesConfigurationPage />} />
              </Route>
            </Route>
            {/*
              S153 shares the /facilities base with S152 - same service, same programme - but is
              guarded on its own system code, so a role entitled to one and not the other lands on
              the no-entitlement page rather than an empty screen. All of it now sits under
              `maintenance`, evidence included: it used to hang off `/facilities/maintenance-evidence`,
              a fourth spelling of the same idea that existed only because there was nowhere else to
              put it.
            */}
            <Route path="facilities" element={<SystemRoutes system="S153" />}>
              <Route path="maintenance">
                <Route path="faults">
                  <Route index element={<FaultRegisterPage />} />
                  <Route path=":faultId" element={<FaultDetailPage />} />
                </Route>
                <Route path="work-orders">
                  <Route index element={<WorkOrderQueuePage />} />
                  <Route path=":workOrderId" element={<WorkOrderDetailPage />} />
                </Route>
                <Route path="schedules">
                  <Route index element={<PreventiveSchedulesPage />} />
                  <Route path=":scheduleId" element={<ScheduleDetailPage />} />
                </Route>
                <Route path="vendors" element={<MaintenanceVendorsPage />} />
                <Route path="evidence/:evidenceId" element={<EvidenceDetailPage />} />
              </Route>
            </Route>
            {/*
              S159 sits at /bookings rather than under /facilities, and that is the one IFIMP system
              whose route base does not mirror its service. Deliberate: a lecturer booking a hall does
              not think of themselves as visiting facilities, and a path somebody can be told over the
              phone is worth more than a URL that mirrors deployment topology.

              The three static children are declared before `:bookingId`. React Router ranks static
              segments above dynamic ones regardless of order, so this is for the reader rather than
              the router - but a new static child that looks like a UUID would break that, and having
              them together is what makes it obvious.
            */}
            {/*
              Bookings sits under /facilities because it is an IFIMP screen - S159 books the
              spaces S152 registers. It was top-level while the route names followed systems;
              now that they follow platforms, a booking under anything else would be the odd
              one out among the three.
            */}
            <Route path="facilities">
              <Route path="bookings" element={<SystemRoutes system="S159" />}>
                <Route index element={<BookingDiaryPage />} />
                <Route path="availability" element={<AvailabilitySearchPage />} />
                <Route path="resources" element={<BookableResourcesPage />} />
                <Route path="turnaround" element={<SetupTaskQueuePage />} />
                <Route path=":bookingId" element={<BookingDetailPage />} />
              </Route>
            </Route>

            <Route path="facilities">
              <Route path="building-systems" element={<SystemRoutes system="S156" />}>
                <Route index element={<BuildingSystemsPage />} />
              </Route>
              <Route path="energy" element={<SystemRoutes system="S157" />}>
                <Route index element={<EnergyPage />} />
              </Route>
              <Route path="space-planning" element={<SystemRoutes system="S158" />}>
                <Route index element={<SpacePlanningPage />} />
              </Route>
              <Route path="cleaning" element={<SystemRoutes system="S169" />}>
                <Route index element={<CleaningPage />} />
              </Route>
              <Route path="event-logistics" element={<SystemRoutes system="S173" />}>
                <Route index element={<EventLogisticsPage />} />
              </Route>
              <Route path="construction" element={<SystemRoutes system="S176" />}>
                <Route index element={<ConstructionPage />} />
              </Route>
            </Route>
            {/*
              One parent for FTLMP - fleet, fuel and dispatch are three systems in one
              deployable, and the URL now says so. Each keeps its own SystemRoutes guard, so
              entitlement is unchanged; only the address moved.
            */}
            <Route path="fleetvehicle">
              <Route path="fleet" element={<SystemRoutes system="S166" />}>
                <Route index element={<FleetDashboardPage />} />
                <Route path="vehicles">
                  <Route index element={<VehicleRegisterPage />} />
                  <Route path=":vehicleId" element={<VehicleDetailPage />} />
                </Route>
                <Route path="drivers">
                  <Route index element={<DriverRegisterPage />} />
                  <Route path=":driverId" element={<DriverDetailPage />} />
                </Route>
                <Route path="trips">
                  <Route index element={<TripQueuePage />} />
                  <Route path=":tripId" element={<TripDetailPage />} />
                </Route>
                <Route path="workflow">
                  <Route index element={<WorkflowQueuePage />} />
                  <Route path=":itemId" element={<WorkflowDetailPage />} />
                </Route>
                <Route path="compliance" element={<CompliancePage />} />
                <Route path="evidence" element={<EvidenceAuditPage />} />
                <Route path="integrations" element={<IntegrationHealthPage />} />
              </Route>
              <Route path="fuel" element={<SystemRoutes system="S168" />}>
                <Route index element={<FuelDashboardPage />} />
                <Route path="transactions">
                  <Route index element={<FuelTransactionsPage />} />
                  <Route path=":transactionId" element={<FuelTransactionDetailPage />} />
                </Route>
                <Route path="logbooks">
                  <Route index element={<DriverLogbooksPage />} />
                  <Route path=":logbookId" element={<DriverLogbookDetailPage />} />
                </Route>
                <Route path="reconciliation" element={<FuelReconciliationPage />} />
                <Route path="anomalies">
                  <Route index element={<FuelAnomaliesPage />} />
                  <Route path=":anomalyId" element={<FuelAnomalyDetailPage />} />
                </Route>
                <Route path="cards" element={<FuelCardsPage />} />
                <Route path="imports" element={<FuelImportsPage />} />
                <Route path="policies">
                  <Route index element={<FuelPoliciesPage />} />
                  <Route path=":policyId" element={<FuelPolicyDetailPage />} />
                </Route>
                <Route path="integrations" element={<FuelIntegrationPage />} />
              </Route>
              <Route path="dispatch" element={<SystemRoutes system="S171" />}>
                <Route index element={<DispatchDashboardPage />} />
                <Route path="items">
                  <Route index element={<CourierItemsPage />} />
                  <Route path=":itemId" element={<CourierItemDetailPage />} />
                </Route>
                <Route path="manifests">
                  <Route index element={<ManifestsPage />} />
                  <Route path=":manifestId" element={<ManifestDetailPage />} />
                </Route>
                <Route path="inbound" element={<InboundMailPage />} />
                <Route path="exceptions">
                  <Route index element={<DispatchExceptionsPage />} />
                  <Route path=":caseId" element={<DispatchExceptionDetailPage />} />
                </Route>
                <Route path="scans" element={<ScanImportsPage />} />
                <Route path="integrations" element={<DispatchIntegrationPage />} />
              </Route>
            </Route>
            {/*
              S174 under `emergency`, so SSEMP's remaining systems - S160 to S163 - have somewhere to
              land that is not a collision. The service is named for the platform; the segment is
              named for the system, which is the same split FTLMP and IFIMP now use.
            */}
            <Route path="safetysecurity">
              <Route path="visitors" element={<SystemRoutes system="S160" />}>
                <Route index element={<VisitorDashboardPage />} />
                <Route path="visits">
                  <Route index element={<VisitorVisitsPage />} />
                  <Route path=":visitId" element={<VisitorVisitDetailPage />} />
                </Route>
                <Route path="roll-call" element={<VisitorRollCallPage />} />
              </Route>
              <Route path="incidents" element={<SystemRoutes system="S163" />}>
                <Route index element={<IncidentDashboardPage />} />
                <Route path="cases">
                  <Route index element={<IncidentsPage />} />
                  <Route path=":incidentId" element={<IncidentDetailPage />} />
                </Route>
              </Route>
              <Route path="emergency" element={<SystemRoutes system="S174" />}>
              <Route index element={<EmergencyDashboardPage />} />
              <Route path="activations">
                <Route index element={<ActivationsPage />} />
                <Route path=":activationId" element={<ActivationDetailPage />} />
              </Route>
              <Route path="break-glass" element={<BreakGlassPage />} />
              <Route path="templates">
                <Route index element={<EmergencyTemplatesPage />} />
                <Route path=":templateId" element={<EmergencyTemplateDetailPage />} />
              </Route>
              <Route path="audiences" element={<EmergencyAudiencesPage />} />
              <Route path="drills" element={<EmergencyDrillsPage />} />
              <Route path="integrations" element={<EmergencyIntegrationPage />} />
              </Route>
            </Route>
            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Routes>
      </Suspense>
    </NotifierProvider>
  </BrowserRouter>
  );
};

export default App;
