import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { LogbookTransition, driverLogbooksApi } from 'modules/fuel/api/fuelApi';
import {
  LOGBOOK_RULES,
  logbookLocked,
  logbookSubmissionBlockers,
  logbookTransitionAllowed,
} from 'modules/fuel/api/workflow';
import { CreateLogbookDialog, LogbookTransitionDialog } from 'modules/fuel/dialogs/logbookDialogs';
import HistoryTimeline from 'modules/fuel/components/HistoryTimeline';
import { siteOf } from 'modules/fuel/components/fuelFormat';
import { humanise } from 'modules/fleet/api/enums';
import { Alert, Button, PageHeader, SectionCard, StatusChip } from 'modules/fuel/components/fuelMigration';
import DataState from 'shared/components/DataState';
import Icon from 'shared/components/Icon';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { useNotifier } from 'shared/components/Notifier';
import { formatDate, formatDateTime, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fleetPaths, fuelPaths } from 'shared/layout/navigation';
import { canCreateLogbooks, canReviewLogbooks } from 'modules/fleet/api/access';

/** One sentence per transition. "Transition applied" tells an operator nothing. */
const CONFIRMATIONS: Record<LogbookTransition, string> = {
  submit: 'Logbook submitted for review.',
  review: 'Review started. The logbook can now be approved or returned.',
  return: 'Returned to the driver with your comment.',
  approve: 'Logbook approved and locked.',
  reopen: 'Logbook reopened. It can be corrected and resubmitted.',
  cancel: 'Logbook cancelled.',
};

/** The order the buttons appear in - forward moves first, then the privileged exits. */
const TRANSITION_ORDER: LogbookTransition[] = [
  'submit',
  'review',
  'approve',
  'return',
  'reopen',
  'cancel',
];

/**
 * A driver logbook, its journey detail and every transition legal from where it stands.
 *
 * Which buttons appear is decided by the record's own `requireState` guards, transcribed in
 * `workflow.ts` - so the dashboard offers approve only from under review, cancel only from draft,
 * submitted or returned, and reopen only from approved. The service still decides; this just stops
 * the dashboard offering an action that can only be refused.
 */
