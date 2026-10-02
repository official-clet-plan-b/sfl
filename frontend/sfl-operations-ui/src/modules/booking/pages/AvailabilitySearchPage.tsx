import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import {
  Button,
  Card,
  PageSection,
  SectionDescription,
  SectionHeader,
  SectionTitle,
  useBreadcrumbs,
} from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { bookingPaths, facilitiesPaths } from 'shared/layout/navigation';
import { humaniseCode, readinessTone } from 'modules/facilities/components/facilitiesFormat';
import { spaceTypes } from 'modules/facilities/api/enums';
import type { SpaceType } from 'modules/facilities/api/enums';
import { availabilityApi, bookingsApi } from '../api/bookingApi';
import type { SpaceAvailability } from '../api/dto';
import { BOOKING_PURPOSES, HOLD_REASON_DESCRIPTIONS } from '../api/enums';
import type { BookingPurpose } from '../api/enums';
import { canOverrideReadiness, canRequest } from '../api/workflow';
import RequestBookingDialog from '../dialogs/RequestBookingDialog';
import {
  formatWindow,
  fromLocalInput,
  nextHourLocalInput,
  plusHoursLocalInput,
  windowProblem,
} from '../components/bookingFormat';
import { DateTimeField, NumberInput, SelectInput } from 'modules/facilities/dialogs/dialogKit';
import StatusBadge from 'modules/facilities/components/StatusBadge';

/**
 * "What is free?" - SRS-SFL-S159-02, and the way into every booking.
 *
 * ## Unavailable spaces stay on the list
 *
 * A hall that cannot take the window is shown with the reason rather than filtered out. The question
 * behind "what is free at ten?" is almost always "can I have Hall A at ten?", and a hall simply
 * absent from a list answers neither - the operator cannot tell whether it is taken, blocked, or not
 * a bookable space at all.
 *
 * ## Nothing here reserves anything
 *
 * Two people can both be told Hall A is free and both request it; the first wins and the second is
 * refused. That is deliberate: holding a space during a five-minute browse would mean the estate's
 * diary was mostly locked by people who had wandered off. The notice says so, because "it said it was
 * free" is otherwise a reasonable complaint.
 *
 * ## Why the buffers are on the search and not only on the booking
 *
 * Availability has to be asked with the buffers the booking will carry. A hall that looks free for a
 * two-hour examination is not free once thirty minutes of layout change are added at each end, and a
 * search that ignored them would show spaces the request is then refused for.
 */
