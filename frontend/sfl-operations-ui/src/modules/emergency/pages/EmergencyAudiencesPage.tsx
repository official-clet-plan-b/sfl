import { useMemo, useState } from 'react';
import { emergencyRecordsApi } from 'modules/emergency/api/emergencyApi';
import type { AudienceGroup, RecipientZone } from 'modules/emergency/api/dto';
import { RECORD_LIFECYCLES, type RecordLifecycle } from 'modules/emergency/api/enums';
import { useSiteRecords } from 'modules/emergency/components/useSiteRecords';
import { CreateAudienceDialog, CreateZoneDialog } from 'modules/emergency/dialogs/recordDialogs';
import { humanise } from 'modules/fleet/api/enums';
import DataState from 'shared/components/DataState';
import Icon from 'shared/components/Icon';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { useClampPage, useServerPage } from 'shared/hooks/useServerPage';
import { emergencyPaths } from 'shared/layout/navigation';
import { canManageAudiences } from 'modules/emergency/api/workflow';
import { Button, Banner, Tabs, TabsList, TabsTrigger, type TableColumn } from '@rfdtech/components';
import RegisterTable from 'modules/emergency/components/RegisterTable';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import StatMetric from 'modules/emergency/components/StatMetric';
import StatusBadge from 'modules/emergency/components/StatusBadge';
import { TextField, EnumField } from 'modules/emergency/components/FormFields';
import { CellStack } from 'modules/emergency/components/RegisterTable';

/**
 * Who receives a broadcast: audience groups and the zones a broadcast can be narrowed to.
 *
 * Paired for the same reason templates and scenarios are - an activation chooses from both at once,
 * and the two answer one question between them. Neither holds a contact detail: an audience group
 * is a directory pointer and a count, a zone is a facilities-location pointer and a name.
 *
 * The recipient count is the load-bearing field on this screen. It is what the service fans out to
 * and the denominator every delivery and acknowledgement percentage is read against, and no
 * endpoint can correct one once it is created - so a group sized wrongly quietly distorts every
 * activation that ever uses it.
 *
 * Both tables are searched, filtered and paged by the service now. The search box used to be
 * captioned "filters the loaded records", which was true and useless: it narrowed the first two
 * hundred groups the site happened to return.
 *
 * The two figures above the tables still come from that two-hundred-record read, because the service
 * has no aggregate for either. Total reach is a sum and the zero-sized warning names every offending
 * group, and neither can be assembled from a page - so they say what they cover rather than implying
 * the whole site.
 */
