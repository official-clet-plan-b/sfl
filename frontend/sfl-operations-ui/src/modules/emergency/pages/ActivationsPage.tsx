import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { Download, Plus, RefreshCw } from 'lucide-react';
import {
  Button,
  Dropdown,
  MetricCards,
  PageSection,
  Tabs,
  TabsList,
  TabsTrigger,
  useTableState,
  type TableColumn,
} from '@rfdtech/components';
import type { NotificationActivation } from 'modules/emergency/api/dto';
import {
  ACTIVATION_MODES,
  ACTIVATION_STATUSES,
  OPERATOR_REACHABLE_STATUSES,
  PRIORITIES,
} from 'modules/emergency/api/enums';
import type { ActivationMode, ActivationStatus, Priority } from 'modules/emergency/api/enums';
import { activationsApi, emergencyReportsApi } from 'modules/emergency/api/emergencyApi';
import { afterActionOutstanding, canCreateActivations, canExportEmergencyEvidence } from 'modules/emergency/api/workflow';
import { ActivationStatusChip } from 'modules/emergency/components/EmergencyFields';
import { formatElapsed } from 'modules/emergency/components/emergencyFormat';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import RegisterTable, {
  CellStack,
  DEFAULT_PAGE_SIZE,
  PAGE_SIZE_OPTIONS,
} from 'modules/emergency/components/RegisterTable';
import StatMetric from 'modules/emergency/components/StatMetric';
import StatusBadge from 'modules/emergency/components/StatusBadge';
import { useSiteRecords } from 'modules/emergency/components/useSiteRecords';
import { ComposeActivationDialog } from 'modules/emergency/dialogs/activationDialogs';
import { humanise } from 'modules/fleet/api/enums';
import DataState from 'shared/components/DataState';
import Icon from 'shared/components/Icon';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { useClampPage } from 'shared/hooks/useServerPage';
import { emergencyPaths } from 'shared/layout/navigation';

const QUEUE_VIEWS = [
  { value: 'OPEN', label: 'Open' },
  { value: 'LIVE', label: 'Live now' },
  { value: 'AWAITING_APPROVAL', label: 'Awaiting approval' },
  { value: 'AFTER_ACTION_DUE', label: 'After-action due' },
  { value: 'ALL', label: 'Everything returned' },
];

/**
 * The activation register.
 *
 * `GET /activations` takes a site and a status and nothing else. Mode, priority, incident reference
 * and the four queue views are applied here over the returned window, and every one of those
 * controls says so - a filter that silently searches only what happened to be loaded is how an
 * operator concludes a broadcast was never sent.
 *
 * The status filter offers the whole enum, not only the statuses this dashboard can produce.
 * `ACTIVATING`, `PARTIALLY_DELIVERED`, `ESCALATED`, `FAILED`, `CANCELLED` and `REOPENED` are set by
 * provider callbacks, by the scheduled sweep, or by nothing at all - but a stored record can hold
 * them, and a filter that cannot find such a record is worse than one that returns nothing.
 *
 * Status, mode, priority and the reference search live in the table's own URL state, so a link such
 * as `?activations.f_status=ACTIVE` opens the register already narrowed.
 */
