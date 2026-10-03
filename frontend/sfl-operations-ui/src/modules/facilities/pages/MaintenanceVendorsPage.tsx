import { useState } from 'react';
import { Plus } from 'lucide-react';
import {
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
  useBreadcrumbs,
} from '@rfdtech/components';
import type { TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import type { MaintenanceVendor } from '../api/dto';
import { listVendors, registerVendor } from '../api/facilitiesApi';
import { canManageVendors } from '../api/workflow';
import StatusBadge from '../components/StatusBadge';
import RegisterVendorDialog from '../dialogs/RegisterVendorDialog';
import { formatDate, orDash } from '../components/facilitiesFormat';

/**
 * The vendor register.
 *
 * ## What this is not
 *
 * Not the procurement master. It holds enough to assign work, know the contracted response time and
 * see whether the contract has run out, and it carries `externalVendorId` so procurement's record of
 * the same company can be reconciled with it later. Anything more would be a second source of truth
 * for supplier data that nobody has agreed to maintain.
 *
 * ## Why expired vendors stay on the list
 *
 * `assignable` and `unassignableReason` come from the service, which refuses to assign work to a
 * vendor whose contract has lapsed. Hiding them would leave a supervisor wondering where a
 * contractor they use every week has gone; showing them with the reason answers it here, and a
 * contract renewed this morning is assignable again without anybody clearing a cache.
 */
const MaintenanceVendorsPage = () => {
  const notify = useNotifier();
  useBreadcrumbs([{ label: 'Facilities', href: facilitiesPaths.dashboard }, { label: 'Vendors' }]);
  const [siteCode, setSiteCode] = useState<string>(defaultSite);
  const [registering, setRegistering] = useState(false);

  const vendors = useApiQuery(
    (signal) => listVendors(siteCode || undefined, signal),
    [siteCode],
  );

  const columns: TableColumn<MaintenanceVendor>[] = [
    {
      id: 'vendorCode',
      header: 'Code',
      width: 140,
      cell: ({ row }) => <span className="font-medium text-foreground">{row.vendorCode}</span>,
    },
    { id: 'name', header: 'Vendor', accessorKey: 'name' },
    {
      id: 'specialisation',
      header: 'Specialisation',
      cell: ({ row }) => orDash(row.specialisation),
    },
    {
      id: 'responseHours',
      header: 'Response',
      width: 130,
      cell: ({ row }) =>
        row.responseHours ? (
          <span>{row.responseHours}h contracted</span>
        ) : (
          <span className="text-xs text-muted-foreground">Not contracted</span>
        ),
    },
    {
      id: 'contractExpiresOn',
      header: 'Contract',
      width: 150,
      cell: ({ row }) =>
        row.contractExpiresOn ? (
          formatDate(row.contractExpiresOn)
        ) : (
          <span className="text-xs text-muted-foreground">No end date</span>
        ),
    },
    {
      id: 'assignable',
      header: 'Availability',
      width: 190,
      cell: ({ row: vendor }) =>
        vendor.assignable ? (
          <StatusBadge value="ASSIGNABLE" label="Can take work" tone="ready" />
        ) : (
          <div className="flex flex-col items-start gap-0.5">
            <StatusBadge value="UNAVAILABLE" label="Unavailable" tone="blocked" />
            <span className="text-xs text-muted-foreground">
              {orDash(vendor.unassignableReason)}
            </span>
          </div>
        ),
    },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Vendors</SectionTitle>
          <SectionDescription>
            Contractors, their contracts and their contracted response times
          </SectionDescription>
          <SectionActions className="items-end [&_button]:whitespace-nowrap">
            <SiteSelect value={siteCode} onChange={setSiteCode} />
            {canManageVendors() && (
              <Button variant="primary" onClick={() => setRegistering(true)}>
                <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
                Register a vendor
              </Button>
            )}
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <DataState loading={false} error={vendors.error} onRetry={vendors.refetch}>
          <Table paramPrefix="vendors" variant="soft">
            <Card bordered>
              <TableContent
                variant="soft"
                columns={columns}
                data={vendors.data ?? []}
                rowKey={(vendor) => vendor.id}
                loading={vendors.loading}
                aria-label="Vendors"
                emptyContent={
                  <EmptyState
                    title="No vendors registered"
                    description="Register a contractor to assign work to them. A contracted response time shortens the SLA on anything they take."
                  />
                }
              />
            </Card>
          </Table>
        </DataState>
      </PageSection>

      {registering && (
        <RegisterVendorDialog
          siteCode={siteCode}
          onClose={() => setRegistering(false)}
          onSubmit={async (request) => {
            const created = await registerVendor(request);
            setRegistering(false);
            notify.notifySuccess(`${created.name} registered.`);
            vendors.refetch();
          }}
        />
      )}
    </>
  );
};

export default MaintenanceVendorsPage;
