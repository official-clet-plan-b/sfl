import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { Plus } from 'lucide-react';
import { Button, Dropdown, PageSection, useTableState, type TableColumn } from '@rfdtech/components';
import ActionDialog from 'modules/emergency/components/ActionDialog';
import { DateTimeField, EnumField, TextField } from 'modules/emergency/components/FormFields';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import RegisterTable, { CellStack, DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from 'modules/emergency/components/RegisterTable';
import StatusBadge from 'modules/emergency/components/StatusBadge';
import { humanise } from 'modules/fleet/api/enums';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { visitorPaths } from 'shared/layout/navigation';
import { useNotifier } from 'shared/components/Notifier';
import { visitorApi } from '../api/visitorApi';
import type { VisitPurpose, VisitStatus, VisitorVisit } from '../api/dto';

const statuses: VisitStatus[] = ['PRE_REGISTERED', 'CONFIRMED', 'CHECKED_IN', 'CHECKED_OUT', 'REJECTED', 'CANCELLED', 'NO_SHOW'];
const purposes: VisitPurpose[] = ['MEETING', 'INTERVIEW', 'EVENT', 'DELIVERY', 'MAINTENANCE_CONTRACTOR', 'OTHER'];
const toIso = (value: string) => new Date(value).toISOString();

const VisitorVisitsPage = () => {
  const navigate = useNavigate();
  const notify = useNotifier();
  const [siteCode, setSiteCode] = useState(defaultSite);
  const { page, pageSize, setPage, filters } = useTableState({ paramPrefix: 'visits', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const requestedStatus = filters.status as VisitStatus | undefined;
  const status: VisitStatus | '' = requestedStatus && statuses.includes(requestedStatus) ? requestedStatus : '';
  const [statusField, setStatusField] = useState(status);
  const [open, setOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form, setForm] = useState({ visitorName: '', visitorOrganization: '', visitorContact: '', hostId: '', hostName: '', purpose: 'MEETING' as VisitPurpose, expectedArrival: '', expectedDeparture: '' });

  const query = useApiQuery(
    (signal) => visitorApi.search({ siteCode: siteCode || undefined, status: status || undefined, page: page - 1, size: pageSize, sort: 'expectedArrival,desc' }, signal),
    [siteCode, status, page, pageSize],
  );

  const columns = useMemo<TableColumn<VisitorVisit>[]>(() => [
    { id: 'visitor', header: 'Visitor', width: 240, cell: ({ row }) => <CellStack primary={row.visitorName} secondary={row.visitorOrganization ?? row.visitorContact ?? 'No organisation'} /> },
    { id: 'host', header: 'Host', width: 190, cell: ({ row }) => <CellStack primary={row.hostName ?? row.hostId} secondary={row.hostName ? row.hostId : undefined} /> },
    { id: 'purpose', header: 'Purpose', width: 150, cell: ({ row }) => <StatusBadge value={row.purpose} /> },
    { id: 'arrival', header: 'Expected arrival', width: 180, cell: ({ row }) => formatDateTime(row.expectedArrival) },
    { id: 'site', header: 'Site', width: 100, cell: ({ row }) => row.siteCode },
    { id: 'status', header: 'Status', width: 150, align: 'right', cell: ({ row }) => <StatusBadge value={row.status} /> },
  ], []);

  const create = async () => {
    if (!siteCode || !form.visitorName.trim() || !form.hostId.trim() || !form.expectedArrival) return;
    setSubmitting(true);
    try {
      const visit = await visitorApi.preRegister({
        siteCode,
        visitorName: form.visitorName.trim(),
        visitorOrganization: form.visitorOrganization.trim() || undefined,
        visitorContact: form.visitorContact.trim() || undefined,
        hostId: form.hostId.trim(),
        hostName: form.hostName.trim() || undefined,
        purpose: form.purpose,
        expectedArrival: toIso(form.expectedArrival),
        expectedDeparture: form.expectedDeparture ? toIso(form.expectedDeparture) : undefined,
      });
      notify.notifySuccess('Visit pre-registered', `${visit.visitorName} · ${visit.siteCode}`);
      setOpen(false);
      query.refetch();
      navigate(visitorPaths.visitDetail(visit.id));
    } catch (error) {
      notify.notifyError(error);
    } finally {
      setSubmitting(false);
    }
  };

  return <>
    <PageHeading
      title="Visitor management"
      subtitle="Pre-register visits, manage approvals and follow every visitor through arrival and departure."
      crumbs={[{ label: 'Safety & security' }, { label: 'Visitors' }]}
      actions={<>
        <SiteSelect label="Site" value={siteCode} onChange={(value) => { setSiteCode(value); setPage(1); }} allowEmpty className="w-44" />
        {permits('VISITOR_VISIT_CREATE') && <Button variant="primary" onClick={() => setOpen(true)}><Plus size={14} strokeWidth={1.5} aria-hidden /> Pre-register visit</Button>}
      </>}
    />
    <PageSection>
      <Panel title="Visit register" subtitle="Server-paginated and scoped to the selected site and lifecycle state.">
        <RegisterTable
          paramPrefix="visits"
          framed={false}
          columns={columns}
          rows={query.data?.content ?? []}
          rowKey={(row) => row.id}
          loading={query.initialising}
          onRowClick={(row) => navigate(visitorPaths.visitDetail(row.id))}
          emptyTitle="No visits match these filters"
          emptyDescription="Pre-register a visit, or widen the site and status."
          totalItems={query.data?.totalElements ?? 0}
          size={pageSize}
          filterCount={1}
          filters={
            <Dropdown
              name="status"
              aria-label="Filter by status"
              value={statusField || null}
              onValueChange={(next) => setStatusField((next ?? '') as VisitStatus | '')}
              options={statuses.map((value) => ({ value, label: humanise(value) }))}
              placeholder="All statuses"
              clearable
            />
          }
        />
      </Panel>
    </PageSection>

    <ActionDialog open={open} title="Pre-register a visit" description="Reserve the visit window and start the approval workflow." submitLabel="Pre-register visit" submitting={submitting} submitDisabled={!siteCode || !form.visitorName.trim() || !form.hostId.trim() || !form.expectedArrival} onClose={() => setOpen(false)} onSubmit={create}>
      <div className="grid gap-4 sm:grid-cols-2">
        <SiteSelect value={siteCode} onChange={setSiteCode} required />
        <EnumField label="Purpose" value={form.purpose} options={purposes} required onChange={(value) => value && setForm((current) => ({ ...current, purpose: value }))} />
        <TextField label="Visitor name" value={form.visitorName} onChange={(value) => setForm((current) => ({ ...current, visitorName: value }))} required />
        <TextField label="Organisation" value={form.visitorOrganization} onChange={(value) => setForm((current) => ({ ...current, visitorOrganization: value }))} />
        <TextField label="Contact" value={form.visitorContact} onChange={(value) => setForm((current) => ({ ...current, visitorContact: value }))} />
        <TextField label="Host ID" value={form.hostId} onChange={(value) => setForm((current) => ({ ...current, hostId: value }))} required />
        <TextField label="Host name" value={form.hostName} onChange={(value) => setForm((current) => ({ ...current, hostName: value }))} />
        <DateTimeField label="Expected arrival" value={form.expectedArrival} required onChange={(value) => setForm((current) => ({ ...current, expectedArrival: value }))} />
        <DateTimeField label="Expected departure" value={form.expectedDeparture} onChange={(value) => setForm((current) => ({ ...current, expectedDeparture: value }))} />
      </div>
    </ActionDialog>
  </>;
};

export default VisitorVisitsPage;