const ActivationsPage = () => {
  const navigate = useNavigate();
  const { notifySuccess, notifyError } = useNotifier();

  const [siteCode, setSiteCode] = useState(defaultSite);
  const [view, setView] = useState('OPEN');
  const [composing, setComposing] = useState(false);
  const [exporting, setExporting] = useState(false);

  const table = useTableState({
    paramPrefix: 'activations',
    defaultPageSize: DEFAULT_PAGE_SIZE,
    pageSizeOptions: PAGE_SIZE_OPTIONS,
  });
  const { page, pageSize, setPage, filters, search } = table;
  const status = (filters.status ?? '') as ActivationStatus | '';
  const mode = (filters.mode ?? '') as ActivationMode | '';
  const priority = (filters.priority ?? '') as Priority | '';
  const reference = search;

  const [statusField, setStatusField] = useState(status);
  const [modeField, setModeField] = useState(mode);
  const [priorityField, setPriorityField] = useState(priority);

  /**
   * Each view is a set of server-side predicates, not a pass over what came back.
   *
   * That was gap 2. The service knows what "open", "live", "awaiting approval" and "after-action
   * due" mean now - `NotificationActivation.open()` and `.active()` are expressed as SQL rather
   * than re-implemented here over a window.
   */
  const viewParams =
    view === 'OPEN'
      ? { openOnly: true }
      : view === 'LIVE'
        ? { liveOnly: true }
        : view === 'AWAITING_APPROVAL'
          ? { status: 'PENDING_APPROVAL' as const }
          : view === 'AFTER_ACTION_DUE'
            ? { afterActionOutstanding: true }
            : {};

  const records = useSiteRecords(siteCode);

  const query = useApiQuery(
    (signal) =>
      activationsApi.search(
        {
          siteCode,
          status: status || undefined,
          mode: mode || undefined,
          priority: priority || undefined,
          incidentReference: reference.trim() || undefined,
          ...viewParams,
          page: page - 1,
          size: pageSize,
        },
        signal,
      ),
    [siteCode, status, mode, priority, reference, view, page, pageSize],
  );

  useClampPage(page - 1, query.data?.totalPages, (clamped) => setPage(clamped + 1));

  /**
   * The four header counts, each its own site-wide query.
   *
   * Counted by the service, not from the page on screen. "After-action due" in particular is the
   * outstanding break-glass sends at the **site** - reading it off a page of twenty-five would
   * have quietly under-reported the one number an auditor asks about.
   */
  const counts = useApiQuery(
    (signal) =>
      Promise.all([
        activationsApi.search({ siteCode, liveOnly: true, size: 1 }, signal),
        activationsApi.search({ siteCode, status: 'PENDING_APPROVAL', size: 1 }, signal),
        activationsApi.search({ siteCode, afterActionOutstanding: true, size: 1 }, signal),
        activationsApi.search({ siteCode, openOnly: true, size: 1 }, signal),
      ]).then(([liveNow, pendingApproval, afterAction, open]) => ({
        live: liveNow.totalElements,
        pending: pendingApproval.totalElements,
        afterActionDue: afterAction.totalElements,
        open: open.totalElements,
      })),
    [siteCode],
  );

  const chooseView = (next: string) => {
    setView(next);
    setPage(1);
  };

  const exportReport = async () => {
    setExporting(true);
    try {
      const fileName = await emergencyReportsApi.activations(siteCode);
      notifySuccess(
        `Downloaded ${fileName}.`,
        'The service exports the site’s activation register, not the filtered view.',
      );
    } catch (error) {
      notifyError(error);
    } finally {
      setExporting(false);
    }
  };

  const columns = useMemo<TableColumn<NotificationActivation>[]>(
    () => [
      {
        id: 'activation',
        header: 'Activation',
        width: 280,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.activationNumber} · ${records.templateName(row.templateId)}`}
            secondary={
              row.incidentReference
                ? `Incident ${row.incidentReference}`
                : records.scenarioName(row.scenarioId)
            }
          />
        ),
      },
      {
        id: 'mode',
        header: 'Mode',
        width: 130,
        cell: ({ row }) => (
          <div className="flex items-center gap-1.5">
            <StatusBadge value={row.mode} />
            {afterActionOutstanding(row) && (
              <Icon
                name="alert-circle"
                size={14}
                className="shrink-0 text-[var(--clet-error-text)]"
                aria-label="After-action approval outstanding"
              />
            )}
          </div>
        ),
      },
      {
        id: 'priority',
        header: 'Priority',
        width: 110,
        cell: ({ row }) => <StatusBadge value={row.priority} />,
      },
      {
        id: 'reach',
        header: 'Reach',
        width: 110,
        align: 'right',
        cell: ({ row }) => formatNumber(records.audienceReach(row.audienceGroupIds)),
      },
      {
        id: 'channels',
        header: 'Channels',
        width: 90,
        align: 'right',
        cell: ({ row }) => formatNumber(row.channels.length),
      },
      {
        id: 'sent',
        header: 'Time to send',
        width: 120,
        align: 'right',
        cell: ({ row }) => formatElapsed(row.fastLaneMillis),
      },
      {
        id: 'updated',
        header: 'Last change',
        width: 160,
        cell: ({ row }) => formatDateTime(row.metadata.lastModifiedAt),
      },
      {
        id: 'status',
        header: 'Status',
        width: 170,
        align: 'right',
        cell: ({ row }) => <ActivationStatusChip status={row.status} />,
      },
    ],
    [records],
  );

  return (
    <>
      <PageHeading
        title="Activations"
        subtitle="Every broadcast this site has composed, sent, stood down or closed."
        crumbs={[{ label: 'Emergency', to: emergencyPaths.dashboard }, { label: 'Activations' }]}
        actions={
          <>
            <SiteSelect
              label="Site"
              value={siteCode}
              onChange={(next) => {
                setSiteCode(next);
                setPage(1);
              }}
              required
              className="w-44"
            />
            {/* Declaring an emergency and exporting the record of one are separate grants. */}
            {canCreateActivations() && (
              <Button variant="primary" onClick={() => setComposing(true)}>
                <Plus size={14} strokeWidth={1.5} aria-hidden /> Compose activation
              </Button>
            )}
            {canExportEmergencyEvidence() && (
              <Button variant="outline" loading={exporting} onClick={exportReport}>
                <Download size={14} strokeWidth={1.5} aria-hidden /> Export CSV
              </Button>
            )}
            <Button variant="outline" onClick={query.refetch}>
              <RefreshCw size={14} strokeWidth={1.5} aria-hidden /> Refresh
            </Button>
          </>
        }
      />

      <PageSection>
        <MetricCards>
          <StatMetric
            label="Live now"
            value={formatNumber(counts.data?.live ?? 0)}
            icon="siren"
            tone={(counts.data?.live ?? 0) > 0 ? 'critical' : 'neutral'}
            caption="Broadcast out, not stood down"
            loading={counts.initialising}
            onClick={() => chooseView('LIVE')}
          />
          <StatMetric
            label="Awaiting approval"
            value={formatNumber(counts.data?.pending ?? 0)}
            icon="user-plus"
            tone={(counts.data?.pending ?? 0) > 0 ? 'caution' : 'neutral'}
            caption="Submitted, nobody has decided"
            loading={counts.initialising}
            onClick={() => chooseView('AWAITING_APPROVAL')}
          />
          <StatMetric
            label="After-action due"
            value={formatNumber(counts.data?.afterActionDue ?? 0)}
            icon="zap"
            tone={(counts.data?.afterActionDue ?? 0) > 0 ? 'critical' : 'neutral'}
            caption="Break-glass sends blocking closure"
            loading={counts.initialising}
            onClick={() => chooseView('AFTER_ACTION_DUE')}
          />
          <StatMetric
            label="Open at this site"
            value={formatNumber(counts.data?.open ?? 0)}
            icon="megaphone"
            caption="Not closed, cancelled or rejected"
            loading={counts.initialising}
            onClick={() => chooseView('OPEN')}
          />
        </MetricCards>
      </PageSection>

      <PageSection>
        <Panel
          title="Activation register"
          subtitle="Server-paginated and scoped to the selected site, view and filters."
        >
          <Tabs variant="pill" value={view} onValueChange={chooseView}>
            <TabsList>
              {QUEUE_VIEWS.map((entry) => (
                <TabsTrigger key={entry.value} value={entry.value}>
                  {entry.label}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>
          <DataState loading={false} error={query.error} onRetry={query.refetch}>
            <RegisterTable
              paramPrefix="activations"
              framed={false}
              columns={columns}
              rows={query.data?.content ?? []}
              rowKey={(row) => row.id}
              loading={query.initialising}
              onRowClick={(row) => navigate(emergencyPaths.activationDetail(row.id))}
              emptyTitle="No activation matches these filters"
              emptyDescription="Try another view, or remove a filter or the reference search."
              searchPlaceholder="Search activation or incident reference"
              filterCount={3}
              totalItems={query.data?.totalElements ?? 0}
              size={pageSize}
              filters={
                <>
                  <Dropdown
                    name="status"
                    aria-label="Filter by status"
                    value={statusField || null}
                    onValueChange={(next) => setStatusField((next ?? '') as ActivationStatus | '')}
                    options={ACTIVATION_STATUSES.map((value) => ({
                      value,
                      label: OPERATOR_REACHABLE_STATUSES.includes(value)
                        ? humanise(value)
                        : `${humanise(value)} (set elsewhere)`,
                    }))}
                    placeholder="All statuses"
                    clearable
                  />
                  <Dropdown
                    name="mode"
                    aria-label="Filter by mode"
                    value={modeField || null}
                    onValueChange={(next) => setModeField((next ?? '') as ActivationMode | '')}
                    options={ACTIVATION_MODES.map((value) => ({ value, label: humanise(value) }))}
                    placeholder="All modes"
                    clearable
                  />
                  <Dropdown
                    name="priority"
                    aria-label="Filter by priority"
                    value={priorityField || null}
                    onValueChange={(next) => setPriorityField((next ?? '') as Priority | '')}
                    options={PRIORITIES.map((value) => ({ value, label: humanise(value) }))}
                    placeholder="All priorities"
                    clearable
                  />
                </>
              }
            />
          </DataState>
        </Panel>
      </PageSection>

      {composing && (
        <ComposeActivationDialog
          open
          defaultSiteCode={siteCode}
          records={records}
          onClose={() => setComposing(false)}
          onSaved={(activation) => {
            notifySuccess(
              `${activation.activationNumber} created as a draft.`,
              'Nothing has been sent. Submit it for approval from its detail screen.',
            );
            query.refetch();
            navigate(emergencyPaths.activationDetail(activation.id));
          }}
        />
      )}
    </>
  );
};

export default ActivationsPage;
