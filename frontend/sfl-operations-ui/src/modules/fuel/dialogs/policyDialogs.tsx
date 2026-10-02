import { FuelPolicy } from 'modules/fuel/api/dto';
import { fuelPoliciesApi } from 'modules/fuel/api/fuelApi';
import { Alert, FormDialog } from 'modules/fuel/components/fuelUi';
import SiteSelect from 'shared/components/SiteSelect';
import { DateTimeField, CheckboxField as Checkbox, NumberInput, TextAreaInput, TextInput } from 'modules/fuel/components/fuelUi';
import { useFleetForm, type FleetForm } from 'shared/validation/useFleetForm';
import {
  compose,
  dateRangeError,
  integerAtLeast,
  maxLength,
  nonNegativeNumber,
  positiveNumber,
  required,
  validDateTime,
} from 'shared/validation/validators';

const twoColumn = 'grid gap-4 sm:grid-cols-2';
const threeColumn = 'grid gap-4 sm:grid-cols-3';

/** `datetime-local` text to the ISO instant the service expects; blank stays blank. */
const toInstant = (value: string): string | null =>
  value ? new Date(value).toISOString() : null;

/** Comma-separated entry to the `Set<String>` the request takes. Upper-cased, as the domain does. */
const toSet = (value: string): string[] =>
  value
    .split(',')
    .map((entry) => entry.trim().toUpperCase())
    .filter(Boolean);

/**
 * An ISO instant back to the `YYYY-MM-DDTHH:mm` a `DateTimeField` holds.
 *
 * <p>The inverse of {@link toInstant}, needed only by the edit form: a stored policy carries UTC
 * instants and the field speaks local wall-clock, so prefilling with the raw string would show the
 * wrong hour and then save it.
 */
