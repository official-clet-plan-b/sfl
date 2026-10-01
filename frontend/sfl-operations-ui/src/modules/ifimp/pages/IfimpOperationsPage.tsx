import { useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';
import Alert from 'shared/components/Alert';
import Button from 'shared/components/Button';
import DataState from 'shared/components/DataState';
import DataTable, { Column } from 'shared/components/DataTable';
import FilterBar from 'shared/components/FilterBar';
import PageHeader from 'shared/components/PageHeader';
import SiteSelect, { defaultSite, sflSites } from 'shared/components/SiteSelect';
import StatusChip from 'shared/components/StatusChip';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import { IfimpRecord, readIfimpDataset } from '../api/ifimpPhase2Api';
import IfimpCreateDialog, { CreateAction } from '../components/IfimpCreateDialog';
import IfimpRecordDialog from '../components/IfimpRecordDialog';

export interface IfimpView {
  label: string;
  path: string;
  description: string;
  columns?: Array<{ key: string; label: string }>;
  create?: CreateAction;
  actions?: CreateAction[];
  query?: Record<string, string>;
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

const identifier = (row: IfimpRecord): string =>
  String(row.id ?? row.code ?? row.deviceCode ?? row.projectNumber ?? JSON.stringify(row));

const humanise = (value: string): string =>
  value
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/_/g, ' ')
    .replace(/^./, (letter: string) => letter.toUpperCase());

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
  '/api/v1/facilities/event-logistics/setup-tasks': ['s078EventReference', 'title', 'startsAt', 'roomCode', 'readiness', 'status'],
  '/api/v1/facilities/event-logistics/templates': ['eventCategory', 'resourceType', 'quantity', 'gapCount', 'updatedAt'],
  '/api/v1/facilities/event-logistics/risk-criteria': ['siteCode', 'attendanceThreshold', 'externalContractorsAreHigherRisk', 'temporaryStructuresAreHigherRisk'],
  '/api/v1/facilities/event-logistics/integration': ['system', 'status', 'detail', 'checkedAt'],
  '/api/v1/facilities/construction/dashboard': ['siteCode', 'proposed', 'registered', 'inProgress', 'overdueMilestones', 'openDefects'],
  '/api/v1/facilities/construction/projects': ['projectNumber', 'title', 'status', 'projectManagerName', 'budgetBaseline', 'currency'],
  '/api/v1/facilities/construction/contractors': ['contractorCode', 'name', 'complianceStatus', 'insuranceExpiresOn', 'siteAccessStatus'],
  '/api/v1/facilities/construction/dashboard/integrations': ['system', 'status', 'detail', 'checkedAt'],
};

const renderValue = (value: unknown) => {
  if (value === null || value === undefined || value === '') {
    return <span className="text-gray-400">—</span>;
  }
  if (typeof value === 'boolean') {
    return <StatusChip value={value ? 'Yes' : 'No'} tone={value ? 'ready' : 'neutral'} />;
  }
  const text = ['string', 'number'].includes(typeof value) ? String(value) : JSON.stringify(value);
  const statusLike = typeof value === 'string' && /^[A-Z][A-Z0-9_ -]+$/.test(value);
  return statusLike ? <StatusChip value={humanise(value)} /> : <span>{text}</span>;
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
  const basePath = requestedView
    ? location.pathname.slice(0, location.pathname.lastIndexOf('/'))
    : location.pathname;
  const query = useApiQuery(
    (signal) => readIfimpDataset(active.path, siteCode, active.query, signal),
    [active.path, siteCode],
  );

  const declaredColumns = active.columns ?? (namedColumns[active.path] ?? []).map((key) => ({ key, label: humanise(key) }));
  const columns: Column<IfimpRecord>[] = declaredColumns.map((column) => ({
    key: column.key,
    header: column.label,
    cell: (row) => renderValue(row[column.key]),
  }));

  return (
    <>
      <PageHeader
        title={title}
        subtitle={subtitle}
        crumbs={[{ label: 'Facilities', to: facilitiesPaths.dashboard }, { label: system }]}
        actions={<div className="flex gap-2">{active.create && <Button startIcon="plus" onClick={() => setCreating(true)}>{active.create.label}</Button>}<Button variant="outline" startIcon="refresh" onClick={query.refetch}>Refresh</Button></div>}
      />

      {dependencyNote && (
        <Alert variant="info" title="Integration position" className="mb-5">
          {dependencyNote}
        </Alert>
      )}

      <FilterBar>
        <SiteSelect
          value={siteCode}
          onChange={setSiteCode}
          required
          helperText="Phase 2 operations are shown for one site at a time."
        />
      </FilterBar>

      <div className="mb-5 flex flex-wrap gap-2" aria-label={`${title} views`}>
        {views.map((candidate, index) => (
          <Button
            key={candidate.path}
            variant={active.path === candidate.path ? 'primary' : 'outline'}
            onClick={() => navigate(index === 0 ? basePath : `${basePath}/${viewSlug(candidate)}`)}
          >
            {candidate.label}
          </Button>
        ))}
      </div>

      <div className="mb-3">
        <h2 className="text-title-xs font-semibold text-gray-900">{active.label}</h2>
        <p className="mt-1 text-theme-sm text-gray-500">{active.description}</p>
      </div>

      <DataState
        loading={query.loading}
        error={query.error}
        empty={query.data?.rows.length === 0}
        emptyTitle={`No ${active.label.toLowerCase()} found`}
        emptyHint={`There are no records for ${siteCode || 'the sites you can access'}.`}
        onRetry={query.refetch}
      >
        <DataTable
          rows={query.data?.rows ?? []}
          columns={columns}
          getRowId={identifier}
          loading={query.loading}
          caption={active.label}
          onRowClick={(active.actions?.length ?? 0) > 0 ? setSelected : undefined}
        />
      </DataState>
      {active.create && <IfimpCreateDialog key={`${active.path}-${creating}`} action={active.create} siteCode={siteCode} open={creating} onClose={() => setCreating(false)} onCreated={query.refetch} />}
      {selected && <IfimpRecordDialog record={selected} title={active.label} siteCode={siteCode} actions={active.actions} onClose={() => setSelected(undefined)} onChanged={query.refetch} />}
    </>
  );
};

export default IfimpOperationsPage;
