import { useState } from 'react';
import { Plus } from 'lucide-react';
import {
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
import { humaniseCode } from 'modules/facilities/components/facilitiesFormat';
import StatusBadge from 'modules/facilities/components/StatusBadge';
import { bookableResourcesApi } from '../api/bookingApi';
import type { BookableResource } from '../api/dto';
import { RESOURCE_CATEGORIES } from '../api/enums';
import type { ResourceCategory } from '../api/enums';
import { canManageResources } from '../api/workflow';
import CellStack from '../components/CellStack';
import ControlButton from '../components/ControlButton';
import RegisterResourceDialog from '../dialogs/RegisterResourceDialog';

/**
 * The bookable-resource register - SRS-SFL-S159-01.
 *
 * Projectors, furniture sets and everything else booked alongside a room. Separate from the S152
 * asset register and deliberately so: an asset is fixed plant whose condition feeds a space's
 * readiness, a resource is portable and its scarcity is the point.
 *
 * **Quantity is the column that matters.** One row holds forty chairs, and availability is arithmetic
 * against what is committed for a window. A quantity of exactly one is different in kind rather than
 * degree - it makes the resource exclusive, and exclusivity is enforced by the database's own
 * exclusion constraint rather than by that arithmetic. The register says which, because the number
 * alone does not.
 */
const BookableResourcesPage = () => {
  const notify = useNotifier();
  useBreadcrumbs([
    { label: 'Facilities', href: facilitiesPaths.dashboard },
    { label: 'Bookable resources' },
  ]);
  const { filters } = useTableState({ paramPrefix: 'resources' });
  // The search endpoint takes one category, so the filter is a single choice rather than a facet.
  const category = filters.category ?? '';
  const [categoryValue, setCategoryValue] = useState(category);
  const [siteCode, setSiteCode] = useState<string>(defaultSite);
  const [registering, setRegistering] = useState(false);

  const resources = useApiQuery(
    (signal) =>
      bookableResourcesApi.search(
        {
          siteCode: siteCode || undefined,
          category: (category as ResourceCategory) || undefined,
        },
        signal,
      ),
    [siteCode, category],
  );

  const rows = resources.data ?? [];

  const columns: TableColumn<BookableResource>[] = [
    {
      id: 'name',
      header: 'Resource',
      width: 260,
      cell: ({ row: resource }) => (
        <CellStack primary={resource.name} secondary={resource.resourceCode} />
      ),
    },
    {
      id: 'category',
      header: 'Category',
      width: 160,
      cell: ({ row: resource }) => humaniseCode(resource.category),
    },
    {
      id: 'quantity',
      header: 'Quantity',
      align: 'right',
      width: 110,
      accessorKey: 'quantity',
    },
    {
      id: 'exclusive',
      header: 'Contention',
      width: 170,
      cell: ({ row: resource }) =>
        resource.exclusive ? (
          <span title="Enforced by the database's exclusion constraint, not by arithmetic.">
            <StatusBadge value="EXCLUSIVE" tone="accent" />
          </span>
        ) : (
          <span className="text-xs text-muted-foreground">Shared pool</span>
        ),
    },
    {
      id: 'requiresSetup',
      header: 'Turnaround',
      width: 150,
      cell: ({ row: resource }) =>
        resource.requiresSetup ? (
          <StatusBadge value="SETUP" label="Raises a task" tone="caution" />
        ) : (
          <span className="text-muted-foreground">-</span>
        ),
    },
    { id: 'site', header: 'Site', width: 110, accessorKey: 'siteCode' },
    {
      id: 'lifecycle',
      header: 'Lifecycle',
      width: 130,
      align: 'right',
      cell: ({ row: resource }) => <StatusBadge value={resource.lifecycleStatus} />,
    },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Bookable resources</SectionTitle>
          <SectionDescription>
            Projectors, furniture and everything else booked alongside a room
          </SectionDescription>
          <SectionActions>
            <SiteSelect value={siteCode} onChange={setSiteCode} allowEmpty emptyLabel="All sites" />
            <ControlButton
              state={canManageResources()}
              variant="primary"
              onClick={() => setRegistering(true)}
            >
              <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
              Register a resource
            </ControlButton>
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <DataState loading={false} error={resources.error} onRetry={resources.refetch}>
          <Table paramPrefix="resources" variant="soft">
            <Card bordered>
              <TableHeader>
                <TableFilter variant="spread">
                  <Dropdown
                    name="category"
                    aria-label="Category"
                    placeholder="All categories"
                    value={categoryValue || null}
                    onValueChange={(next) => setCategoryValue(next ?? '')}
                    clearable
                    options={RESOURCE_CATEGORIES.map((value) => ({
                      value,
                      label: humaniseCode(value),
                    }))}
                  />
                </TableFilter>
              </TableHeader>
              <TableContent
                variant="soft"
                columns={columns}
                data={rows}
                rowKey={(resource) => resource.id}
                loading={resources.loading}
                aria-label="Bookable resources"
                emptyContent={
                  <EmptyState
                    title="No bookable resources"
                    description="Nothing is registered for this site and category yet."
                  />
                }
              />
            </Card>
          </Table>
        </DataState>
      </PageSection>

      {registering && (
        <RegisterResourceDialog
          onClose={() => setRegistering(false)}
          onSubmit={async (body) => {
            const created = await bookableResourcesApi.register(body);
            setRegistering(false);
            notify.notifySuccess(`${created.resourceCode} registered.`);
            resources.refetch();
          }}
        />
      )}
    </>
  );
};

export default BookableResourcesPage;
