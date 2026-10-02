import { useMemo } from 'react';
import { CourierItem, DispatchManifest } from 'modules/dispatch/api/dto';
import { courierItemsApi, manifestsApi } from 'modules/dispatch/api/dispatchApi';
import {
  DriverSelect,
  TripSelect,
  VehicleSelect,
} from 'modules/fleet/components/FleetReferenceSelect';
import SiteSelect from 'shared/components/SiteSelect';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import {
  Callout,
  FormModal,
  NumberField,
  SelectField,
  TextAreaField,
  TextField,
} from 'modules/dispatch/components/formKit';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, integerAtLeast, maxLength, required } from 'shared/validation/validators';

const twoColumn = 'grid gap-4 sm:grid-cols-2';

interface CreateManifestDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: (manifest: DispatchManifest) => void;
  defaultSiteCode: string;
}

/**
 * Create a manifest - `POST /manifests`.
 *
 * Created as a draft, which is the only state in which items can be added; sealing freezes the
 * contents. The trip, vehicle and driver are optional here and assignable later, because a manifest
 * is usually assembled before the movement carrying it is known.
 */
export const CreateManifestDialog = ({
  open,
  onClose,
  onSaved,
  defaultSiteCode,
}: CreateManifestDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      siteCode: defaultSiteCode,
      manifestNumber: '',
      route: '',
      assignedHandler: '',
      destinationCentre: '',
      examinationContext: '',
      tripId: '',
      vehicleId: '',
      driverId: '',
    },
    schema: {
      siteCode: required('Site code'),
      manifestNumber: maxLength('Manifest number', 60),
      route: compose(required('Route'), maxLength('Route', 200)),
      assignedHandler: compose(required('Handler'), maxLength('Handler', 160)),
      destinationCentre: maxLength('Destination centre', 200),
      examinationContext: maxLength('Examination context', 200),
    },
    onSubmit: async (values) => {
      const saved = await manifestsApi.create({
        siteCode: values.siteCode.trim().toUpperCase(),
        manifestNumber: values.manifestNumber.trim() || null,
        route: values.route.trim(),
        assignedHandler: values.assignedHandler.trim(),
        destinationCentre: values.destinationCentre.trim() || null,
        examinationContext: values.examinationContext.trim() || null,
        tripId: values.tripId || null,
        vehicleId: values.vehicleId || null,
        driverId: values.driverId || null,
      });
      onSaved(saved);
      onClose();
    },
  });

  return (
    <FormModal
      open={open}
      title="Create a manifest"
      description="Created as a draft. Items can only be added before it is sealed."
      submitLabel="Create manifest"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={form.submit}
    >
      <div className={twoColumn}>
        <SiteSelect
          required
          value={form.values.siteCode}
          onChange={(value) =>
            form.setValues({ siteCode: value, tripId: '', vehicleId: '', driverId: '' })
          }
          {...form.fieldProps('siteCode')}
        />
        <TextField
          label="Manifest number"
          value={form.values.manifestNumber}
          onChange={(value) => form.setValue('manifestNumber', value)}
          {...form.fieldProps('manifestNumber', 'Leave blank and the service allocates one.')}
        />
        <TextField
          label="Route"
          required
          value={form.values.route}
          onChange={(value) => form.setValue('route', value)}
          {...form.fieldProps('route')}
        />
        <TextField
          label="Handler"
          required
          value={form.values.assignedHandler}
          onChange={(value) => form.setValue('assignedHandler', value)}
          {...form.fieldProps('assignedHandler', 'Accountable for the consignment.')}
        />
        <TextField
          label="Destination centre"
          value={form.values.destinationCentre}
          onChange={(value) => form.setValue('destinationCentre', value)}
          {...form.fieldProps('destinationCentre')}
        />
        <TextField
          label="Examination context"
          value={form.values.examinationContext}
          onChange={(value) => form.setValue('examinationContext', value)}
          {...form.fieldProps('examinationContext', 'The session or paper this serves, if any.')}
        />
      </div>

      <Callout tone="info" title="Movement assignment is optional now">
        A manifest is usually assembled before the trip carrying it is chosen. Trip, vehicle and
        driver can be assigned later, up to the point the manifest is dispatched.
      </Callout>

      <div className={twoColumn}>
        <TripSelect
          siteCode={form.values.siteCode}
          value={form.values.tripId}
          onChange={(value) => form.setValue('tripId', value)}
          {...form.fieldProps('tripId')}
        />
        <VehicleSelect
          siteCode={form.values.siteCode}
          value={form.values.vehicleId}
          onChange={(value) => form.setValue('vehicleId', value)}
          allowEmpty
          emptyLabel="No vehicle"
          {...form.fieldProps('vehicleId')}
        />
        <DriverSelect
          siteCode={form.values.siteCode}
          value={form.values.driverId}
          onChange={(value) => form.setValue('driverId', value)}
          allowEmpty
          emptyLabel="No driver"
          {...form.fieldProps('driverId')}
        />
      </div>
    </FormModal>
  );
};

