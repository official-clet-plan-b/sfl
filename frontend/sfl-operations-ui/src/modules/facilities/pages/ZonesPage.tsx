import { useState } from 'react';
import { Plus } from 'lucide-react';
import {
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
import type { Zone, ZoneMember } from '../api/dto';
import {
  addZoneMember,
  changeZoneLifecycle,
  createZone,
  listBuildings,
  listDeviceReferences,
  listSpaces,
  listZoneMembers,
  listZones,
  removeZoneMember,
} from '../api/facilitiesApi';
import { createZoneControl, manageZoneMembersControl, retireZoneControl } from '../api/workflow';
import CellStack from '../components/CellStack';
import ControlButton from '../components/ControlButton';
import RowActions, { RemoveRowAction, RetireRowAction } from '../components/RowActions';
import StatusBadge from '../components/StatusBadge';
import { formatDateTime, orDash } from '../components/facilitiesFormat';
import { LifecycleDialog } from '../dialogs/common';
import { AddZoneMemberDialog, CreateZoneDialog } from '../dialogs/zoneDialogs';

/**
 * Zones and what they cover.
 *
 * A zone is how the safety and emergency systems address the estate - life-safety events arrive per
 * zone, emergency broadcasts target recipient zones - so "what is actually in this zone" is the
 * question the screen exists to answer. Selecting a zone loads its membership rather than navigating
 * away, because the comparison between zones is the common task.
 *
 * Membership is edited here for the same reason it is read here: an empty zone resolves to nobody,
 * and the moment somebody notices that is the moment they should be able to fix it.
 */
const ZonesPage = () => {
  const notify = useNotifier();
  useBreadcrumbs([{ label: 'Facilities', href: facilitiesPaths.dashboard }, { label: 'Zones' }]);
  const [siteCode, setSiteCode] = useState<string>(defaultSite);
  const [selected, setSelected] = useState<Zone | null>(null);
  const [adding, setAdding] = useState(false);
  const [addingMember, setAddingMember] = useState(false);
  const [retiring, setRetiring] = useState<Zone | null>(null);

  const zones = useApiQuery((signal) => listZones(siteCode || undefined, signal), [siteCode]);
  const members = useApiQuery(
    (signal) => (selected ? listZoneMembers(selected.id, signal) : Promise.resolve([])),
    [selected?.id],
  );

  /**
   * What each member actually is, rather than the identifier it is stored as.
   *
   * `ZoneMember` carries only `memberType` and `memberId`, so the register was a column of raw
   * UUIDs - which tells an operator deciding whether an evacuation zone covers the right rooms
   * precisely nothing. The names come from the same three registers the add dialog picks out of, so
   * the row reads as what it is and keeps the identifier underneath for support.
   *
   * Fetched per selected zone rather than per member: three requests for a whole zone, not one per
   * row. Where a name cannot be resolved - a record archived or outside this site - the identifier
   * stands on its own rather than being hidden behind a guess.
   */
  const memberNames = useApiQuery(
    async (signal) => {
      if (!selected) {
        return new Map<string, string>();
      }
      const [spaces, buildings, devices] = await Promise.all([
        listSpaces(selected.siteCode, signal),
        listBuildings(selected.siteCode, signal),
        listDeviceReferences({ siteCode: selected.siteCode }, signal),
      ]);
      return new Map<string, string>([
        ...spaces.map((space): [string, string] => [space.id, `${space.roomCode} · ${space.name}`]),
        ...buildings.map((building): [string, string] => [
          building.id,
          `${building.buildingCode} · ${building.name}`,
        ]),
        ...devices.map((device): [string, string] => [
          device.id,
          `${device.deviceCode} · ${device.name}`,
        ]),
      ]);
    },
    [selected?.id, selected?.siteCode],
  );

  const zoneColumns: TableColumn<Zone>[] = [
    {
      id: 'zoneCode',
      header: 'Code',
      width: 150,
      cell: ({ row }) => <span className="font-medium text-foreground">{row.zoneCode}</span>,
    },
    { id: 'name', header: 'Zone', accessorKey: 'name' },
    {
      id: 'purpose',
      header: 'Purpose',
      cell: ({ row }) => <span className="text-muted-foreground">{orDash(row.purpose)}</span>,
    },
    {
      id: 'parent',
      header: 'Nested',
      width: 110,
      align: 'right',
      cell: ({ row }) =>
        row.parentZoneId ? <StatusBadge value="NESTED" label="Nested" tone="neutral" /> : null,
    },
    {
      id: 'actions',
      header: '',
      width: 110,
      align: 'right',
      cell: ({ row: zone }) => (
        <RowActions>
          <RetireRowAction
            state={retireZoneControl(zone)}
            onClick={() => setRetiring(zone)}
            label={`Retire ${zone.zoneCode}`}
          />
        </RowActions>
      ),
    },
  ];

  const memberColumns: TableColumn<ZoneMember>[] = [
    {
      id: 'memberType',
      header: 'Type',
      width: 130,
      cell: ({ row }) => <StatusBadge value={row.memberType} tone="neutral" />,
    },
    {
      id: 'memberId',
      header: 'Record',
      cell: ({ row: member }) => {
        const name = memberNames.data?.get(member.memberId);
        return name ? (
          <CellStack primary={name} secondary={member.memberId} />
        ) : (
          <span className="font-mono text-xs">{member.memberId}</span>
        );
      },
    },
    {
      id: 'addedBy',
      header: 'Added by',
      width: 160,
      accessorKey: 'addedBy',
    },
    {
      id: 'addedAt',
      header: 'Added',
      width: 190,
      align: 'right',
      cell: ({ row }) => <span className="text-muted-foreground">{formatDateTime(row.addedAt)}</span>,
    },
    {
      id: 'actions',
      header: '',
      width: 110,
      align: 'right',
      cell: ({ row: member }) => (
        <RowActions>
          <RemoveRowAction
            state={selected ? manageZoneMembersControl(selected) : { kind: 'hidden' }}
            onClick={() => void removeMember(member)}
            label="Remove from this zone"
          />
        </RowActions>
      ),
    },
  ];

  /**
   * Removing a member.
   *
   * No confirmation dialog, and that is deliberate rather than an omission: the act is a single
   * reversible click, the row states exactly what is being removed, and adding it back is the button
   * directly above. A confirmation here would be ceremony. The irreversible acts in this module -
   * archiving - do get one.
   */
  const removeMember = async (member: ZoneMember) => {
    if (!selected) {
      return;
    }
    try {
      await removeZoneMember(selected.id, member.memberType, member.memberId);
      notify.notifySuccess(`Removed from ${selected.zoneCode}.`);
      members.refetch();
    } catch (cause) {
      notify.notifyError(cause);
    }
  };

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Zones</SectionTitle>
          <SectionDescription>
            How safety, life-safety and emergency systems address this estate
          </SectionDescription>
          <SectionActions>
            <SiteSelect
              value={siteCode}
              onChange={(value) => {
                setSiteCode(value);
                setSelected(null);
              }}
              allowEmpty
              emptyLabel="All sites"
            />
            <ControlButton state={createZoneControl()} variant="primary" onClick={() => setAdding(true)}>
              <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
              Add a zone
            </ControlButton>
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <SectionHeader>
          <SectionTitle>Zones</SectionTitle>
          <SectionDescription>Select one to see what it covers</SectionDescription>
        </SectionHeader>
        <DataState loading={false} error={zones.error} onRetry={zones.refetch}>
          <Table paramPrefix="zones" variant="soft">
            <Card bordered>
              <TableContent
                variant="soft"
                columns={zoneColumns}
                data={zones.data ?? []}
                rowKey={(zone) => zone.id}
                loading={zones.loading}
                onRowClick={setSelected}
                aria-label="Zones"
                emptyContent={
                  <EmptyState
                    title="No zones configured"
                    description="A zone is what an evacuation broadcast or a life-safety alarm resolves against."
                  />
                }
              />
            </Card>
          </Table>
        </DataState>
      </PageSection>

      {selected && (
        <PageSection>
          <SectionHeader>
            <SectionTitle>{`What ${selected.zoneCode} covers`}</SectionTitle>
            <SectionDescription>
              {`${selected.name}${selected.purpose ? ` · ${selected.purpose}` : ''}`}
            </SectionDescription>
            <SectionActions>
              <ControlButton
                state={manageZoneMembersControl(selected)}
                variant="outline"
                size="sm"
                onClick={() => setAddingMember(true)}
              >
                <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
                Add a record
              </ControlButton>
            </SectionActions>
          </SectionHeader>
          <DataState loading={false} error={members.error} onRetry={members.refetch} minHeight={120}>
            <Table paramPrefix="zone-members" variant="soft">
              <Card bordered>
                <TableContent
                  variant="soft"
                  columns={memberColumns}
                  data={members.data ?? []}
                  rowKey={(member) => member.id}
                  loading={members.loading}
                  aria-label="Zone members"
                  emptyContent={
                    <EmptyState
                      title="This zone is empty"
                      description="A zone with no members resolves to nothing - an alarm against it would reach nobody."
                    />
                  }
                />
              </Card>
            </Table>
          </DataState>
        </PageSection>
      )}

      {adding && (
        <CreateZoneDialog
          siteCode={siteCode || defaultSite}
          onClose={() => setAdding(false)}
          onSubmit={async (request) => {
            const created = await createZone(request);
            setAdding(false);
            notify.notifySuccess(`${created.zoneCode} added to ${created.siteCode}.`);
            zones.refetch();
          }}
        />
      )}

      {addingMember && selected && (
        <AddZoneMemberDialog
          zone={selected}
          onClose={() => setAddingMember(false)}
          onSubmit={async (request) => {
            await addZoneMember(selected.id, request);
            setAddingMember(false);
            notify.notifySuccess(`Added to ${selected.zoneCode}.`);
            members.refetch();
          }}
        />
      )}

      {retiring && (
        <LifecycleDialog
          noun="zone"
          label={retiring.zoneCode}
          current={retiring.lifecycleStatus}
          expectedVersion={retiring.metadata.version}
          onClose={() => setRetiring(null)}
          onSubmit={async (status, expectedVersion) => {
            const saved = await changeZoneLifecycle(retiring.id, { status, expectedVersion });
            setRetiring(null);
            if (selected?.id === saved.id) {
              setSelected(saved);
            }
            notify.notifySuccess(`${saved.zoneCode} is now ${status.toLowerCase()}.`);
            zones.refetch();
          }}
        />
      )}
    </>
  );
};

export default ZonesPage;
