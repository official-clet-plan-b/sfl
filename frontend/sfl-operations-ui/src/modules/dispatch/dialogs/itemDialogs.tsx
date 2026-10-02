import { CourierItem } from 'modules/dispatch/api/dto';
import {
  CUSTODY_REQUIRED_TYPES,
  ITEM_DIRECTIONS,
  ITEM_TYPES,
  ItemDirection,
  ItemType,
  SENSITIVITIES,
  Sensitivity,
} from 'modules/dispatch/api/enums';
import { courierItemsApi, inboundMailApi } from 'modules/dispatch/api/dispatchApi';
import { EVIDENCE_RETENTION_CLASSES, EvidenceRetentionClass, humanise } from 'modules/fleet/api/enums';
import SiteSelect from 'shared/components/SiteSelect';
import { Field, FieldLabel, Radio, RadioGroup } from '@rfdtech/components';
import {
  Callout,
  EnumSelectField,
  FormModal,
  TextAreaField,
  TextField,
} from 'modules/dispatch/components/formKit';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, maxLength, required } from 'shared/validation/validators';

const twoColumn = 'grid gap-4 sm:grid-cols-2';

interface RegisterItemDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: (item: CourierItem) => void;
  defaultSiteCode: string;
  /** Fixes the direction and posts to the inbound endpoint, which the mailroom screen wants. */
  inboundOnly?: boolean;
}

/**
 * Register a courier item - `POST /items`, or `POST /inbound` when the direction is fixed.
 *
 * Two things are the service's to decide and are shown rather than asked for. The item number is
 * allocated when left blank, so the field says so instead of demanding one an operator would invent.
 * And `chainOfCustodyRequired` is **derived** by `CourierItem` from the type and sensitivity - the
 * dialog previews what that derivation will produce, because it changes what the item then obliges
 * the handler to do, but it never sends the flag.
 */