const DriverLogbookDetailPage = () => {
  const { logbookId = '' } = useParams();
  const navigate = useNavigate();
  const { notifySuccess, notifyError } = useNotifier();
  const [dialog, setDialog] = useState<LogbookTransition | null>(null);
  const [completingDraft, setCompletingDraft] = useState(false);
  const [working, setWorking] = useState(false);

  const logbook = useApiQuery(
    (signal) => driverLogbooksApi.findById(logbookId, signal),
    [logbookId],
  );

  /** The record's real transitions, from the audit log. */
  const history = useApiQuery(
    (signal) => driverLogbooksApi.history(logbookId, signal),
    [logbookId],
  );

  const refreshAll = () => {
    logbook.refetch();
    history.refetch();
  };

  const record = logbook.data;

  /**
   * `review` is the one transition with nothing to fill in - the service takes no comment for it and
   * the domain asks for nothing - so it runs from the button rather than through a dialog that
   * would have a single "Confirm" in it.
   */
  const startReview = async () => {
    setWorking(true);
    try {
      await driverLogbooksApi.transition(logbookId, 'review', { comment: null });
      notifySuccess(CONFIRMATIONS.review);
      refreshAll();
    } catch (error) {
      // Shown with the service's own wording - a refused transition is never silent.
      notifyError(error);
    } finally {
      setWorking(false);
    }
  };

  const submissionBlockers = record ? logbookSubmissionBlockers(record) : [];

  return (
    <div>
      <PageHeader
        title={record?.logbookNumber ?? 'Driver logbook'}
        subtitle={record ? `${record.origin} → ${record.destination} · ${record.purpose}` : undefined}
        crumbs={[
          { label: 'Fuel', to: fuelPaths.dashboard },
          { label: 'Driver logbooks', to: fuelPaths.logbooks },
          { label: record?.logbookNumber ?? '…' },
        ]}
        actions={
          <Button
            variant="outline"
            startIcon="arrow-left"
            onClick={() => navigate(fuelPaths.logbooks)}
          >
            Register
          </Button>
        }
        meta={
          record && (
            <div className="flex flex-wrap items-center gap-2">
              <StatusChip value={record.status} />
              <StatusChip value={record.useClassification} tone="neutral" />
              {record.declarationAccepted ? (
                <StatusChip value="ACCEPTED" label="Declaration accepted" tone="ready" />
              ) : (
                <StatusChip value="PENDING" label="Declaration not accepted" tone="caution" />
              )}
            </div>
          )
        }
      />

      <DataState
        loading={logbook.initialising}
        error={logbook.error}
        onRetry={logbook.refetch}
        minHeight={300}
      >
        {record && (
          <div className="space-y-5">
            {record.status === 'APPROVED' && (
              <Alert variant="success" title="This logbook is approved and locked">
                Nothing can change it. Correcting an approved record needs a privileged reopen, which
                is recorded with its reason.
              </Alert>
            )}
            {record.status === 'RETURNED' && record.reviewComment && (
              <Alert variant="warning" title="Returned to the driver for correction">
                {record.reviewComment}
              </Alert>
            )}
            {record.status === 'CANCELLED' && (
              <Alert variant="info" title="This logbook is cancelled">
                {record.transitionReason ?? 'No reason was recorded.'}
              </Alert>
            )}

            <SectionCard title="Actions">
              <div className="flex flex-wrap items-center gap-2">
                {TRANSITION_ORDER.filter(
                  (transition) =>
                    logbookTransitionAllowed(record, transition) &&
                    /*
                      State says the record could take this step; the grant says whether this person
                      is the one who takes it. Submitting is the driver's own act
                      (FUEL_LOGBOOK_CREATE); reviewing, returning and approving belong to the
                      reviewer, and collapsing the two would let a driver approve their own journey.
                    */
                    (transition === 'submit' ? canCreateLogbooks() : canReviewLogbooks()),
                ).map((transition) => {
                  const blockedSubmit = transition === 'submit' && submissionBlockers.length > 0;
                  return transition === 'review' ? (
                    <Button
                      key={transition}
                      variant="primary"
                      startIcon="play"
                      loading={working}
                      onClick={startReview}
                    >
                      Start review
                    </Button>
                  ) : (
                    <Button
                      key={transition}
                      variant={blockedSubmit ? 'outline' : buttonVariant(transition)}
                      startIcon={blockedSubmit ? 'edit' : buttonIcon(transition)}
                      onClick={() => (blockedSubmit ? setCompletingDraft(true) : setDialog(transition))}
                    >
                      {blockedSubmit
                        ? 'Complete draft'
                        : transition === 'submit' && record.status === 'RETURNED'
                        ? 'Resubmit'
                        : LOGBOOK_RULES[transition].label}
                    </Button>
                  );
                })}
                <Button
                  variant="ghost"
                  startIcon="truck"
                  endIcon="chevron-right"
                  onClick={() => navigate(fleetPaths.vehicleDetail(record.vehicleId))}
                >
                  Vehicle
                </Button>
                <Button
                  variant="ghost"
                  startIcon="driver"
                  endIcon="chevron-right"
                  onClick={() => navigate(fleetPaths.driverDetail(record.driverId))}
                >
                  Driver
                </Button>
                {record.tripId && (
                  <Button
                    variant="ghost"
                    startIcon="route"
                    endIcon="chevron-right"
                    onClick={() => navigate(fleetPaths.tripDetail(record.tripId as string))}
                  >
                    Trip
                  </Button>
                )}
              </div>

              {logbookTransitionAllowed(record, 'submit') && submissionBlockers.length > 0 && (
                <Alert variant="warning" title="Not ready to submit" className="mt-4">
                  <ul className="mt-1 list-disc space-y-1 pl-4">
                    {submissionBlockers.map((blocker) => (
                      <li key={blocker}>{blocker}</li>
                    ))}
                  </ul>
                  <div className="mt-3">
                    <Button size="sm" startIcon="edit" onClick={() => setCompletingDraft(true)}>
                      Complete draft
                    </Button>
                  </div>
                </Alert>
              )}

              {logbookLocked(record) && (
                <p className="mt-3 flex items-center gap-1.5 text-theme-sm text-gray-600">
                  <Icon name="lock" size={14} className="shrink-0 text-gray-600" />
                  This record is {humanise(record.status).toLowerCase()}. No further transition is
                  offered.
                </p>
              )}
            </SectionCard>

            <div className="grid gap-5 xl:grid-cols-[1.4fr_1fr]">
              <div className="space-y-5">
                <SectionCard title="Journey">
                  <KeyValueGrid
                    items={[
                      { label: 'Site', value: siteOf(record.siteCode) },
                      { label: 'Journey date', value: formatDate(record.journeyDate) },
                      {
                        label: 'Use classification',
                        value: humanise(record.useClassification),
                      },
                      { label: 'Start time', value: formatDateTime(record.startTime) },
                      { label: 'End time', value: formatDateTime(record.endTime) },
                      {
                        label: 'Distance',
                        value:
                          record.endOdometer === null || record.endOdometer === undefined
                            ? 'Journey not closed'
                            : `${formatNumber(record.endOdometer - record.startOdometer)} km`,
                      },
                      {
                        label: 'Opening odometer',
                        value: `${formatNumber(record.startOdometer)} km`,
                      },
                      {
                        label: 'Closing odometer',
                        value:
                          record.endOdometer === null || record.endOdometer === undefined
                            ? '-'
                            : `${formatNumber(record.endOdometer)} km`,
                      },
                      { label: 'Origin', value: record.origin },
                      { label: 'Destination', value: record.destination },
                      { label: 'Purpose', value: record.purpose, span: 2 },
                      { label: 'Route notes', value: record.routeNotes ?? '-', span: 2 },
                      {
                        label: 'Passenger and load notes',
                        value: record.passengerLoadNotes ?? '-',
                        span: 2,
                      },
                      { label: 'Evidence reference', value: record.evidenceId ?? '-', span: 2 },
                    ]}
                  />
                </SectionCard>

                <SectionCard title="Review" subtitle="What the reviewer recorded">
                  <KeyValueGrid
                    columns={2}
                    items={[
                      { label: 'Submitted at', value: formatDateTime(record.submittedAt) },
                      { label: 'Approved at', value: formatDateTime(record.approvedAt) },
                      { label: 'Review comment', value: record.reviewComment ?? '-', span: 2 },
                      {
                        label: 'Last transition reason',
                        value: record.transitionReason ?? '-',
                        span: 2,
                      },
                    ]}
                  />
                </SectionCard>
              </div>

              <div className="space-y-5">
                <SectionCard
                  title="History"
                  subtitle="Recorded transitions, from the audit log"
                  actions={
                    <Button variant="ghost" size="sm" startIcon="refresh" onClick={history.refetch}>
                      Refresh
                    </Button>
                  }
                >
                  <DataState
                    loading={history.initialising}
                    error={history.error}
                    onRetry={history.refetch}
                    minHeight={160}
                  >
                    <HistoryTimeline events={history.data} recordNoun="logbook" />
                  </DataState>
                </SectionCard>

                <SectionCard title="Where this can go next">
                  <ul className="space-y-2.5">
                    {TRANSITION_ORDER.map((transition) => {
                      const allowed = logbookTransitionAllowed(record, transition);
                      const rule = LOGBOOK_RULES[transition];
                      return (
                        <li key={transition} className="flex items-start gap-2.5">
                          <Icon
                            name={allowed ? 'check-circle' : 'close'}
                            size={15}
                            className={
                              allowed ? 'mt-0.5 shrink-0 text-success-700' : 'mt-0.5 shrink-0 text-gray-400'
                            }
                          />
                          <div className="min-w-0">
                            <p
                              className={
                                allowed
                                  ? 'text-theme-sm font-medium text-gray-900'
                                  : 'text-theme-sm text-gray-500'
                              }
                            >
                              {rule.label}
                              {rule.privileged && (
                                <span className="ml-1.5 text-theme-xs font-semibold text-gold-900">
                                  privileged
                                </span>
                              )}
                            </p>
                            <p className="text-theme-xs text-gray-600">
                              {allowed
                                ? `Needs ${rule.permission}.`
                                : `From ${rule.from.map((state) => humanise(state).toLowerCase()).join(', ')}.`}
                            </p>
                          </div>
                        </li>
                      );
                    })}
                  </ul>
                </SectionCard>
              </div>
            </div>

            {dialog && (
              <LogbookTransitionDialog
                open
                logbook={record}
                transition={dialog}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess(CONFIRMATIONS[dialog]);
                  refreshAll();
                }}
              />
            )}

            {completingDraft && (
              <CreateLogbookDialog
                open
                logbook={record}
                defaultSiteCode={siteOf(record.siteCode)}
                onClose={() => setCompletingDraft(false)}
                onSaved={() => {
                  notifySuccess(
                    'Logbook draft updated. You can submit it when all blockers are cleared.',
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

const buttonVariant = (transition: LogbookTransition) => {
  switch (transition) {
    case 'submit':
      return 'primary' as const;
    case 'approve':
      return 'accent' as const;
    case 'cancel':
      return 'danger' as const;
    default:
      return 'outline' as const;
  }
};

const buttonIcon = (transition: LogbookTransition) => {
  switch (transition) {
    case 'submit':
      return 'upload' as const;
    case 'approve':
      return 'check-circle' as const;
    case 'return':
      return 'arrow-left' as const;
    case 'reopen':
      return 'refresh' as const;
    case 'cancel':
      return 'close' as const;
    default:
      return 'play' as const;
  }
};

export default DriverLogbookDetailPage;
