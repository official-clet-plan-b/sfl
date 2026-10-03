import IfimpOperationsPage, { type IfimpView } from './IfimpOperationsPage';
import type { CreateAction } from '../components/IfimpCreateDialog';
import type { SflPermission } from 'shared/layout/permissions';

const createAction = (system: string, label: string, types: string[], statuses: string[]): CreateAction => ({
  label,
  path: `/api/v1/facilities/registers/${system}/records`,
  fields: [
    { key: 'recordType', label: 'Record type', type: 'select', required: true, options: types },
    { key: 'title', label: 'Title', required: true },
    { key: 'status', label: 'Status', type: 'select', required: true, options: statuses, initial: statuses[0] },
    { key: 'ownerReference', label: 'Owner or responsible party' },
    { key: 'dueAt', label: 'Due date and time', type: 'datetime' },
    { key: 'severity', label: 'Severity', type: 'select', options: ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] },
    { key: 'details', label: 'Details, evidence or reference', type: 'textarea' },
  ],
  toBody: (values, siteCode) => ({ ...values, siteCode, dueAt: values.dueAt || null }),
});

const statusAction = (statuses: string[]): CreateAction => ({
  label: 'Update lifecycle',
  method: 'PATCH',
  path: '/api/v1/facilities/registers/records/{id}/status',
  fields: [{ key: 'status', label: 'New status', type: 'select', required: true, options: statuses }],
});

/**
 * One tab of an estate register.
 *
 * `types` is both what the create form offers and what the tab lists. They were separate, and a tab
 * that listed only the first type hid every record of the others. Whatever the form can create, the
 * tab must be able to show.
 */
const view = (label: string, description: string, path: string, system: string, types: string[], statuses: string[], writePermission?: SflPermission): IfimpView => ({
  label,
  description,
  path: `/facilities/${path}`,
  endpoint: `/api/v1/facilities/registers/${system}/records`,
  query: { recordType: types.join(',') },
  writePermission,
  columns: [
    { key: 'title', label: 'Title' },
    { key: 'recordType', label: 'Type' },
    { key: 'status', label: 'Status' },
    { key: 'ownerReference', label: 'Owner' },
    { key: 'dueAt', label: 'Due' },
    { key: 'severity', label: 'Severity' },
  ],
  create: createAction(system, `Create ${label.toLowerCase().replace(/s$/, '')}`, types, statuses),
  actions: [statusAction(statuses)],
});

const RegisterPage = ({ system, title, subtitle, views }: { system: string; title: string; subtitle: string; views: IfimpView[] }) => (
  <IfimpOperationsPage system={system} title={title} subtitle={subtitle} views={views} />
);

const leaseStatuses = ['DRAFT', 'ACTIVE', 'DUE', 'PENDING_APPROVAL', 'RENEWED', 'TERMINATED'];

export const LeasePage = () => <RegisterPage system="S177" title="Lease & tenancy" subtitle="Govern agreement obligations, renewals, amendments and evidence." views={[
  view('Agreements', 'Lease and tenancy portfolio by site.', 'agreements', 'S177', ['LEASE', 'TENANCY'], leaseStatuses),
  view('Obligations', 'Notice, insurance, rent-review and compliance dates.', 'obligations', 'S177', ['OBLIGATION', 'RENEWAL', 'RENT_REVIEW'], leaseStatuses),
  view('Amendments', 'Controlled amendment and termination approvals.', 'amendments', 'S177', ['AMENDMENT', 'TERMINATION'], leaseStatuses),
]} />;
