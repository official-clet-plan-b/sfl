import { useState } from 'react';
import { Button, Notice } from '@rfdtech/components';
import { Plus } from 'lucide-react';
import SiteSelect from 'shared/components/SiteSelect';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type {
  ChecklistItemRequest,
  CreateChecklistRequest,
  ReadinessChecklist,
  UpdateChecklistRequest,
} from '../api/dto';
import { blockerSeverities, operatingModes, spaceTypes } from '../api/enums';
import type { BlockerSeverity, OperatingMode, SpaceType } from '../api/enums';
import { humaniseCode } from '../components/facilitiesFormat';
import { StaleWriteNotice } from './common';
import { Checkbox, FormDialog, NumberInput, SelectInput, TextAreaInput, TextInput } from 'modules/facilities/dialogs/dialogKit';

/**
 * Creating and editing a readiness checklist.
 *
 * <h2>Severity is declared on the item, not chosen by the assessor</h2>
 *
 * <p>This is the whole reason a checklist is a record rather than a form. `severityIfFailed` is set
 * here, once, by whoever writes the standard - so two officers assessing the same hall on the same
 * morning produce the same verdict. An assessor answers pass or fail and nothing else. Setting
 * `CRITICAL` on an item is therefore a decision about the estate, not about one inspection: a
 * critical failure is what forbids a space being ready at all.
 *
 * <h2>Applicability, and why "Any" is a real answer</h2>
 *
 * <p>Space type and operating mode narrow which spaces a checklist applies to, and the most specific
 * match wins when an assessment is taken. Leaving both blank means it applies to everything, which is
 * the right answer for a baseline checklist and the wrong one for an examination standard.
 */

/** The item editor's own state, kept as strings so a half-typed weight does not collapse to zero. */
interface ItemDraft {
  itemCode: string;
  description: string;
  severityIfFailed: BlockerSeverity;
  mandatory: boolean;
  weight: string;
}

const emptyItem = (): ItemDraft => ({
  itemCode: '',
  description: '',
  severityIfFailed: 'MAJOR',
  mandatory: true,
  weight: '1',
});

const toRequest = (items: ItemDraft[]): ChecklistItemRequest[] =>
  items.map((item, index) => ({
    itemCode: item.itemCode.trim(),
    description: item.description.trim(),
    severityIfFailed: item.severityIfFailed,
    mandatory: item.mandatory,
    weight: item.weight === '' ? null : Number(item.weight),
    // The order they are shown in is the order they were written in. An assessor works down the
    // list, and a checklist whose questions reshuffle between versions is one nobody trusts.
    sortOrder: index,
  }));

/** Client mirror of `@NotEmpty @Valid List<ChecklistItem>` plus the item-level constraints. */
const itemProblems = (items: ItemDraft[]): string | null => {
  if (items.length === 0) {
    return 'A checklist needs at least one item. Without one, an assessment records no answers.';
  }
  if (items.some((item) => item.itemCode.trim() === '' || item.description.trim() === '')) {
    return 'Every item needs a code and a description.';
  }
  const codes = items.map((item) => item.itemCode.trim().toLowerCase());
  if (new Set(codes).size !== codes.length) {
    return 'Two items share a code. An assessment answers by code, so they have to be distinct.';
  }
  if (items.some((item) => item.weight !== '' && Number(item.weight) < 0)) {
    return 'A weight cannot be negative.';
  }
  return null;
};

interface ItemEditorProps {
  items: ItemDraft[];
  onChange: (items: ItemDraft[]) => void;
}

