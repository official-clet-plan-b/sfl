import { useMemo, useState } from 'react';
import {
  Banner,
  Button,
  Dropdown,
  PageSection,
  SectionActions,
  SectionHeader,
  SectionTitle,
  useTableState,
  type TableColumn,
} from '@rfdtech/components';
import { Plus, RefreshCw } from 'lucide-react';
import type { FuelCard } from 'modules/fuel/api/dto';
import { FUEL_CARD_STATUSES, type FuelCardStatus } from 'modules/fuel/api/enums';
import { fuelCardsApi } from 'modules/fuel/api/fuelApi';
import { useClampPage, useRegisterPaging } from 'modules/fuel/components/useRegisterPaging';
import { DriverSelect, VehicleSelect } from 'modules/fleet/components/FleetReferenceSelect';
import { shortId, siteOf } from 'modules/fuel/components/fuelFormat';
import {
  CellStack,
  ErrorBanner,
  FuelBadge,
  FuelFormSheet,
  Panel,
  RegisterTable,
} from 'modules/fuel/components/fuelUi';
import {
  DateField,
  NumberField,
  SelectField,
  TextAreaField,
  TextField,
  type SelectOption,
} from 'modules/fuel/components/fuelFields';
import { canManageFuelCards } from 'modules/fleet/api/access';
import { humanise } from 'modules/fleet/api/enums';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDate, formatDateTime, formatNumber, todayIsoDate } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, maxLength, positiveNumber, required } from 'shared/validation/validators';

type CardAction = 'assign' | 'suspend' | 'reinstate' | 'cancel';

const maskedCard = (value: unknown): string | undefined => {
  if (typeof value !== 'string' || value.trim() === '') {
    return undefined;
  }
  const stripped = value.trim();
  if (/\d{12,}/.test(stripped.replace(/\s+/g, ''))) {
    return 'Enter only the masked provider reference, never a full card number.';
  }
  return /^\*{2,}\d{4}$/.test(stripped)
    ? undefined
    : 'Use the masked form from the provider, e.g. ****1234.';
};

const asNumber = (value: string): number | null => (value === '' ? null : Number(value));

const GHANA_FUEL_CARD_PROVIDERS: SelectOption[] = [
  { value: 'GOIL GHANA', label: 'GOIL Ghana' },
  { value: 'TOTALENERGIES GHANA', label: 'TotalEnergies Ghana' },
  { value: 'SHELL / VIVO ENERGY GHANA', label: 'Shell / Vivo Energy Ghana' },
  { value: 'PUMA ENERGY GHANA', label: 'Puma Energy Ghana' },
  { value: 'STAR OIL GHANA', label: 'Star Oil Ghana' },
  { value: 'ZEN PETROLEUM', label: 'Zen Petroleum' },
  { value: 'ENGEN GHANA', label: 'Engen Ghana' },
  { value: 'ALLIED OIL GHANA', label: 'Allied Oil Ghana' },
  { value: 'FRIMPS OIL', label: 'Frimps Oil' },
  { value: 'CLET FUEL CARDS', label: 'CLET Fuel Cards / internal demo' },
];

const demoMaskedReference = (): string => `****${String(Date.now()).slice(-4)}`;

/**
 * S168 fuel-card register.
 *
 * The backend already stores only masked references; this page keeps the same rule in the browser.
 * Managers can issue and move cards through their lifecycle, while read-only operators can still
 * see why reconciliation treats a card transaction as allowed, mismatched, over-limit or unknown.
 */
