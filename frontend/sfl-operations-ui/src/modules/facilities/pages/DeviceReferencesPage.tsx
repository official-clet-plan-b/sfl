import { useState } from 'react';
import { Plus } from 'lucide-react';
import {
  Banner,
  Card,
  Dropdown,
  EmptyState,
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
  Table,
  TableContent,
  TableFilter,
  TableHeader,
  useBreadcrumbs,
  useTableState,
} from '@rfdtech/components';
import type { TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import type { DeviceReference } from '../api/dto';
import { deviceReferenceTypes } from '../api/enums';
import type { DeviceReferenceType } from '../api/enums';
import {
  changeDeviceReferenceLifecycle,
  listDeviceReferences,
  registerDeviceReference,
  updateDeviceReference,
} from '../api/facilitiesApi';
import { editDeviceControl, registerDeviceControl, retireDeviceControl } from '../api/workflow';
import ControlButton from '../components/ControlButton';
import RowActions, { EditRowAction, RetireRowAction } from '../components/RowActions';
import StatusBadge from '../components/StatusBadge';
import { humaniseCode, orDash, relativeTime } from '../components/facilitiesFormat';
import { LifecycleDialog } from '../dialogs/common';
import { EditDeviceDialog, RegisterDeviceDialog } from '../dialogs/deviceDialogs';

/**
 * Device references - the identity and location of devices vendor systems operate.
 *
 * The facilities register does not run cameras, readers or panels; it owns where each one is, so that
 * a CCTV event, an access denial or a fire alarm can be placed in a space and a zone without every
 * consuming system inventing its own device registry.
 *
 * The reported time is the *vendor's* observation, not our receipt, which is why "last reported" can
 * be old on a device the feed is talking to constantly.
 */
const DeviceReferencesPage = () => {
  const notify = useNotifier();
  useBreadcrumbs([{ label: 'Facilities', href: facilitiesPaths.dashboard }, { label: 'Devices' }]);
  const { filters } = useTableState({ paramPrefix: 'devices' });
  // The search endpoint takes one value per axis, so the type is a single choice.
  const type = filters.type ?? '';
  const [typeValue, setTypeValue] = useState(type);
  const [siteCode, setSiteCode] = useState<string>(defaultSite);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<DeviceReference | null>(null);
  const [retiring, setRetiring] = useState<DeviceReference | null>(null);

  const { data, loading, error, refetch } = useApiQuery(
    (signal) =>
      listDeviceReferences(
        {
          siteCode: siteCode || undefined,
          type: (type as DeviceReferenceType) || undefined,
        },
        signal,
      ),
    [siteCode, type],
  );

  const columns: TableColumn<DeviceReference>[] = [
    {
      id: 'deviceCode',
      header: 'Code',
      width: 150,
      cell: ({ row }) => <span className="font-medium text-foreground">{row.deviceCode}</span>,
    },
    { id: 'name', header: 'Device', accessorKey: 'name' },
    {
      id: 'type',
      header: 'Type',
      cell: ({ row }) => humaniseCode(row.type),
    },
    {
      id: 'vendor',
      header: 'Vendor',
      width: 150,
      cell: ({ row }) => <span className="text-muted-foreground">{orDash(row.vendor)}</span>,
    },
    {
      id: 'status',
      header: 'Reported status',
      width: 150,
      cell: ({ row: device }) => (
        <StatusBadge
          value={device.status}
          tone={
            device.status === 'ONLINE'
              ? 'ready'
              : device.status === 'OFFLINE'
                ? 'blocked'
                : device.status === 'DEGRADED'
                  ? 'caution'
                  : 'neutral'
          }
        />
      ),
    },
    {
      id: 'reported',
      header: 'Last reported',
      width: 160,
      align: 'right',
      cell: ({ row }) => (
        <span className="text-muted-foreground">{relativeTime(row.statusReportedAt)}</span>
      ),
    },
    {
      id: 'actions',
      header: '',
      width: 150,
      align: 'right',
      cell: ({ row: device }) => (
        <RowActions>
          <EditRowAction
            state={editDeviceControl(device)}
            onClick={() => setEditing(device)}
            label={`Edit ${device.deviceCode}`}
          />
          <RetireRowAction
            state={retireDeviceControl(device)}
            onClick={() => setRetiring(device)}
            label={`Retire ${device.deviceCode}`}
          />
        </RowActions>
      ),
    },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Device references</SectionTitle>
          <SectionDescription>Where each vendor-operated device sits on this estate</SectionDescription>
          <SectionActions>
            <SiteSelect value={siteCode} onChange={setSiteCode} allowEmpty emptyLabel="All sites" />
            <ControlButton state={registerDeviceControl()} variant="primary" onClick={() => setAdding(true)}>
              <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
              Register a device
            </ControlButton>
          </SectionActions>
        </SectionHeader>
      </PageSection>

      {data?.some((device) => device.status === 'UNKNOWN') && (
        <PageSection>
          <Banner
            variant="info"
            heading="Devices showing UNKNOWN have never been reported on by their vendor system."
            subtext="The facilities register holds the reference; the vendor feed supplies the status."
          />
        </PageSection>
      )}

      <PageSection>
        <DataState loading={false} error={error} onRetry={refetch}>
          <Table paramPrefix="devices" variant="soft">
            <Card bordered>
              <TableHeader>
                <TableFilter variant="spread">
                  <Dropdown
                    name="type"
                    aria-label="Device type"
                    placeholder="All device types"
                    clearable
                    value={typeValue || null}
                    onValueChange={(next) => setTypeValue(next ?? '')}
                    options={deviceReferenceTypes.map((value) => ({
                      value,
                      label: humaniseCode(value),
                    }))}
                  />
                </TableFilter>
              </TableHeader>
              <TableContent
                variant="soft"
                columns={columns}
                data={data ?? []}
                rowKey={(device) => device.id}
                loading={loading}
                aria-label="Device references"
                emptyContent={
                  <EmptyState
                    title="No device references"
                    description="Register one so CCTV, access and life-safety events can be placed in a space."
                  />
                }
              />
            </Card>
          </Table>
        </DataState>
      </PageSection>

      {adding && (
        <RegisterDeviceDialog
          siteCode={siteCode || defaultSite}
          onClose={() => setAdding(false)}
          onSubmit={async (request) => {
            const created = await registerDeviceReference(request);
            setAdding(false);
            notify.notifySuccess(`${created.deviceCode} registered at ${created.siteCode}.`);
            refetch();
          }}
        />
      )}

      {editing && (
        <EditDeviceDialog
          device={editing}
          onClose={() => setEditing(null)}
          onSubmit={async (request) => {
            const saved = await updateDeviceReference(editing.id, request);
            setEditing(null);
            notify.notifySuccess(`${saved.deviceCode} updated.`);
            refetch();
          }}
        />
      )}

      {retiring && (
        <LifecycleDialog
          noun="device reference"
          label={retiring.deviceCode}
          current={retiring.lifecycleStatus}
          expectedVersion={retiring.metadata.version}
          onClose={() => setRetiring(null)}
          onSubmit={async (status, expectedVersion) => {
            const saved = await changeDeviceReferenceLifecycle(retiring.id, {
              status,
              expectedVersion,
            });
            setRetiring(null);
            notify.notifySuccess(`${saved.deviceCode} is now ${status.toLowerCase()}.`);
            refetch();
          }}
        />
      )}
    </>
  );
};

export default DeviceReferencesPage;
