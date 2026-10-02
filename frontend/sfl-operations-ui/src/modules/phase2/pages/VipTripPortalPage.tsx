import { useState } from 'react';
import { useNavigate } from 'react-router';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Notice,
  PageSection,
  SectionActions,
  SectionHeader,
  SectionTitle,
  Table,
  TableContent,
  type TableColumn,
} from '@rfdtech/components';
import { tripsApi } from 'modules/fleet/api/fleetApi';
import type { TripResponse } from 'modules/fleet/api/dto';
import { Checkbox, DateTimeField, TextInput } from 'modules/facilities/dialogs/dialogKit';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { fleetPaths } from 'shared/layout/navigation';
import {
  DESTINATION_MAX,
  PRINCIPAL_MAX,
  VIP_PURPOSE_PREFIX,
  parseVipPurpose,
  toTripRequest,
  validateVipRequest,
  type VipRequestDraft,
} from '../api/vipRequest';

const EMPTY: VipRequestDraft = { principal: '', destination: '', start: '', end: '', chaseCar: false };

const assignmentBadge = (trip: TripResponse) => {
  if (trip.status === 'CANCELLED') {
    return <Badge variant="error">Cancelled</Badge>;
  }
  if (trip.vehicleId && trip.driverId) {
    return <Badge variant="success">Assigned</Badge>;
  }
  return <Badge variant="warning">Awaiting assignment</Badge>;
};

/**
 * Executive transport requests.
 *
 * A request names who is travelling, where and when - never a vehicle or a driver. It becomes a
 * planned trip with no assignment, and the transport office assigns it from Trips & assignments, so
 * the person asking is never the person choosing the car.
 *
 * Only a role that may create trips is shown the form. Everyone else who can open the page sees the
 * requests and where each stands, and is told who raises them, rather than being offered a button the
 * service would refuse.
 */
export const VipTripPortalPage = () => {
  const navigate = useNavigate();
  const { notifySuccess, notifyError } = useNotifier();
  const [siteCode, setSiteCode] = useState(defaultSite);
  const [draft, setDraft] = useState<VipRequestDraft>(EMPTY);
  const [attempted, setAttempted] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const canRequest = permits('FLEET_TRIP_MANAGE');
  const canAssign = permits('FLEET_TRIP_ASSIGN');
  const requests = useApiQuery(
    (signal) =>
      tripsApi.search(
        { siteCode, size: 50, sort: 'plannedStart,desc', purposePrefix: VIP_PURPOSE_PREFIX },
        signal,
      ),
    [siteCode],
  );

  const errors = attempted ? validateVipRequest(draft) : {};
  const set = <K extends keyof VipRequestDraft>(key: K) => (value: VipRequestDraft[K]) =>
    setDraft((current) => ({ ...current, [key]: value }));

  const submit = async () => {
    setAttempted(true);
    if (Object.keys(validateVipRequest(draft)).length > 0) {
      return;
    }
    setSubmitting(true);
    try {
      await tripsApi.create(toTripRequest(draft, siteCode));
      notifySuccess('Transport request submitted', 'The transport office will assign a vehicle and driver.');
      setDraft(EMPTY);
      setAttempted(false);
      requests.refetch();
    } catch (cause) {
      notifyError(cause);
    } finally {
      setSubmitting(false);
    }
  };

  const columns: TableColumn<TripResponse>[] = [
    { id: 'trip', header: 'Request', cell: ({ row }) => <span className="font-medium">{row.tripNumber}</span> },
    {
      id: 'principal',
      header: 'Principal',
      cell: ({ row }) => {
        const parsed = parseVipPurpose(row.purpose);
        return (
          <div>
            {parsed?.principal ?? row.purpose}
            {parsed?.chaseCar && <div className="text-xs text-muted-foreground">Chase-car support requested</div>}
          </div>
        );
      },
    },
    { id: 'route', header: 'Route', cell: ({ row }) => `${row.origin} → ${row.destination}` },
    {
      id: 'when',
      header: 'Planned',
      cell: ({ row }) => (
        <div>
          {new Date(row.plannedStart).toLocaleString()}
          <div className="text-xs text-muted-foreground">until {new Date(row.plannedEnd).toLocaleString()}</div>
        </div>
      ),
    },
    { id: 'assignment', header: 'Assignment', cell: ({ row }) => assignmentBadge(row) },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>VIP trip & driver booking</SectionTitle>
          <SectionActions style={{ alignItems: 'flex-end' }}>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            {canAssign && (
              <Button variant="outline" className="whitespace-nowrap" onClick={() => navigate(fleetPaths.trips)}>
                Assign trips
              </Button>
            )}
          </SectionActions>
        </SectionHeader>
        <p className="max-w-3xl text-sm text-muted-foreground">
          Transport for the Director-General, Registrar, Board members and visiting dignitaries. A request
          says who is travelling, where and when. It never names a vehicle or a driver: the transport
          office assigns those from Trips & assignments.
        </p>
      </PageSection>

      {canRequest ? (
        <PageSection>
          <Card bordered className="p-5">
            <h3 className="font-semibold">Request executive transport</h3>
            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <TextInput
                label="Principal or dignitary"
                required
                value={draft.principal}
                onChange={set('principal')}
                maxLength={PRINCIPAL_MAX}
                placeholder="Who is travelling?"
                error={!!errors.principal}
                helperText={errors.principal}
              />
              <TextInput
                label="Destination"
                required
                value={draft.destination}
                onChange={set('destination')}
                maxLength={DESTINATION_MAX}
                placeholder="Where are they going?"
                error={!!errors.destination}
                helperText={errors.destination}
              />
              <DateTimeField
                label="Planned start"
                required
                value={draft.start}
                onChange={set('start')}
                error={!!errors.start}
                helperText={errors.start}
              />
              <DateTimeField
                label="Planned end"
                required
                value={draft.end}
                onChange={set('end')}
                error={!!errors.end}
                helperText={errors.end}
              />
            </div>
            <div className="mt-4">
              <Checkbox
                checked={draft.chaseCar}
                onChange={set('chaseCar')}
                label="Chase-car or motorcade support required"
              />
            </div>
            <Button className="mt-4" variant="primary" disabled={submitting} onClick={() => void submit()}>
              {submitting ? 'Submitting…' : 'Submit request'}
            </Button>
          </Card>
        </PageSection>
      ) : (
        <PageSection>
          <Notice variant="info" title="Requests are raised by the transport office">
            <p className="text-sm">
              Your role can follow these requests but not create them. Ask the fleet office to raise one,
              and it will appear below.
            </p>
          </Notice>
        </PageSection>
      )}

      <PageSection>
        <SectionHeader>
          <SectionTitle>Requests and assignments</SectionTitle>
        </SectionHeader>
        {requests.error ? (
          <EmptyState title="Requests are unavailable" description={requests.error.message} />
        ) : (
          <Table paramPrefix="vip-trips" variant="soft">
            <Card bordered>
              <TableContent
                columns={columns}
                data={requests.data?.content ?? []}
                loading={requests.initialising}
                rowKey={(row) => row.id}
                emptyContent={<EmptyState title="No VIP requests yet" />}
              />
            </Card>
          </Table>
        )}
      </PageSection>
    </>
  );
};