export const RegisterItemDialog = ({
  open,
  onClose,
  onSaved,
  defaultSiteCode,
  inboundOnly = false,
}: RegisterItemDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      siteCode: defaultSiteCode,
      itemNumber: '',
      direction: (inboundOnly ? 'INBOUND' : 'OUTBOUND') as ItemDirection,
      itemType: 'ORDINARY_MAIL' as ItemType,
      sensitivity: 'ORDINARY' as Sensitivity,
      origin: '',
      destination: '',
      sender: '',
      recipient: '',
      assignedHandler: '',
    },
    schema: {
      siteCode: required('Site code'),
      itemNumber: maxLength('Item number', 60),
      direction: required('Direction'),
      itemType: required('Item type'),
      sensitivity: required('Sensitivity'),
      origin: compose(required('Origin'), maxLength('Origin', 200)),
      destination: compose(required('Destination'), maxLength('Destination', 200)),
      sender: maxLength('Sender', 200),
      recipient: maxLength('Recipient', 200),
      assignedHandler: maxLength('Handler', 160),
    },
    onSubmit: async (values) => {
      const common = {
        siteCode: values.siteCode.trim().toUpperCase(),
        itemNumber: values.itemNumber.trim() || null,
        itemType: values.itemType,
        sensitivity: values.sensitivity,
        origin: values.origin.trim(),
        destination: values.destination.trim(),
        sender: values.sender.trim() || null,
        recipient: values.recipient.trim() || null,
        assignedHandler: values.assignedHandler.trim() || null,
      };
      const saved = inboundOnly
        ? await inboundMailApi.register(common)
        : await courierItemsApi.register({ ...common, direction: values.direction });
      onSaved(saved);
      onClose();
    },
  });

  const custodyExpected =
    CUSTODY_REQUIRED_TYPES.includes(form.values.itemType) ||
    form.values.sensitivity !== 'ORDINARY';

  return (
    <FormModal
      open={open}
      title={inboundOnly ? 'Register inbound mail' : 'Register a courier item'}
      description="Recorded as received. Its number is allocated by the service when you leave it blank."
      submitLabel="Register item"
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
          onChange={(value) => form.setValue('siteCode', value)}
          {...form.fieldProps('siteCode')}
        />
        <TextField
          label="Item number"
          value={form.values.itemNumber}
          onChange={(value) => form.setValue('itemNumber', value)}
          {...form.fieldProps('itemNumber', 'Leave blank and the service allocates one.')}
        />
        {!inboundOnly && (
          <Field className="sm:col-span-2">
            <FieldLabel htmlFor={undefined}>Direction</FieldLabel>
            <RadioGroup
              orientation="horizontal"
              value={form.values.direction}
              onValueChange={(value) => form.setValue('direction', value as ItemDirection)}
            >
              {ITEM_DIRECTIONS.map((direction) => (
                <Radio key={direction} value={direction} label={humanise(direction)} />
              ))}
            </RadioGroup>
          </Field>
        )}
        <EnumSelectField
          label="Item type"
          required
          value={form.values.itemType}
          options={ITEM_TYPES}
          onChange={(value) => form.setValue('itemType', (value || 'ORDINARY_MAIL') as ItemType)}
          {...form.fieldProps('itemType')}
        />
        <EnumSelectField
          label="Sensitivity"
          required
          value={form.values.sensitivity}
          options={SENSITIVITIES}
          onChange={(value) => form.setValue('sensitivity', (value || 'ORDINARY') as Sensitivity)}
          {...form.fieldProps('sensitivity')}
        />
        <TextField
          label="Handler"
          value={form.values.assignedHandler}
          onChange={(value) => form.setValue('assignedHandler', value)}
          {...form.fieldProps('assignedHandler', 'Who is accountable for it now.')}
        />
        <TextField
          label="Origin"
          required
          value={form.values.origin}
          onChange={(value) => form.setValue('origin', value)}
          {...form.fieldProps('origin')}
        />
        <TextField
          label="Destination"
          required
          value={form.values.destination}
          onChange={(value) => form.setValue('destination', value)}
          {...form.fieldProps('destination')}
        />
        <TextField
          label="Sender"
          value={form.values.sender}
          onChange={(value) => form.setValue('sender', value)}
          {...form.fieldProps('sender')}
        />
        <TextField
          label="Recipient"
          value={form.values.recipient}
          onChange={(value) => form.setValue('recipient', value)}
          {...form.fieldProps('recipient')}
        />
      </div>

      <Callout tone={custodyExpected ? 'warning' : 'info'} title="Chain of custody">
        {custodyExpected
          ? 'This type and sensitivity will require a chain of custody. Every handover has to be recorded, and the manifest carrying it cannot close until the chain is complete.'
          : 'Ordinary mail at ordinary sensitivity does not require a recorded chain of custody. The service decides this from the type and sensitivity, not from anything entered here.'}
      </Callout>
    </FormModal>
  );
};

interface MisrouteDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
  item: CourierItem;
}

/** Re-route a misdirected item - `POST /items/{id}/misroute`. The reason is mandatory. */
export const MisrouteItemDialog = ({ open, onClose, onSaved, item }: MisrouteDialogProps) => {
  const form = useFleetForm({
    initialValues: { reason: '', handler: item.assignedHandler ?? '' },
    schema: {
      reason: compose(required('Reason'), maxLength('Reason', 1000)),
      handler: maxLength('Handler', 160),
    },
    onSubmit: async (values) => {
      await courierItemsApi.misroute(item.id, {
        reason: values.reason.trim(),
        handler: values.handler.trim() || null,
      });
      onSaved();
      onClose();
    },
  });

  return (
    <FormModal
      open={open}
      title="Record a misroute"
      description={`${item.itemNumber} · ${item.origin} → ${item.destination}`}
      submitLabel="Record misroute"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={form.submit}
    >
      <Callout tone="info">
        The item returns to the received state with the reason recorded against it, so it can be
        staged again for the right destination. The misroute stays on the record.
      </Callout>
      <TextAreaField
        label="What went wrong"
        required
        rows={3}
        value={form.values.reason}
        onChange={(value) => form.setValue('reason', value)}
        {...form.fieldProps('reason')}
      />
      <TextField
        label="Reassign to"
        value={form.values.handler}
        onChange={(value) => form.setValue('handler', value)}
        {...form.fieldProps('handler', 'Optional. Leave as is to keep the current handler.')}
      />
    </FormModal>
  );
};

