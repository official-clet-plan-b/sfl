import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { TripResponse } from 'modules/fleet/api/dto';
import { humanise } from 'modules/fleet/api/enums';
import { driversApi, tripsApi, vehiclesApi } from 'modules/fleet/api/fleetApi';
import {
  canAcknowledgeTrips,
  canAssignTrips,
  canCancelTrips,
  canCloseTrips,
  canManageTrips,
  canRecordInspections,
  canReportOwnTripLocation,
  canStartOwnTrip,
} from 'modules/fleet/api/access';
import TripRouteMap from 'modules/fleet/components/TripRouteMap';
import {
  AcknowledgeTripDialog,
  AssignTripDialog,
  CancelTripDialog,
  CloseTripDialog,
  HoldTripDialog,
  RecordInspectionDialog,
  StartTripDialog,
} from 'modules/fleet/dialogs/tripDialogs';
import { useOwnLocationReporting } from 'modules/fleet/hooks/useOwnLocationReporting';
import Alert from 'shared/components/Alert';
import Button from 'shared/components/Button';
import DataState from 'shared/components/DataState';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { useNotifier } from 'shared/components/Notifier';
import PageHeader from 'shared/components/PageHeader';
import SectionCard from 'shared/components/SectionCard';
import StatusChip from 'shared/components/StatusChip';
import WorkflowTimeline, { TimelineEntry } from 'shared/components/WorkflowTimeline';
import { formatDateTime, formatNumber, formatOdometer } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fleetPaths } from 'shared/layout/navigation';

type DialogKey =
  | 'assign'
  | 'start'
  | 'close'
  | 'cancel'
  | 'hold'
  | 'resume'
  | 'inspection'
  | 'confirm'
  | 'defer'
  | null;

/** Which transitions the trip's current status permits - mirrors the service's transition policy. */
const permitted = (trip: TripResponse) => ({
  assign: ['PLANNED', 'ASSIGNED', 'ON_HOLD'].includes(trip.status),
  start: trip.status === 'ASSIGNED',
  hold: ['ASSIGNED', 'IN_PROGRESS'].includes(trip.status),
  resume: trip.status === 'ON_HOLD',
  close: trip.status === 'IN_PROGRESS',
  cancel: ['PLANNED', 'ASSIGNED', 'ON_HOLD'].includes(trip.status),
  inspect: ['PLANNED', 'ASSIGNED', 'IN_PROGRESS'].includes(trip.status),
});

/**
 * Whether to offer the driver's own confirm/defer controls.
 *
 * <h2>Three conditions, and the middle one is the interesting one</h2>
 *
 * The permission is necessary and not sufficient. The service also requires the signed-in identity to
 * be bound to the driver on this trip - so `canAcknowledgeTrips()` alone would put the button on
 * every driver's screen for every trip they can open.
 *
 * The check for "is this mine" is `!canManageTrips()`, which reads oddly and is exactly right: a
 * driver-only actor can only *load* a trip that is theirs, because the by-id read narrows to the
 * driver bound to their sign-in and refuses anything else. So for an actor without the supervising
 * permission, holding the trip in hand is proof it is theirs. A supervisor, who can load anybody's,
 * is deliberately not offered the control - the service refuses them, because a record saying the
 * driver confirmed must mean the driver confirmed.
 *
 * Status must be ASSIGNED: there is nothing to answer for on a trip already under way or finished.
 */
const answerable = (trip: TripResponse) =>
  trip.status === 'ASSIGNED' && canAcknowledgeTrips() && !canManageTrips();

/**
 * Whether to offer the driver's own start control.
 *
 * The same reasoning as {@link answerable}: `canStartOwnTrip()` alone is not enough, because a dual-
 * role actor who also holds the dispatcher's `FLEET_TRIP_MANAGE` can load a trip that is not theirs,
 * and the service refuses their own-scoped start on it. `!canManageTrips()` is what makes "I could
 * load this trip" mean "this trip is mine" for a driver-only actor.
 */
const startableOwn = (trip: TripResponse) =>
  trip.status === 'ASSIGNED' && canStartOwnTrip() && !canManageTrips();

/**
 * Whether this screen should be reporting the device's own position right now.
 *
 * Same reasoning as {@link startableOwn}: the permission alone is not "this is my trip", and the
 * status has to be {@code IN_PROGRESS} - before or after, there is nothing for a device to be
 * reporting. A dispatcher viewing the same trip never reports anything; only the driver's own open
 * tab on their own assigned, moving trip does.
 */