const EmergencyAudiencesPage = () => {
  const { notifySuccess } = useNotifier();

  const [siteCode, setSiteCode] = useState(defaultSite);
  const [tab, setTab] = useState('audiences');
  const [search, setSearch] = useState('');
  const [lifecycle, setLifecycle] = useState<RecordLifecycle | ''>('');
  const [creatingAudience, setCreatingAudience] = useState(false);
  const [creatingZone, setCreatingZone] = useState(false);

  /** Kept for the reach total and the zero-sized warning, which no endpoint aggregates. */
  const records = useSiteRecords(siteCode);

  const trimmed = search.trim();
  const filterKey = `${siteCode}|${trimmed}|${lifecycle}`;
  const paging = useServerPage(filterKey);

  const audienceQuery = useApiQuery(
    (signal) =>
      emergencyRecordsApi.audienceGroups(
        {
          siteCode,
          search: trimmed || undefined,
          lifecycle: lifecycle || undefined,
          page: paging.page,
          size: paging.size,
        },
        signal,
      ),
    [siteCode, trimmed, lifecycle, paging.page, paging.size],
  );

  const zoneQuery = useApiQuery(
    (signal) =>
      emergencyRecordsApi.recipientZones(
        {
          siteCode,
          search: trimmed || undefined,
          lifecycle: lifecycle || undefined,
          page: paging.page,
          size: paging.size,
        },
        signal,
      ),
    [siteCode, trimmed, lifecycle, paging.page, paging.size],
  );

  const active = tab === 'audiences' ? audienceQuery : zoneQuery;
  useClampPage(paging.page, active.data?.totalPages, paging.setPage);

  const refreshAll = () => {
    audienceQuery.refetch();
    zoneQuery.refetch();
    records.refetch();
  };

  const totalReach = records.audiences.reduce(
    (total, audience) => total + audience.recipientCount,
    0,
  );
  const emptyGroups = records.audiences.filter((audience) => audience.recipientCount === 0);

  const audienceColumns = useMemo<TableColumn<AudienceGroup>[]>(
    () => [
      {
        id: 'group',
        header: 'Group',
        width: 280,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.groupCode} · ${row.name}`}
            secondary={row.directoryReference ?? 'No directory reference recorded'}
          />
        ),
      },
      {
        id: 'recipients',
        header: 'Recipients',
        width: 130,
        align: 'right',
        cell: ({ row }) =>
          row.recipientCount === 0 ? (
            <span className="font-medium text-error-800">0</span>
          ) : (
            formatNumber(row.recipientCount)
          ),
      },
      {
        id: 'share',
        header: 'Share of site',
        width: 130,
        align: 'right',
        cell: ({ row }) =>
          totalReach > 0 ? `${Math.round((100 * row.recipientCount) / totalReach)}%` : '-',
      },
      {
        id: 'lifecycle',
        header: 'Lifecycle',
        width: 120,
        cell: ({ row }) => <StatusBadge value={row.lifecycle} />,
      },
      {
        id: 'created',
        header: 'Created',
        width: 160,
        align: 'right',
        cell: ({ row }) => formatDateTime(row.metadata.createdAt),
      },
    ],
    [totalReach],
  );

  const zoneColumns = useMemo<TableColumn<RecipientZone>[]>(
    () => [
      {
        id: 'zone',
        header: 'Zone',
        width: 300,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.zoneCode} · ${row.name}`}
            secondary={row.locationReference ?? 'Not mapped to a facilities location'}
          />
        ),
      },
      {
        id: 'lifecycle',
        header: 'Lifecycle',
        width: 130,
        cell: ({ row }) => <StatusBadge value={row.lifecycle} />,
      },
      {
        id: 'createdBy',
        header: 'Created by',
        width: 180,
        cell: ({ row }) => row.metadata.createdBy,
      },
      {
        id: 'created',
        header: 'Created',
        width: 170,
        align: 'right',
        cell: ({ row }) => formatDateTime(row.metadata.createdAt),
      },
    ],
    [],
  );

  return (
    <div>
      <PageHeading
        title="Audiences and zones"
        subtitle="Who a broadcast reaches, and where it can be narrowed to."
        crumbs={[
          { label: 'Emergency', to: emergencyPaths.dashboard },
          { label: 'Audiences and zones' },
        ]}
        actions={
          <>
            {/* Both write to the audience register, so both sit behind the one grant. */}
            {canManageAudiences() && (
              <>
                <Button variant="primary" onClick={() => setCreatingAudience(true)}><Icon name="plus" size={14} aria-hidden="true" />
                  Create audience group
                </Button>
                <Button variant="outline" onClick={() => setCreatingZone(true)}><Icon name="plus" size={14} aria-hidden="true" />
                  Create zone
                </Button>
              </>
            )}
            <Button variant="outline" onClick={refreshAll}><Icon name="refresh" size={14} aria-hidden="true" />
              Refresh
            </Button>
          </>
        }
      />

      <div className="mb-5 grid gap-4 sm:grid-cols-3">
        <StatMetric
          label="Recipients across all groups"
          value={formatNumber(totalReach)}
          icon="users"
          caption="Summed here across up to 200 groups"
        />
        <StatMetric
          label="Audience groups"
          value={formatNumber(audienceQuery.data?.totalElements ?? 0)}
          icon="clipboard"
          tone={emptyGroups.length > 0 ? 'caution' : 'neutral'}
          caption={
            emptyGroups.length > 0
              ? `${emptyGroups.length} sized at zero recipients`
              : 'All sized above zero'
          }
        />
        <StatMetric
          label="Recipient zones"
          value={formatNumber(zoneQuery.data?.totalElements ?? 0)}
          icon="map-pin"
          caption="Places a broadcast can be narrowed to"
        />
      </div>

      {emptyGroups.length > 0 && (
        <Banner
          variant="warning"
          heading={`${emptyGroups.length} audience group${emptyGroups.length === 1 ? ' is' : 's are'} sized at zero`}
          className="mb-5"
  subtext={<>A group with no recipients sends to nobody and still reports a successful broadcast - the
          channel record shows a target of zero, which is not the same as a failure and reads
          exactly like a clean send.{' '}
          {emptyGroups.map((group) => group.name).join(', ')}. The count cannot be corrected through
          any endpoint; create a replacement group with the right size. Checked across up to 200
          groups at this site.</>} />
      )}

      <div className="mb-5">
        <Panel>
          <div className="grid gap-4 sm:grid-cols-2 lg:max-w-3xl lg:grid-cols-3">
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            <TextField
              label="Search"
              value={search}
              onChange={setSearch}
              placeholder="Code or name"
              helperText="Searched by the service across both registers."
            />
            <EnumField
              label="Lifecycle"
              value={lifecycle}
              options={RECORD_LIFECYCLES}
              onChange={(value) => setLifecycle(value)}
              allowEmpty
            />
          </div>
        </Panel>
      </div>

      <Panel>
        <div className="px-5 pt-4">
          <Tabs value={tab} onValueChange={setTab} variant="pill">
            <TabsList>
              <TabsTrigger value="audiences">Audience groups</TabsTrigger>
              <TabsTrigger value="zones">Recipient zones</TabsTrigger>
            </TabsList>
          </Tabs>
        </div>

        <DataState
          loading={active.initialising}
          error={active.error}
          onRetry={active.refetch}
          minHeight={300}
        >
          {tab === 'audiences' ? (
            <RegisterTable
              paramPrefix="emergency-audiences"
              rows={audienceQuery.data?.content ?? []}
              columns={audienceColumns}
              rowKey={(row) => row.id}
              loading={audienceQuery.loading}
              emptyTitle="No audience group matches this search."
              totalItems={audienceQuery.data?.totalElements ?? 0}
              size={audienceQuery.data?.size ?? paging.size}
              framed={false}
            />
          ) : (
            <RegisterTable
              paramPrefix="emergency-zones"
              rows={zoneQuery.data?.content ?? []}
              columns={zoneColumns}
              rowKey={(row) => row.id}
              loading={zoneQuery.loading}
              emptyTitle="No zone matches this search."
              totalItems={zoneQuery.data?.totalElements ?? 0}
              size={zoneQuery.data?.size ?? paging.size}
              framed={false}
            />
          )}
        </DataState>

        <div className="flex items-start gap-1.5 px-5 pt-2 pb-4 text-theme-xs text-gray-600">
          <Icon name="info" size={13} className="mt-0.5 shrink-0 text-teal-700" />
          <span>
            {tab === 'audiences'
              ? 'Contact detail stays in the directory and never reaches this dashboard. The service exposes creation and reads only, so a count cannot be amended after the fact.'
              : `Naming a zone on an activation records ${humanise('ACCESS_CONTROL').toLowerCase()} lockdown and CCTV preservation context against it. SFL never actuates certified life-safety hardware.`}
          </span>
        </div>
      </Panel>

      {creatingAudience && (
        <CreateAudienceDialog
          open
          defaultSiteCode={siteCode}
          onClose={() => setCreatingAudience(false)}
          onSaved={(audience) => {
            notifySuccess(
              `${audience.groupCode} created.`,
              `${formatNumber(audience.recipientCount)} recipients. The count cannot be changed later.`,
            );
            refreshAll();
          }}
        />
      )}

      {creatingZone && (
        <CreateZoneDialog
          open
          defaultSiteCode={siteCode}
          onClose={() => setCreatingZone(false)}
          onSaved={(zone) => {
            notifySuccess(`${zone.zoneCode} created.`);
            refreshAll();
          }}
        />
      )}
    </div>
  );
};

export default EmergencyAudiencesPage;