interface DistributeDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
  item: CourierItem;
}

/**
 * Record internal distribution of inbound mail - `POST /inbound/{id}/distribute`.
 *
 * The acknowledgement is the point: it names who took the item, and the signature reference is the
 * only evidence that it happened. Legal from received or staged; once distributed the item's inbound
 * obligation is discharged.
 */
export const DistributeInboundDialog = ({
  open,
  onClose,
  onSaved,
  item,
}: DistributeDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      acknowledgedBy: item.recipient ?? '',
      distributionReference: '',
      signatureStorageReference: '',
      signatureFileName: '',
      signatureSha256: '',
      retentionClass: '' as EvidenceRetentionClass | '',
    },
    schema: {
      acknowledgedBy: compose(required('Acknowledged by'), maxLength('Acknowledged by', 200)),
      distributionReference: maxLength('Distribution reference', 120),
      signatureStorageReference: maxLength('Signature reference', 500),
      signatureFileName: maxLength('Signature file name', 255),
      signatureSha256: maxLength('Signature checksum', 128),

    },
    // The service refuses distribution with neither evidence, so this must be an either/or - not a
    // requirement pinned to a single field, which would reject a signature-only submission.
    crossFieldValidate: (values) =>
      values.distributionReference.trim() || values.signatureStorageReference.trim()
        ? {}
        : {
            distributionReference:
              'A distribution acknowledgement is required: fill this in, or register a signature below.',
          },
    onSubmit: async (values) => {
      await inboundMailApi.distribute(item.id, {
        acknowledgedBy: values.acknowledgedBy.trim(),
        distributionReference: values.distributionReference.trim() || null,
        signatureFileName: values.signatureFileName.trim() || null,
        signatureContentType: null,
        signatureStorageReference: values.signatureStorageReference.trim() || null,
        signatureSha256: values.signatureSha256.trim() || null,
        retentionClass: values.retentionClass || null,
      });
      onSaved();
      onClose();
    },
  });

  return (
    <FormModal
      open={open}
      title="Record distribution"
      description={`${item.itemNumber} · for ${item.recipient ?? 'an unnamed recipient'}`}
      submitLabel="Record distribution"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={form.submit}
    >
      <div className={twoColumn}>
        <TextField
          label="Acknowledged by"
          required
          value={form.values.acknowledgedBy}
          onChange={(value) => form.setValue('acknowledgedBy', value)}
          {...form.fieldProps('acknowledgedBy', 'Who physically took the item.')}
        />
        <TextField
          label="Distribution reference"
          value={form.values.distributionReference}
          onChange={(value) => form.setValue('distributionReference', value)}
          {...form.fieldProps(
            'distributionReference',
            'Internal reference. Required if no signature evidence is registered below.',
          )}
        />
      </div>

      <Callout tone="info" title="Signature evidence">
        Required unless a distribution reference is given above - between the two, this is the only
        proof the acknowledgement happened. Register the signature in the evidence store first, then
        paste its storage reference here.
      </Callout>

      <div className={twoColumn}>
        <TextField
          label="Signature storage reference"
          value={form.values.signatureStorageReference}
          onChange={(value) => form.setValue('signatureStorageReference', value)}
          {...form.fieldProps('signatureStorageReference')}
        />
        <TextField
          label="Signature file name"
          value={form.values.signatureFileName}
          onChange={(value) => form.setValue('signatureFileName', value)}
          {...form.fieldProps('signatureFileName')}
        />
        <TextField
          label="Signature checksum"
          value={form.values.signatureSha256}
          onChange={(value) => form.setValue('signatureSha256', value)}
          {...form.fieldProps('signatureSha256', 'SHA-256, if the capture produced one.')}
        />
        <EnumSelectField
          label="Retention class"
          value={form.values.retentionClass}
          options={EVIDENCE_RETENTION_CLASSES}
          onChange={(value) => form.setValue('retentionClass', value)}
          allowEmpty
          emptyLabel="Not set"
          {...form.fieldProps('retentionClass', 'How long the evidence must be kept.')}
        />
      </div>
    </FormModal>
  );
};
