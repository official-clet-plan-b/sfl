import { useState } from 'react';
import { DateField, DateTimeField } from 'shared/components/DateField';
import FormDialog from 'shared/components/FormDialog';
import SiteSelect from 'shared/components/SiteSelect';
import { NumberInput, SelectInput, TextAreaInput, TextInput } from 'shared/components/fields';
import { FleetApiError } from 'shared/errors/FleetApiError';
import { IfimpRecord, IfimpWriteMethod, writeIfimpRecord } from '../api/ifimpPhase2Api';

export interface CreateField {
  key: string;
  label: string;
  type?: 'text' | 'number' | 'textarea' | 'select' | 'date' | 'datetime';
  required?: boolean;
  options?: string[];
  initial?: string;
}

export interface CreateAction {
  label: string;
  path: string;
  fields: CreateField[];
  method?: IfimpWriteMethod;
  destructive?: boolean;
  visible?: (record: IfimpRecord) => boolean;
  disabledReason?: (record: IfimpRecord) => string | undefined;
  toBody?: (values: Record<string, string>, siteCode: string) => Record<string, unknown>;
}

interface Props {
  action: CreateAction;
  siteCode: string;
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
  record?: IfimpRecord;
}

const recordValue = (record: IfimpRecord | undefined, key: string): unknown => {
  if (!record) return undefined;
  if (record[key] !== null && record[key] !== undefined) return record[key];
  for (const candidate of Object.values(record)) {
    if (candidate !== null && typeof candidate === 'object' && !Array.isArray(candidate)) {
      const nested = (candidate as IfimpRecord)[key];
      if (nested !== null && nested !== undefined) return nested;
    }
  }
  return undefined;
};

const initialValues = (action: CreateAction, siteCode: string, record?: IfimpRecord) =>
  Object.fromEntries(action.fields.map((field) => {
    const current = recordValue(record, field.key);
    return [field.key, field.key === 'siteCode' ? siteCode : (field.initial ?? (current == null ? '' : String(current)))];
  }));

const defaultBody = (values: Record<string, string>) =>
  Object.fromEntries(Object.entries(values).map(([key, value]) => [key, value === '' ? null : value]));

const IfimpCreateDialog = ({ action, siteCode, open, onClose, onCreated, record }: Props) => {
  const [values, setValues] = useState<Record<string, string>>(() => initialValues(action, siteCode, record));
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<FleetApiError>();
  const update = (key: string, value: string) => setValues((current) => ({ ...current, [key]: value }));
  const invalid = action.fields.some((field) => field.required && !values[field.key]?.trim());

  const submit = async () => {
    setSubmitting(true);
    setError(undefined);
    try {
      const path = action.path.replace(/\{(\w+)\}/g, (_, key: string) => encodeURIComponent(String(recordValue(record, key) ?? values[key] ?? '')));
      const body = action.toBody ? action.toBody(values, siteCode) : defaultBody(values);
      const version = recordValue(record, 'version') ?? recordValue(record, 'recordVersion');
      if (version !== undefined && body.expectedVersion === undefined) {
        body.expectedVersion = version;
      }
      await writeIfimpRecord(path, body, action.method);
      onCreated();
      onClose();
    } catch (cause) {
      setError(cause instanceof FleetApiError ? cause : new FleetApiError({ status: 0, code: 'UNKNOWN', message: 'The request could not be completed.' }));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog open={open} title={action.label} description="Required fields are marked. The Facilities service applies the workflow rules." submitLabel={action.label} destructive={action.destructive} submitting={submitting} submitDisabled={invalid} formError={error} onClose={onClose} onSubmit={submit}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {action.fields.map((field) => {
          const common = { key: field.key, label: field.label, required: field.required, value: values[field.key] ?? '', onChange: (value: string) => update(field.key, value) };
          if (field.key === 'siteCode') return <SiteSelect {...common} />;
          if (field.type === 'select') return <SelectInput {...common} options={(field.options ?? []).map((value) => ({ value, label: value.replace(/_/g, ' ') }))} />;
          if (field.type === 'number') return <NumberInput {...common} />;
          if (field.type === 'textarea') return <TextAreaInput {...common} className="sm:col-span-2" />;
          if (field.type === 'date') return <DateField {...common} />;
          if (field.type === 'datetime') return <DateTimeField {...common} />;
          return <TextInput {...common} />;
        })}
      </div>
    </FormDialog>
  );
};

export default IfimpCreateDialog;