const FuelCardsPage = () => {
  const { notifySuccess, notifyError } = useNotifier();
  const [siteCode, setSiteCode] = useState(defaultSite);
  // Seeded from the URL so a reload or a shared link restores the filter the table's own state says
  // is applied.
  const { filters } = useTableState({ paramPrefix: 'fuel-cards' });
  const [status, setStatus] = useState<FuelCardStatus | ''>((filters.status as FuelCardStatus) ?? '');
  const [selected, setSelected] = useState<FuelCard | null>(null);
  const [issuing, setIssuing] = useState(false);
  const [action, setAction] = useState<{ card: FuelCard; action: CardAction } | null>(null);

  const mayManage = canManageFuelCards();
  const filterKey = `${siteCode}|${status}`;
  const paging = useRegisterPaging('fuel-cards', filterKey);

  const query = useApiQuery(
    (signal) =>
      fuelCardsApi.search(
        {
          siteCode,
          status,
          maskedReference: paging.search || undefined,
          page: paging.page,
          size: paging.size,
        },
        signal,
      ),
    [filterKey, paging.search, paging.page, paging.size],
  );

  useClampPage(paging.page, query.data?.totalPages, paging.setPage);

  const cards = useMemo(() => query.data?.content ?? [], [query.data]);
  const activeCards = cards.filter((card) => card.status === 'ACTIVE').length;

  const columns = useMemo<TableColumn<FuelCard>[]>(
    () => [
      {
        id: 'card',
        header: 'Card',
        minWidth: 200,
        cell: ({ row }) => (
          <CellStack
            primary={row.maskedReference}
            secondary={`${row.provider} · ${siteOf(row.siteCode)}`}
          />
        ),
      },
      {
        id: 'assignment',
        header: 'Assigned to',
        minWidth: 160,
        cell: ({ row }) => (
          <CellStack
            primary={row.vehicleId ? `Vehicle ${shortId(row.vehicleId)}` : 'No vehicle'}
            secondary={row.driverId ? `Driver ${shortId(row.driverId)}` : 'No driver'}
          />
        ),
      },
      {
        id: 'limits',
        header: 'Limits',
        minWidth: 200,
        cell: ({ row }) => (
          <CellStack
            primary={`Txn ${row.perTransactionLimit === null ? 'policy' : formatNumber(row.perTransactionLimit)}`}
            secondary={`Daily ${row.dailyLimit === null ? 'policy' : formatNumber(row.dailyLimit)} · Monthly ${
              row.monthlyLimit === null ? 'policy' : formatNumber(row.monthlyLimit)
            }`}
          />
        ),
      },
      {
        id: 'expiry',
        header: 'Validity',
        minWidth: 150,
        cell: ({ row }) => (
          <CellStack
            primary={`Issued ${formatDate(row.issuedOn)}`}
            secondary={row.expiresOn ? `Expires ${formatDate(row.expiresOn)}` : 'No expiry'}
          />
        ),
      },
      {
        id: 'status',
        header: 'Status',
        width: 130,
        align: 'right',
        cell: ({ row }) => <FuelBadge value={row.status} />,
      },
    ],
    [],
  );

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Fuel cards</SectionTitle>
          <SectionActions>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            <Button variant="outline" onClick={query.refetch}>
              <RefreshCw size={14} strokeWidth={1.5} aria-hidden />
              Refresh
            </Button>
            {mayManage && (
              <Button variant="primary" onClick={() => setIssuing(true)}>
                <Plus size={14} strokeWidth={1.5} aria-hidden />
                Issue card
              </Button>
            )}
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <div className="grid gap-6 xl:grid-cols-[1.4fr_0.9fr]">
          <Panel
            title="Card register"
            description={`${formatNumber(query.data?.totalElements ?? 0)} cards · ${formatNumber(activeCards)} active on this page`}
          >
            {query.error && <ErrorBanner error={query.error} onRetry={query.refetch} className="mb-4" />}
            <RegisterTable
              paramPrefix="fuel-cards"
              columns={columns}
              rows={cards}
              rowKey={(row) => row.id}
              loading={query.loading}
              onRowClick={setSelected}
              empty={{
                title: 'None issued',
                description: 'Issue a card or clear the filters. Only masked references are stored.',
                filteredTitle: 'No fuel cards match the filter',
              }}
              filtersApplied={Boolean(status || paging.search)}
              totalPages={query.data?.totalPages ?? 0}
              totalItems={query.data?.totalElements ?? 0}
              pageSize={paging.size}
              searchPlaceholder="Search masked reference"
              spreadFilters
              onResetFilters={() => setStatus('')}
              filters={
                <Dropdown
                  name="status"
                  aria-label="Filter by status"
                  value={status || null}
                  onValueChange={(next) => setStatus((next ?? '') as FuelCardStatus | '')}
                  options={FUEL_CARD_STATUSES.map((value) => ({ value, label: humanise(value) }))}
                  placeholder="Status: All"
                  clearable
                />
              }
            />
          </Panel>

          <Panel
            title={selected ? selected.maskedReference : 'Card detail'}
            description={
              selected
                ? 'The assignment and ceilings reconciliation reads.'
                : 'Select a card to inspect or manage it.'
            }
            actions={selected ? <FuelBadge value={selected.status} /> : undefined}
          >
            {selected ? (
              <div className="space-y-4">
                <KeyValueGrid
                  columns={2}
                  items={[
                    { label: 'Provider', value: selected.provider },
                    { label: 'Status', value: humanise(selected.status) },
                    { label: 'Site', value: siteOf(selected.siteCode) },
                    { label: 'Issued on', value: formatDate(selected.issuedOn) },
                    { label: 'Expires on', value: selected.expiresOn ? formatDate(selected.expiresOn) : 'No expiry' },
                    { label: 'Vehicle', value: selected.vehicleId ? shortId(selected.vehicleId) : 'Not assigned' },
                    { label: 'Driver', value: selected.driverId ? shortId(selected.driverId) : 'Not assigned' },
                    {
                      label: 'Transaction limit',
                      value:
                        selected.perTransactionLimit === null
                          ? 'Policy fallback'
                          : formatNumber(selected.perTransactionLimit),
                    },
                    {
                      label: 'Daily limit',
                      value: selected.dailyLimit === null ? 'Policy fallback' : formatNumber(selected.dailyLimit),
                    },
                    {
                      label: 'Monthly limit',
                      value:
                        selected.monthlyLimit === null ? 'Policy fallback' : formatNumber(selected.monthlyLimit),
                    },
                    { label: 'Last changed', value: formatDateTime(selected.metadata.lastModifiedAt) },
                    { label: 'Version', value: selected.metadata.version },
                  ]}
                />

                {selected.suspensionReason && (
                  <Banner variant="warning" heading="Lifecycle reason" subtext={selected.suspensionReason} />
                )}

                {selected.notes && (
                  <div className="rounded-lg bg-(--clet-surface-subtle) px-4 py-3 text-sm">
                    {selected.notes}
                  </div>
                )}

                {mayManage ? (
                  <div className="flex flex-wrap gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={selected.status === 'CANCELLED'}
                      onClick={() => setAction({ card: selected, action: 'assign' })}
                    >
                      Assign
                    </Button>
                    {selected.status !== 'SUSPENDED' && (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={selected.status === 'CANCELLED'}
                        onClick={() => setAction({ card: selected, action: 'suspend' })}
                      >
                        Suspend
                      </Button>
                    )}
                    {selected.status === 'SUSPENDED' && (
                      <Button
                        size="sm"
                        variant="success"
                        onClick={() => setAction({ card: selected, action: 'reinstate' })}
                      >
                        Reinstate
                      </Button>
                    )}
                    <Button
                      size="sm"
                      variant="destructive"
                      disabled={selected.status === 'CANCELLED'}
                      onClick={() => setAction({ card: selected, action: 'cancel' })}
                    >
                      Cancel
                    </Button>
                  </div>
                ) : (
                  <Banner
                    variant="info"
                    heading="Read-only"
                    subtext="You can inspect the card register, but issuing and lifecycle changes require FUEL_CARD_MANAGE."
                  />
                )}
              </div>
            ) : (
              <Banner
                variant="info"
                heading="No full card numbers"
                subtext="SFL stores and displays only the provider-masked reference, such as ****1234. The card platform remains outside SFL."
              />
            )}
          </Panel>
        </div>
      </PageSection>

      {issuing && (
        <IssueCardDialog
          open
          defaultSiteCode={siteCode}
          onClose={() => setIssuing(false)}
          onSaved={(card) => {
            notifySuccess(`${card.maskedReference} issued.`, 'The card is now available to reconciliation.');
            setSelected(card);
            query.refetch();
          }}
        />
      )}

      {action && (
        <CardActionDialog
          open
          card={action.card}
          action={action.action}
          onClose={() => setAction(null)}
          onSaved={(card) => {
            notifySuccess(`${card.maskedReference} ${pastTense(action.action)}.`);
            setSelected(card);
            query.refetch();
          }}
          onError={notifyError}
        />
      )}
    </>
  );
};