const ItemEditor = ({ items, onChange }: ItemEditorProps) => {
  const patch = (index: number, change: Partial<ItemDraft>) =>
    onChange(items.map((item, position) => (position === index ? { ...item, ...change } : item)));

  return (
    <div className="space-y-3">
      {items.map((item, index) => (
        <div
          key={index}
          className="space-y-3 rounded-lg border border-border bg-surface-muted/20 px-4 py-3"
        >
          <div className="flex items-start justify-between gap-3">
            <span className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
              Item {index + 1}
            </span>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => onChange(items.filter((_, position) => position !== index))}
              disabled={items.length === 1}
              title={items.length === 1 ? 'A checklist needs at least one item.' : undefined}
            >
              Remove
            </Button>
          </div>

          <div className="grid gap-3 sm:grid-cols-3">
            <TextInput
              label="Code"
              value={item.itemCode}
              onChange={(value) => patch(index, { itemCode: value })}
              required
              maxLength={60}
              placeholder="LIGHTING"
            />
            <SelectInput
              label="If it fails"
              value={item.severityIfFailed}
              onChange={(value) => patch(index, { severityIfFailed: value as BlockerSeverity })}
              required
              options={blockerSeverities.map((value) => ({ value, label: humaniseCode(value) }))}
              helperText={item.severityIfFailed === 'CRITICAL' ? 'Forbids the space being ready.' : undefined}
            />
            <NumberInput
              label="Weight"
              value={item.weight}
              onChange={(value) => patch(index, { weight: value })}
              min={0}
              helperText="Its share of the score."
            />
          </div>

          <TextInput
            label="What is being checked"
            value={item.description}
            onChange={(value) => patch(index, { description: value })}
            required
            maxLength={500}
            placeholder="All ceiling lighting is working and the hall is evenly lit."
          />

          <Checkbox
            checked={item.mandatory}
            onChange={(checked) => patch(index, { mandatory: checked })}
            label="Mandatory"
            hint="A failure here fails the whole assessment, whatever the score."
          />
        </div>
      ))}

      <Button variant="outline" onClick={() => onChange([...items, emptyItem()])}>
        <Plus size={14} aria-hidden="true" />
        Add an item
      </Button>
    </div>
  );
};

interface CreateChecklistDialogProps {
  siteCode: string;
  onClose: () => void;
  onSubmit: (request: CreateChecklistRequest) => Promise<void>;
}

export const CreateChecklistDialog = ({
  siteCode,
  onClose,
  onSubmit,
}: CreateChecklistDialogProps) => {
  const [site, setSite] = useState(siteCode);
  const [checklistCode, setChecklistCode] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [spaceType, setSpaceType] = useState<SpaceType | ''>('');
  const [operatingMode, setOperatingMode] = useState<OperatingMode | ''>('');
  const [items, setItems] = useState<ItemDraft[]>([emptyItem()]);
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const itemError = itemProblems(items);
  const missingCode = checklistCode.trim() === '';
  const missingName = name.trim() === '';
  const invalid = !site || missingCode || missingName || itemError !== null;

  const submit = async () => {
    setTouched(true);
    if (invalid) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({
        siteCode: site,
        checklistCode: checklistCode.trim(),
        name: name.trim(),
        description: description.trim() || null,
        spaceType: spaceType || null,
        operatingMode: operatingMode || null,
        items: toRequest(items),
      });
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title="Add a readiness checklist"
      description="What an assessment asks, and what a failure costs"
      submitLabel="Add the checklist"
      submitting={submitting}
      submitDisabled={touched && invalid}
      formError={formError}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <SiteSelect value={site} onChange={setSite} required />
          <TextInput
            label="Checklist code"
            value={checklistCode}
            onChange={setChecklistCode}
            onBlur={() => setTouched(true)}
            required
            maxLength={60}
            placeholder="EXAM-HALL"
            error={touched && missingCode}
            helperText={touched && missingCode ? 'A code is required.' : 'Unique within the site.'}
          />
        </div>

        <TextInput
          label="Name"
          value={name}
          onChange={setName}
          onBlur={() => setTouched(true)}
          required
          maxLength={200}
          placeholder="Examination hall readiness"
          error={touched && missingName}
          helperText={touched && missingName ? 'A name is required.' : undefined}
        />

        <TextAreaInput
          label="Description"
          value={description}
          onChange={setDescription}
          rows={2}
          maxLength={1000}
        />

        <ApplicabilityFields
          spaceType={spaceType}
          operatingMode={operatingMode}
          onSpaceTypeChange={setSpaceType}
          onOperatingModeChange={setOperatingMode}
        />

        <div>
          <h3 className="mb-2 text-sm font-medium text-foreground">Items</h3>
          <ItemEditor items={items} onChange={setItems} />
        </div>

        {touched && itemError && (
          <Notice variant="error" title="The items are not complete">
            <p className="text-sm">{itemError}</p>
          </Notice>
        )}
      </div>
    </FormDialog>
  );
};

interface EditChecklistDialogProps {
  checklist: ReadinessChecklist;
  onClose: () => void;
  onSubmit: (request: UpdateChecklistRequest) => Promise<void>;
}