const toLocalDateTime = (instant: string | null): string => {
  if (!instant) {
    return '';
  }
  const at = new Date(instant);
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}T${pad(at.getHours())}:${pad(at.getMinutes())}`;
};

/** The form's own shape. One declaration so create and edit cannot drift apart. */
interface PolicyValues {
  siteCode: string;
  name: string;
  effectiveFrom: string;
  effectiveTo: string;
  policyVersion: string;
  maxPerTransaction: string;
  dailyLimit: string;
  monthlyLimit: string;
  tankCapacity: string;
  minConsumption: string;
  maxConsumption: string;
  odometerJumpTolerance: string;
  receiptRequired: boolean;
  receiptGraceHours: string;
  materialityAmount: string;
  anomalySlaHours: string;
  costVarianceTolerance: string;
  repeatedPatternWindowHours: string;
  repeatedPatternThreshold: string;
  allowedFuelProducts: string;
  approvedVendors: string;
}

const emptyPolicy = (siteCode: string): PolicyValues => ({
  siteCode,
  name: '',
  effectiveFrom: '',
  effectiveTo: '',
  policyVersion: '1',
  maxPerTransaction: '',
  dailyLimit: '',
  monthlyLimit: '',
  tankCapacity: '',
  minConsumption: '',
  maxConsumption: '',
  odometerJumpTolerance: '500',
  receiptRequired: true,
  receiptGraceHours: '24',
  materialityAmount: '',
  anomalySlaHours: '24',
  costVarianceTolerance: '0.30',
  repeatedPatternWindowHours: '720',
  repeatedPatternThreshold: '3',
  allowedFuelProducts: '',
  approvedVendors: '',
});

/** A stored policy back into the form. `?? ''` throughout: a blank limit is a limit not applied. */
const valuesOf = (policy: FuelPolicy): PolicyValues => ({
  // `SiteCodeValue` is a branded string; the form holds a plain one because the edit dialog never
  // sends it back.
  siteCode: policy.siteCode.value,
  name: policy.name,
  effectiveFrom: toLocalDateTime(policy.effectiveFrom),
  effectiveTo: toLocalDateTime(policy.effectiveTo),
  policyVersion: String(policy.policyVersion),
  maxPerTransaction: String(policy.maxPerTransaction ?? ''),
  dailyLimit: policy.dailyLimit == null ? '' : String(policy.dailyLimit),
  monthlyLimit: policy.monthlyLimit == null ? '' : String(policy.monthlyLimit),
  tankCapacity: policy.tankCapacity == null ? '' : String(policy.tankCapacity),
  minConsumption: policy.minConsumption == null ? '' : String(policy.minConsumption),
  maxConsumption: policy.maxConsumption == null ? '' : String(policy.maxConsumption),
  odometerJumpTolerance: String(policy.odometerJumpTolerance ?? 0),
  receiptRequired: policy.receiptRequired,
  receiptGraceHours: String(policy.receiptGraceHours ?? 0),
  materialityAmount: String(policy.materialityAmount ?? ''),
  anomalySlaHours: String(policy.anomalySlaHours ?? 24),
  costVarianceTolerance: String(policy.costVarianceTolerance ?? '0.30'),
  repeatedPatternWindowHours: String(policy.repeatedPatternWindowHours ?? 720),
  repeatedPatternThreshold: String(policy.repeatedPatternThreshold ?? 3),
  allowedFuelProducts: (policy.allowedFuelProducts ?? []).join(', '),
  approvedVendors: (policy.approvedVendors ?? []).join(', '),
});

/** Everything the service takes except the site, which only creation supplies. */
const toRequestBody = (values: PolicyValues) => ({
  name: values.name.trim(),
  effectiveFrom: toInstant(values.effectiveFrom) as string,
  effectiveTo: toInstant(values.effectiveTo),
  policyVersion: Number(values.policyVersion),
  maxPerTransaction: Number(values.maxPerTransaction),
  dailyLimit: values.dailyLimit === '' ? null : Number(values.dailyLimit),
  monthlyLimit: values.monthlyLimit === '' ? null : Number(values.monthlyLimit),
  tankCapacity: values.tankCapacity === '' ? null : Number(values.tankCapacity),
  minConsumption: values.minConsumption === '' ? null : Number(values.minConsumption),
  maxConsumption: values.maxConsumption === '' ? null : Number(values.maxConsumption),
  odometerJumpTolerance: Number(values.odometerJumpTolerance),
  receiptRequired: values.receiptRequired,
  receiptGraceHours: Number(values.receiptGraceHours),
  materialityAmount: Number(values.materialityAmount),
  anomalySlaHours: Number(values.anomalySlaHours),
  costVarianceTolerance: Number(values.costVarianceTolerance),
  repeatedPatternWindowHours: Number(values.repeatedPatternWindowHours),
  repeatedPatternThreshold: Number(values.repeatedPatternThreshold),
  allowedFuelProducts: toSet(values.allowedFuelProducts),
  approvedVendors: toSet(values.approvedVendors),
});

/**
 * The validation rules, shared.
 *
 * <p>Declared once and used by both forms because a rule that holds on creation and not on edit is
 * a rule the register can be walked around: create a valid policy, then edit it into an invalid one.
 */
const policySchema = {
  siteCode: required('Site code'),
  name: compose(required('Policy name'), maxLength('Policy name', 160)),
  effectiveFrom: compose(required('Effective from'), validDateTime('Effective from')),
  effectiveTo: validDateTime('Effective to'),
  policyVersion: compose(required('Policy version'), integerAtLeast('Policy version', 1)),
  maxPerTransaction: compose(
    required('Maximum per transaction'),
    positiveNumber('Maximum per transaction'),
  ),
  dailyLimit: nonNegativeNumber('Daily limit'),
  monthlyLimit: nonNegativeNumber('Monthly limit'),
  tankCapacity: nonNegativeNumber('Tank capacity'),
  minConsumption: nonNegativeNumber('Minimum consumption'),
  maxConsumption: nonNegativeNumber('Maximum consumption'),
  odometerJumpTolerance: compose(
    required('Odometer jump tolerance'),
    integerAtLeast('Odometer jump tolerance', 0),
  ),
  receiptGraceHours: compose(
    required('Receipt grace period'),
    integerAtLeast('Receipt grace period', 0),
  ),
  materialityAmount: compose(
    required('Materiality amount'),
    nonNegativeNumber('Materiality amount'),
  ),
  anomalySlaHours: compose(required('Anomaly SLA'), integerAtLeast('Anomaly SLA', 1)),
  costVarianceTolerance: compose(
    required('Cost variance tolerance'),
    nonNegativeNumber('Cost variance tolerance'),
  ),
  repeatedPatternWindowHours: compose(
    required('Repeated-pattern window'),
    integerAtLeast('Repeated-pattern window', 1),
  ),
  repeatedPatternThreshold: compose(
    required('Repeated-pattern threshold'),
    integerAtLeast('Repeated-pattern threshold', 1),
  ),
};

/**
 * The fields that decide an outcome, and therefore the ones that need a new version when they move.
 *
 * <p>Mirrors `FuelPolicy.hasSameRulesAs` on the service, which is the authority - this copy exists
 * so the operator is told on the field rather than by a refusal. Name and the effective period are
 * absent from both: renaming a policy does not change how it judges, and the period decides which
 * policy applies rather than what it does.
 */
const RULE_FIELDS = [
  'maxPerTransaction',
  'dailyLimit',
  'monthlyLimit',
  'tankCapacity',
  'minConsumption',
  'maxConsumption',
  'odometerJumpTolerance',
  'receiptRequired',
  'receiptGraceHours',
  'materialityAmount',
  'anomalySlaHours',
  'costVarianceTolerance',
  'repeatedPatternWindowHours',
  'repeatedPatternThreshold',
  'allowedFuelProducts',
  'approvedVendors',
] as const satisfies readonly (keyof PolicyValues)[];

/**
 * Whether any rule field differs, comparing numbers as numbers.
 *
 * <p>`'50'` and `'50.00'` are the same ceiling and a string comparison calls them different, which
 * would demand a version bump for an edit that changed nothing. The service compares with
 * `BigDecimal.compareTo` for the same reason.
 */
const rulesDiffer = (original: PolicyValues, current: PolicyValues): boolean =>
  RULE_FIELDS.some((field) => {
    const before = original[field];
    const after = current[field];
    if (typeof before === 'boolean' || typeof after === 'boolean') {
      return before !== after;
    }
    const beforeText = String(before).trim();
    const afterText = String(after).trim();
    if (beforeText === afterText) {
      return false;
    }
    const beforeNumber = Number(beforeText);
    const afterNumber = Number(afterText);
    if (beforeText !== '' && afterText !== '' && !Number.isNaN(beforeNumber) && !Number.isNaN(afterNumber)) {
      return beforeNumber !== afterNumber;
    }
    return true;
  });

const policyCrossFieldValidate = (values: PolicyValues): Record<string, string> => {
  const errors: Record<string, string> = {};
  // `FuelPolicy` refuses `effectiveTo` that does not strictly follow `effectiveFrom`.
  const range = dateRangeError(
    values.effectiveFrom,
    values.effectiveTo,
    'Effective from',
    'Effective to',
  );
  if (range) {
    errors.effectiveTo = range;
  }
  // The consumption range is only used when both bounds are set (`reconcile` checks for both),
  // and an inverted pair would make CONSUMPTION_RANGE fail every transaction.
  if (values.minConsumption && values.maxConsumption) {
    if (Number(values.minConsumption) > Number(values.maxConsumption)) {
      errors.maxConsumption = 'Maximum consumption must be at least the minimum.';
    }
  }
  return errors;
};

interface CreatePolicyDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: (policy: FuelPolicy) => void;
  defaultSiteCode: string;
}

/**
 * Create a fuel policy - `POST /api/v1/fuel/policies`.
 *
 * Every field on `PolicyRequest` is here, in the order the rules read them: identity and period,
 * then the limits reconciliation checks, then the exception settings. The four primitives
 * (`policyVersion`, `odometerJumpTolerance`, `receiptGraceHours`, `anomalySlaHours`) always send a
 * number - sending `null` for a Java `int`/`long` fails deserialisation *before* Bean Validation
 * runs, and the operator gets a Jackson message instead of a field error.
 */
export const CreatePolicyDialog = ({
  open,
  onClose,
  onSaved,
  defaultSiteCode,
}: CreatePolicyDialogProps) => {
  const form = useFleetForm<PolicyValues>({
    initialValues: emptyPolicy(defaultSiteCode),
    schema: policySchema,
    crossFieldValidate: policyCrossFieldValidate,
    onSubmit: async (values) => {
      const saved = await fuelPoliciesApi.create({
        siteCode: values.siteCode.trim().toUpperCase(),
        ...toRequestBody(values),
      });
      onSaved(saved);
      onClose();
    },
  });

  return (
    <FormDialog
      open={open}
      title="Create a fuel policy"
      description="Reconciliation reads the policy that was in effect when the transaction occurred, so the period matters as much as the limits."
      submitLabel="Create policy"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={form.submit}
    >
      <Alert variant="info" title="Periods may not overlap">
        The service refuses a policy whose period overlaps an active one for this site, and names the
        policies it clashes with. Two active policies covering one instant would make the rules a
        transaction is judged against depend on which row the query returned.
      </Alert>

      <div className={twoColumn}>
        <SiteSelect
          required
          value={form.values.siteCode}
          onChange={(value) => form.setValue('siteCode', value)}
          {...form.fieldProps('siteCode')}
        />
      </div>

      <PolicyFields form={form} />
    </FormDialog>
  );
};

/**
 * Every rule on a policy, in the order reconciliation reads them.
 *
 * <p>One component shared by creation and revision. It was inline in the create dialog, which is
 * why there was no edit form: twenty-one fields is enough that a second copy would have been
 * written once and then diverged, and a limit that can be set but not corrected is the reason a
 * register accumulates policies nobody meant to keep.
 *
 * <p>The site is not here. Creation asks for it; an edit may not change it.
 */
const PolicyFields = ({ form }: { form: FleetForm<PolicyValues> }) => (
  <>
    <div className={twoColumn}>
      <TextInput
        label="Policy name"
        required
        value={form.values.name}
        onChange={(value) => form.setValue('name', value)}
        {...form.fieldProps('name')}
      />
      <NumberInput
        label="Policy version"
        required
        min={1}
        value={form.values.policyVersion}
        onChange={(value) => form.setValue('policyVersion', value)}
        {...form.fieldProps('policyVersion', 'Recorded against every reconciliation.')}
      />
      <DateTimeField
          label="Effective from"
          required
          value={form.values.effectiveFrom}
          onChange={(value) => form.setValue('effectiveFrom', value)}
          {...form.fieldProps('effectiveFrom')}
        />
        <DateTimeField
          label="Effective to"
          value={form.values.effectiveTo}
          onChange={(value) => form.setValue('effectiveTo', value)}
          {...form.fieldProps('effectiveTo', 'Leave blank for an open-ended policy.')}
        />
      </div>

      <div className={threeColumn}>
        <NumberInput
          label="Maximum per transaction"
          required
          step={0.001}
          value={form.values.maxPerTransaction}
          onChange={(value) => form.setValue('maxPerTransaction', value)}
          {...form.fieldProps('maxPerTransaction', 'Quantity, in the unit you dispense in.')}
        />
        <NumberInput
          label="Tank capacity"
          step={0.001}
          value={form.values.tankCapacity}
          onChange={(value) => form.setValue('tankCapacity', value)}
          {...form.fieldProps('tankCapacity', 'Optional. Skipped when blank.')}
        />
        <NumberInput
          label="Daily limit"
          step={0.001}
          value={form.values.dailyLimit}
          onChange={(value) => form.setValue('dailyLimit', value)}
          {...form.fieldProps('dailyLimit', 'Checked daily for vehicles, drivers and fuel cards.')}
        />
        <NumberInput
          label="Monthly limit"
          step={0.001}
          value={form.values.monthlyLimit}
          onChange={(value) => form.setValue('monthlyLimit', value)}
          {...form.fieldProps('monthlyLimit', 'Checked monthly for vehicles, drivers and fuel cards.')}
        />
        <NumberInput
          label="Odometer jump tolerance"
          required
          suffix="km"
          value={form.values.odometerJumpTolerance}
          onChange={(value) => form.setValue('odometerJumpTolerance', value)}
          {...form.fieldProps('odometerJumpTolerance')}
        />
        <NumberInput
          label="Minimum consumption"
          step={0.0001}
          value={form.values.minConsumption}
          onChange={(value) => form.setValue('minConsumption', value)}
          {...form.fieldProps('minConsumption', 'Quantity per kilometre.')}
        />
        <NumberInput
          label="Maximum consumption"
          step={0.0001}
          value={form.values.maxConsumption}
          onChange={(value) => form.setValue('maxConsumption', value)}
          {...form.fieldProps('maxConsumption', 'Both bounds are needed for the rule to run.')}
        />
        <NumberInput
          label="Materiality amount"
          required
          step={0.01}
          value={form.values.materialityAmount}
          onChange={(value) => form.setValue('materialityAmount', value)}
          {...form.fieldProps('materialityAmount', 'At or above this, an anomaly is material.')}
        />
      </div>

      <div className={twoColumn}>
        <NumberInput
          label="Receipt grace period"
          required
          suffix="hrs"
          value={form.values.receiptGraceHours}
          onChange={(value) => form.setValue('receiptGraceHours', value)}
          {...form.fieldProps('receiptGraceHours')}
        />
        <NumberInput
          label="Anomaly SLA"
          required
          min={1}
          suffix="hrs"
          value={form.values.anomalySlaHours}
          onChange={(value) => form.setValue('anomalySlaHours', value)}
          {...form.fieldProps('anomalySlaHours', 'Time to resolve before the sweep escalates.')}
        />
        <NumberInput
          label="Cost variance tolerance"
          required
          min={0}
          step={0.01}
          value={form.values.costVarianceTolerance}
          onChange={(value) => form.setValue('costVarianceTolerance', value)}
          {...form.fieldProps(
            'costVarianceTolerance',
            'Decimal tolerance, e.g. 0.30 means 30% from the previous unit price.',
          )}
        />
        <NumberInput
          label="Repeated-pattern window"
          required
          min={1}
          suffix="hrs"
          value={form.values.repeatedPatternWindowHours}
          onChange={(value) => form.setValue('repeatedPatternWindowHours', value)}
          {...form.fieldProps('repeatedPatternWindowHours', 'Look-back window for recent anomalies.')}
        />
        <NumberInput
          label="Repeated-pattern threshold"
          required
          min={1}
          value={form.values.repeatedPatternThreshold}
          onChange={(value) => form.setValue('repeatedPatternThreshold', value)}
          {...form.fieldProps(
            'repeatedPatternThreshold',
            'The next anomaly at or above this count triggers repeated-pattern handling.',
          )}
        />
        <TextInput
          label="Allowed fuel products"
          value={form.values.allowedFuelProducts}
          onChange={(value) => form.setValue('allowedFuelProducts', value)}
          {...form.fieldProps(
            'allowedFuelProducts',
            'Comma separated. Leave blank to allow any product.',
          )}
        />
        <TextInput
          label="Approved vendors"
          value={form.values.approvedVendors}
          onChange={(value) => form.setValue('approvedVendors', value)}
          {...form.fieldProps('approvedVendors', 'Comma separated. Blank allows any vendor.')}
        />
      </div>

    <Checkbox
      checked={form.values.receiptRequired}
      onChange={(checked) => form.setValue('receiptRequired', checked)}
      label="A receipt is required"
      hint="A transaction with no receipt raises a missing-receipt anomaly once the grace period has elapsed."
    />
  </>
);

interface EditPolicyDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: (policy: FuelPolicy) => void;
  policy: FuelPolicy;
}

/**
 * Revise a fuel policy - `PUT /api/v1/fuel/policies/{id}`.
 *
 * <h2>Editing an effective-dated rule set is safe here, and it is worth saying why</h2>
 *
 * <p>Every reconciliation run stores the policy id <em>and</em> the version it applied, so a past
 * judgement remains readable as the rules that produced it however the policy reads today. Nothing
 * downstream is recomputed from the current row. That is the property that makes an in-place edit
 * defensible rather than a rewriting of history.
 *
 * <p>What it does not do is renumber anything for you. `Policy version` is the operator's own
 * numbering and is the field to bump when a revision is material enough that somebody reading an
 * old reconciliation should be able to tell the two apart.
 */
export const EditPolicyDialog = ({ open, onClose, onSaved, policy }: EditPolicyDialogProps) => {
  const original = valuesOf(policy);

  const form = useFleetForm<PolicyValues>({
    initialValues: original,
    schema: policySchema,
    crossFieldValidate: (values) => {
      const errors = policyCrossFieldValidate(values);
      // The service refuses this with FUEL_POLICY_VERSION_NOT_ADVANCED, and it is right to: a
      // reconciliation stores the policy id and version and nothing else about the rules, so a
      // limit changed under the same version makes that pair describe two different rule sets.
      // Caught here as well so the operator is told while still holding the form, on the field
      // that fixes it, rather than by a refusal after submitting.
      if (rulesDiffer(original, values) && values.policyVersion === original.policyVersion) {
        errors.policyVersion = `Give this a new version - ${
          Number(original.policyVersion) + 1
        } - because a limit changed and past reconciliations record version ${original.policyVersion}.`;
      }
      return errors;
    },
    onSubmit: async (values) => {
      const saved = await fuelPoliciesApi.update(policy.id, toRequestBody(values));
      onSaved(saved);
      onClose();
    },
  });

  const versionNeedsBump =
    rulesDiffer(original, form.values) && form.values.policyVersion === original.policyVersion;

  return (
    <FormDialog
      open={open}
      title={`Edit ${policy.name}`}
      description="Reconciliation records the policy version it applied, so runs already completed keep the rules that judged them."
      submitLabel="Save policy"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={form.submit}
    >
      {versionNeedsBump ? (
        <Alert variant="warning" title="This revision needs a new version number">
          A limit has changed. Every reconciliation records the policy version that judged it and
          nothing else about the rules, so reusing version {policy.policyVersion} would make that
          number mean one thing for the runs before this edit and another for the runs after. Set
          the version to {Number(policy.policyVersion) + 1}.
        </Alert>
      ) : (
        <Alert variant="info" title={`${policy.siteCode.value} · version ${policy.policyVersion}`}>
          Widening the period is checked against the other active policies at this site, this one
          excepted. Transactions reconciled before now keep the version they were judged under.
        </Alert>
      )}

      <PolicyFields form={form} />
    </FormDialog>
  );
};

interface WithdrawPolicyDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: (policy: FuelPolicy) => void;
  policy: FuelPolicy;
}

/**
 * Withdraw a fuel policy - `DELETE /api/v1/fuel/policies/{id}`.
 *
 * <h2>Why the word is "withdraw" and not "delete"</h2>
 *
 * <p>The row cannot go. Every reconciliation run names the policy that judged it, and a register
 * where those references can be broken is worse than one that cannot be tidied: the audit trail
 * would still say a transaction was judged under a policy, and there would be no policy to read.
 *
 * <p>So the control does what the service does - moves the policy to ARCHIVED - and the dialog says
 * so plainly rather than offering a delete that quietly means something else. The practical effect
 * is the one the operator wants: it stops applying to anything new, and it releases its period so a
 * replacement can cover the same dates.
 */
export const WithdrawPolicyDialog = ({
  open,
  onClose,
  onSaved,
  policy,
}: WithdrawPolicyDialogProps) => {
  const form = useFleetForm({
    initialValues: { reason: '' },
    schema: { reason: compose(required('Reason'), maxLength('Reason', 1000)) },
    onSubmit: async (values) => {
      const saved = await fuelPoliciesApi.withdraw(policy.id, values.reason.trim());
      onSaved(saved);
      onClose();
      form.reset();
    },
  });

  return (
    <FormDialog
      open={open}
      title={`Withdraw ${policy.name}`}
      description="The policy stops applying to new transactions and stays readable for the ones it has already judged."
      submitLabel="Withdraw policy"
      submitting={form.submitting}
      formError={form.formError}
      destructive
      onClose={onClose}
      onSubmit={form.submit}
    >
      <Alert variant="warning" title="Reconciliation at this site will have no policy">
        A run refuses outright when it cannot find a policy covering the transaction&rsquo;s own
        timestamp. If this is the only one in force, create its replacement before withdrawing it -
        or immediately after, since withdrawing frees the period.
      </Alert>
      <TextAreaInput
        label="Reason"
        required
        rows={3}
        value={form.values.reason}
        onChange={(value) => form.setValue('reason', value)}
        {...form.fieldProps('reason', 'Recorded in the audit trail against the withdrawal.')}
      />
    </FormDialog>
  );
};
