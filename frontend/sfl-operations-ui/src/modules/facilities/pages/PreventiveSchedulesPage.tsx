import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Plus } from 'lucide-react';
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
import { facilitiesPaths } from 'shared/layout/navigation';
import type { PreventiveSchedule } from '../api/dto';
import { createSchedule, listSchedules, runGeneration } from '../api/facilitiesApi';
import { canManageSchedules } from '../api/workflow';
import StatusBadge from '../components/StatusBadge';
import CreateScheduleDialog from '../dialogs/CreateScheduleDialog';
import { formatDate } from '../components/facilitiesFormat';

/**
 * Preventive maintenance schedules.
 *
 * ## The loop this screen closes
 *
 * S152 has carried `serviceIntervalDays` and `lastServicedOn` on every asset since it shipped, and
 * its dashboard has counted what is overdue from them. Nothing acted on either: the interval could
 * be set at registration and then only watched. A schedule raises the work, and closing that work
 * writes the service date back to the asset.
 *
 * ## Why "due for generation" is the service's answer and not a date comparison
 *
 * `dueForGeneration` accounts for something a client cannot see: whether this cycle has *already*
 * been generated for. A schedule inside its lead-time window that has already raised its work order
 * is not due, and a screen comparing `generateOn` to today would say it was - then a supervisor
 * would press Generate, nothing would happen, and they would reasonably conclude the button was
 * broken.
 */
const PreventiveSchedulesPage = () => {
  const navigate = useNavigate();
  const notify = useNotifier();
  useBreadcrumbs([
    { label: 'Facilities', href: facilitiesPaths.dashboard },
    { label: 'Preventive schedules' },
  ]);
  const [siteCode, setSiteCode] = useState<string>(defaultSite);
  const [creating, setCreating] = useState(false);

  const schedules = useApiQuery(
    (signal) => listSchedules({ siteCode: siteCode || undefined }, signal),
    [siteCode],
  );

  const dueNow = (schedules.data ?? []).filter((schedule) => schedule.dueForGeneration).length;

  const generate = async () => {
    try {
      const run = await runGeneration();
      notify.notifySuccess(
        run.workOrdersRaised > 0
          ? `${run.workOrdersRaised} work order(s) raised for ${formatDate(run.generatedFor)}.`
          : 'Nothing was due. Every schedule inside its window has already generated.',
      );
      schedules.refetch();
    } catch (cause) {
      notify.notifyError(cause);
    }
  };

  const columns: TableColumn<PreventiveSchedule>[] = [
    {
      id: 'scheduleCode',
      header: 'Schedule',
      width: 160,
      cell: ({ row }) => <span className="font-medium text-foreground">{row.scheduleCode}</span>,
    },
    { id: 'name', header: 'What it covers', accessorKey: 'name' },
    {
      id: 'intervalDays',
      header: 'Every',
      width: 110,
      cell: ({ row }) => `${row.intervalDays} days`,
    },
    {
      id: 'nextDueOn',
      header: 'Next due',
      width: 140,
      cell: ({ row }) => formatDate(row.nextDueOn),
    },
    {
      id: 'generateOn',
      header: 'Raises on',
      width: 140,
      cell: ({ row }) => (
        <div className="flex flex-col gap-0.5">
          <span>{formatDate(row.generateOn)}</span>
          <span className="text-xs text-muted-foreground">{row.leadTimeDays} days ahead</span>
        </div>
      ),
    },
    {
      id: 'dueForGeneration',
      header: 'State',
      width: 150,
      cell: ({ row: schedule }) =>
        schedule.dueForGeneration ? (
          <StatusBadge value="DUE_NOW" label="Due now" tone="caution" />
        ) : schedule.lifecycleStatus === 'ACTIVE' ? (
          <StatusBadge value="SCHEDULED" label="Scheduled" tone="neutral" />
        ) : (
          <StatusBadge value={schedule.lifecycleStatus} tone="neutral" />
        ),
    },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Preventive schedules</SectionTitle>
          <SectionDescription>Planned servicing, and what it has raised</SectionDescription>
          <SectionActions>
            <SiteSelect value={siteCode} onChange={setSiteCode} />
            {canManageSchedules() && (
              <>
                <Button variant="outline" onClick={generate}>
                  Generate due work
                </Button>
                <Button variant="primary" onClick={() => setCreating(true)}>
                  <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
                  New schedule
                </Button>
              </>
            )}
          </SectionActions>
        </SectionHeader>
      </PageSection>

      {dueNow > 0 && (
        <PageSection>
          <Banner
            variant="info"
            heading={`${dueNow} schedule(s) are due to raise work`}
            subtext="The scheduler does this hourly on its own. Generating by hand raises the same work and is safe to repeat - a schedule that has already generated for its current cycle produces nothing."
          />
        </PageSection>
      )}

      <PageSection>
        <DataState loading={false} error={schedules.error} onRetry={schedules.refetch}>
          <Table paramPrefix="schedules" variant="soft">
            <Card bordered>
              <TableContent
                variant="soft"
                columns={columns}
                data={schedules.data ?? []}
                rowKey={(schedule) => schedule.id}
                loading={schedules.loading}
                onRowClick={(schedule) => navigate(facilitiesPaths.scheduleDetail(schedule.id))}
                aria-label="Preventive schedules"
                emptyContent={
                  <EmptyState
                    title="No preventive schedules"
                    description="A schedule raises a work order ahead of each service date, and closing that work records the service against the asset."
                  />
                }
              />
            </Card>
          </Table>
        </DataState>
      </PageSection>

      {creating && (
        <CreateScheduleDialog
          siteCode={siteCode}
          onClose={() => setCreating(false)}
          onSubmit={async (request) => {
            const created = await createSchedule(request);
            setCreating(false);
            notify.notifySuccess(`Schedule ${created.scheduleCode} created.`);
            schedules.refetch();
          }}
        />
      )}
    </>
  );
};

export default PreventiveSchedulesPage;
