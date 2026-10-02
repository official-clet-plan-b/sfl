import SiteSelect from 'shared/components/SiteSelect';
import { Notice } from '@rfdtech/components';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, maxLength, required } from 'shared/validation/validators';
import type { AddZoneMemberRequest, CreateZoneRequest, Zone } from '../api/dto';
import { zoneMemberTypes } from '../api/enums';
import type { ZoneMemberType } from '../api/enums';
import { listBuildings, listDeviceReferences, listSpaces, listZones } from '../api/facilitiesApi';
import { humaniseCode } from '../components/facilitiesFormat';
import { FormDialog, SelectInput, TextInput } from 'modules/facilities/dialogs/dialogKit';

/**
 * Creating a zone, and saying what it covers.
 *
 * <h2>A zone is an address, not a folder</h2>
 *
 * <p>Life-safety events arrive per zone and emergency broadcasts target one, so "what is actually in
 * this zone" decides who a fire alarm reaches and who an evacuation message wakes. An empty zone
 * resolves to nobody; a zone with the wrong member evacuates the wrong building. That is why the
 * member dialog offers records rather than an identifier field, and why it offers only records from
 * the zone's own site.
 *
 * <h2>The same-site rule, enforced by what is offered</h2>
 *
 * <p>`FacilitiesMasterDataService` refuses a member whose site differs from the zone's. Rather than
 * let an operator find that out by being refused, the picker is scoped to the zone's site, so the
 * refusal cannot be reached from this screen at all. The service still enforces it - this only stops
 * the dashboard offering something it knows will fail.
 */

interface CreateZoneDialogProps {
  siteCode: string;
  onClose: () => void;
  onSubmit: (request: CreateZoneRequest) => Promise<void>;
}

export const CreateZoneDialog = ({ siteCode, onClose, onSubmit }: CreateZoneDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      siteCode,
      zoneCode: '',
      name: '',
      purpose: '',
      parentZoneId: '',
    },
    schema: {
      siteCode: required('Site'),
      zoneCode: compose(required('Zone code'), maxLength('Zone code', 60)),
      name: compose(required('Name'), maxLength('Name', 160)),
      purpose: maxLength('Purpose', 300),
    },
    onSubmit: (values) =>
      onSubmit({
        siteCode: values.siteCode,
        zoneCode: values.zoneCode.trim(),
        name: values.name.trim(),
        purpose: values.purpose.trim() || null,
        parentZoneId: values.parentZoneId || null,
      }),
  });

  const parents = useApiQuery(
    (signal) => (form.values.siteCode ? listZones(form.values.siteCode, signal) : Promise.resolve([])),
    [form.values.siteCode],
  );

  return (
    <FormDialog
      open
      title="Add a zone"
      description="How safety, life-safety and emergency systems address part of this estate"
      submitLabel="Add the zone"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={() => void form.submit()}
    >
      <div className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <SiteSelect
            value={form.values.siteCode}
            onChange={(value) => form.setValue('siteCode', value)}
            required
            {...form.fieldProps('siteCode')}
          />
          <TextInput
            label="Zone code"
            value={form.values.zoneCode}
            onChange={(value) => form.setValue('zoneCode', value)}
            required
            maxLength={60}
            placeholder="ZONE-NORTH"
            {...form.fieldProps('zoneCode', 'Unique within the site.')}
          />
        </div>

        <TextInput
          label="Name"
          value={form.values.name}
          onChange={(value) => form.setValue('name', value)}
          required
          maxLength={160}
          placeholder="North wing"
          {...form.fieldProps('name')}
        />

        <TextInput
          label="Purpose"
          value={form.values.purpose}
          onChange={(value) => form.setValue('purpose', value)}
          maxLength={300}
          placeholder="Evacuation zone for the north stair core"
          {...form.fieldProps('purpose', 'What this zone is used to address.')}
        />

        <SelectInput
          label="Nested inside"
          value={form.values.parentZoneId}
          onChange={(value) => form.setValue('parentZoneId', value)}
          allowEmpty
          emptyLabel="Not nested"
          disabled={!form.values.siteCode || parents.loading}
          options={(parents.data ?? []).map((zone) => ({
            value: zone.id,
            label: `${zone.zoneCode} · ${zone.name}`,
          }))}
          helperText="A larger zone this one sits inside, if any."
        />

      </div>
    </FormDialog>
  );
};

