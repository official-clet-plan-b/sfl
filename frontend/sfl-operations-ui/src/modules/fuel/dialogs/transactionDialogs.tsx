import { useEffect, useMemo, useState } from 'react';
import { FuelTransaction } from 'modules/fuel/api/dto';
import { CURRENCIES, FUEL_PRODUCTS } from 'modules/fuel/api/enums';
import { fuelPricesApi, fuelTransactionsApi } from 'modules/fuel/api/fuelApi';
import { DriverSelect, VehicleSelect } from 'modules/fleet/components/FleetReferenceSelect';
import { ActiveTripSelect, useDriverTrips } from 'modules/fuel/components/ActiveTripSelect';
import { formatMoney } from 'modules/fuel/components/fuelFormat';
import { Alert, FormDialog, EvidenceFileField } from 'modules/fuel/components/fuelMigration';
import PlaceField from 'shared/components/PlaceField';
import { EnumSelect, NumberInput, SelectInput, TextAreaInput, TextInput } from 'modules/fuel/components/fuelMigration';
import { evidenceFilesApi } from 'shared/evidence/evidenceFilesApi';
import { FleetApiError } from 'shared/errors/FleetApiError';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { useRecentValues } from 'shared/hooks/useRecentValues';
import { isPersona } from 'shared/layout/personas';
import { useFleetForm } from 'shared/validation/useFleetForm';
import {
  compose,
  integerAtLeast,
  maxLength,
  positiveNumber,
  required,
} from 'shared/validation/validators';

const twoColumn = 'grid gap-4 sm:grid-cols-2';
const threeColumn = 'grid gap-4 sm:grid-cols-3';

/**
 * Litres, from the money paid and the price posted at the pump.
 *
 * <h2>Why this direction, and not the other</h2>
 *
 * <p>The form used to ask for litres and price per litre and derive the total. Both inputs belonged
 * to the person being reimbursed, so the total could be anything they wanted it to be - and the
 * question that prompted this change is exactly that: a driver buys GHS 100 of fuel, agrees with the
 * attendant to record GHS 120, and nothing in the arithmetic objects.
 *
 * <p>Turning it around fixes what the claimant controls. The price comes from the platform, the
 * amount comes from the receipt, and litres is what falls out. It is also simply what happened: at a
 * Ghanaian forecourt you buy an amount of money's worth of fuel, not a number of litres.
 *
 * <p>The consequence that matters is downstream. Litres feeds tank capacity, consumption range and
 * the daily and monthly volume ceilings - so inflating the money now inflates the volume, and an
 * inflated volume runs into physical limits the policy already knows how to check. Before, an
 * overstated amount touched none of them.
 */
const derivedLitres = (amountPaid: string, unitPrice: string): number | null => {
  const amount = Number(amountPaid);
  const price = Number(unitPrice);
  if (!amountPaid || !unitPrice || !Number.isFinite(amount) || !Number.isFinite(price) || price <= 0) {
    return null;
  }
  // Three decimals, matching the domain's scale for quantity. Rounding here rather than letting the
  // service round means the litres shown to the driver is the litres that gets stored.
  return Math.round((amount / price) * 1000) / 1000;
};

/**
 * The total the service will compute from what is being submitted.
 *
 * <p>`FuelTransaction` recomputes `quantity × unitPrice` at scale 2 and refuses a total that
 * disagrees. Since litres was rounded to three places above, amount ÷ price × price is not always the
 * amount typed - so the figure sent is this one, not the driver's. The difference is at most a
 * pesewa, and it is shown rather than silently corrected.
 */
const derivedTotal = (litres: number | null, unitPrice: string): number | null => {
  const price = Number(unitPrice);
  if (litres === null || !Number.isFinite(price)) {
    return null;
  }
  return Math.round(litres * price * 100) / 100;
};

interface CaptureDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: (transaction: FuelTransaction) => void;
  defaultSiteCode: string;
}