const reportingOwn = (trip: TripResponse) =>
  trip.status === 'IN_PROGRESS' && canReportOwnTripLocation() && !canManageTrips();

/** A related record rendered as a navigable tile - the assignment's vehicle and driver. */
const linkTile = 'block rounded-xl border border-gray-200 p-3 transition hover:border-brand-500';

/**
 * Trip detail - the workflow surface.
 *
 * Buttons are shown only for transitions the current status allows, so an operator is never
 * offered an action the service is certain to reject. The service remains the authority: an
 * attempted transition that the policy refuses still surfaces its error.
 */
const TripDetailPage = () => {
  const { tripId = '' } = useParams();
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();
  const [dialog, setDialog] = useState<DialogKey>(null);
  const tripActions = {
    assign: canAssignTrips(),
    manage: canManageTrips(),
    close: canCloseTrips(),
    cancel: canCancelTrips(),
    inspect: canRecordInspections(),
  };

  const trip = useApiQuery((signal) => tripsApi.findById(tripId, signal), [tripId]);
  const inspections = useApiQuery((signal) => tripsApi.inspections(tripId, signal), [tripId]);
  // Always called, never conditionally - only the argument depends on whether the trip has loaded yet
  // and whether this is the driver's own moving trip.
  useOwnLocationReporting(tripId, Boolean(trip.data && reportingOwn(trip.data)));

  const vehicleId = trip.data?.vehicleId ?? '';
  const driverId = trip.data?.driverId ?? '';

  const vehicle = useApiQuery(
    (signal) => (vehicleId ? vehiclesApi.findById(vehicleId, signal) : Promise.resolve(undefined)),
    [vehicleId],
  );
  const driver = useApiQuery(
    (signal) => (driverId ? driversApi.findById(driverId, signal) : Promise.resolve(undefined)),
    [driverId],
  );

  const refreshAll = () => {
    trip.refetch();
    inspections.refetch();
    vehicle.refetch();
  };

  const timeline: TimelineEntry[] = trip.data
    ? [
        {
          id: 'created',
          title: 'Trip planned',
          detail: `${trip.data.origin} → ${trip.data.destination}`,
          actor: trip.data.createdBy,
          occurredAt: trip.data.createdAt,
        },
        ...(trip.data.actualStart
          ? [
              {
                id: 'started',
                title: 'Trip started',
                detail:
                  trip.data.startOdometer !== null
                    ? `Start odometer ${formatNumber(trip.data.startOdometer)} km`
                    : null,
                occurredAt: trip.data.actualStart,
                tone: 'accent' as const,
              },
            ]
          : []),
        ...(trip.data.holdReason
          ? [
              {
                id: 'held',
                title: 'Placed on hold',
                detail: trip.data.holdReason,
                occurredAt: trip.data.lastModifiedAt ?? trip.data.createdAt,
                tone: 'danger' as const,
              },
            ]
          : []),
        ...(trip.data.actualEnd
          ? [
              {
                id: 'closed',
                title: 'Trip closed',
                detail: trip.data.closureReason,
                occurredAt: trip.data.actualEnd,
              },
            ]
          : []),
        ...(trip.data.cancellationReason
          ? [
              {
                id: 'cancelled',
                title: 'Trip cancelled',
                detail: trip.data.cancellationReason,
                occurredAt: trip.data.lastModifiedAt ?? trip.data.createdAt,
                tone: 'danger' as const,
              },
            ]
          : []),
      ]
    : [];

  return (
    <div>
      <PageHeader
        title={trip.data?.tripNumber ?? 'Trip'}
        subtitle={
          trip.data
            ? `${trip.data.origin} → ${trip.data.destination} · ${trip.data.purpose}`
            : undefined
        }
        crumbs={[
          { label: 'Fleet', to: fleetPaths.dashboard },
          { label: 'Trips', to: fleetPaths.trips },
          { label: trip.data?.tripNumber ?? '…' },
        ]}
        actions={
          <Button variant="outline" startIcon="arrow-left" onClick={() => navigate(fleetPaths.trips)}>
            Trip queue
          </Button>
        }
        meta={
          trip.data && (
            <div className="flex flex-wrap items-center gap-2">
              <StatusChip value={trip.data.status} />
              <StatusChip value={trip.data.operatingMode} tone="neutral" />
              <StatusChip value={trip.data.siteCode} label={trip.data.siteCode} tone="neutral" />
            </div>
          )
        }
      />

      <DataState
        loading={trip.initialising}
        error={trip.error}
        onRetry={trip.refetch}
        minHeight={320}
      >
        {trip.data && (
          <div className="space-y-5">
            {answerable(trip.data) && (
              <SectionCard
                title="Your assignment"
                subtitle="Tell the dispatcher whether you can take this trip"
              >
                {trip.data.acknowledgementState === 'PENDING' ? (
                  <div className="flex flex-wrap items-center gap-2">
                    <Button variant="primary" startIcon="check-circle" onClick={() => setDialog('confirm')}>
                      Confirm
                    </Button>
                    <Button variant="outline" onClick={() => setDialog('defer')}>
                      Defer with reason
                    </Button>
                  </div>
                ) : (
                  <Alert variant={trip.data.acknowledgementState === 'DEFERRED' ? 'warning' : 'success'}>
                    {trip.data.acknowledgementState === 'DEFERRED'
                      ? `You deferred this trip on ${formatDateTime(trip.data.acknowledgedAt)} - ${trip.data.acknowledgementReason}`
                      : `You confirmed this trip on ${formatDateTime(trip.data.acknowledgedAt)}.`}
                  </Alert>
                )}
              </SectionCard>
            )}

            <SectionCard title="Actions" subtitle="Transitions permitted from the current status">
              <div className="flex flex-wrap items-center gap-2">
                {permitted(trip.data).assign && tripActions.assign && (
                  <Button variant="primary" onClick={() => setDialog('assign')}>
                    {trip.data.vehicleId ? 'Reassign' : 'Assign vehicle & driver'}
                  </Button>
                )}
                {permitted(trip.data).inspect && tripActions.inspect && (
                  <Button variant="outline" startIcon="clipboard" onClick={() => setDialog('inspection')}>
                    Record inspection
                  </Button>
                )}
                {permitted(trip.data).start && (tripActions.manage || startableOwn(trip.data)) && (
                  <Button variant="accent" startIcon="play" onClick={() => setDialog('start')}>
                    Start trip
                  </Button>
                )}
                {permitted(trip.data).hold && tripActions.manage && (
                  <Button variant="outline" startIcon="stop" onClick={() => setDialog('hold')}>
                    Place on hold
                  </Button>
                )}
                {permitted(trip.data).resume && tripActions.manage && (
                  <Button variant="outline" startIcon="play" onClick={() => setDialog('resume')}>
                    Resume
                  </Button>
                )}
                {permitted(trip.data).close && tripActions.close && (
                  <Button variant="primary" startIcon="flag" onClick={() => setDialog('close')}>
                    Close trip
                  </Button>
                )}
                {permitted(trip.data).cancel && tripActions.cancel && (
                  <Button variant="danger" startIcon="close" onClick={() => setDialog('cancel')}>
                    Cancel trip
                  </Button>
                )}
                {['COMPLETED', 'CANCELLED'].includes(trip.data.status) && (
                  <p className="text-theme-sm text-gray-600">
                    This trip is {humanise(trip.data.status).toLowerCase()}. Its record is now
                    read-only history.
                  </p>
                )}
              </div>
            </SectionCard>

            {trip.data.vehicleId && !['COMPLETED', 'CANCELLED'].includes(trip.data.status) && (
              <SectionCard
                title="Live route"
                subtitle={
                  reportingOwn(trip.data)
                    ? 'Your position is shared with the fleet office while this trip is in progress'
                    : 'Planned route, and the vehicle\'s current position once the trip is under way'
                }
              >
                <TripRouteMap trip={trip.data} />
              </SectionCard>
            )}

            <div className="grid gap-5 xl:grid-cols-3">
              <SectionCard title="Trip record" className="xl:col-span-2">
                <div className="space-y-5">
                  <KeyValueGrid
                    items={[
                      { label: 'Trip number', value: trip.data.tripNumber },
                      { label: 'Operating mode', value: humanise(trip.data.operatingMode) },
                      { label: 'Site', value: trip.data.siteCode },
                      { label: 'Purpose', value: trip.data.purpose, span: 2 },
                      { label: 'Origin', value: trip.data.origin },
                      { label: 'Destination', value: trip.data.destination },
                      { label: 'Planned start', value: formatDateTime(trip.data.plannedStart) },
                      { label: 'Planned end', value: formatDateTime(trip.data.plannedEnd) },
                      { label: 'Actual start', value: formatDateTime(trip.data.actualStart) },
                      { label: 'Actual end', value: formatDateTime(trip.data.actualEnd) },
                      { label: 'Start odometer', value: formatNumber(trip.data.startOdometer) },
                      { label: 'End odometer', value: formatNumber(trip.data.endOdometer) },
                      {
                        label: 'Distance covered',
                        value: formatOdometer(trip.data.distanceCovered),
                      },
                      { label: 'Record version', value: trip.data.version },
                    ]}
                  />

                  {(trip.data.holdReason ||
                    trip.data.closureReason ||
                    trip.data.cancellationReason) && (
                    <>
                      <div className="h-px bg-gray-200" />
                      <KeyValueGrid
                        columns={2}
                        items={[
                          ...(trip.data.holdReason
                            ? [
                                {
                                  label: 'Hold reason',
                                  value: trip.data.holdReason,
                                  span: 2 as const,
                                },
                              ]
                            : []),
                          ...(trip.data.closureReason
                            ? [
                                {
                                  label: 'Closure reason',
                                  value: trip.data.closureReason,
                                  span: 2 as const,
                                },
                              ]
                            : []),
                          ...(trip.data.closureEvidenceId
                            ? [
                                {
                                  label: 'Closure evidence',
                                  value: trip.data.closureEvidenceId,
                                  span: 2 as const,
                                },
                              ]
                            : []),
                          /*
                            Who ended the journey, and whether that was the driver.

                            The end odometer is an input to the fuel consumption and odometer-jump
                            rules, and a reading taken at the vehicle is a different fact from one
                            relayed by phone and entered at a desk. Both were recorded identically
                            until `closedBy` existed, so this is the line that tells them apart.
                            Matched on the staff reference because that is what an actor id is.
                          */
                          ...(trip.data.closedBy
                            ? [
                                {
                                  label: 'Closed by',
                                  value:
                                    driver.data && driver.data.staffReference === trip.data.closedBy
                                      ? `${trip.data.closedBy} (the assigned driver)`
                                      : `${trip.data.closedBy} - on the driver's behalf`,
                                  span: 2 as const,
                                },
                              ]
                            : []),
                          ...(trip.data.cancellationReason
                            ? [
                                {
                                  label: 'Cancellation reason',
                                  value: trip.data.cancellationReason,
                                  span: 2 as const,
                                },
                              ]
                            : []),
                        ]}
                      />
                    </>
                  )}
                </div>
              </SectionCard>

              <div className="space-y-5">
                <SectionCard title="Assignment">
                  {trip.data.vehicleId || trip.data.driverId ? (
                    <div className="space-y-3">
                      {vehicle.data && (
                        <Link to={fleetPaths.vehicleDetail(vehicle.data.id)} className={linkTile}>
                          <p className="text-theme-xs text-gray-500">Vehicle</p>
                          <p className="text-theme-sm font-semibold text-gray-800">
                            {vehicle.data.registrationNumber}
                          </p>
                          <p className="text-theme-xs text-gray-500">
                            {vehicle.data.make} {vehicle.data.model} ·{' '}
                            {formatOdometer(vehicle.data.odometerValue, vehicle.data.odometerUnit)}
                          </p>
                        </Link>
                      )}
                      {driver.data && (
                        <Link to={fleetPaths.driverDetail(driver.data.id)} className={linkTile}>
                          <p className="text-theme-xs text-gray-500">Driver</p>
                          <p className="text-theme-sm font-semibold text-gray-800">
                            {driver.data.displayName}
                          </p>
                          <p className="text-theme-xs text-gray-500">
                            Class {driver.data.licenceClass} ·{' '}
                            {humanise(driver.data.eligibilityStatus)}
                          </p>
                        </Link>
                      )}
                    </div>
                  ) : (
                    <Alert variant="warning">
                      No vehicle or driver assigned yet. The trip cannot start until both are set.
                    </Alert>
                  )}
                </SectionCard>

                <SectionCard title="History">
                  <WorkflowTimeline entries={timeline} />
                </SectionCard>
              </div>
            </div>

            <SectionCard
              title="Inspections"
              subtitle="Pre-trip and post-trip checks recorded against this trip"
            >
              <DataState
                loading={inspections.initialising}
                error={inspections.error}
                empty={(inspections.data?.length ?? 0) === 0}
                emptyTitle="No inspections recorded"
                emptyHint="A valid pre-trip inspection is required before the trip can start."
                onRetry={inspections.refetch}
                minHeight={140}
              >
                <div className="space-y-3">
                  {inspections.data?.map((inspection) => (
                    <div key={inspection.id} className="rounded-xl border border-gray-200 p-3.5">
                      <div className="flex flex-col justify-between gap-2 sm:flex-row">
                        <div className="min-w-0">
                          <div className="flex flex-wrap items-center gap-2">
                            <p className="text-theme-sm font-semibold text-gray-800">
                              {humanise(inspection.inspectionType)}
                            </p>
                            <StatusChip value={inspection.result} />
                            <StatusChip value={inspection.status} />
                          </div>
                          <p className="mt-0.5 text-theme-xs text-gray-500">
                            {formatDateTime(inspection.performedAt)} ·{' '}
                            {inspection.performedBy ?? 'unknown operator'} ·{' '}
                            {formatNumber(inspection.odometerReading)} km
                          </p>
                        </div>
                        {!inspection.permitsUse && (
                          <div className="shrink-0">
                            <StatusChip value="BLOCKED" label="Blocks vehicle use" tone="blocked" />
                          </div>
                        )}
                      </div>

                      {inspection.findings.length > 0 && (
                        <div className="mt-3 space-y-2">
                          {/* One inspection can carry two findings against the same check code
                              (two tyres, two lamps), so the position is part of the key. */}
                          {inspection.findings.map((finding, findingIndex) => (
                            <div
                              key={`${inspection.id}-${findingIndex}-${finding.checkCode}`}
                              className="flex flex-wrap items-center gap-2"
                            >
                              <StatusChip value={finding.severity} />
                              <p className="text-theme-sm text-gray-700">
                                <strong className="font-semibold text-gray-800">
                                  {finding.checkCode}
                                </strong>{' '}
                                - {finding.description}
                              </p>
                              {finding.resolved && (
                                <StatusChip value="RESOLVED" label="Resolved" tone="ready" />
                              )}
                            </div>
                          ))}
                        </div>
                      )}

                      {inspection.hasOpenCriticalDefect && (
                        <Alert variant="error" className="mt-3">
                          An unresolved critical defect is recorded - the vehicle is blocked from
                          use until it is cleared.
                        </Alert>
                      )}

                      {inspection.notes && (
                        <p className="mt-2.5 text-theme-sm text-gray-600">{inspection.notes}</p>
                      )}
                    </div>
                  ))}
                </div>
              </DataState>
            </SectionCard>

            {/*
             * Each dialog is mounted only while it is open. A dialog that stays mounted keeps the
             * form state it was seeded with, so the odometer and reasons from one opening would
             * reappear in the next - and a value captured before the trip or vehicle query resolved
             * would never be replaced.
             */}
            {dialog === 'assign' && (
              <AssignTripDialog
                open
                trip={trip.data}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess('Trip assignment updated.');
                  refreshAll();
                }}
              />
            )}
            {dialog === 'start' && (
              <StartTripDialog
                open
                trip={trip.data}
                vehicleOdometer={vehicle.data?.odometerValue}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess('Trip started.');
                  refreshAll();
                }}
              />
            )}
            {dialog === 'close' && (
              <CloseTripDialog
                open
                trip={trip.data}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess('Trip closed.');
                  refreshAll();
                }}
              />
            )}
            {dialog === 'cancel' && (
              <CancelTripDialog
                open
                trip={trip.data}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess('Trip cancelled.');
                  refreshAll();
                }}
              />
            )}
            {(dialog === 'hold' || dialog === 'resume') && (
              <HoldTripDialog
                open
                action={dialog === 'resume' ? 'RESUME' : 'HOLD'}
                trip={trip.data}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess(dialog === 'resume' ? 'Trip resumed.' : 'Trip placed on hold.');
                  refreshAll();
                }}
              />
            )}
            {dialog === 'inspection' && (
              <RecordInspectionDialog
                open
                trip={trip.data}
                vehicleOdometer={vehicle.data?.odometerValue}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess('Inspection recorded.');
                  refreshAll();
                }}
              />
            )}
            {(dialog === 'confirm' || dialog === 'defer') && (
              <AcknowledgeTripDialog
                open
                answer={dialog === 'defer' ? 'DEFERRED' : 'CONFIRMED'}
                trip={trip.data}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess(
                    dialog === 'defer'
                      ? 'Deferred. The dispatcher has been told.'
                      : 'Confirmed. The dispatcher has been told.',
                  );
                  refreshAll();
                }}
              />
            )}
          </div>
        )}
      </DataState>
    </div>
  );
};

export default TripDetailPage;