const AvailabilitySearchPage = () => {
  const navigate = useNavigate();
  const notify = useNotifier();
  useBreadcrumbs([{ label: 'Bookings', href: bookingPaths.diary }, { label: 'Find a space' }]);

  const [siteCode, setSiteCode] = useState<string>(defaultSite);
  const [startsLocal, setStartsLocal] = useState(() => nextHourLocalInput());
  const [endsLocal, setEndsLocal] = useState(() => plusHoursLocalInput(nextHourLocalInput(), 2));
  const [purpose, setPurpose] = useState('');
  const [spaceType, setSpaceType] = useState('');
  const [minimumCapacity, setMinimumCapacity] = useState('');
  const [setupMinutes, setSetupMinutes] = useState('0');
  const [teardownMinutes, setTeardownMinutes] = useState('0');
  const [booking, setBooking] = useState<SpaceAvailability | null>(null);

  const problem = windowProblem(startsLocal, endsLocal);

  /*
    Availability is asked of one site, never of the estate.

    `GET /booking-availability/spaces` takes `siteCode` as a **required** parameter, and rightly:
    "what is free on Tuesday" has no answer across three centres somebody would act on. An actor whose
    scope is every site therefore opens this screen with no site chosen, and the query must wait for
    one rather than be sent without it - `buildQueryString` drops an empty value, so the request went
    out with the parameter missing and came back as a bare 400 naming the endpoint.

    Every other screen in this module treats an empty site as "all sites", which is the right reading
    for a register and the wrong one here. Stated as its own condition rather than folded into
    `problem`, because "choose a site" and "that window is backwards" are different instructions.
  */
  const needsSite = !siteCode;

  /*
    Both queries run against the same instants, so the resource counts on the request dialog belong to
    the window its verdict was given for. Derived once rather than at each call site: `new Date()` on
    a `datetime-local` value is local-to-UTC, and doing it twice invites the two halves to drift.
  */
  const window = useMemo(
    () =>
      problem || needsSite
        ? null
        : {
            siteCode,
            from: fromLocalInput(startsLocal),
            to: fromLocalInput(endsLocal),
            setupMinutes: Number(setupMinutes) || 0,
            teardownMinutes: Number(teardownMinutes) || 0,
          },
    [problem, needsSite, siteCode, startsLocal, endsLocal, setupMinutes, teardownMinutes],
  );

  const spaces = useApiQuery(
    (signal) =>
      window
        ? availabilityApi.spaces(
            {
              ...window,
              purpose: (purpose as BookingPurpose) || undefined,
              spaceType: (spaceType as SpaceType) || undefined,
              minimumCapacity: minimumCapacity === '' ? undefined : Number(minimumCapacity),
            },
            signal,
          )
        : Promise.resolve([]),
    [window, purpose, spaceType, minimumCapacity],
  );

  /*
    Gated on its own permission rather than ridden in on the page's.

    `BookingAvailabilityService.resources` requires `FACILITIES_RESOURCE_READ` while `spaces` requires
    `FACILITIES_BOOKING_READ`. Today every role holding one holds the other - both are in the
    matrix's shared `READ_ONLY` set - so asking unconditionally would work, and would keep working
    right up until a role held one and not the other. At that point the request would 403 and the
    dialog would show an empty resource list, which reads as "nothing is free" rather than "you may
    not see this".
  */
  const mayReadResources = permits('FACILITIES_RESOURCE_READ');
  const resources = useApiQuery(
    (signal) =>
      window && mayReadResources ? availabilityApi.resources(window, signal) : Promise.resolve([]),
    [window, mayReadResources],
  );

  const rows = spaces.data ?? [];
  const free = rows.filter((space) => space.free);
  const overridable = rows.filter((space) => !space.free && space.availableWithOverride);
  const taken = rows.filter((space) => !space.free && !space.availableWithOverride);
  const mayOverride = canOverrideReadiness().kind === 'allowed';
  const mayRequest = canRequest().kind === 'allowed';

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Find a space</SectionTitle>
          <SectionDescription>What can take this window, and what cannot - with the reason</SectionDescription>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <SectionHeader>
          <SectionTitle>The window</SectionTitle>
        </SectionHeader>
        <Card bordered>
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <SiteSelect
            value={siteCode}
            onChange={setSiteCode}
            required
            error={needsSite}
            helperText={needsSite ? 'Availability is asked of one site at a time.' : undefined}
          />
          <DateTimeField label="Starts" value={startsLocal} onChange={setStartsLocal} required />
          <DateTimeField
            label="Ends"
            value={endsLocal}
            onChange={setEndsLocal}
            required
            error={Boolean(problem)}
            helperText={problem ?? undefined}
          />
          <SelectInput
            label="Purpose"
            value={purpose}
            onChange={setPurpose}
            allowEmpty
            emptyLabel="Any purpose"
            options={BOOKING_PURPOSES.map((value) => ({ value, label: humaniseCode(value) }))}
            helperText="An examination needs a fully ready space."
          />
          <NumberInput
            label="Setup buffer"
            value={setupMinutes}
            onChange={setSetupMinutes}
            min={0}
            suffix="min"
            helperText="Asked for with the booking - a hall free for two hours may not be free for three."
          />
          <NumberInput
            label="Teardown buffer"
            value={teardownMinutes}
            onChange={setTeardownMinutes}
            min={0}
            suffix="min"
          />
          <SelectInput
            label="Space type"
            value={spaceType}
            onChange={setSpaceType}
            allowEmpty
            emptyLabel="Any type"
            options={spaceTypes.map((value) => ({ value, label: humaniseCode(value) }))}
          />
          <NumberInput
            label="Seats at least"
            value={minimumCapacity}
            onChange={setMinimumCapacity}
            min={0}
            suffix="people"
          />
        </div>
        </Card>
      </PageSection>

      <PageSection>
      <DataState
        loading={spaces.loading}
        error={spaces.error}
        empty={rows.length === 0}
        emptyTitle={
          needsSite
            ? 'Choose a site'
            : problem
              ? 'Give a valid window'
              : 'No spaces at this site match'
        }
        emptyHint={
          needsSite
            ? 'Availability is worked out one site at a time, so this needs a centre before it can answer.'
            : (problem ?? 'Widen the type, drop the capacity, or try a different window.')
        }
        onRetry={spaces.refetch}
      >
        <div className="space-y-5">
          <SpaceGroup
            title={`Free - ${free.length}`}
            subtitle={window ? formatWindow(window.from, window.to) : undefined}
            spaces={free}
            onOpen={(space) => navigate(facilitiesPaths.spaceDetail(space.roomId))}
            onBook={mayRequest ? setBooking : undefined}
          />

          {overridable.length > 0 && (
            <SpaceGroup
              title={`Readiness refuses - ${overridable.length}`}
              subtitle={
                mayOverride
                  ? 'Free of other bookings. You may book into these with a recorded reason.'
                  : 'Free of other bookings, but readiness refuses them. An override is needed.'
              }
              spaces={overridable}
              onOpen={(space) => navigate(facilitiesPaths.spaceDetail(space.roomId))}
              onBook={mayRequest && mayOverride ? setBooking : undefined}
            />
          )}

          {taken.length > 0 && (
            <SpaceGroup
              title={`Already taken - ${taken.length}`}
              subtitle="Held by another booking for some part of this window."
              spaces={taken}
              onOpen={(space) => navigate(facilitiesPaths.spaceDetail(space.roomId))}
            />
          )}
        </div>
      </DataState>
      </PageSection>

      {booking && window && (
        <RequestBookingDialog
          space={booking}
          startsAt={window.from}
          endsAt={window.to}
          setupMinutes={window.setupMinutes}
          teardownMinutes={window.teardownMinutes}
          purpose={(purpose as BookingPurpose) || ''}
          resources={resources.data ?? []}
          onClose={() => setBooking(null)}
          onSubmit={async (body) => {
            const created = await bookingsApi.request(body);
            setBooking(null);
            notify.notifySuccess(
              created.status === 'CONFIRMED'
                ? `${created.bookingReference} confirmed - this booking needed no approval.`
                : `${created.bookingReference} requested. It holds the space until it is decided.`,
            );
            navigate(bookingPaths.bookingDetail(created.id));
          }}
        />
      )}
    </>
  );
};