/**
 * Manual capture - `POST /api/v1/fuel/transactions`.
 *
 * <h2>What this form used to ask, and why almost none of it survived</h2>
 *
 * <p>It asked for a vehicle, a driver, litres, a price per litre, a vendor as free text, a station as
 * free text, a provider transaction reference, and - the line that prompted this rewrite - a "receipt
 * evidence reference" with the instruction *register the receipt under Evidence & audit, then paste
 * its identifier*. A driver has no Evidence & audit screen. That instruction described a workflow the
 * person reading it could not perform, so in practice the field was left blank and fuel was claimed
 * with no receipt at all.
 *
 * <p>Each field is now either derived, chosen from a list the platform controls, or a file:
 *
 * <ul>
 *   <li><b>Trip first, and it fills in the vehicle.</b> The driver knows which journey they are on;
 *       the journey knows the vehicle and the driver. Three questions become one.</li>
 *   <li><b>No driver field</b> when a driver is capturing their own purchase - they are signed in,
 *       and the trip says who they are. A fuel officer recording on someone's behalf still picks.</li>
 *   <li><b>Vendor is a list</b> of the policy's approved providers, and the station is a place chosen
 *       from Google Places. Free text made "GOIL", "Goil Tema" and "goil" three vendors, which meant
 *       the approved-vendor rule was comparing against strings that never matched.</li>
 *   <li><b>Amount paid replaces litres.</b> See {@link derivedLitres} - this is the change that makes
 *       overstating a claim run into the volume rules instead of past them.</li>
 *   <li><b>Two photographs, both required</b>: the pump meter and the receipt. Uploaded here, scanned
 *       by the service, and attached to the transaction without anybody copying an identifier.</li>
 * </ul>
 *
 * <p>`sourceSystem` stays fixed to `MANUAL`. It selects `FUEL_TRANSACTION_CAPTURE` in the service, and
 * provenance should say where a record really came from.
 */
