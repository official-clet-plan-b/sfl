import { useState } from 'react';
import { Link, useParams } from 'react-router';
import Alert from 'shared/components/Alert';
import Button from 'shared/components/Button';
import DataState from 'shared/components/DataState';
import FormDialog from 'shared/components/FormDialog';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { useNotifier } from 'shared/components/Notifier';
import PageHeader from 'shared/components/PageHeader';
import SectionCard from 'shared/components/SectionCard';
import StatusChip from 'shared/components/StatusChip';
import { Checkbox, EnumSelect, NumberInput, TextAreaInput, TextInput } from 'shared/components/fields';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { incidentPaths, riskAssessmentPaths } from 'shared/layout/navigation';
import RiskContextFields from 'modules/riskassessment/components/RiskAssessmentSelect';
import { incidentApi } from '../api/incidentApi';
import { incidentRiskScore, incidentWorkflow } from '../api/workflow';
import type { CorrectiveAction, Impact, Likelihood, RetentionClass, Severity } from '../api/dto';

type Action = 'triage' | 'investigate' | 'evidence' | 'capa' | 'close' | 'risk' | null;
type CapaTransition = 'IN_PROGRESS' | 'VERIFY' | 'CANCEL';
const severities: Severity[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'EMERGENCY'];
const likelihoods: Likelihood[] = ['RARE', 'UNLIKELY', 'POSSIBLE', 'LIKELY', 'ALMOST_CERTAIN'];
const impacts: Impact[] = ['NEGLIGIBLE', 'MINOR', 'MODERATE', 'MAJOR', 'CATASTROPHIC'];
const retention: RetentionClass[] = ['STANDARD', 'EXTENDED', 'PERMANENT'];

