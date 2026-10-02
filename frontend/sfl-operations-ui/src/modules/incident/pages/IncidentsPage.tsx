import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { Plus } from 'lucide-react';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { incidentPaths } from 'shared/layout/navigation';
import RiskContextFields from 'modules/riskassessment/components/RiskAssessmentSelect';
import { incidentApi } from '../api/incidentApi';
import type { IncidentSource, IncidentStatus, SecurityIncident, Severity } from '../api/dto';
import { Button, Dropdown, PageSection, useTableState, type TableColumn } from '@rfdtech/components';
import { humanise } from 'modules/fleet/api/enums';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import StatusBadge from 'modules/emergency/components/StatusBadge';
import ActionDialog from 'modules/emergency/components/ActionDialog';
import { TextField, TextAreaField, EnumField, CheckboxField } from 'modules/emergency/components/FormFields';
import RegisterTable, { CellStack, DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from 'modules/emergency/components/RegisterTable';

const statuses: IncidentStatus[] = ['TRIAGE', 'INVESTIGATING', 'CLOSED'];
const severities: Severity[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'EMERGENCY'];
const sources: IncidentSource[] = ['REPORTED', 'HSE', 'CCTV_SEED', 'ACCESS_SEED', 'INTRUSION_SEED', 'FIRE_SEED'];

const IncidentsPage = () => {
  const navigate = useNavigate();
  const notify = useNotifier();
  const [siteCode, setSiteCode] = useState(defaultSite);
  const { page, pageSize, setPage, filters } = useTableState({ paramPrefix: 'cases', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const requestedStatus = filters.status as IncidentStatus | undefined;
  const requestedSeverity = filters.severity as Severity | undefined;
  const status: IncidentStatus | '' = requestedStatus && statuses.includes(requestedStatus) ? requestedStatus : '';
  const severity: Severity | '' = requestedSeverity && severities.includes(requestedSeverity) ? requestedSeverity : '';
  const [statusField, setStatusField] = useState(status);
  const [severityField, setSeverityField] = useState(severity);
  const [open, setOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form, setForm] = useState({ source: 'REPORTED' as IncidentSource, anonymous: false, reporterId: '', reporterContact: '', description: '', nearMiss: false, riskAssessmentId: '', activityType: '' });
  const query = useApiQuery((signal) => incidentApi.search({ siteCode: siteCode || undefined, status: status || undefined, severity: severity || undefined, page: page - 1, size: pageSize, sort: 'createdAt,desc' }, signal), [siteCode, status, severity, page, pageSize]);
  const columns = useMemo<TableColumn<SecurityIncident>[]>(() => [
    { id: 'case', header: 'Case', width: 280, cell: ({ row }) => <CellStack primary={row.reference} secondary={row.description} /> },
    { id: 'type', header: 'Type', width: 130, cell: ({ row }) => <StatusBadge value={row.nearMiss ? 'NEAR_MISS' : 'INCIDENT'} /> },
    { id: 'severity', header: 'Severity', width: 130, cell: ({ row }) => row.severity ? <StatusBadge value={row.severity} /> : '-' },
    { id: 'site', header: 'Site', width: 100, hideBelowLg: true, cell: ({ row }) => row.siteCode },
    { id: 'reported', header: 'Reported', width: 170, hideBelowLg: true, cell: ({ row }) => formatDateTime(row.metadata.createdAt) },
    { id: 'status', header: 'Status', width: 140, align: 'right', cell: ({ row }) => <StatusBadge value={row.status} /> },
  ], []);

  const report = async () => {
    if (!siteCode || !form.description.trim()) return;
    setSubmitting(true);
    try {
      const incident = await incidentApi.report({ siteCode, source: form.source, anonymous: form.anonymous, reporterId: form.anonymous ? undefined : form.reporterId.trim() || undefined, reporterContact: form.reporterContact.trim() || undefined, description: form.description.trim(), nearMiss: form.nearMiss, riskAssessmentId: form.riskAssessmentId || undefined, activityType: form.activityType.trim() || undefined });
      notify.notifySuccess('Incident reported', incident.reference);
      setOpen(false); query.refetch(); navigate(incidentPaths.detail(incident.id));
    } catch (error) { notify.notifyError(error); } finally { setSubmitting(false); }
  };

  return <>
    <PageHeading
      title="Incidents and near misses"
      subtitle="Report, triage, investigate and close safety and security cases."
      crumbs={[{ label: 'Safety & security' }, { label: 'Incidents' }]}
      actions={<>
        <SiteSelect label="Site" value={siteCode} onChange={(value) => { setSiteCode(value); setPage(1); }} allowEmpty className="w-44" />
        {permits('INCIDENT_REPORT_CREATE') && <Button variant="primary" onClick={() => setOpen(true)}><Plus size={14} strokeWidth={1.5} aria-hidden /> Report incident</Button>}
      </>}
    />
    <PageSection>
      <Panel title="Case register" subtitle="Cases are ordered by report time and retain their human-readable reference.">
        <RegisterTable
          paramPrefix="cases"
          framed={false}
          columns={columns}
          rows={query.data?.content ?? []}
          rowKey={(row) => row.id}
          loading={query.initialising}
          onRowClick={(row) => navigate(incidentPaths.detail(row.id))}
          emptyTitle="No cases match these filters"
          emptyDescription="Report an incident or near miss, or widen the site, status and severity."
          totalItems={query.data?.totalElements ?? 0}
          size={pageSize}
          filterCount={2}
          filters={<>
            <Dropdown name="status" aria-label="Filter by status" value={statusField || null} onValueChange={(next) => setStatusField((next ?? '') as IncidentStatus | '')} options={statuses.map((value) => ({ value, label: humanise(value) }))} placeholder="All statuses" clearable />
            <Dropdown name="severity" aria-label="Filter by severity" value={severityField || null} onValueChange={(next) => setSeverityField((next ?? '') as Severity | '')} options={severities.map((value) => ({ value, label: humanise(value) }))} placeholder="All severities" clearable />
          </>}
        />
      </Panel>
    </PageSection>
    <ActionDialog open={open} title="Report an incident or near miss" description="Capture the facts known now. Triage and investigation follow as separate accountable steps." submitLabel="Open case" submitting={submitting} submitDisabled={!siteCode || !form.description.trim()} onClose={() => setOpen(false)} onSubmit={report}>
      <div className="grid gap-4 sm:grid-cols-2">
        <SiteSelect value={siteCode} onChange={(value) => { setSiteCode(value); setForm((current) => ({ ...current, riskAssessmentId: '' })); }} required />
        <EnumField label="Source" value={form.source} options={sources} required onChange={(value) => value && setForm((current) => ({ ...current, source: value }))} />
      </div>
      <div className="grid gap-4 sm:grid-cols-2"><CheckboxField checked={form.nearMiss} onChange={(value) => setForm((current) => ({ ...current, nearMiss: value }))} label="This is a near miss" /><CheckboxField checked={form.anonymous} onChange={(value) => setForm((current) => ({ ...current, anonymous: value, reporterId: value ? '' : current.reporterId }))} label="Anonymous report" /></div>
      {!form.anonymous && <div className="grid gap-4 sm:grid-cols-2"><TextField label="Reporter ID" value={form.reporterId} onChange={(value) => setForm((current) => ({ ...current, reporterId: value }))} /><TextField label="Reporter contact" value={form.reporterContact} onChange={(value) => setForm((current) => ({ ...current, reporterContact: value }))} /></div>}
      <TextAreaField label="What happened?" value={form.description} onChange={(value) => setForm((current) => ({ ...current, description: value }))} rows={5} required />
      <RiskContextFields siteCode={siteCode} riskAssessmentId={form.riskAssessmentId} activityType={form.activityType} onRiskAssessmentChange={(value) => setForm((current) => ({ ...current, riskAssessmentId: value }))} onActivityTypeChange={(value) => setForm((current) => ({ ...current, activityType: value }))} />
    </ActionDialog>
  </>;
};
export default IncidentsPage;
