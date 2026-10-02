import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { Button, Input, PageSection } from '@rfdtech/components';
import { WorkflowItemResponse } from 'modules/fleet/api/dto';
import {
  FLEET_WORKFLOW_STATUSES,
  FLEET_WORKFLOW_TYPES,
  FleetWorkflowStatus,
  FleetWorkflowType,
  WORKFLOW_PRIORITIES,
  WORKFLOW_SEVERITIES,
  WorkflowPriority,
  WorkflowSeverity,
  humanise,
} from 'modules/fleet/api/enums';
import { workflowApi } from 'modules/fleet/api/fleetApi';
import FleetTable, {
  CellStack,
  FilterDropdown,
  FleetColumn,
  useRegisterState,
} from 'modules/fleet/components/FleetTable';
import RegisterHeader from 'modules/fleet/components/RegisterHeader';
import StatusBadge from 'modules/fleet/components/StatusBadge';
import { useReferenceNames } from 'modules/fleet/components/useReferenceNames';
import { RaiseWorkflowItemDialog } from 'modules/fleet/dialogs/workflowDialogs';
import { sflActor } from 'shared/api/config';
import Icon from 'shared/components/Icon';
import { useNotifier } from 'shared/components/Notifier';
import { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fleetPaths } from 'shared/layout/navigation';

dayjs.extend(relativeTime);

/** The one-click views of the queue; each is a request the filters could already make. */
const VIEWS = [
  { value: 'all', label: 'All' },
  { value: 'overdue', label: 'Overdue' },
  { value: 'escalated', label: 'Escalated' },
  { value: 'mine', label: 'Assigned to me' },
] as const;

/** The SLA line: when it falls due, and how far past or short of that it is. */
const SlaCell = ({ row }: { row: WorkflowItemResponse }) => {
  const due = dayjs(row.slaDueAt);
  const hours = Math.abs(due.diff(dayjs(), 'hour'));
  return (
    <div className="min-w-0">
      <div className={row.slaBreached ? 'font-semibold text-error-700' : 'font-semibold'}>
        {formatDateTime(row.slaDueAt)}
      </div>
      <div className="text-theme-xs opacity-70">
        {row.slaBreached
          ? `Overdue by ${hours < 1 ? 'under an hour' : due.fromNow(true)}`
          : `Due in ${due.fromNow(true)}`}
        {row.escalationLevel > 0 ? ` · level ${row.escalationLevel}` : ''}
      </div>
    </div>
  );
};