/**
 * Editing a checklist.
 *
 * <p>The items are the delicate part. `UpdateChecklist` treats any list at all as a **replacement**
 * for every item and bumps the version, and omitting the list leaves the questions alone. So the
 * dialog tracks whether the operator actually touched them and sends the list only when they did -
 * otherwise renaming a checklist would silently rewrite its questions with whatever this form last
 * rendered.
 *
 * <p>Assessments already taken keep the version they were taken at, so an edit changes what is asked
 * next rather than rewriting what was answered. The dialog says so, because "edit" usually does not
 * mean that.
 */
export const EditChecklistDialog = ({
  checklist,
  onClose,
  onSubmit,
}: EditChecklistDialogProps) => {
  const [name, setName] = useState(checklist.name);
  const [description, setDescription] = useState(checklist.description ?? '');
  const [items, setItems] = useState<ItemDraft[]>(
    checklist.items
      .slice()
      .sort((left, right) => left.sortOrder - right.sortOrder)
      .map((item) => ({
        itemCode: item.itemCode,
        description: item.description,
        severityIfFailed: item.severityIfFailed,
        mandatory: item.mandatory,
        weight: String(item.weight),
      })),
  );
  const [itemsTouched, setItemsTouched] = useState(false);
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const itemError = itemsTouched ? itemProblems(items) : null;
  const missingName = name.trim() === '';
  const invalid = missingName || itemError !== null;

  const submit = async () => {
    setTouched(true);
    if (invalid) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({
        name: name.trim(),
        description: description.trim() || null,
        // Omitted unless the operator edited them - supplying any replaces them all.
        items: itemsTouched ? toRequest(items) : null,
        expectedVersion: checklist.metadata.version,
      });
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title={`Edit ${checklist.checklistCode}`}
      description={`Version ${checklist.version}. Applicability and the site cannot be changed.`}
      submitLabel="Save changes"
      submitting={submitting}
      submitDisabled={touched && invalid}
      formError={formError}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <TextInput
          label="Name"
          value={name}
          onChange={setName}
          onBlur={() => setTouched(true)}
          required
          maxLength={200}
          error={touched && missingName}
          helperText={touched && missingName ? 'A name is required.' : undefined}
        />

        <TextAreaInput
          label="Description"
          value={description}
          onChange={setDescription}
          rows={2}
          maxLength={1000}
        />

        <div>
          <h3 className="mb-2 text-sm font-medium text-foreground">Items</h3>
          <ItemEditor
            items={items}
            onChange={(next) => {
              setItemsTouched(true);
              setItems(next);
            }}
          />
        </div>

        {itemsTouched && (
          <Notice variant="warning" title="Editing the items publishes a new version">
            <p className="text-sm">
              Every item is replaced by the list above and the checklist version moves to{' '}
              {checklist.version + 1}. Assessments already taken keep the version they were taken at,
              so this changes what is asked next rather than what was answered.
            </p>
          </Notice>
        )}

        {touched && itemError && (
          <Notice variant="error" title="The items are not complete">
            <p className="text-sm">{itemError}</p>
          </Notice>
        )}

        <StaleWriteNotice error={formError} />
      </div>
    </FormDialog>
  );
};

/** Space type and operating mode, where blank genuinely means "any". */
const ApplicabilityFields = ({
  spaceType,
  operatingMode,
  onSpaceTypeChange,
  onOperatingModeChange,
}: {
  spaceType: SpaceType | '';
  operatingMode: OperatingMode | '';
  onSpaceTypeChange: (value: SpaceType | '') => void;
  onOperatingModeChange: (value: OperatingMode | '') => void;
}) => (
  <div className="grid gap-4 sm:grid-cols-2">
    <SelectInput
      label="Applies to"
      value={spaceType}
      onChange={(value) => onSpaceTypeChange(value as SpaceType | '')}
      allowEmpty
      emptyLabel="Any space type"
      options={spaceTypes.map((value) => ({ value, label: humaniseCode(value) }))}
      helperText="The most specific match wins when an assessment is taken."
    />
    <SelectInput
      label="In mode"
      value={operatingMode}
      onChange={(value) => onOperatingModeChange(value as OperatingMode | '')}
      allowEmpty
      emptyLabel="Any mode"
      options={operatingModes.map((value) => ({ value, label: humaniseCode(value) }))}
      helperText="Leave as any unless this is an examination-only standard."
    />
  </div>
);