const IncidentDetailPage = () => {
  const { incidentId = '' } = useParams();
  const notify = useNotifier();
  const query = useApiQuery((signal) => incidentApi.get(incidentId, signal), [incidentId]);
  const evidenceQuery = useApiQuery((signal) => incidentApi.evidence(incidentId, signal), [incidentId]);
  const capaQuery = useApiQuery((signal) => incidentApi.correctiveActions(incidentId, signal), [incidentId]);
  const [action, setAction] = useState<Action>(null);
  const [submitting, setSubmitting] = useState(false);
  const [triage, setTriage] = useState({ severity: 'MEDIUM' as Severity, likelihood: 'POSSIBLE' as Likelihood, impact: 'MODERATE' as Impact, reportable: false, notes: '' });
  const [investigation, setInvestigation] = useState({ investigatorId: '', notes: '' });
  const [evidence, setEvidence] = useState({ fileReference: '', fileName: '', mediaType: '', sizeBytes: '', contentHash: '', retentionClass: 'STANDARD' as RetentionClass, notes: '' });
  const [capa, setCapa] = useState({ description: '', ownerId: '', dueDate: '', mandatory: true });
  const [closureNotes, setClosureNotes] = useState('');
  const [riskContext, setRiskContext] = useState({ riskAssessmentId: '', activityType: '' });
  const [transitioning, setTransitioning] = useState<{ item: CorrectiveAction; next: CapaTransition } | null>(null);
  const [transitionNotes, setTransitionNotes] = useState('');
  const incident = query.data;

  const finish = (message: string) => { notify.notifySuccess(message); setAction(null); query.refetch(); };
  const submit = async () => {
    if (!incident || !action) return;
    setSubmitting(true);
    try {
      if (action === 'triage') { await incidentApi.triage(incident.id, { ...triage, reportabilityNotes: triage.notes || undefined, expectedVersion: incident.metadata.version }); finish('Triage recorded'); }
      if (action === 'investigate') { await incidentApi.investigate(incident.id, { investigatorId: investigation.investigatorId, investigationNotes: investigation.notes || undefined, expectedVersion: incident.metadata.version }); finish(incident.status === 'TRIAGE' ? 'Investigation opened' : 'Investigation updated'); }
      if (action === 'evidence') { await incidentApi.attachEvidence(incident.id, { fileReference: evidence.fileReference, fileName: evidence.fileName || undefined, mediaType: evidence.mediaType || undefined, sizeBytes: evidence.sizeBytes ? Number(evidence.sizeBytes) : undefined, contentHash: evidence.contentHash, retentionClass: evidence.retentionClass, notes: evidence.notes || undefined }); evidenceQuery.refetch(); finish('Evidence reference attached'); }
      if (action === 'capa') { await incidentApi.openCapa(incident.id, capa); capaQuery.refetch(); finish('Corrective action opened'); }
      if (action === 'close') { await incidentApi.close(incident.id, closureNotes, incident.metadata.version); finish('Incident closed'); }
      if (action === 'risk') { await incidentApi.recordRiskContext(incident.id, { riskAssessmentId: riskContext.riskAssessmentId || undefined, activityType: riskContext.activityType.trim() || undefined, expectedVersion: incident.metadata.version }); finish('Risk context recorded - any linked assessment is flagged for review'); }
    } catch (error) { notify.notifyError(error); } finally { setSubmitting(false); }
  };
  const transition = async () => {
    if (!transitioning) return;
    const { item, next } = transitioning;
    if ((next === 'VERIFY' || next === 'CANCEL') && !transitionNotes.trim()) return;
    setSubmitting(true);
    try {
      await incidentApi.transitionCapa(incidentId, item.id, { transition: next, notes: transitionNotes.trim() || undefined });
      capaQuery.refetch();
      notify.notifySuccess('Corrective action updated');
      setTransitioning(null);
      setTransitionNotes('');
    } catch (error) { notify.notifyError(error); } finally { setSubmitting(false); }
  };

  const score = incident?.riskRating ? incidentRiskScore(incident.riskRating.likelihood, incident.riskRating.impact) : null;
  return <div>
    <PageHeader title={incident?.reference ?? 'Incident case'} subtitle={incident?.nearMiss ? 'Near-miss investigation and corrective-action record.' : 'Incident investigation and corrective-action record.'} crumbs={[{ label: 'Incidents', to: incidentPaths.cases }, { label: incident?.reference ?? 'Case' }]} actions={incident && incident.status !== 'CLOSED' ? <>
      {incidentWorkflow.canTriage(incident) && permits('INCIDENT_TRIAGE') && <Button variant="outline" onClick={() => setAction('triage')}>Triage</Button>}
      {incidentWorkflow.canInvestigate(incident) && permits('INCIDENT_INVESTIGATE') && <Button variant="primary" onClick={() => setAction('investigate')}>{incident.status === 'TRIAGE' ? 'Open investigation' : 'Update investigation'}</Button>}
      {permits('INCIDENT_EVIDENCE_MANAGE') && <Button variant="outline" onClick={() => setAction('evidence')}>Attach evidence</Button>}
      {permits('INCIDENT_CAPA_MANAGE') && <Button variant="outline" onClick={() => setAction('capa')}>Add CAPA</Button>}
      {(permits('INCIDENT_TRIAGE') || permits('INCIDENT_INVESTIGATE')) && <Button variant="outline" onClick={() => { setRiskContext({ riskAssessmentId: incident.riskAssessmentId ?? '', activityType: incident.activityType ?? '' }); setAction('risk'); }}>Risk context</Button>}
            {incidentWorkflow.canClose(incident) && permits('INCIDENT_CLOSE') && <Button variant="accent" onClick={() => setAction('close')}>Close case</Button>}
    </> : undefined} />
    <DataState loading={query.initialising} error={query.error} onRetry={query.refetch}>
      {incident && <div className="space-y-5">
        {incident.emergencyEscalated && <Alert variant="error" title="Emergency escalation triggered">This case has been rated EMERGENCY and the escalation event is permanently recorded.</Alert>}
        <SectionCard title="Case standing" actions={<StatusChip value={incident.status} size="md" />}>
          <KeyValueGrid columns={4} items={[{ label: 'Site', value: incident.siteCode }, { label: 'Type', value: incident.nearMiss ? 'Near miss' : 'Incident' }, { label: 'Source', value: <StatusChip value={incident.source} /> }, { label: 'Severity', value: incident.severity ? <StatusChip value={incident.severity} /> : 'Not triaged' }, { label: 'Likelihood', value: incident.riskRating?.likelihood }, { label: 'Impact', value: incident.riskRating?.impact }, { label: 'Risk score', value: score }, { label: 'Reportable', value: incident.reportable ? 'Yes' : 'No' }]} />
        </SectionCard>
        <SectionCard title="Reported facts"><p className="whitespace-pre-wrap text-theme-sm leading-6 text-gray-800">{incident.description}</p><div className="mt-5"><KeyValueGrid columns={3} items={[{ label: 'Reporter', value: incident.anonymous ? 'Anonymous' : incident.reporterId }, { label: 'Reporter contact', value: incident.reporterContact }, { label: 'Reported', value: formatDateTime(incident.metadata.createdAt) }]} /></div></SectionCard>
        <SectionCard title="Risk context" subtitle="Phase 2 S165-04: the assessment this happened under is flagged for out-of-cycle review.">
          <KeyValueGrid columns={2} items={[{ label: 'Activity under way', value: incident.activityType ?? 'Not recorded' }, { label: 'Risk assessment', value: incident.riskAssessmentId ? (permits('RISK_ASSESSMENT_READ') ? <Link className="text-teal-700 underline-offset-2 hover:underline" to={riskAssessmentPaths.detail(incident.riskAssessmentId)}>Open the linked assessment</Link> : 'Linked') : 'Not linked' }]} />
        </SectionCard>
        <div className="grid gap-5 lg:grid-cols-2">
          <SectionCard title="Investigation"><KeyValueGrid columns={2} items={[{ label: 'Investigator', value: incident.investigatorId }, { label: 'Last changed', value: formatDateTime(incident.metadata.lastModifiedAt) }, { label: 'Findings', value: incident.investigationNotes, span: 2 }, { label: 'Reportability notes', value: incident.reportabilityNotes, span: 2 }, { label: 'Closure notes', value: incident.closureNotes, span: 2 }]} /></SectionCard>
          <SectionCard title="Record provenance"><KeyValueGrid columns={2} items={[{ label: 'Created by', value: incident.metadata.createdBy }, { label: 'Source channel', value: incident.metadata.sourceChannel }, { label: 'Last changed by', value: incident.metadata.lastModifiedBy }, { label: 'Version', value: incident.metadata.version }, { label: 'Closed', value: formatDateTime(incident.closedAt) }, { label: 'Correlation ID', value: incident.metadata.correlationId }]} /></SectionCard>
        </div>
        <SectionCard title="Corrective and preventive actions" subtitle="Mandatory open actions block case closure.">
          {capaQuery.data?.length ? <div className="space-y-3">{capaQuery.data.map((item) => <div key={item.id} className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-gray-200 p-4"><div><p className="font-semibold text-gray-900">{item.description}</p><p className="text-theme-xs text-gray-600">Owner {item.ownerId} · due {item.dueDate}{item.mandatory ? ' · mandatory for closure' : ''}</p></div><div className="flex items-center gap-2"><StatusChip value={item.status} />{item.status === 'OPEN' && <Button size="sm" onClick={() => setTransitioning({ item, next: 'IN_PROGRESS' })}>Start</Button>}{item.status === 'IN_PROGRESS' && permits('INCIDENT_CAPA_VERIFY') && <Button size="sm" onClick={() => setTransitioning({ item, next: 'VERIFY' })}>Verify</Button>}{!['VERIFIED', 'CANCELLED'].includes(item.status) && <Button size="sm" variant="ghost" onClick={() => setTransitioning({ item, next: 'CANCEL' })}>Cancel</Button>}</div></div>)}</div> : <p className="text-theme-sm text-gray-600">No corrective actions have been recorded.</p>}
        </SectionCard>
        <SectionCard title="Evidence references" subtitle="Files remain in the approved evidence store; this case retains provenance and the SHA-256 digest.">
          {evidenceQuery.data?.length ? <div className="space-y-3">{evidenceQuery.data.map((item) => <div key={item.id} className="rounded-md border border-gray-200 p-4"><div className="flex flex-wrap items-center justify-between gap-2"><p className="font-semibold text-gray-900">{item.fileName ?? item.fileReference}</p><StatusChip value={item.retentionClass} /></div><p className="mt-1 text-theme-xs text-gray-600">{item.fileReference} · uploaded {formatDateTime(item.uploadedAt)} by {item.uploadedBy}</p><p className="mt-2 break-all font-mono text-theme-xs text-gray-500">SHA-256 {item.contentHash}</p></div>)}</div> : <p className="text-theme-sm text-gray-600">No evidence references have been attached.</p>}
        </SectionCard>
      </div>}
    </DataState>

    <FormDialog open={action !== null} title={action === 'triage' ? 'Triage case' : action === 'investigate' ? 'Investigation' : action === 'evidence' ? 'Attach evidence reference' : action === 'capa' ? 'Open corrective action' : action === 'risk' ? 'Record the risk context' : 'Close case'} submitLabel={action === 'close' ? 'Close case' : 'Save'} submitting={submitting} destructive={false} submitDisabled={action === 'investigate' ? !investigation.investigatorId.trim() : action === 'evidence' ? !evidence.fileReference.trim() || evidence.contentHash.trim().length !== 64 : action === 'capa' ? !capa.description.trim() || !capa.ownerId.trim() || !capa.dueDate : action === 'close' ? !closureNotes.trim() : false} onClose={() => setAction(null)} onSubmit={submit}>
      {action === 'triage' && <div className="space-y-4"><div className="grid gap-4 sm:grid-cols-3"><EnumSelect label="Severity" value={triage.severity} options={severities} onChange={(value) => value && setTriage((current) => ({ ...current, severity: value }))} required /><EnumSelect label="Likelihood" value={triage.likelihood} options={likelihoods} onChange={(value) => value && setTriage((current) => ({ ...current, likelihood: value }))} required /><EnumSelect label="Impact" value={triage.impact} options={impacts} onChange={(value) => value && setTriage((current) => ({ ...current, impact: value }))} required /></div><Checkbox checked={triage.reportable} onChange={(value) => setTriage((current) => ({ ...current, reportable: value }))} label="Potentially statutorily reportable" /><TextAreaInput label="Reportability notes" value={triage.notes} onChange={(value) => setTriage((current) => ({ ...current, notes: value }))} /></div>}
      {action === 'investigate' && <div className="space-y-4"><TextInput label="Investigator ID" value={investigation.investigatorId} onChange={(value) => setInvestigation((current) => ({ ...current, investigatorId: value }))} required /><TextAreaInput label="Investigation findings" value={investigation.notes} onChange={(value) => setInvestigation((current) => ({ ...current, notes: value }))} rows={5} /></div>}
      {action === 'evidence' && <div className="grid gap-4 sm:grid-cols-2"><TextInput label="File reference" value={evidence.fileReference} onChange={(value) => setEvidence((current) => ({ ...current, fileReference: value }))} required /><TextInput label="File name" value={evidence.fileName} onChange={(value) => setEvidence((current) => ({ ...current, fileName: value }))} /><TextInput label="Media type" value={evidence.mediaType} onChange={(value) => setEvidence((current) => ({ ...current, mediaType: value }))} /><NumberInput label="Size" suffix="bytes" value={evidence.sizeBytes} onChange={(value) => setEvidence((current) => ({ ...current, sizeBytes: value }))} /><TextInput label="SHA-256 digest" value={evidence.contentHash} onChange={(value) => setEvidence((current) => ({ ...current, contentHash: value }))} helperText="Exactly 64 hexadecimal characters." className="sm:col-span-2" required /><EnumSelect label="Retention class" value={evidence.retentionClass} options={retention} onChange={(value) => value && setEvidence((current) => ({ ...current, retentionClass: value }))} required /><TextAreaInput label="Notes" value={evidence.notes} onChange={(value) => setEvidence((current) => ({ ...current, notes: value }))} /></div>}
      {action === 'capa' && <div className="space-y-4"><TextAreaInput label="Corrective action" value={capa.description} onChange={(value) => setCapa((current) => ({ ...current, description: value }))} required /><div className="grid gap-4 sm:grid-cols-2"><TextInput label="Owner ID" value={capa.ownerId} onChange={(value) => setCapa((current) => ({ ...current, ownerId: value }))} required /><label className="text-theme-sm font-medium text-gray-800">Due date<input type="date" className="mt-2 h-10 w-full rounded-md border border-gray-500 px-3" value={capa.dueDate} onChange={(event) => setCapa((current) => ({ ...current, dueDate: event.target.value }))} required /></label></div><Checkbox checked={capa.mandatory} onChange={(value) => setCapa((current) => ({ ...current, mandatory: value }))} label="Mandatory for closure" hint="The incident cannot close until this action is verified or cancelled." /></div>}
      {action === 'risk' && incident && <><Alert variant="info" title="Saving flags the assessment for review">A linked assessment - or every published assessment at {incident.siteCode} for this activity - goes on the S165 review queue, ahead of its normal cycle. A link to an assessment at another site is refused.</Alert><RiskContextFields siteCode={incident.siteCode} riskAssessmentId={riskContext.riskAssessmentId} activityType={riskContext.activityType} onRiskAssessmentChange={(value) => setRiskContext((current) => ({ ...current, riskAssessmentId: value }))} onActivityTypeChange={(value) => setRiskContext((current) => ({ ...current, activityType: value }))} /></>}
      {action === 'close' && <><Alert variant="warning" title="Closure is final">The service will refuse closure while any mandatory corrective action remains open.</Alert><TextAreaInput label="Closure notes" value={closureNotes} onChange={setClosureNotes} rows={5} required /></>}
    </FormDialog>
    <FormDialog open={transitioning !== null} title={transitioning?.next === 'IN_PROGRESS' ? 'Start corrective action' : transitioning?.next === 'VERIFY' ? 'Verify effectiveness' : 'Cancel corrective action'} description={transitioning?.item.description} submitLabel={transitioning?.next === 'IN_PROGRESS' ? 'Start work' : transitioning?.next === 'VERIFY' ? 'Verify action' : 'Cancel action'} submitting={submitting} destructive={transitioning?.next === 'CANCEL'} submitDisabled={transitioning?.next !== 'IN_PROGRESS' && !transitionNotes.trim()} onClose={() => { setTransitioning(null); setTransitionNotes(''); }} onSubmit={() => void transition()}>
      {transitioning?.next !== 'IN_PROGRESS' && <TextAreaInput label={transitioning?.next === 'VERIFY' ? 'Effectiveness verification notes' : 'Cancellation reason'} value={transitionNotes} onChange={setTransitionNotes} rows={4} required />}
      {transitioning?.next === 'IN_PROGRESS' && <Alert variant="info" title="Move this action into progress">The owner can now record work against the corrective action before an authorised reviewer verifies its effectiveness.</Alert>}
    </FormDialog>
  </div>;
};
export default IncidentDetailPage;
