import { useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';
import { Eye, Pencil, Plus, RefreshCw } from 'lucide-react';
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
  Tabs,
  TabsList,
  TabsTrigger,
  useBreadcrumbs,
} from '@rfdtech/components';
import type { TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite, sflSites } from 'shared/components/SiteSelect';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { facilitiesPaths } from 'shared/layout/navigation';
import type { SflPermission } from 'shared/layout/permissions';
import { IfimpRecord, readIfimpDataset } from '../api/ifimpPhase2Api';
import IfimpCreateDialog, { CreateAction } from '../components/IfimpCreateDialog';
import IfimpRecordDialog, { visibleRecordActions } from '../components/IfimpRecordDialog';
import IfimpValue, { humaniseIfimpField } from '../components/IfimpValue';

export interface IfimpView {
  label: string;
  path: string;
  endpoint?: string;
  description: string;
  columns?: Array<{ key: string; label: string }>;
  create?: CreateAction;
  actions?: CreateAction[];
  query?: Record<string, string>;
  /**
   * What it takes to create or change a record on this view. When set, a role without it sees the
   * records and no Create or Manage controls - the service would refuse every write, so the screen
   * does not offer one.
   */
  writePermission?: SflPermission;
}

/** Stable route segment used by both the sidebar and the compact secondary navigation. */
export const viewSlug = (view: IfimpView): string => {
  const segments = view.path.split('/').filter(Boolean);
  return segments[segments.length - 1] ?? '';
};

export interface IfimpOperationsPageProps {
  system: string;
  title: string;
  subtitle: string;
  dependencyNote?: string;
  views: IfimpView[];
}

// Phase 2 operational endpoints work on one site at a time. Wildcard actors therefore start on the
// first real site instead of sending an empty siteCode and meeting a validation error on arrival.
const phase2DefaultSite = defaultSite || sflSites()[0] || '';

const valueAtPath = (row: IfimpRecord, path: string): unknown =>
  path.split('.').reduce<unknown>((current, segment) => (
    current !== null && typeof current === 'object'
      ? (current as Record<string, unknown>)[segment]
      : undefined
  ), row);

const identifier = (row: IfimpRecord): string => {
  const nestedTask = valueAtPath(row, 'task.id');
  return String(row.id ?? nestedTask ?? row.code ?? row.deviceCode ?? row.projectNumber ?? JSON.stringify(row));
};

