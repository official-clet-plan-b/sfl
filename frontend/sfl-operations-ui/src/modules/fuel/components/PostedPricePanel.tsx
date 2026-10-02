import { useMemo, useState } from 'react';
import { FuelPostedPrice } from 'modules/fuel/api/dto';
import { CURRENCIES, FUEL_PRODUCTS } from 'modules/fuel/api/enums';
import { fuelPricesApi } from 'modules/fuel/api/fuelApi';
import { formatUnitPrice } from 'modules/fuel/components/fuelFormat';
import { Alert, Button, CellStack, DataTable, FormDialog, SectionCard } from 'modules/fuel/components/fuelMigration';
import type { Column } from 'modules/fuel/components/fuelMigration';
import DataState from 'shared/components/DataState';
import { useNotifier } from 'shared/components/Notifier';
import { EnumSelect, NumberInput, SelectInput, TextAreaInput } from 'modules/fuel/components/fuelMigration';
import { formatDate } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, maxLength, positiveNumber, required } from 'shared/validation/validators';

/**
 * The prices this site's approved providers post, and the way to record them.
 *
 * <h2>Why this sits on the policy screen</h2>
 *
 * <p>Because a posted price is a rule, not an observation. It decides what a driver is allowed to
 * claim per litre, in the same way the per-transaction ceiling decides how much they may buy at once,
 * and it is set by the same person under the same permission. Putting it anywhere a driver can reach
 * would hand them the number they are being measured against.
 *
 * <h2>Why it has to exist at all</h2>
 *
 * <p>The whole price control is inert without it. With no price on file the POSTED_PRICE rule records
 * "no reference price" and passes, which is the honest thing for it to do and also means nothing is
 * checking what a litre cost. This panel is where that gap gets closed - and it says so, loudly, for
 * every approved provider that still has no price.
 */

const twoColumn = 'grid gap-4 sm:grid-cols-2';

interface PostedPricePanelProps {
  siteCode: string;
}

export const PostedPricePanel = ({ siteCode }: PostedPricePanelProps) => {
  const { notifySuccess } = useNotifier();
  const [recording, setRecording] = useState(false);

  const providers = useApiQuery(
    (signal) => (siteCode ? fuelPricesApi.providers(siteCode, signal) : Promise.resolve(undefined)),
    [siteCode],
  );

  const prices = useApiQuery(
    (signal) =>
      siteCode
        ? fuelPricesApi.postedPrices({ siteCode, inForceOnly: true }, signal)
        : Promise.resolve(undefined),
    [siteCode],
  );

  const rows = useMemo(() => prices.data ?? [], [prices.data]);

  /**
   * Approved providers with no price in force.
   *
   * <p>The number that matters on this panel. Every one of these is a provider a driver may buy from
   * and whose price nobody is checking, which is precisely the hole the control exists to close.
   */
  const uncovered = useMemo(() => {
    const covered = new Set(rows.map((price) => price.vendor.toUpperCase()));
    return (providers.data ?? []).filter((vendor) => !covered.has(vendor.toUpperCase()));
  }, [providers.data, rows]);

  const columns = useMemo<Column<FuelPostedPrice>[]>(
    () => [
      {
        key: 'vendor',
        header: 'Provider',
        width: 200,
        cell: (row) => <CellStack primary={row.vendor} secondary={row.fuelProduct} />,
      },
      {
        key: 'price',
        header: 'Price per litre',
        width: 160,
        cell: (row) => (
          <span className="font-semibold text-gray-900">
            {formatUnitPrice(row.unitPrice, row.currency)}
          </span>
        ),
      },
      {
        key: 'from',
        header: 'In force since',
        width: 160,
        cell: (row) => formatDate(row.effectiveFrom),
      },
      {
        key: 'source',
        header: 'Source',
        width: 160,
        // A price a person typed and a price a provider's feed delivered carry different weight when
        // one is disputed, and after a month nobody can tell them apart from the number alone.
        cell: (row) => <span className="text-theme-xs text-gray-600">{row.source}</span>,
      },
    ],
    [],
  );

  const canManage = permits('FUEL_POLICY_MANAGE');

  return (
    <SectionCard
      title="Posted fuel prices"
      subtitle="What each approved provider charges per litre. A driver records what they paid; this decides the litres."
      actions={
        canManage && (
          <Button size="sm" variant="outline" startIcon="plus" onClick={() => setRecording(true)}>
            Record a price
          </Button>
        )
      }
    >
      <div className="space-y-4">
        {uncovered.length > 0 && (
          <Alert variant="warning" title="Some approved providers have no price on file">
            {uncovered.join(', ')} {uncovered.length === 1 ? 'has' : 'have'} no posted price, so fuel
            bought there is recorded at whatever price per litre the driver enters and nothing checks
            it. Record the forecourt price to close that.
          </Alert>
        )}

        <DataState
          loading={prices.initialising}
          error={prices.error}
          empty={rows.length === 0}
          emptyTitle="No posted prices"
          emptyHint="Until a price is recorded, the price per litre on every fuel capture is taken on trust."
          onRetry={prices.refetch}
          minHeight={140}
        >
          <DataTable
            rows={rows}
            columns={columns}
            getRowId={(row) => row.id}
            loading={prices.loading}
            caption="Fuel prices in force at this site, by provider and product."
            dense
          />
        </DataState>
      </div>

      {recording && (
        <RecordPostedPriceDialog
          open
          siteCode={siteCode}
          providers={providers.data ?? []}
          onClose={() => setRecording(false)}
          onSaved={(price) => {
            notifySuccess(
              `${price.vendor} ${price.fuelProduct} is now ${formatUnitPrice(price.unitPrice, price.currency)} per litre.`,
              'Transactions from this point are checked against it. Earlier ones keep the price that was in force when they happened.',
            );
            prices.refetch();
          }}
        />
      )}
    </SectionCard>
  );
};