interface AddZoneMemberDialogProps {
  zone: Zone;
  onClose: () => void;
  onSubmit: (request: AddZoneMemberRequest) => Promise<void>;
}

/**
 * Adding a record to a zone.
 *
 * The member type decides which register is offered underneath, and the register is scoped to the
 * zone's own site. `FLOOR` is deliberately absent from the picker rather than from the type list:
 * the service accepts a floor as a member, and there is no endpoint that lists floors for a site
 * without first naming a building. That gap is recorded rather than worked around with a
 * building-then-floor cascade nobody asked for; the type is offered and the picker says why it
 * cannot help.
 */
export const AddZoneMemberDialog = ({ zone, onClose, onSubmit }: AddZoneMemberDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      memberType: 'ROOM' as ZoneMemberType,
      memberId: '',
    },
    schema: { memberId: required('Record') },
    onSubmit: (values) =>
      onSubmit({ memberType: values.memberType, memberId: values.memberId }),
  });

  const spaces = useApiQuery(
    (signal) =>
      form.values.memberType === 'ROOM' ? listSpaces(zone.siteCode, signal) : Promise.resolve([]),
    [zone.siteCode, form.values.memberType],
  );
  const buildings = useApiQuery(
    (signal) =>
      form.values.memberType === 'BUILDING'
        ? listBuildings(zone.siteCode, signal)
        : Promise.resolve([]),
    [zone.siteCode, form.values.memberType],
  );
  const devices = useApiQuery(
    (signal) =>
      form.values.memberType === 'DEVICE'
        ? listDeviceReferences({ siteCode: zone.siteCode }, signal)
        : Promise.resolve([]),
    [zone.siteCode, form.values.memberType],
  );

  const options =
    form.values.memberType === 'ROOM'
      ? (spaces.data ?? []).map((space) => ({
          value: space.id,
          label: `${space.roomCode} · ${space.name}`,
        }))
      : form.values.memberType === 'BUILDING'
        ? (buildings.data ?? []).map((building) => ({
            value: building.id,
            label: `${building.buildingCode} · ${building.name}`,
          }))
        : form.values.memberType === 'DEVICE'
          ? (devices.data ?? []).map((device) => ({
              value: device.id,
              label: `${device.deviceCode} · ${device.name}`,
            }))
          : [];

  const floorsUnsupported = form.values.memberType === 'FLOOR';

  return (
    <FormDialog
      open
      title={`Add to ${zone.zoneCode}`}
      description={`Records from ${zone.siteCode}, which is the only site this zone may cover.`}
      submitLabel="Add it"
      submitting={form.submitting}
      submitDisabled={floorsUnsupported}
      formError={form.formError}
      onClose={onClose}
      onSubmit={() => void form.submit()}
    >
      <div className="space-y-4">
        <SelectInput
          label="What kind of record"
          value={form.values.memberType}
          onChange={(value) => {
            form.setValue('memberType', value as ZoneMemberType);
            form.setValue('memberId', '');
          }}
          required
          options={zoneMemberTypes.map((value) => ({ value, label: humaniseCode(value) }))}
        />

        {floorsUnsupported ? (
          <Notice variant="warning" title="A floor cannot be chosen here yet">
            <p className="text-sm">
              The service accepts a floor as a zone member, but there is no endpoint that lists the
              floors of a site without first naming a building - so this screen has nothing to offer
              you. Add the building, or the individual rooms, instead. This is recorded in the gap
              report rather than worked around.
            </p>
          </Notice>
        ) : (
          <SelectInput
            label="Which record"
            value={form.values.memberId}
            onChange={(value) => form.setValue('memberId', value)}
            required
            disabled={options.length === 0}
            options={options}
            {...form.fieldProps(
              'memberId',
              options.length === 0
                ? `No ${humaniseCode(form.values.memberType).toLowerCase()} records are registered at ${zone.siteCode}.`
                : undefined,
            )}
          />
        )}

      </div>
    </FormDialog>
  );
};