const namedColumns: Record<string, string[]> = {
  '/api/v1/facilities/building-systems/health': ['siteCode', 'state', 'activeAlerts', 'linkedWorkOrders', 'procurementGate'],
  '/api/v1/facilities/building-systems/devices': ['deviceCode', 'name', 'systemType', 'kind', 'status', 'buildingCode'],
  '/api/v1/facilities/building-systems/alerts': ['deviceCode', 'priority', 'status', 'reason', 'raisedAt'],
  '/api/v1/facilities/building-systems/quarantine': ['deviceCode', 'channel', 'code', 'status', 'observedAt'],
  '/api/v1/facilities/building-systems/rules': ['name', 'quantity', 'condition', 'priority', 'enabled', 'version'],
  '/api/v1/facilities/building-systems/readings': ['deviceCode', 'channel', 'quantity', 'value', 'observedAt'],
  '/api/v1/facilities/energy/health': ['siteCode', 'procurementGate', 'activeMeters', 'heldReadings', 'latestPostedAt'],
  '/api/v1/facilities/energy/meters': ['meterCode', 'name', 'utility', 'source', 'status', 'buildingCode'],
  '/api/v1/facilities/energy/readings': ['meterCode', 'utility', 'value', 'status', 'observedAt', 'enteredBy'],
  '/api/v1/facilities/energy/alerts': ['type', 'utility', 'meterCode', 'status', 'detail', 'raisedAt'],
  '/api/v1/facilities/energy/budgets': ['utility', 'periodStart', 'consumptionBudget', 'costBudget', 'currency', 'version'],
  '/api/v1/facilities/energy/tariffs': ['utility', 'unitRate', 'currency', 'effectiveFrom', 'version'],
  '/api/v1/facilities/energy/kpis': ['scope', 'utility', 'periodStart', 'value', 'completeness', 'publishedAt'],
  '/api/v1/facilities/space-planning/dashboard': ['siteCode', 'draftScenarios', 'openRequests', 'nonCompliantRooms', 'activeSignals'],
  '/api/v1/facilities/space-planning/scenarios': ['name', 'version', 'status', 'changeType', 'createdBy', 'updatedAt'],
  '/api/v1/facilities/space-planning/allocations': ['roomCode', 'unitCode', 'headcount', 'spaceType', 'effectiveFrom'],
  '/api/v1/facilities/space-planning/standards': ['spaceType', 'areaPerPerson', 'maximumOccupancy', 'version', 'effectiveFrom'],
  '/api/v1/facilities/space-planning/utilisation/signals': ['roomCode', 'signalType', 'utilisation', 'active', 'observedAt'],
  '/api/v1/facilities/space-planning/requests': ['requestNumber', 'title', 'changeType', 'status', 'requestedBy', 'createdAt'],
  '/api/v1/facilities/cleaning/dashboard': ['siteCode', 'scheduled', 'completed', 'overdue', 'slaCompliance', 'averageRating'],
  '/api/v1/facilities/cleaning/tasks': ['taskNumber', 'roomCode', 'taskType', 'status', 'vendorName', 'dueAt'],
  '/api/v1/facilities/cleaning/schedules': ['name', 'roomCode', 'frequency', 'active', 'nextDueAt'],
  '/api/v1/facilities/cleaning/checklist-templates': ['name', 'spaceType', 'version', 'active', 'itemCount', 'updatedAt'],
  '/api/v1/facilities/cleaning/vendors': ['vendorCode', 'name', 'status', 'slaCompliance', 'averageRating'],
  '/api/v1/facilities/cleaning/capacity': ['vendorName', 'crewCount', 'peakCommitments', 'availableCrews', 'from', 'to'],
  '/api/v1/facilities/event-logistics/setup-tasks': ['task.taskReference', 'task.title', 'task.startsAt', 'task.roomCode', 'readiness', 'task.status'],
  '/api/v1/facilities/event-logistics/templates': ['eventCategory', 'resourceType', 'quantity', 'gapCount', 'updatedAt'],
  '/api/v1/facilities/event-logistics/risk-criteria': ['attendanceThreshold', 'externalContractorsAreHigherRisk', 'temporaryStructuresAreHigherRisk', 'higherRiskCategories'],
  '/api/v1/facilities/event-logistics/integration': ['ccpEvents', 'owningSystems', 'riskAssessments'],
  '/api/v1/facilities/construction/dashboard': ['siteCode', 'proposed', 'registered', 'inProgress', 'overdueMilestones', 'openDefects'],
  '/api/v1/facilities/construction/projects': ['projectNumber', 'title', 'status', 'projectManagerName', 'budgetBaseline', 'currency'],
  '/api/v1/facilities/construction/contractors': ['contractorCode', 'name', 'complianceStatus', 'insuranceExpiresOn', 'siteAccessStatus'],
  '/api/v1/facilities/construction/dashboard/integrations': ['system', 'status', 'detail', 'checkedAt'],
};

/**
 * Shared page furniture for the six Phase 2 IFIMP modules. Endpoints, labels and columns are all
 * explicitly declared by the domain page; only loading/filter/empty behaviour is shared. This is
 * the same register pattern as the existing Facilities screens, not a generic API browser.
 */
