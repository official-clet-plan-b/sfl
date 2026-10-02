import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import Button from 'shared/components/Button';
import DataTable, { CellStack, type Column } from 'shared/components/DataTable';
import FormDialog from 'shared/components/FormDialog';
import { useNotifier } from 'shared/components/Notifier';
import PageHeader from 'shared/components/PageHeader';
import SectionCard from 'shared/components/SectionCard';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import StatusChip from 'shared/components/StatusChip';
import { Checkbox, EnumSelect, TextAreaInput, TextInput } from 'shared/components/fields';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { incidentPaths } from 'shared/layout/navigation';
import RiskContextFields from 'modules/riskassessment/components/RiskAssessmentSelect';
import { incidentApi } from '../api/incidentApi';
import type { IncidentSource, IncidentStatus, SecurityIncident, Severity } from '../api/dto';

const statuses: IncidentStatus[] = ['TRIAGE', 'INVESTIGATING', 'CLOSED'];
const severities: Severity[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'EMERGENCY'];
const sources: IncidentSource[] = ['REPORTED', 'HSE', 'CCTV_SEED', 'ACCESS_SEED', 'INTRUSION_SEED', 'FIRE_SEED'];

const IncidentsPage = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const notify = useNotifier();
  const [siteCode, setSiteCode] = useState(defaultSite);
  const requestedStatus = searchParams.get('status') as IncidentStatus | null;
  const requestedSeverity = searchParams.get('severity') as Severity | null;
  const [status, setStatus] = useState<IncidentStatus | ''>(requestedStatus && statuses.includes(requestedStatus) ? requestedStatus : '');
  const [severity, setSeverity] = useState<Severity | ''>(requestedSeverity && severities.includes(requestedSeverity) ? requestedSeverity : '');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [open, setOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form, setForm] = useState({ source: 'REPORTED' as IncidentSource, anonymous: false, reporterId: '', reporterContact: '', description: '', nearMiss: false, riskAssessmentId: '', activityType: '' });
  const query = useApiQuery((signal) => incidentApi.search({ siteCode: siteCode || undefined, status: status || undefined, severity: severity || undefined, page, size, sort: 'createdAt,desc' }, signal), [siteCode, status, severity, page, size]);
  const columns = useMemo<Column<SecurityIncident>[]>(() => [
    { key: 'case', header: 'Case', width: 280, cell: (row) => <CellStack primary={row.reference} secondary={row.description} /> },
    { key: 'type', header: 'Type', width: 130, cell: (row) => <StatusChip value={row.nearMiss ? 'NEAR_MISS' : 'INCIDENT'} /> },
    { key: 'severity', header: 'Severity', width: 130, cell: (row) => row.severity ? <StatusChip value={row.severity} /> : '-' },
    { key: 'site', header: 'Site', width: 100, hideBelowLg: true, cell: (row) => row.siteCode },
    { key: 'reported', header: 'Reported', width: 170, hideBelowLg: true, cell: (row) => formatDateTime(row.metadata.createdAt) },
    { key: 'status', header: 'Status', width: 140, align: 'right', cell: (row) => <StatusChip value={row.status} /> },
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

  return <div>
    <PageHeader title="Incidents and near misses" subtitle="Report, triage, investigate and close safety and security cases." crumbs={[{ label: 'Safety & security' }, { label: 'Incidents' }]} actions={permits('INCIDENT_REPORT_CREATE') ? <Button variant="primary" startIcon="plus" onClick={() => setOpen(true)}>Report incident</Button> : undefined} />
    <SectionCard title="Case register" subtitle="Cases are ordered by report time and retain their human-readable reference." flush>
      <div className="grid gap-4 px-5 py-4 sm:grid-cols-3">
        <SiteSelect value={siteCode} onChange={(value) => { setSiteCode(value); setPage(0); }} allowEmpty />
        <EnumSelect label="Status" value={status} options={statuses} allowEmpty onChange={(value) => { setStatus(value); setPage(0); }} />
        <EnumSelect label="Severity" value={severity} options={severities} allowEmpty onChange={(value) => { setSeverity(value); setPage(0); }} />
      </div>
      <DataTable rows={query.data?.content ?? []} columns={columns} getRowId={(row) => row.id} loading={query.loading} onRowClick={(row) => navigate(incidentPaths.detail(row.id))} caption="Incident cases" page={page} pageSize={size} totalElements={query.data?.totalElements ?? 0} onPageChange={setPage} onPageSizeChange={(value) => { setSize(value); setPage(0); }} emptyMessage="No cases match these filters." />
    </SectionCard>
    <FormDialog open={open} title="Report an incident or near miss" description="Capture the facts known now. Triage and investigation follow as separate accountable steps." submitLabel="Open case" submitting={submitting} submitDisabled={!siteCode || !form.description.trim()} onClose={() => setOpen(false)} onSubmit={report}>
      <div className="grid gap-4 sm:grid-cols-2">
        <SiteSelect value={siteCode} onChange={(value) => { setSiteCode(value); setForm((current) => ({ ...current, riskAssessmentId: '' })); }} required />
        <EnumSelect label="Source" value={form.source} options={sources} required onChange={(value) => value && setForm((current) => ({ ...current, source: value }))} />
      </div>
      <div className="grid gap-4 sm:grid-cols-2"><Checkbox checked={form.nearMiss} onChange={(value) => setForm((current) => ({ ...current, nearMiss: value }))} label="This is a near miss" /><Checkbox checked={form.anonymous} onChange={(value) => setForm((current) => ({ ...current, anonymous: value, reporterId: value ? '' : current.reporterId }))} label="Anonymous report" /></div>
      {!form.anonymous && <div className="grid gap-4 sm:grid-cols-2"><TextInput label="Reporter ID" value={form.reporterId} onChange={(value) => setForm((current) => ({ ...current, reporterId: value }))} /><TextInput label="Reporter contact" value={form.reporterContact} onChange={(value) => setForm((current) => ({ ...current, reporterContact: value }))} /></div>}
      <TextAreaInput label="What happened?" value={form.description} onChange={(value) => setForm((current) => ({ ...current, description: value }))} rows={5} required />
      <RiskContextFields siteCode={siteCode} riskAssessmentId={form.riskAssessmentId} activityType={form.activityType} onRiskAssessmentChange={(value) => setForm((current) => ({ ...current, riskAssessmentId: value }))} onActivityTypeChange={(value) => setForm((current) => ({ ...current, activityType: value }))} />
    </FormDialog>
  </div>;
};
export default IncidentsPage;
