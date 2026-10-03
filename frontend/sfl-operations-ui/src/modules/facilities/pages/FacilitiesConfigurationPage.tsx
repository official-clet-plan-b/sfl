import { useState } from 'react';
import {
  Banner,
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
import type { ConfigurationValue } from '../api/dto';
import { describeKey } from '../api/configurationCatalogue';
import { listConfiguration, putConfiguration } from '../api/facilitiesApi';
import { canManageConfiguration } from '../api/workflow';
import CellStack from '../components/CellStack';
import RowActions, { EditRowAction } from '../components/RowActions';
import StatusBadge from '../components/StatusBadge';
import { formatDateTime } from '../components/facilitiesFormat';
import { EditConfigurationDialog } from '../dialogs/configurationDialogs';

/**
 * The runtime configuration the facilities rules are read from.
 *
 * <h2>What was wrong with this screen</h2>
 *
 * <p>It listed `facilities.readiness.staleness-threshold` in a monospace column and offered no way to
 * change it. That is a setting's *name*, not a description of one - an operator deciding whether
 * seven days is right for their centre could not get there from the string, and could not act on the
 * answer if they had. The label and the effect now lead; the key is kept as secondary text because
 * support reads it in a log line and has to find the row.
 *
 * <h2>Which value is in force</h2>
 *
 * <p>A site override shadows the default of the same key, and both are shown. "The staleness window
 * is seven days" and "it is seven days everywhere except Accra" are different facts and only one of
 * them is usually true, so the scope column distinguishes the override, the default it shadows, and
 * a default nothing has overridden.
 *
 * <p>Every threshold is read at evaluation time (NFR 23.8), so a value changed at 09:00 applies to
 * the 09:01 evaluation without a redeploy.
 */
const FacilitiesConfigurationPage = () => {
  const notify = useNotifier();
  useBreadcrumbs([{ label: 'Facilities', href: facilitiesPaths.dashboard }, { label: 'Configuration' }]);
  const [siteCode, setSiteCode] = useState<string>(defaultSite);
  const [editing, setEditing] = useState<ConfigurationValue | null>(null);

  const { data, loading, error, refetch } = useApiQuery(
    (signal) => listConfiguration(siteCode || undefined, signal),
    [siteCode],
  );

  const mayManage = canManageConfiguration();

  /** A site override shadows the default of the same key; both are shown, the override first. */
  const overriddenKeys = new Set(
    (data ?? []).filter((value) => value.siteCode !== null).map((value) => value.key),
  );

  const columns: TableColumn<ConfigurationValue>[] = [
    {
      id: 'setting',
      header: 'Setting',
      cell: ({ row: value }) => {
        const entry = describeKey(value.key, value.description);
        return (
          <div className="min-w-0">
            <CellStack primary={entry.label} secondary={value.key} />
            <p className="mt-1 max-w-xl text-xs text-muted-foreground">{entry.effect}</p>
          </div>
        );
      },
    },
    {
      id: 'value',
      header: 'In force',
      width: 150,
      cell: ({ row }) => <span className="font-medium text-foreground">{row.value}</span>,
    },
    {
      id: 'scope',
      header: 'Scope',
      width: 170,
      cell: ({ row: value }) =>
        value.siteCode ? (
          <StatusBadge value="OVERRIDE" label={`${value.siteCode} override`} tone="accent" />
        ) : overriddenKeys.has(value.key) ? (
          <StatusBadge value="SHADOWED" label="Default (overridden)" tone="neutral" />
        ) : (
          <StatusBadge value="DEFAULT" label="Platform default" tone="neutral" />
        ),
    },
    {
      id: 'version',
      header: 'Version',
      align: 'right',
      width: 90,
      cell: ({ row }) => `v${row.version}`,
    },
    {
      id: 'updated',
      header: 'Last set',
      width: 200,
      align: 'right',
      cell: ({ row }) => (
        <span className="text-muted-foreground">
          {formatDateTime(row.updatedAt)} by {row.updatedBy}
        </span>
      ),
    },
    {
      id: 'actions',
      header: '',
      width: 80,
      align: 'right',
      cell: ({ row: value }) => (
        <RowActions>
          <EditRowAction
            state={mayManage ? { kind: 'allowed' } : { kind: 'hidden' }}
            onClick={() => setEditing(value)}
            label={`Change ${describeKey(value.key, value.description).label}`}
          />
        </RowActions>
      ),
    },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Configuration</SectionTitle>
          <SectionDescription>
            The thresholds the facilities rules are evaluated against, and which value is in force
          </SectionDescription>
          <SectionActions className="items-end [&_button]:whitespace-nowrap">
            <SiteSelect
              value={siteCode}
              onChange={setSiteCode}
              allowEmpty
              emptyLabel="Platform defaults"
            />
          </SectionActions>
        </SectionHeader>
      </PageSection>

      {data && !mayManage && (
        <PageSection>
          <Banner
            variant="info"
            heading="These values are read-only for you."
            subtext="Changing one needs the facilities configuration management permission."
          />
        </PageSection>
      )}

      <PageSection>
        <DataState loading={false} error={error} onRetry={refetch}>
          <Table paramPrefix="configuration" variant="soft">
            <Card bordered>
              <TableContent
                variant="soft"
                columns={columns}
                data={data ?? []}
                rowKey={(value) => `${value.key}:${value.siteCode ?? 'default'}`}
                loading={loading}
                aria-label="Runtime configuration"
                emptyContent={
                  <EmptyState
                    title="No configuration values"
                    description="The service seeds its defaults on first migration; an empty list means something is wrong."
                  />
                }
              />
            </Card>
          </Table>
        </DataState>
      </PageSection>

      {editing && (
        <EditConfigurationDialog
          value={editing}
          siteCode={siteCode || defaultSite}
          onClose={() => setEditing(null)}
          onSubmit={async (key, request) => {
            const saved = await putConfiguration(key, request);
            setEditing(null);
            notify.notifySuccess(
              saved.siteCode
                ? `${key} is now ${saved.value} for ${saved.siteCode}, at v${saved.version}.`
                : `${key} is now ${saved.value} everywhere, at v${saved.version}.`,
            );
            refetch();
          }}
        />
      )}
    </>
  );
};

export default FacilitiesConfigurationPage;