interface AddItemDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
  manifest: DispatchManifest;
  /** Items already on the manifest, so the picker cannot offer a duplicate. */
  existingItemIds: string[];
}

/**
 * Add a courier item to a draft manifest - `POST /manifests/{id}/items`.
 *
 * The picker offers items at the manifest's own site that are not already on it and are still in a
 * state that can be consigned. An item already dispatched under another manifest would be refused,
 * so it is not offered.
 */
export const AddManifestItemDialog = ({
  open,
  onClose,
  onSaved,
  manifest,
  existingItemIds,
}: AddItemDialogProps) => {
  const site = manifest.siteCode.value;

  const items = useApiQuery(
    // The service filters by status now, so the picker asks for exactly the items that can be
    // added rather than fetching a window and sieving it. `size` is generous because this is a
    // select, not a register, and an operator scrolling a dropdown is already the wrong shape.
    (signal) =>
      courierItemsApi.search(
        { siteCode: site, direction: 'OUTBOUND', status: 'RECEIVED', size: 200 },
        signal,
      ),
    [site],
  );

  const options = useMemo(
    () =>
      (items.data?.content ?? [])
        // Only the already-on-this-manifest test is left here: it is about this dialog's own state,
        // not about the register, so the service has no way to answer it.
        .filter((item: CourierItem) => !existingItemIds.includes(item.id))
        .map((item) => ({
          value: item.id,
          label: `${item.itemNumber} · ${item.destination} (${item.sensitivity.toLowerCase()})`,
        })),
    [items.data, existingItemIds],
  );

  const form = useFleetForm({
    initialValues: { courierItemId: '', expectedSealId: '', expectedQuantity: '1' },
    schema: {
      courierItemId: required('Item'),
      expectedSealId: maxLength('Expected seal', 120),
      expectedQuantity: compose(
        required('Expected quantity'),
        integerAtLeast('Expected quantity', 0),
      ),
    },
    onSubmit: async (values) => {
      await manifestsApi.addItem(manifest.id, {
        courierItemId: values.courierItemId,
        expectedSealId: values.expectedSealId.trim() || null,
        expectedQuantity: Number(values.expectedQuantity),
      });
      onSaved();
      onClose();
    },
  });

  return (
    <FormModal
      open={open}
      title="Add an item to the manifest"
      description={`${manifest.manifestNumber} · ${manifest.route}`}
      submitLabel="Add item"
      submitting={form.submitting}
      submitDisabled={options.length === 0}
      formError={form.formError}
      onClose={onClose}
      onSubmit={form.submit}
    >
      {!items.loading && options.length === 0 && (
        <Callout tone="warning" title="No item is available to add">
          Every outbound item at {site} is either already on this manifest or past the point where it
          can be consigned. Register the item first, or check whether it is on another manifest.
        </Callout>
      )}

      <SelectField
        label="Courier item"
        required
        value={form.values.courierItemId}
        onChange={(value) => form.setValue('courierItemId', value)}
        options={options}
        disabled={items.loading || options.length === 0}
        {...form.fieldProps(
          'courierItemId',
          items.loading ? 'Loading items…' : 'Outbound items at this site that can still be consigned.',
        )}
      />

      <div className={twoColumn}>
        <TextField
          label="Expected seal"
          value={form.values.expectedSealId}
          onChange={(value) => form.setValue('expectedSealId', value)}
          {...form.fieldProps('expectedSealId', 'The seal this line should carry, if sealed.')}
        />
        <NumberField
          label="Expected quantity"
          required
          min={0}
          value={form.values.expectedQuantity}
          onChange={(value) => form.setValue('expectedQuantity', value)}
          {...form.fieldProps('expectedQuantity', 'Counted against the receipt at the destination.')}
        />
      </div>
    </FormModal>
  );
};

interface SealDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
  manifest: DispatchManifest;
  itemCount: number;
}

/**
 * Seal the manifest - `POST /manifests/{id}/seal`.
 *
 * One way and draft-only: sealing freezes the contents, and there is no unseal. The seal identifiers
 * are what every later custody handover and the destination receipt are checked against, so a
 * mistyped one surfaces as a broken-seal exception rather than as a correction.
 */