interface SpaceGroupProps {
  title: string;
  subtitle?: string;
  spaces: SpaceAvailability[];
  onOpen: (space: SpaceAvailability) => void;
  /** Absent when this actor cannot book, which hides the control rather than disabling it. */
  onBook?: (space: SpaceAvailability) => void;
}

const SpaceGroup = ({ title, subtitle, spaces, onOpen, onBook }: SpaceGroupProps) => {
  if (spaces.length === 0) {
    return null;
  }
  return (
    <PageSection>
      <SectionHeader>
        <SectionTitle>{title}</SectionTitle>
        {subtitle && <SectionDescription>{subtitle}</SectionDescription>}
      </SectionHeader>
      <Card bordered>
      <ul className="divide-y divide-border">
        {spaces.map((space) => (
          <li key={space.roomId} className="flex flex-wrap items-center gap-3 py-3 first:pt-0">
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-semibold text-foreground">{space.roomCode}</span>
                <StatusBadge value={space.readinessStatus} tone={readinessTone(space.readinessStatus)} />
                {space.capacity !== null && (
                  <span className="text-xs text-muted-foreground">Seats {space.capacity}</span>
                )}
              </div>
              {space.name && <p className="truncate text-sm text-muted-foreground">{space.name}</p>}

              {space.readinessIssue && (
                <p className="mt-1 text-xs text-warning-text">
                  {HOLD_REASON_DESCRIPTIONS[space.readinessIssue]}
                  {space.readinessDetail ? ` ${space.readinessDetail}` : ''}
                </p>
              )}

              {/*
                Named, not counted. "Held by 2 bookings" makes an operator open another screen to find
                out whether one of them is theirs - which, on a space they are trying to book, it
                frequently is.
              */}
              {space.heldBy.length > 0 && (
                <p className="mt-1 text-xs text-muted-foreground">
                  Held by{' '}
                  {space.heldBy
                    .map((held) => `${held.bookingReference} (${formatWindow(held.occupiedFrom, held.occupiedTo)})`)
                    .join(', ')}
                </p>
              )}
            </div>

            <div className="flex shrink-0 items-center gap-2">
              <Button size="sm" variant="ghost" onClick={() => onOpen(space)}>
                Open the space
              </Button>
              {onBook && (
                <Button size="sm" variant="outline" onClick={() => onBook(space)}>
                  Book it
                </Button>
              )}
            </div>
          </li>
        ))}
      </ul>
      </Card>
    </PageSection>
  );
};

export default AvailabilitySearchPage;