interface IssueCardDialogProps {
  open: boolean;
  defaultSiteCode: string;
  onClose: () => void;
  onSaved: (card: FuelCard) => void;
}

const IssueCardDialog = ({ open, defaultSiteCode, onClose, onSaved }: IssueCardDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      siteCode: defaultSiteCode,
      maskedReference: demoMaskedReference(),
      provider: 'GOIL GHANA',
      vehicleId: '',
      driverId: '',
      issuedOn: todayIsoDate(),
      expiresOn: '',
      dailyLimit: '',
      monthlyLimit: '',
      perTransactionLimit: '',
      notes: '',
    },
    schema: {
      siteCode: required('Site'),
      maskedReference: compose(required('Masked reference'), maskedCard),
      provider: compose(required('Provider'), maxLength('Provider', 160)),
      dailyLimit: positiveNumber('Daily limit'),
      monthlyLimit: positiveNumber('Monthly limit'),
      perTransactionLimit: positiveNumber('Transaction limit'),
      notes: maxLength('Notes', 500),
    },
    crossFieldValidate: (values) =>
      values.expiresOn && values.issuedOn && values.expiresOn < values.issuedOn
        ? { expiresOn: 'Expiry cannot be before the issue date.' }
        : {},
    onSubmit: async (values) => {
      const card = await fuelCardsApi.issue({
        siteCode: values.siteCode.trim().toUpperCase(),
        maskedReference: values.maskedReference.trim(),
        provider: values.provider.trim(),
        vehicleId: values.vehicleId || null,
        driverId: values.driverId || null,
        issuedOn: values.issuedOn || null,
        expiresOn: values.expiresOn || null,
        dailyLimit: asNumber(values.dailyLimit),
        monthlyLimit: asNumber(values.monthlyLimit),
        perTransactionLimit: asNumber(values.perTransactionLimit),
        notes: values.notes.trim() || null,
      });
      onSaved(card);
      onClose();
    },
  });

  return (
    <FuelFormSheet
      open={open}
      title="Issue a fuel card"
      description="Records a card the provider has issued, from a safe masked reference. Never type a full card number here."
      submitLabel="Issue card"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="md"
      onClose={onClose}
      onSubmit={form.submit}
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <SelectField
          label="Provider"
          required
          value={form.values.provider}
          onChange={(value) => form.setValue('provider', value)}
          options={GHANA_FUEL_CARD_PROVIDERS}
          {...form.fieldProps('provider')}
        />
        <div>
          <TextField
            label="Masked reference"
            required
            placeholder="****1234"
            value={form.values.maskedReference}
            onChange={(value) => form.setValue('maskedReference', value)}
            {...form.fieldProps('maskedReference', 'Never enter the full card number')}
          />
          <Button
            size="sm"
            variant="ghost"
            className="mt-1"
            onClick={() => form.setValue('maskedReference', demoMaskedReference())}
          >
            <RefreshCw size={14} strokeWidth={1.5} aria-hidden />
            Generate demo reference
          </Button>
        </div>
        <VehicleSelect
          siteCode={form.values.siteCode}
          value={form.values.vehicleId}
          onChange={(value) => form.setValue('vehicleId', value)}
          allowEmpty
          emptyLabel="No vehicle"
          {...form.fieldProps('vehicleId', 'Optional at issue. Assign later if needed.')}
        />
        <SiteSelect
          required
          value={form.values.siteCode}
          onChange={(value) => form.setValues({ siteCode: value, vehicleId: '', driverId: '' })}
          {...form.fieldProps('siteCode')}
        />
        <DriverSelect
          siteCode={form.values.siteCode}
          value={form.values.driverId}
          onChange={(value) => form.setValue('driverId', value)}
          allowEmpty
          emptyLabel="No driver"
          {...form.fieldProps('driverId', 'Optional at issue.')}
        />
        <DateField
          label="Issued on"
          value={form.values.issuedOn}
          onChange={(value) => form.setValue('issuedOn', value)}
          {...form.fieldProps('issuedOn')}
        />
        <DateField
          label="Expires on"
          value={form.values.expiresOn}
          onChange={(value) => form.setValue('expiresOn', value)}
          {...form.fieldProps('expiresOn', 'An expired card is not usable.')}
        />
      </div>

      <p className="-mb-2 text-sm font-semibold">Limits</p>
      <div className="grid gap-4 sm:grid-cols-2">
        <NumberField
          label="Per transaction"
          step={0.01}
          value={form.values.perTransactionLimit}
          onChange={(value) => form.setValue('perTransactionLimit', value)}
          {...form.fieldProps('perTransactionLimit', 'Blank means the site policy applies.')}
        />
        <NumberField
          label="Daily"
          step={0.01}
          value={form.values.dailyLimit}
          onChange={(value) => form.setValue('dailyLimit', value)}
          {...form.fieldProps('dailyLimit', 'Blank means the site policy applies.')}
        />
        <NumberField
          label="Monthly"
          step={0.01}
          value={form.values.monthlyLimit}
          onChange={(value) => form.setValue('monthlyLimit', value)}
          {...form.fieldProps('monthlyLimit', 'Blank means the site policy applies.')}
        />
      </div>

      <TextAreaField
        label="Notes"
        rows={3}
        value={form.values.notes}
        onChange={(value) => form.setValue('notes', value)}
        {...form.fieldProps('notes')}
      />

      <Banner
        variant="info"
        heading="SFL only holds the masked reference. The card platform stays with the provider."
        subtext="Live card references come from the fuel-card provider feed, already masked. Full card numbers are refused before they leave the browser."
      />
    </FuelFormSheet>
  );
};