const IfimpOperationsPage = ({
  system,
  title,
  subtitle,
  dependencyNote,
  views,
}: IfimpOperationsPageProps) => {
  const location = useLocation();
  const navigate = useNavigate();
  const { view: requestedView } = useParams<{ view?: string }>();
  const [siteCode, setSiteCode] = useState(phase2DefaultSite);
  const [creating, setCreating] = useState(false);
  const [selected, setSelected] = useState<IfimpRecord>();
  const active = views.find((candidate) => viewSlug(candidate) === requestedView) ?? views[0];
  const canWrite = !active.writePermission || permits(active.writePermission);
  const createAction = canWrite ? active.create : undefined;
  const recordActions = canWrite ? active.actions : undefined;
  const basePath = requestedView
    ? location.pathname.slice(0, location.pathname.lastIndexOf('/'))
    : location.pathname;
  useBreadcrumbs([{ label: 'Facilities', href: facilitiesPaths.dashboard }, { label: system }]);
  const query = useApiQuery(
    (signal) => readIfimpDataset(active.endpoint ?? active.path, siteCode, active.query, signal),
    [active.endpoint, active.path, siteCode],
  );

  const inferredKeys = Object.keys(query.data?.rows[0] ?? {}).slice(0, 6);
  const declaredColumns = active.columns ?? (namedColumns[active.path] ?? inferredKeys).map((key) => ({
    key,
    label: humaniseIfimpField(key.split('.').slice(-1)[0] ?? key),
  }));
  const columns: TableColumn<IfimpRecord>[] = declaredColumns.map((column) => ({
    id: column.key,
    header: column.label,
    cell: ({ row }) => <IfimpValue value={valueAtPath(row, column.key)} compact />,
  }));
  columns.push({
    id: 'recordActions',
    header: 'Actions',
    align: 'right',
    cell: ({ row }) => {
      const canManage = visibleRecordActions(recordActions ?? [], row).length > 0;
      return (
        <Button
          size="sm"
          variant="outline"
          // The row opens the same record; this button must not open it twice.
          onClick={(event) => {
            event.stopPropagation();
            setSelected(row);
          }}
        >
          {canManage ? <Pencil size={14} aria-hidden="true" /> : <Eye size={14} aria-hidden="true" />}
          {canManage ? 'Manage' : 'View'}
        </Button>
      );
    },
  });

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>{title}</SectionTitle>
          <SectionDescription>{subtitle}</SectionDescription>
          <SectionActions>
            <Button variant="outline" onClick={query.refetch}>
              <RefreshCw size={14} strokeWidth={1.5} aria-hidden="true" />
              Refresh
            </Button>
            {createAction && (
              <Button variant="primary" onClick={() => setCreating(true)}>
                <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
                {createAction.label}
              </Button>
            )}
          </SectionActions>
        </SectionHeader>
      </PageSection>

      {dependencyNote && (
        <PageSection>
          <Banner variant="info" heading="Integration position" subtext={dependencyNote} />
        </PageSection>
      )}

      <PageSection>
        <div className="max-w-sm">
          <SiteSelect
            value={siteCode}
            onChange={setSiteCode}
            required
            helperText="Records are shown for one site at a time."
          />
        </div>
      </PageSection>

      <PageSection>
        <Tabs
          variant="pill"
          value={active.path}
          onValueChange={(path) => {
            const next = views.find((candidate) => candidate.path === path);
            if (next) {
              navigate(next === views[0] ? basePath : `${basePath}/${viewSlug(next)}`);
            }
          }}
        >
          <TabsList aria-label={`${title} views`}>
            {views.map((candidate) => (
              <TabsTrigger key={candidate.path} value={candidate.path}>
                {candidate.label}
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>
      </PageSection>

      <PageSection>
        <SectionHeader>
          <SectionTitle>{active.label}</SectionTitle>
          <SectionDescription>{active.description}</SectionDescription>
        </SectionHeader>
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <Table paramPrefix="ifimp" variant="soft">
            <Card bordered>
              <TableContent
                variant="soft"
                columns={columns}
                data={query.data?.rows ?? []}
                rowKey={identifier}
                loading={query.loading}
                onRowClick={setSelected}
                aria-label={active.label}
                emptyContent={
                  <EmptyState
                    title={`No ${active.label.toLowerCase()} found`}
                    description={`There are no records for ${siteCode || 'the sites you can access'}.`}
                  />
                }
              />
            </Card>
          </Table>
        </DataState>
      </PageSection>
      {createAction && <IfimpCreateDialog key={`${active.path}-${creating}`} action={createAction} siteCode={siteCode} open={creating} onClose={() => setCreating(false)} onCreated={query.refetch} />}
      {selected && <IfimpRecordDialog record={selected} title={active.label} siteCode={siteCode} actions={recordActions} onClose={() => setSelected(undefined)} onChanged={query.refetch} />}
    </>
  );
};

export default IfimpOperationsPage;
