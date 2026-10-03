import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import dayjs from 'dayjs';
import { Plus } from 'lucide-react';
import { Button, Dropdown, PageSection, Tabs, TabsList, TabsTrigger, useTableState, type TableColumn } from '@rfdtech/components';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { drillPaths } from 'shared/layout/navigation';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import RegisterTable, { CellStack, DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from 'modules/emergency/components/RegisterTable';
import { drillApi } from '../api/drillApi';
import type { Drill } from '../api/dto';
import { drillStatusLabel, drillStatuses, drillTypeLabel, drillTypes, type DrillStatus, type DrillType } from '../api/enums';
import DrillCalendar from '../components/DrillCalendar';
import { DrillStatusChip } from '../components/drillChips';
import { PlanDialog } from '../dialogs/planDialogs';

type View = 'list' | 'calendar';

/**
 * The S175 register and calendar. Both read the same search: the list paged and filtered, the calendar the
 * month's window. Without a site, every site the caller is scoped to - the calendar is often a cross-site view.
 */
const DrillsPage = () => {
  const navigate = useNavigate();
  const [siteCode, setSiteCode] = useState(defaultSite);
  const [view, setView] = useState<View>('list');
  const [month, setMonth] = useState(dayjs().startOf('month'));
  const [planning, setPlanning] = useState(false);
  const { page, pageSize, setPage, filters, search } = useTableState({ paramPrefix: 'drills', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const status = (filters.status ?? '') as DrillStatus | '';
  const drillType = (filters.drillType ?? '') as DrillType | '';
  const [statusField, setStatusField] = useState(status);
  const [typeField, setTypeField] = useState(drillType);

  const list = useApiQuery(
    (signal) => drillApi.search({ siteCode: siteCode || undefined, status: status || undefined, drillType: drillType || undefined, q: search.trim() || undefined, page: page - 1, size: pageSize, sort: 'scheduledFor,desc' }, signal),
    [siteCode, status, drillType, search, page, pageSize],
  );
  const calendar = useApiQuery(
    (signal) => view === 'calendar'
      ? drillApi.search({ siteCode: siteCode || undefined, from: month.subtract(7, 'day').toISOString(), to: month.add(1, 'month').add(7, 'day').toISOString(), size: 200 }, signal)
      : Promise.resolve(undefined),
    [view, siteCode, month],
  );

  const columns = useMemo<TableColumn<Drill>[]>(() => [
    { id: 'drill', header: 'Drill', width: 300, cell: ({ row }) => <CellStack primary={row.reference} secondary={row.plan.title} /> },
    { id: 'type', header: 'Type', width: 170, cell: ({ row }) => drillTypeLabel[row.plan.drillType] },
    { id: 'site', header: 'Site', width: 100, cell: ({ row }) => row.siteCode },
    { id: 'when', header: 'Scheduled for', width: 170, hideBelowLg: true, cell: ({ row }) => (row.plan.scheduledFor ? formatDateTime(row.plan.scheduledFor) : 'Not dated') },
    { id: 'point', header: 'Assembly point', width: 140, hideBelowLg: true, cell: ({ row }) => row.plan.assemblyZone ?? '-' },
    { id: 'status', header: 'Status', width: 160, align: 'right', cell: ({ row }) => <DrillStatusChip status={row.status} /> },
  ], []);

  return (
    <>
      <PageHeading
        title="Drill register"
        subtitle="Every drill, planned to closed. Postponed and cancelled drills keep their plan and the reason."
        crumbs={[{ label: 'Drills', to: drillPaths.dashboard }, { label: 'Register' }]}
        actions={
          <>
            <SiteSelect label="Site" value={siteCode} onChange={(value) => { setSiteCode(value); setPage(1); }} className="w-44" />
            {permits('DRILL_PLAN') && <Button variant="primary" onClick={() => setPlanning(true)}><Plus size={14} strokeWidth={1.5} aria-hidden /> Plan a drill</Button>}
          </>
        }
      />
      <PageSection>
        <Tabs variant="pill" value={view} onValueChange={(value) => setView(value as View)}>
          <TabsList>
            <TabsTrigger value="list">List</TabsTrigger>
            <TabsTrigger value="calendar">Calendar</TabsTrigger>
          </TabsList>
        </Tabs>
      </PageSection>
      <PageSection>
        {view === 'list' ? (
          <Panel title="Drills" subtitle="Newest scheduled first.">
            <RegisterTable
              paramPrefix="drills"
              framed={false}
              columns={columns}
              rows={list.data?.content ?? []}
              rowKey={(row) => row.id}
              loading={list.initialising}
              onRowClick={(row) => navigate(drillPaths.detail(row.id))}
              emptyTitle="No drills match these filters"
              emptyDescription="Widen the status or type, clear the search, or plan a drill."
              searchPlaceholder="Search reference, title or scenario"
              totalItems={list.data?.totalElements ?? 0}
              size={pageSize}
              filterCount={2}
              filters={<>
                <Dropdown name="status" aria-label="Filter by status" value={statusField || null} onValueChange={(next) => setStatusField((next ?? '') as DrillStatus | '')} options={drillStatuses.map((value) => ({ value, label: drillStatusLabel[value] }))} placeholder="All statuses" clearable />
                <Dropdown name="drillType" aria-label="Filter by type" value={typeField || null} onValueChange={(next) => setTypeField((next ?? '') as DrillType | '')} options={drillTypes.map((value) => ({ value, label: drillTypeLabel[value] }))} placeholder="All types" clearable />
              </>}
            />
          </Panel>
        ) : (
          <Panel title="Calendar" subtitle={siteCode ? `Drills at ${siteCode}` : 'Drills at every site you can see'}>
            <DrillCalendar month={month} drills={calendar.data?.content ?? []} onMonthChange={setMonth} onOpen={(drill) => navigate(drillPaths.detail(drill.id))} />
          </Panel>
        )}
      </PageSection>
      {planning && (
        <PlanDialog
          siteCode={siteCode}
          onClose={() => setPlanning(false)}
          onSaved={(detail) => { setPlanning(false); navigate(drillPaths.detail(detail.drill.id)); }}
        />
      )}
    </>
  );
};

export default DrillsPage;