interface CardActionDialogProps {
  open: boolean;
  card: FuelCard;
  action: CardAction;
  onClose: () => void;
  onSaved: (card: FuelCard) => void;
  onError: (error: unknown) => void;
}

const CardActionDialog = ({ open, card, action, onClose, onSaved, onError }: CardActionDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      vehicleId: card.vehicleId ?? '',
      driverId: card.driverId ?? '',
      reason: '',
    },
    schema: {
      reason:
        action === 'suspend' || action === 'cancel'
          ? compose(required('Reason'), maxLength('Reason', 500))
          : maxLength('Reason', 500),
    },
    onSubmit: async (values) => {
      try {
        const saved = await fuelCardsApi.transition(card.id, action, {
          vehicleId: action === 'assign' ? values.vehicleId || null : null,
          driverId: action === 'assign' ? values.driverId || null : null,
          reason: values.reason.trim() || null,
        });
        onSaved(saved);
        onClose();
      } catch (error) {
        onError(error);
        throw error;
      }
    },
  });

  const destructive = action === 'cancel' || action === 'suspend';

  return (
    <FuelFormSheet
      open={open}
      title={`${humanise(action)} ${card.maskedReference}`}
      description={actionDescription(action)}
      submitLabel={actionLabel(action)}
      submitting={form.submitting}
      formError={form.formError}
      destructive={destructive}
      maxWidth="sm"
      onClose={onClose}
      onSubmit={form.submit}
    >
      {action === 'assign' ? (
        <div className="grid gap-4">
          <VehicleSelect
            siteCode={siteOf(card.siteCode)}
            value={form.values.vehicleId}
            onChange={(value) => form.setValue('vehicleId', value)}
            allowEmpty
            emptyLabel="No vehicle"
            {...form.fieldProps('vehicleId')}
          />
          <DriverSelect
            siteCode={siteOf(card.siteCode)}
            value={form.values.driverId}
            onChange={(value) => form.setValue('driverId', value)}
            allowEmpty
            emptyLabel="No driver"
            {...form.fieldProps('driverId')}
          />
        </div>
      ) : action === 'reinstate' ? (
        <Banner
          variant="info"
          heading="The card will become active again"
          subtext="Reinstatement clears the suspension reason. Historic transactions remain tied to this same masked card row."
        />
      ) : (
        <TextAreaField
          label="Reason"
          required
          rows={4}
          autoFocus
          value={form.values.reason}
          onChange={(value) => form.setValue('reason', value)}
          {...form.fieldProps('reason')}
        />
      )}
    </FuelFormSheet>
  );
};

const actionDescription = (action: CardAction): string =>
  ({
    assign: 'Updates the vehicle and driver assignment used by reconciliation.',
    suspend: 'Temporarily blocks the card. Reconciliation treats suspended cards as not usable.',
    reinstate: 'Returns a suspended card to active use.',
    cancel: 'Cancels the card permanently. The row stays for audit and historic transaction matching.',
  })[action];

const actionLabel = (action: CardAction): string =>
  ({
    assign: 'Save assignment',
    suspend: 'Suspend card',
    reinstate: 'Reinstate card',
    cancel: 'Cancel card',
  })[action];

const pastTense = (action: CardAction): string =>
  ({
    assign: 'assigned',
    suspend: 'suspended',
    reinstate: 'reinstated',
    cancel: 'cancelled',
  })[action];

export default FuelCardsPage;