export const CaptureTransactionDialog = ({
  open,
  onClose,
  onSaved,
  defaultSiteCode,
}: CaptureDialogProps) => {
  /** Mirrors `FuelAccessPolicy.isDriverOnly`, which is what the service narrows on. */
  const driverOnly = isPersona('driver');
  const [pumpImage, setPumpImage] = useState<File | null>(null);
  const [receipt, setReceipt] = useState<File | null>(null);
  const [uploading, setUploading] = useState<string | null>(null);

  const form = useFleetForm({
    initialValues: {
      siteCode: defaultSiteCode,
      tripId: '',
      vehicleId: '',
      driverId: '',
      vendorReference: '',
      stationReference: '',
      fuelProduct: 'DIESEL',
      amountPaid: '',
      unitPrice: '',
      currency: 'GHS',
      cardReference: '',
      odometerReading: '',
      comments: '',
    },
    schema: {
      siteCode: required('Site code'),
      vehicleId: required('Vehicle'),
      driverId: required('Driver'),
      vendorReference: compose(required('Provider'), maxLength('Provider', 160)),
      stationReference: compose(required('Location'), maxLength('Location', 160)),
      fuelProduct: required('Fuel product'),
      amountPaid: compose(required('Amount paid'), positiveNumber('Amount paid')),
      unitPrice: compose(required('Price per litre'), positiveNumber('Price per litre')),
      currency: required('Currency'),
      odometerReading: compose(
        required('Odometer reading'),
        integerAtLeast('Odometer reading', 0),
      ),
      comments: maxLength('Comments', 1000),
    },
    crossFieldValidate: (values) => {
      const errors: Record<string, string> = {};
      // A driver must attach the trip; only a supervising actor may record fuel without one.
      if (driverOnly && !values.tripId) {
        errors.tripId = 'Choose the trip this fuel was bought for.';
      }
      return errors;
    },
    onSubmit: async (values) => {
      if (!pumpImage) {
        throw FleetApiError.validation('Attach the photograph of the pump meter reading.');
      }
      if (!receipt) {
        throw FleetApiError.validation('Attach the receipt for the fuel bought.');
      }
      const litres = derivedLitres(values.amountPaid, values.unitPrice);
      if (litres === null || litres <= 0) {
        throw FleetApiError.validation('The amount paid and the price per litre must both be set.');
      }

      const site = values.siteCode.trim().toUpperCase();
      /*
        Uploaded before the transaction, not after, and that ordering is deliberate.

        The evidence is filed against the *trip* rather than the transaction, because the transaction
        does not exist yet and evidence needs a record to belong to. Filing it against the trip is
        also the more useful answer: it puts the receipt and the pump photograph beside the journey
        they document, where a reviewer looking at a disputed trip will actually find them.

        If an upload is refused - wrong file type, active content in a PDF, or a file over the limit -
        the submission stops here with that reason and no transaction is created. The reverse order
        would leave a fuel claim asserting evidence that was never accepted.
      */
      const related = values.tripId
        ? { relatedRecordType: 'Trip', relatedRecordId: values.tripId }
        : { relatedRecordType: 'Vehicle', relatedRecordId: values.vehicleId };

      setUploading('Uploading the pump reading…');
      const pump = await evidenceFilesApi.upload({
        siteCode: site,
        ...related,
        evidenceType: 'FUEL_PUMP_READING',
        // The fuel evidence a finance reconciliation is read from lives as long as the financial
        // record it supports, which is the seven-year compliance class - there is no finance class of
        // its own, and inventing one would put these outside every retention sweep that exists.
        retentionClass: 'COMPLIANCE_7_YEARS',
        file: pumpImage,
      });

      setUploading('Uploading the receipt…');
      const receiptEvidence = await evidenceFilesApi.upload({
        siteCode: site,
        ...related,
        evidenceType: 'FUEL_RECEIPT',
        // The fuel evidence a finance reconciliation is read from lives as long as the financial
        // record it supports, which is the seven-year compliance class - there is no finance class of
        // its own, and inventing one would put these outside every retention sweep that exists.
        retentionClass: 'COMPLIANCE_7_YEARS',
        file: receipt,
      });

      setUploading('Recording the transaction…');
      const saved = await fuelTransactionsApi.capture({
        siteCode: site,
        // Manual capture has no provider record to recognise; the field belonged to the integration.
        providerTransactionId: null,
        sourceSystem: 'MANUAL',
        vehicleId: values.vehicleId,
        driverId: values.driverId,
        tripId: values.tripId || null,
        /*
          The moment of capture, because the form no longer asks for the moment of purchase.

          `occurredAt` is not decoration - it selects the posted price and the policy version the
          transaction is judged against - so it still has to be a real timestamp. Capture time is
          the honest one available: the driver fills in this form at the pump, so the two are
          minutes apart, and the receipt photograph carries the printed time for anyone who needs
          to settle it exactly. A capture posted materially later than the purchase will resolve
          against the later price, which is the one behaviour worth knowing about.
        */
        occurredAt: new Date().toISOString(),
        vendorReference: values.vendorReference.trim(),
        stationReference: values.stationReference.trim() || null,
        fuelProduct: values.fuelProduct.trim().toUpperCase(),
        quantity: litres,
        // Litres, always. The unit select is gone: the price reference, the tank capacity and the
        // consumption thresholds are all per litre, so any other unit made them incomparable.
        quantityUnit: 'LITRE',
        unitPrice: Number(values.unitPrice),
        totalCost: derivedTotal(litres, values.unitPrice),
        currency: values.currency,
        cardReference: values.cardReference.trim() || null,
        odometerReading: Number(values.odometerReading),
        receiptEvidenceId: receiptEvidence.id,
        pumpEvidenceId: pump.id,
        comments: values.comments.trim() || null,
      });
      setUploading(null);
      // Remembered only after the service accepted it, so a refused submission does not teach the
      // browser a station that was never used.
      recentLocations.remember(values.stationReference.trim());
      onSaved(saved);
      onClose();
    },
  });

  const trips = useDriverTrips(form.values.siteCode);
  const chosenTrip = useMemo(
    () => trips.active.find((trip) => trip.id === form.values.tripId) ?? null,
    [trips.active, form.values.tripId],
  );

  /*
    Choosing a trip fills in the vehicle and the driver.

    Both come off the trip record rather than from a lookup, and the driver in particular is why the
    driver field can disappear: a driver's trip list is narrowed server-side to their own trips, so
    the driver on the trip they picked is them.
  */
  useEffect(() => {
    if (!chosenTrip) {
      return;
    }
    form.setValues({
      vehicleId: chosenTrip.vehicleId ?? '',
      driverId: chosenTrip.driverId ?? '',
    });
    // `form` is intentionally absent: it is rebuilt every render and would re-run this on each one.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chosenTrip]);

  const providers = useApiQuery(
    (signal) =>
      form.values.siteCode
        ? fuelPricesApi.providers(form.values.siteCode, signal)
        : Promise.resolve(undefined),
    [form.values.siteCode],
  );

  /** The price in force for the chosen provider and product, if the site has recorded one. */
  const postedPrice = useApiQuery(
    (signal) =>
      form.values.siteCode && form.values.vendorReference && form.values.fuelProduct
        ? fuelPricesApi.postedPrices(
            {
              siteCode: form.values.siteCode,
              vendor: form.values.vendorReference,
              fuelProduct: form.values.fuelProduct,
              inForceOnly: true,
            },
            signal,
          )
        : Promise.resolve(undefined),
    [form.values.siteCode, form.values.vendorReference, form.values.fuelProduct],
  );

  const reference = postedPrice.data?.[0] ?? null;

  /*
    The posted price fills the field in and locks it.

    Not merely a default: while a reference price exists the driver cannot change it, because the
    price is the one figure in the claim that belongs to the forecourt rather than to them. With no
    reference on file the field opens up - a site that has not recorded its prices must still be able
    to record fuel - and the alert below says plainly that nothing is checking it.
  */
  useEffect(() => {
    if (reference) {
      form.setValue('unitPrice', String(reference.unitPrice));
      form.setValue('currency', reference.currency);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reference?.id]);

  // Scoped to the site: a driver at Kumasi should be offered Kumasi stations, not Accra ones. This
  // is also the offline answer, since it is stored in the browser rather than fetched.
  const recentLocations = useRecentValues('fuel-location', form.values.siteCode);
  const litres = derivedLitres(form.values.amountPaid, form.values.unitPrice);
  const total = derivedTotal(litres, form.values.unitPrice);
  const providerOptions = useMemo(
    () => (providers.data ?? []).map((vendor) => ({ value: vendor, label: vendor })),
    [providers.data],
  );

  return (
    <FormDialog
      open={open}
      title="Record a fuel purchase"
      description="Recorded as RECEIVED. It stays outside the reconciled figures until reconciliation runs against the policy in force when the fuel was bought."
      submitLabel="Record purchase"
      submitting={form.submitting}
      // Two photographs go up before the transaction is written, which on a phone at a forecourt is
      // slow enough that a silent button looks broken. The summary slot is pinned above the actions,
      // so the step is visible at the moment the driver is looking at the button.
      summary={uploading}
      formError={form.formError}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={form.submit}
    >
      {/*
        No site control. The dialog is opened from a register that is already filtered to one site,
        and it is handed that site - so asking again was asking the driver to restate something the
        screen behind them already said, on the form where they have the least patience for it. The
        value still travels with the request and still scopes the trip, vehicle, provider and price
        lookups; it is simply not a question any more.
      */}
      <ActiveTripSelect
        required={driverOnly}
        siteCode={form.values.siteCode}
        trips={trips.active}
        loading={trips.loading}
        allowNone={!driverOnly}
        value={form.values.tripId}
        onChange={(value) => form.setValue('tripId', value)}
        {...form.fieldProps('tripId')}
      />

      {/*
        Choosing a trip fills these in; it does not take them away.

        They used to be replaced by a sentence saying the trip had supplied them, which is the one
        thing a form should not do - the driver picks the journey and the two facts they were about
        to check vanish, so the only way to see which vehicle is on the claim is to abandon the trip.
        Prefilled and still on screen is the same saving with none of that: the hint says where the
        value came from, and it stays editable because a fuel officer recording on someone's behalf
        is sometimes correcting exactly this.
      */}
      <div className={twoColumn}>
        <VehicleSelect
          required
          siteCode={form.values.siteCode}
          value={form.values.vehicleId}
          onChange={(value) => form.setValue('vehicleId', value)}
          {...form.fieldProps(
            'vehicleId',
            chosenTrip ? `From ${chosenTrip.tripNumber}. Change it if the fuel was for another vehicle.` : undefined,
          )}
        />
        {/* A driver never sees this: they are signed in, and their trip says who they are. */}
        {!driverOnly && (
          <DriverSelect
            required
            siteCode={form.values.siteCode}
            value={form.values.driverId}
            onChange={(value) => form.setValue('driverId', value)}
            {...form.fieldProps(
              'driverId',
              chosenTrip ? `From ${chosenTrip.tripNumber}.` : undefined,
            )}
          />
        )}
      </div>

      <div className={twoColumn}>
        {/*
          No "time fuel purchased" field. The receipt photograph carries the time, and asking a
          driver to retype it at the pump bought nothing except a second version of it to disagree
          with. The moment of capture is recorded instead - see `occurredAt` in the submit handler.
        */}
        <EnumSelect
          label="Fuel product"
          required
          value={form.values.fuelProduct as (typeof FUEL_PRODUCTS)[number]}
          options={FUEL_PRODUCTS}
          onChange={(value) => form.setValue('fuelProduct', value || 'DIESEL')}
          {...form.fieldProps('fuelProduct')}
        />
        <SelectInput
          label="Provider"
          required
          value={form.values.vendorReference}
          options={providerOptions}
          onChange={(value) => form.setValue('vendorReference', value)}
          {...form.fieldProps(
            'vendorReference',
            providers.loading
              ? 'Loading approved providers…'
              : providerOptions.length === 0
                ? 'This site has no approved provider list. Ask a fleet manager to set one on the fuel policy.'
                : 'Only providers the site’s fuel policy approves.',
          )}
        />
        <PlaceField
          label="Location"
          required
          value={form.values.stationReference}
          onChange={(value) => form.setValue('stationReference', value)}
          recent={recentLocations.values}
          {...form.fieldProps(
            'stationReference',
            'The filling station where you bought it. Choose from the suggestions, or type it.',
          )}
        />
      </div>

      <div className={threeColumn}>
        <NumberInput
          label="Amount paid"
          required
          step={0.01}
          value={form.values.amountPaid}
          onChange={(value) => form.setValue('amountPaid', value)}
          {...form.fieldProps('amountPaid', 'The total on the receipt.')}
        />
        <NumberInput
          label="Price per litre"
          required
          step={0.0001}
          value={form.values.unitPrice}
          onChange={(value) => form.setValue('unitPrice', value)}
          disabled={Boolean(reference)}
          {...form.fieldProps(
            'unitPrice',
            reference
              ? `Posted by ${reference.vendor} from ${new Date(reference.effectiveFrom).toLocaleDateString()}.`
              : 'No posted price is on file for this provider, so this is not being checked.',
          )}
        />
        <EnumSelect
          label="Currency"
          required
          value={form.values.currency as (typeof CURRENCIES)[number]}
          options={CURRENCIES}
          onChange={(value) => form.setValue('currency', value || 'GHS')}
          renderOptionLabel={(option) => option}
          disabled={Boolean(reference)}
          {...form.fieldProps('currency')}
        />
      </div>

      <Alert variant={reference ? 'info' : 'warning'} title="What will be recorded">
        {litres === null ? (
          'Enter the amount paid to see how many litres that buys at the posted price.'
        ) : (
          <>
            <strong>{litres.toFixed(3)} litres</strong> at{' '}
            {formatMoney(Number(form.values.unitPrice), form.values.currency)} per litre ={' '}
            {formatMoney(total ?? 0, form.values.currency)}.{' '}
            {reference
              ? 'Litres is calculated rather than typed, so it can be checked against the tank capacity and the vehicle’s usual consumption.'
              : 'Nobody has recorded what this provider charges, so the price you entered is taken on trust. Ask a fleet manager to record the posted price.'}
          </>
        )}
      </Alert>

      <div className={twoColumn}>
        <NumberInput
          label="Odometer reading"
          required
          suffix="km"
          value={form.values.odometerReading}
          onChange={(value) => form.setValue('odometerReading', value)}
          {...form.fieldProps('odometerReading', 'The reading on the dashboard as you filled up.')}
        />
        <TextInput
          label="Fuel card, if you used one"
          value={form.values.cardReference}
          onChange={(value) => form.setValue('cardReference', value)}
          {...form.fieldProps(
            'cardReference',
            'Optional. Stored masked - only the last four digits are kept.',
          )}
        />
      </div>

      <div className={twoColumn}>
        <EvidenceFileField
          label="Photo of the pump meter"
          required
          capture
          value={pumpImage}
          onChange={setPumpImage}
          helperText="The reading on the pump as you finished filling. This is what the litres above are checked against."
        />
        <EvidenceFileField
          label="Receipt"
          required
          value={receipt}
          onChange={setReceipt}
          helperText="The receipt from the attendant. Photo or PDF."
        />
      </div>

      <TextAreaInput
        label="Comments"
        rows={2}
        value={form.values.comments}
        onChange={(value) => form.setValue('comments', value)}
        {...form.fieldProps('comments')}
      />
    </FormDialog>
  );
};

interface VoidDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
  transaction: FuelTransaction;
}

/**
 * Void - `POST /api/v1/fuel/transactions/{id}/void`.
 *
 * Privileged (`FUEL_TRANSACTION_VOID`) and irreversible: the record moves to `VOIDED` lifecycle and
 * `FuelTransaction.withStatus` refuses everything afterwards, so it can never be reconciled. The
 * reason is mandatory and **replaces the record's comments**, which is worth saying out loud before
 * an operator overwrites something they wanted to keep.
 */
export const VoidTransactionDialog = ({
  open,
  onClose,
  onSaved,
  transaction,
}: VoidDialogProps) => {
  const form = useFleetForm({
    initialValues: { reason: '' },
    schema: { reason: compose(required('Reason'), maxLength('Reason', 1000)) },
    onSubmit: async (values) => {
      await fuelTransactionsApi.void(transaction.id, { reason: values.reason.trim() });
      onSaved();
      onClose();
    },
  });

  return (
    <FormDialog
      open={open}
      title="Void this transaction"
      description={`${transaction.vendorReference} · ${transaction.fuelProduct}`}
      submitLabel="Void transaction"
      submitting={form.submitting}
      formError={form.formError}
      destructive
      onClose={onClose}
      onSubmit={form.submit}
    >
      <Alert variant="warning" title="This cannot be undone">
        The record moves to the voided lifecycle and can never be reconciled. It stays in the
        register and in the audit trail. Your reason replaces the transaction’s comments
        {transaction.comments ? `, which currently read “${transaction.comments}”.` : '.'}
      </Alert>
      <TextAreaInput
        label="Reason"
        required
        rows={3}
        value={form.values.reason}
        onChange={(value) => form.setValue('reason', value)}
        {...form.fieldProps('reason')}
      />
    </FormDialog>
  );
};
