import { useState } from 'react';
import { useParams } from 'react-router';
import DataState from 'shared/components/DataState';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { useNotifier } from 'shared/components/Notifier';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { visitorPaths } from 'shared/layout/navigation';
import { visitorApi } from '../api/visitorApi';
import { visitorWorkflow } from '../api/workflow';
import { Banner, Button, PageSection } from '@rfdtech/components';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import StatusBadge from 'modules/emergency/components/StatusBadge';
import ActionDialog from 'modules/emergency/components/ActionDialog';
import { TextField, TextAreaField } from 'modules/emergency/components/FormFields';

type Action = 'approve' | 'reject' | 'badge' | 'cancel' | null;

const VisitorVisitDetailPage = () => {
  const { visitId = '' } = useParams();
  const notify = useNotifier();
  const query = useApiQuery((signal) => visitorApi.get(visitId, signal), [visitId]);
  const [action, setAction] = useState<Action>(null);
  const [submitting, setSubmitting] = useState(false);
  const [reason, setReason] = useState('');
  const [override, setOverride] = useState('');
  const [badge, setBadge] = useState('');
  const [zones, setZones] = useState('');
  const visit = query.data;

  const run = async (operation: () => Promise<unknown>, success: string) => {
    setSubmitting(true);
    try {
      await operation();
      notify.notifySuccess(success);
      setAction(null);
      setReason(''); setOverride(''); setBadge(''); setZones('');
      query.refetch();
    } catch (error) { notify.notifyError(error); } finally { setSubmitting(false); }
  };

  const submitDialog = () => {
    if (!visit || !action) return;
    const version = visit.metadata.version;
    if (action === 'approve') void run(() => visitorApi.decide(visit.id, { approve: true, watchlistOverrideReason: override || undefined, expectedVersion: version }), 'Visit approved');
    if (action === 'reject') void run(() => visitorApi.decide(visit.id, { approve: false, reason, expectedVersion: version }), 'Visit rejected');
    if (action === 'badge') void run(() => visitorApi.assignBadge(visit.id, { badgeNumber: badge, accessZones: zones.split(',').map((value) => value.trim()).filter(Boolean), expectedVersion: version }), 'Badge assigned');
    if (action === 'cancel') void run(() => visitorApi.cancel(visit.id, { reason, expectedVersion: version }), 'Visit cancelled');
  };

  return <>
    <PageHeading title={visit?.visitorName ?? 'Visitor visit'} subtitle="Approval, access and attendance details for this visit." crumbs={[{ label: 'Visitors', to: visitorPaths.visits }, { label: visit?.visitorName ?? 'Visit' }]} actions={visit ? <>
      {visitorWorkflow.canDecide(visit) && permits('VISITOR_VISIT_APPROVE') && <><Button variant="primary" onClick={() => setAction('approve')}>Approve</Button><Button variant="outline" onClick={() => setAction('reject')}>Reject</Button></>}
      {visitorWorkflow.canAssignBadge(visit) && permits('VISITOR_BADGE_ASSIGN') && <Button variant="primary" onClick={() => setAction('badge')}>{visit.badgeNumber ? 'Change badge' : 'Assign badge'}</Button>}
      {visitorWorkflow.canCheckIn(visit) && permits('VISITOR_CHECKIN') && <Button variant="primary" loading={submitting} onClick={() => void run(() => visitorApi.checkIn(visit.id, visit.metadata.version), 'Visitor checked in')}>Check in</Button>}
      {visitorWorkflow.canCheckOut(visit) && permits('VISITOR_CHECKOUT') && <Button variant="primary" loading={submitting} onClick={() => void run(() => visitorApi.checkOut(visit.id, visit.metadata.version), 'Visitor checked out')}>Check out</Button>}
      {visitorWorkflow.canCancel(visit) && permits('VISITOR_CANCEL') && <Button variant="primary-destructive" onClick={() => setAction('cancel')}>Cancel visit</Button>}
    </> : undefined} />
    <DataState loading={query.initialising} error={query.error} onRetry={query.refetch}>
      {visit && <>
        {visit.watchlistFlagged && <PageSection><Banner variant="warning" heading="Watchlist or restriction match"
  subtext={<>Approval requires a recorded override reason. Verify the match through the approved security process before proceeding.</>} /></PageSection>}
        <PageSection><Panel title="Visit standing" actions={<StatusBadge value={visit.status} size="md" />}>
          <KeyValueGrid columns={3} items={[
            { label: 'Site', value: visit.siteCode }, { label: 'Purpose', value: <StatusBadge value={visit.purpose} /> }, { label: 'Approval required', value: visit.approvalRequired ? 'Yes' : 'No' },
            { label: 'Expected arrival', value: formatDateTime(visit.expectedArrival) }, { label: 'Expected departure', value: formatDateTime(visit.expectedDeparture) }, { label: 'Badge', value: visit.badgeNumber },
            { label: 'Checked in', value: formatDateTime(visit.checkedInAt) }, { label: 'Checked out', value: formatDateTime(visit.checkedOutAt) }, { label: 'Access zones', value: visit.accessZones.join(', ') || '-' },
          ]} />
        </Panel></PageSection>
        <PageSection><div className="grid gap-5 lg:grid-cols-2">
          <Panel title="Visitor and host"><KeyValueGrid columns={2} items={[{ label: 'Visitor', value: visit.visitorName }, { label: 'Organisation', value: visit.visitorOrganization }, { label: 'Contact', value: visit.visitorContact }, { label: 'Host', value: visit.hostName ?? visit.hostId }, { label: 'Host ID', value: visit.hostId }, { label: 'Closure reason', value: visit.closureReason }]} /></Panel>
          <Panel title="Record history"><KeyValueGrid columns={2} items={[{ label: 'Created by', value: visit.metadata.createdBy }, { label: 'Created', value: formatDateTime(visit.metadata.createdAt) }, { label: 'Last changed by', value: visit.metadata.lastModifiedBy }, { label: 'Last changed', value: formatDateTime(visit.metadata.lastModifiedAt) }, { label: 'Version', value: visit.metadata.version }, { label: 'Correlation ID', value: visit.metadata.correlationId }]} /></Panel>
        </div></PageSection>
      </>}
    </DataState>

    <ActionDialog open={action !== null} title={action === 'approve' ? 'Approve visit' : action === 'reject' ? 'Reject visit' : action === 'badge' ? 'Assign badge and access' : 'Cancel visit'} submitLabel={action === 'approve' ? 'Approve' : action === 'badge' ? 'Assign badge' : action === 'reject' ? 'Reject' : 'Cancel visit'} submitting={submitting} destructive={action === 'reject' || action === 'cancel'} submitDisabled={(action === 'reject' || action === 'cancel') ? !reason.trim() : action === 'badge' ? !badge.trim() : Boolean(visit?.watchlistFlagged && !override.trim())} onClose={() => setAction(null)} onSubmit={submitDialog}>
      {action === 'approve' && visit?.watchlistFlagged && <TextAreaField label="Watchlist override reason" value={override} onChange={setOverride} required />}
      {(action === 'reject' || action === 'cancel') && <TextAreaField label="Reason" value={reason} onChange={setReason} required />}
      {action === 'badge' && <div className="space-y-4"><TextField label="Badge number" value={badge} onChange={setBadge} required /><TextField label="Access zones" value={zones} onChange={setZones} helperText="Comma-separated zone codes. Keep access to the minimum needed for this visit." /></div>}
    </ActionDialog>
  </>;
};

export default VisitorVisitDetailPage;