interface RecordDialogProps {
  open: boolean;
  siteCode: string;
  providers: string[];
  onClose: () => void;
  onSaved: (price: FuelPostedPrice) => void;
}

/**
 * Records a new price, closing the one it supersedes.
 *
 * <p>The service does the closing, in the same transaction, and that is the part worth understanding
 * before using this: recording a price does not edit history. Transactions that happened under the
 * old price are still judged against the old price, because reconciliation has to reach the same
 * verdict if it is run again next year.
 */
const RecordPostedPriceDialog = ({
  open,
  siteCode,
  providers,
  onClose,
  onSaved,
}: RecordDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      vendor: providers[0] ?? '',
      fuelProduct: 'DIESEL',
      unitPrice: '',
      currency: 'GHS',
      notes: '',
    },
    schema: {
      vendor: required('Provider'),
      fuelProduct: required('Fuel product'),
      unitPrice: compose(required('Price per litre'), positiveNumber('Price per litre')),
      currency: required('Currency'),
      notes: maxLength('Notes', 500),
    },
    onSubmit: async (values) => {
      const saved = await fuelPricesApi.record({
        siteCode,
        vendor: values.vendor.trim(),
        fuelProduct: values.fuelProduct,
        unitPrice: Number(values.unitPrice),
        currency: values.currency,
        // Omitted: the service stamps it now, which is what "the price changed today" means. A
        // backdated price would have to close an already-closed period, so it is a separate job.
        source: 'ADMINISTERED',
        notes: values.notes.trim() || null,
      });
      onSaved(saved);
      onClose();
    },
  });

  return (
    <FormDialog
      open={open}
      title="Record a posted price"
      description="The price on the forecourt sign. It takes effect now and closes whatever price it replaces."
      submitLabel="Record price"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={form.submit}
    >
      <div className={twoColumn}>
        <SelectInput
          label="Provider"
          required
          value={form.values.vendor}
          options={providers.map((vendor) => ({ value: vendor, label: vendor }))}
          onChange={(value) => form.setValue('vendor', value)}
          {...form.fieldProps(
            'vendor',
            providers.length === 0
              ? 'This site’s policy approves no named vendors, so there is nothing to price.'
              : 'Only providers the fuel policy approves.',
          )}
        />
        <EnumSelect
          label="Fuel product"
          required
          value={form.values.fuelProduct as (typeof FUEL_PRODUCTS)[number]}
          options={FUEL_PRODUCTS}
          onChange={(value) => form.setValue('fuelProduct', value || 'DIESEL')}
          {...form.fieldProps('fuelProduct')}
        />
        <NumberInput
          label="Price per litre"
          required
          step={0.0001}
          value={form.values.unitPrice}
          onChange={(value) => form.setValue('unitPrice', value)}
          {...form.fieldProps('unitPrice')}
        />
        <EnumSelect
          label="Currency"
          required
          value={form.values.currency as (typeof CURRENCIES)[number]}
          options={CURRENCIES}
          onChange={(value) => form.setValue('currency', value || 'GHS')}
          renderOptionLabel={(option) => option}
          {...form.fieldProps('currency')}
        />
      </div>

      <TextAreaInput
        label="Notes"
        rows={2}
        value={form.values.notes}
        onChange={(value) => form.setValue('notes', value)}
        {...form.fieldProps('notes', 'Where the figure came from - a price circular, the sign, a call.')}
      />

      <Alert variant="info" title="What this changes">
        Fuel captured from now on at this provider is recorded at this price, and the driver cannot
        change it. Anything more than the policy’s cost variance tolerance away from it raises a price
        deviation anomaly. Transactions already recorded keep the price in force when they happened.
      </Alert>
    </FormDialog>
  );
};

export default PostedPricePanel;
