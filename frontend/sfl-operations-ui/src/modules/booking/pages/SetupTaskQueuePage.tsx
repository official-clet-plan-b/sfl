import { useState } from 'react';
import { useNavigate } from 'react-router';
import {
  Banner,
  Button,
  Card,
  EmptyState,
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
  Table,
  TableContent,
  useBreadcrumbs,
} from '@rfdtech/components';
import type { TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { bookingPaths, facilitiesPaths } from 'shared/layout/navigation';
import { formatDateTime, orDash } from 'modules/facilities/components/facilitiesFormat';
import StatusBadge from 'modules/facilities/components/StatusBadge';
import { DateTimeField } from 'modules/facilities/dialogs/dialogKit';
import { setupTasksApi } from '../api/bookingApi';
import type { SetupTask } from '../api/dto';
import { canResolveSetupTask } from '../api/workflow';
import CellStack from '../components/CellStack';
import ControlButton from '../components/ControlButton';
import { fromLocalInput, setupTaskTone } from '../components/bookingFormat';
import ResolveSetupTaskDialog from '../dialogs/ResolveSetupTaskDialog';

/**
 * The room-turnaround queue - SRS-SFL-S159-02.
 *
 * ## Ordered by when the room is needed, not when the task was raised
 *
 * That ordering is the service's, and it is the whole value of the screen: a task for this afternoon
 * matters more than one raised last week for next month, and a created-at ordering gets that
 * backwards every single time.
 *
 * ## Why these are not S153 work orders
 *
 * Routing them there would buy the queue, the SLA and the closure evidence for free. It would also
 * put a twenty-minute chair rearrangement in the same queue as a failed standby generator, where the
 * generator ends up on page four. The two queues are separate because the work is not comparable.
 */
const SetupTaskQueuePage = () => {
  const navigate = useNavigate();
  const notify = useNotifier();
  useBreadcrumbs([{ label: 'Facilities', href: facilitiesPaths.dashboard }, { label: 'Room turnaround' }]);
  const [siteCode, setSiteCode] = useState<string>(defaultSite);
  const [dueBefore, setDueBefore] = useState('');
  const [resolving, setResolving] = useState<SetupTask | null>(null);

  const tasks = useApiQuery(
    (signal) =>
      setupTasksApi.queue(
        {
          siteCode: siteCode || undefined,
          dueBefore: dueBefore ? fromLocalInput(dueBefore) : undefined,
          limit: 200,
        },
        signal,
      ),
    [siteCode, dueBefore],
  );

  const rows = tasks.data?.items ?? [];
  const overdue = rows.filter((task) => task.overdue && task.status === 'PENDING').length;

  const columns: TableColumn<SetupTask>[] = [
    {
      id: 'description',
      header: 'Task',
      cell: ({ row: task }) => <CellStack primary={task.description} secondary={task.siteCode} />,
    },
    {
      id: 'dueBy',
      header: 'Room needed by',
      width: 200,
      cell: ({ row: task }) => (
        <span className={task.overdue && task.status === 'PENDING' ? 'font-medium text-error' : undefined}>
          {task.dueBy ? formatDateTime(task.dueBy) : 'No time set'}
        </span>
      ),
    },
    {
      id: 'assignedTo',
      header: 'Assigned',
      cell: ({ row: task }) => orDash(task.assignedTo),
    },
    {
      id: 'status',
      header: 'Status',
      width: 120,
      cell: ({ row: task }) => <StatusBadge value={task.status} tone={setupTaskTone(task.status)} />,
    },
    {
      id: 'resolve',
      header: 'Resolve',
      align: 'right',
      width: 150,
      cell: ({ row: task }) => (
        <ControlButton
          state={canResolveSetupTask(task)}
          size="sm"
          variant="outline"
          // The row opens the booking; this button must not also do that.
          onClick={(event) => {
            event.stopPropagation();
            setResolving(task);
          }}
        >
          Resolve
        </ControlButton>
      ),
    },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Room turnaround</SectionTitle>
          <SectionDescription>
            What has to happen to a room before its next booking, most urgent first
          </SectionDescription>
          <SectionActions className="items-end [&_button]:whitespace-nowrap">
            <SiteSelect value={siteCode} onChange={setSiteCode} allowEmpty emptyLabel="All sites" />
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <div className="flex flex-wrap items-end gap-3">
          <DateTimeField
            label="Needed before"
            value={dueBefore}
            onChange={setDueBefore}
            placeholder="Next two days"
            helperText="Blank shows the service's default window."
            className="w-full max-w-md"
          />
          <Button variant="ghost" onClick={() => setDueBefore('')} disabled={!dueBefore}>
            Reset
          </Button>
        </div>
      </PageSection>

      {overdue > 0 && (
        <PageSection>
          <Banner
            variant="warning"
            heading={`${overdue} past when the room was needed`}
            subtext="The booking each belongs to may already have started. Resolving one still records the outcome."
          />
        </PageSection>
      )}

      <PageSection>
        <DataState loading={false} error={tasks.error} onRetry={tasks.refetch}>
          <Table paramPrefix="setup-tasks" variant="soft">
            <Card bordered>
              <TableContent
                variant="soft"
                columns={columns}
                data={rows}
                rowKey={(task) => task.id}
                loading={tasks.loading}
                onRowClick={(task) => navigate(bookingPaths.bookingDetail(task.bookingId))}
                aria-label="Room turnaround queue"
                emptyContent={
                  <EmptyState
                    title="Nothing to set up"
                    description="No booking in this window takes a resource that needs setting up."
                  />
                }
              />
            </Card>
          </Table>
        </DataState>
      </PageSection>

      {resolving && (
        <ResolveSetupTaskDialog
          task={resolving}
          onClose={() => setResolving(null)}
          onSubmit={async (body) => {
            await setupTasksApi.resolve(resolving.id, body);
            setResolving(null);
            notify.notifySuccess(
              body.outcome === 'DONE' ? 'Marked done.' : 'Marked skipped, with your reason.',
            );
            tasks.refetch();
          }}
        />
      )}
    </>
  );
};

export default SetupTaskQueuePage;