/** The fleet workflow queue - defects, renewals, exceptions and their SLA standing. */
const WorkflowQueuePage = () => {
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();
  const [raiseOpen, setRaiseOpen] = useState(false);

  // The register's state lives in the URL. Every filter change returns to the first page - leaving
  // the page index behind asks the server for a page the narrowed result set no longer has.
  const state = useRegisterState('workflow');
  const { filters, setFilter } = state;
  const view = VIEWS.find((entry) => entry.value === filters.view) ?? VIEWS[0];
  const status = (filters.status ?? '') as FleetWorkflowStatus | '';
  const type = (filters.type ?? '') as FleetWorkflowType | '';
  const priority = (filters.priority ?? '') as WorkflowPriority | '';
  /**
   * Severity, which the queue could not filter on.
   *
   * The service has accepted it since the search endpoint was written, and the column has always
   * shown it - so a supervisor looking for the critical defects could see which rows were critical
   * and had no way to ask for only those.
   */
  const severity = (filters.severity ?? '') as WorkflowSeverity | '';
  const typedAssignee = filters.assignee ?? '';
  const assignee = view.value === 'mine' ? sflActor.user : typedAssignee;
  const overdueOnly = view.value === 'overdue';
  const escalatedOnly = view.value === 'escalated';
  const names = useReferenceNames(state.site);

  const query = useApiQuery(
    (signal) =>
      workflowApi.search(
        {
          siteCode: state.site || undefined,
          status: status || undefined,
          type: type || undefined,
          priority: priority || undefined,
          severity: severity || undefined,
          assignee: assignee || undefined,
          overdueOnly: overdueOnly || undefined,
          escalatedOnly: escalatedOnly || undefined,
          page: state.apiPage,
          size: state.pageSize,
        },
        signal,
      ),
    [
      state.site,
      status,
      type,
      priority,
      severity,
      assignee,
      overdueOnly,
      escalatedOnly,
      state.apiPage,
      state.pageSize,
    ],
  );

  /** How many items each view holds, so the tab says what is behind it before it is opened. */
  const counts = useApiQuery(
    async (signal) => {
      const site = state.site || undefined;
      const [all, overdue, escalated, mine] = await Promise.all([
        workflowApi.search({ siteCode: site, size: 1 }, signal),
        workflowApi.search({ siteCode: site, overdueOnly: true, size: 1 }, signal),
        workflowApi.search({ siteCode: site, escalatedOnly: true, size: 1 }, signal),
        workflowApi.search({ siteCode: site, assignee: sflActor.user, size: 1 }, signal),
      ]);
      return {
        all: all.totalElements,
        overdue: overdue.totalElements,
        escalated: escalated.totalElements,
        mine: mine.totalElements,
      };
    },
    [state.site],
  );

  const columns = useMemo<FleetColumn<WorkflowItemResponse>[]>(
    () => [
      {
        key: 'workflowNumber',
        header: 'Item',
        width: 260,
        cell: (row) => (
          <CellStack
            primary={row.title}
            secondary={`${row.workflowNumber} · ${humanise(row.workflowType)}`}
          />
        ),
      },
      {
        key: 'related',
        header: 'Vehicle or record',
        width: 170,
        cell: (row) => {
          const vehicle =
            row.relatedRecordType === 'Vehicle' && row.relatedRecordId
              ? names.vehicles?.get(row.relatedRecordId)
              : undefined;
          return vehicle
            ? vehicle.registrationNumber
            : row.relatedRecordType
              ? humanise(row.relatedRecordType)
              : '-';
        },
      },
      {
        key: 'priority',
        header: 'Priority',
        width: 110,
        cell: (row) => <StatusBadge value={row.priority} />,
      },
      {
        key: 'severity',
        header: 'Severity',
        width: 110,
        cell: (row) => <StatusBadge value={row.severity} />,
      },
      {
        key: 'assignee',
        header: 'Assignee',
        width: 150,
        cell: (row) => row.assignee ?? <span className="opacity-70">Unassigned</span>,
      },
      {
        key: 'slaDueAt',
        header: 'SLA',
        width: 200,
        cell: (row) => <SlaCell row={row} />,
      },
      {
        key: 'status',
        header: 'Status',
        width: 130,
        cell: (row) => <StatusBadge value={row.status} />,
      },
    ],
    [names.vehicles],
  );

  return (
    <>
      <RegisterHeader
        title="Workflow queue"
        siteCode={state.site}
        onSiteChange={state.setSite}
        actions={
          <>
            <Button
              variant="outline"
              aria-label="Refresh"
              title="Refresh"
              onClick={() => {
                query.refetch();
                counts.refetch();
                names.refetch();
              }}
            >
              <Icon name="refresh" size={14} aria-hidden="true" />
            </Button>
            <Button variant="primary" onClick={() => setRaiseOpen(true)}>
              <Icon name="plus" size={14} aria-hidden="true" />
              Raise item
            </Button>
          </>
        }
      />

      <PageSection>
        <FleetTable
          paramPrefix="workflow"
          rows={query.data?.content ?? []}
          columns={columns}
          getRowId={(row) => row.id}
          loading={query.loading}
          error={query.error}
          onRetry={query.refetch}
          onRowClick={(row) => navigate(fleetPaths.workflowDetail(row.id))}
          totalElements={query.data?.totalElements ?? 0}
          pageSize={state.pageSize}
          caption="Workflow queue"
          heading={{
            title: 'Workflow items',
            description:
              'Defects, compliance renewals, trip exceptions and integration failures with their SLA standing.',
          }}
          tabs={VIEWS.map((entry) => ({
            value: entry.value,
            label: entry.label,
            count: counts.data?.[entry.value],
          }))}
          tab={view.value}
          onTabChange={(value) => setFilter('view', value === 'all' ? '' : value)}
          filters={
            <>
              <FilterDropdown
                name="status"
                label="Status"
                value={status}
                onChange={(value) => setFilter('status', value)}
                options={FLEET_WORKFLOW_STATUSES.map((value) => ({
                  value,
                  label: humanise(value),
                }))}
              />
              <FilterDropdown
                name="type"
                label="Type"
                value={type}
                onChange={(value) => setFilter('type', value)}
                options={FLEET_WORKFLOW_TYPES.map((value) => ({ value, label: humanise(value) }))}
              />
              <FilterDropdown
                name="priority"
                label="Priority"
                value={priority}
                onChange={(value) => setFilter('priority', value)}
                options={WORKFLOW_PRIORITIES.map((value) => ({ value, label: humanise(value) }))}
              />
              <FilterDropdown
                name="severity"
                label="Severity"
                value={severity}
                onChange={(value) => setFilter('severity', value)}
                options={WORKFLOW_SEVERITIES.map((value) => ({ value, label: humanise(value) }))}
              />
              <Input
                name="assignee"
                aria-label="Assignee"
                placeholder="Assignee"
                defaultValue={typedAssignee}
              />
            </>
          }
          emptyTitle="No workflow items match these filters"
          emptyDescription="A clear queue is a good sign - or widen the filters to check."
        />
      </PageSection>

      {/* Mounted only while open, so the dialog picks up the current site filter as its default
          and cannot reopen holding a half-typed item from a previous attempt. */}
      {raiseOpen && (
        <RaiseWorkflowItemDialog
          open
          defaultSiteCode={state.site || defaultSite}
          onClose={() => setRaiseOpen(false)}
          onSaved={() => {
            notifySuccess('Workflow item raised.');
            query.refetch();
            counts.refetch();
          }}
        />
      )}
    </>
  );
};

export default WorkflowQueuePage;