export const SealManifestDialog = ({
  open,
  onClose,
  onSaved,
  manifest,
  itemCount,
}: SealDialogProps) => {
  const form = useFleetForm({
    initialValues: { sealIds: '' },
    schema: { sealIds: compose(required('Seal identifiers'), maxLength('Seal identifiers', 2000)) },
    onSubmit: async (values) => {
      await manifestsApi.seal(manifest.id, {
        sealIds: values.sealIds
          .split(/[,\n]/)
          .map((seal) => seal.trim())
          .filter(Boolean),
      });
      onSaved();
      onClose();
    },
  });

  const parsed = form.values.sealIds
    .split(/[,\n]/)
    .map((seal) => seal.trim())
    .filter(Boolean);

  return (
    <FormModal
      open={open}
      title="Seal the manifest"
      description={`${manifest.manifestNumber} · ${itemCount} item${itemCount === 1 ? '' : 's'}`}
      submitLabel="Seal manifest"
      submitting={form.submitting}
      submitDisabled={itemCount === 0}
      formError={form.formError}
      onClose={onClose}
      onSubmit={form.submit}
    >
      {itemCount === 0 && (
        <Callout tone="danger" title="There is nothing to seal">
          Add at least one item before sealing. A sealed manifest cannot be reopened to add one.
        </Callout>
      )}

      <Callout tone="warning" title="Sealing cannot be undone">
        The contents freeze at this point - no item can be added or removed afterwards. Every custody
        handover and the destination receipt are checked against these identifiers, so a seal typed
        wrongly here surfaces later as a broken-seal exception.
      </Callout>

      <TextAreaField
        label="Seal identifiers"
        required
        rows={4}
        value={form.values.sealIds}
        onChange={(value) => form.setValue('sealIds', value)}
        {...form.fieldProps(
          'sealIds',
          parsed.length > 0
            ? `${parsed.length} seal${parsed.length === 1 ? '' : 's'}: ${parsed.join(', ')}`
            : 'One per line, or comma separated.',
        )}
      />
    </FormModal>
  );
};

interface AssignTripDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
  manifest: DispatchManifest;
}

/** Assign the movement carrying the consignment - `POST /manifests/{id}/assign-trip`. */
export const AssignTripDialog = ({ open, onClose, onSaved, manifest }: AssignTripDialogProps) => {
  const site = manifest.siteCode.value;
  const form = useFleetForm({
    initialValues: {
      tripId: manifest.tripId ?? '',
      vehicleId: manifest.vehicleId ?? '',
      driverId: manifest.driverId ?? '',
    },
    onSubmit: async (values) => {
      await manifestsApi.assignTrip(manifest.id, {
        tripId: values.tripId || null,
        vehicleId: values.vehicleId || null,
        driverId: values.driverId || null,
      });
      onSaved();
      onClose();
    },
  });

  return (
    <FormModal
      open={open}
      title="Assign the movement"
      description={`${manifest.manifestNumber} · ${manifest.route}`}
      submitLabel="Save assignment"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={form.submit}
    >
      <Callout tone="info">
        Assignable while the manifest is a draft or sealed. All three are optional - a consignment
        can be dispatched without a fleet movement recorded against it.
      </Callout>
      <TripSelect
        siteCode={site}
        value={form.values.tripId}
        onChange={(value) => form.setValue('tripId', value)}
        {...form.fieldProps('tripId')}
      />
      <VehicleSelect
        siteCode={site}
        value={form.values.vehicleId}
        onChange={(value) => form.setValue('vehicleId', value)}
        allowEmpty
        emptyLabel="No vehicle"
        {...form.fieldProps('vehicleId')}
      />
      <DriverSelect
        siteCode={site}
        value={form.values.driverId}
        onChange={(value) => form.setValue('driverId', value)}
        allowEmpty
        emptyLabel="No driver"
        {...form.fieldProps('driverId')}
      />
    </FormModal>
  );
};

interface CloseManifestDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
  manifest: DispatchManifest;
  blockers: string[];
}

/**
 * Close the manifest - `POST /manifests/{id}/close`.
 *
 * `DispatchClosurePolicy` refuses closure while an exception case is open or the custody chain is
 * not closable. Both are shown here, separately, because the service's own message names one cause
 * at a time and an operator needs to see everything standing in the way at once.
 */
export const CloseManifestDialog = ({
  open,
  onClose,
  onSaved,
  manifest,
  blockers,
}: CloseManifestDialogProps) => {
  const form = useFleetForm({
    initialValues: { reason: '' },
    schema: { reason: compose(required('Closure reason'), maxLength('Closure reason', 1000)) },
    onSubmit: async (values) => {
      await manifestsApi.close(manifest.id, { reason: values.reason.trim() });
      onSaved();
      onClose();
    },
  });

  return (
    <FormModal
      open={open}
      title="Close the manifest"
      description={manifest.manifestNumber}
      submitLabel="Close manifest"
      submitting={form.submitting}
      submitDisabled={blockers.length > 0}
      formError={form.formError}
      onClose={onClose}
      onSubmit={form.submit}
    >
      {blockers.length > 0 ? (
        <Callout tone="danger" title="The service will refuse this closure">
          <ul className="mt-1 list-disc space-y-1 pl-4">
            {blockers.map((blocker) => (
              <li key={blocker}>{blocker}</li>
            ))}
          </ul>
        </Callout>
      ) : (
        <Callout tone="success">
          No exception case is open and the custody chain is complete. Closure will be accepted.
        </Callout>
      )}
      <TextAreaField
        label="Closure reason"
        required
        rows={3}
        value={form.values.reason}
        onChange={(value) => form.setValue('reason', value)}
        {...form.fieldProps('reason')}
      />
    </FormModal>
  );
};
