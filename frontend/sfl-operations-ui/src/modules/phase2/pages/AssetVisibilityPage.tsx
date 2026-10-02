import { useState } from 'react';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  PageSection,
  SectionActions,
  SectionHeader,
  SectionTitle,
  Table,
  TableContent,
  type TableColumn,
} from '@rfdtech/components';
import { humanise } from 'modules/fleet/api/enums';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { formatLocation, joinWords } from '../api/assets';
import { assetVisibilityApi, type AssetReference } from '../api/phase2Api';
import AssetHistoryDialog from '../dialogs/AssetHistoryDialog';
import RegisterAssetDialog from '../dialogs/RegisterAssetDialog';
import UpdateAssetDialog from '../dialogs/UpdateAssetDialog';

/**
 * The tracked-asset register: what exists, which tag it carries, where it is and who has it.
 *
 * Anyone who can open the page can read an asset's history. Registering, tagging, moving and handing
 * over are the register's writes, held by fewer roles, so those buttons appear only for a role that
 * holds the permission - a read-only role is not offered controls that would answer 403.
 *
 * Reader hardware is not needed to use this: a person records the same location and custody changes a
 * reader would, and history shows which of the two made each one.
 */
export const AssetVisibilityPage = () => {
  const { notifySuccess } = useNotifier();
  const [siteCode, setSiteCode] = useState(defaultSite);
  const [registering, setRegistering] = useState(false);
  const [updatingId, setUpdatingId] = useState<string | null>(null);
  const [historyId, setHistoryId] = useState<string | null>(null);
  const assets = useApiQuery((signal) => assetVisibilityApi.search(siteCode, signal), [siteCode]);
  const canManage = permits('ASSET_REFERENCE_MANAGE');

  const list = assets.data ?? [];
  const updating = list.find((asset) => asset.id === updatingId);
  const viewing = list.find((asset) => asset.id === historyId);

  const columns: TableColumn<AssetReference>[] = [
    {
      id: 'asset',
      header: 'Asset',
      cell: ({ row }) => (
        <div>
          <span className="font-medium">{row.assetCode}</span>
          <div className="text-xs text-muted-foreground">{row.name}</div>
        </div>
      ),
    },
    { id: 'category', header: 'Category', cell: ({ row }) => humanise(row.category) },
    {
      id: 'tag',
      header: 'Tag ID',
      cell: ({ row }) =>
        row.externalReference ? row.externalReference : <Badge variant="outline">Not tagged</Badge>,
    },
    {
      id: 'location',
      header: 'Last known location',
      cell: ({ row }) => formatLocation(row.locationType, row.locationReference),
    },
    { id: 'custodian', header: 'Custodian', cell: ({ row }) => row.custodianReference ?? 'Unassigned' },
    {
      id: 'actions',
      header: '',
      align: 'right',
      cell: ({ row }) => (
        <div className="flex justify-end gap-2">
          <Button size="sm" variant="outline" onClick={() => setHistoryId(row.id)}>
            History
          </Button>
          {canManage && (
            <Button size="sm" variant="outline" onClick={() => setUpdatingId(row.id)}>
              Update
            </Button>
          )}
        </div>
      ),
    },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Asset tagging & inventory</SectionTitle>
          <SectionActions style={{ alignItems: 'flex-end' }}>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            {canManage && (
              <Button variant="primary" className="whitespace-nowrap" onClick={() => setRegistering(true)}>
                Register asset
              </Button>
            )}
          </SectionActions>
        </SectionHeader>
        <p className="max-w-3xl text-sm text-muted-foreground">
          Register an asset, give it a tag, and keep its location and custody on record. One tag identifies
          one asset. Until a reader is connected, people record the moves; a reader will record the same
          changes and be named in the history.
        </p>
      </PageSection>

      <PageSection>
        {assets.error ? (
          <EmptyState title="The asset register is unavailable" description={assets.error.message} />
        ) : (
          <Table paramPrefix="asset-visibility" variant="soft">
            <Card bordered>
              <TableContent
                columns={columns}
                data={list}
                loading={assets.initialising}
                rowKey={(row) => row.id}
                emptyContent={<EmptyState title="No tracked assets at this site" />}
              />
            </Card>
          </Table>
        )}
      </PageSection>

      {registering && (
        <RegisterAssetDialog
          siteCode={siteCode}
          onClose={() => setRegistering(false)}
          onSubmit={async (body) => {
            const created = await assetVisibilityApi.register(body);
            notifySuccess('Asset registered', `${created.assetCode} is on the register.`);
            setRegistering(false);
            assets.refetch();
            return created;
          }}
        />
      )}
      {updating && (
        <UpdateAssetDialog
          asset={updating}
          onClose={() => setUpdatingId(null)}
          onChanged={(applied) => {
            notifySuccess('Asset updated', `Saved the ${joinWords(applied)}.`);
            assets.refetch();
          }}
        />
      )}
      {viewing && <AssetHistoryDialog asset={viewing} onClose={() => setHistoryId(null)} />}
    </>
  );
};
