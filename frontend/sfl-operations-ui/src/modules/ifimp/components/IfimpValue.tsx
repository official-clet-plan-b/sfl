import StatusChip from 'shared/components/StatusChip';
import { cn } from 'shared/components/cn';

export const humaniseIfimpField = (key: string) =>
  key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/_/g, ' ')
    .replace(/^./, (letter) => letter.toUpperCase());

const isRecord = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);

const isIsoInstant = (value: string) =>
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(value) && !Number.isNaN(Date.parse(value));

const primitive = (value: string | number | boolean) => {
  if (typeof value === 'boolean') {
    return <StatusChip value={value ? 'Yes' : 'No'} tone={value ? 'ready' : 'neutral'} />;
  }
  if (typeof value === 'number') {
    return <span>{value.toLocaleString()}</span>;
  }
  if (isIsoInstant(value)) {
    return <time dateTime={value}>{new Date(value).toLocaleString()}</time>;
  }
  const statusLike = /^[A-Z][A-Z0-9_ -]+$/.test(value);
  return statusLike
    ? <StatusChip value={humaniseIfimpField(value)} />
    : <span className="whitespace-pre-wrap break-words">{value}</span>;
};

interface Props {
  value: unknown;
  compact?: boolean;
  depth?: number;
}

/** Readable Phase 2 values: nested API objects become labelled cards, never raw JSON blobs. */
const IfimpValue = ({ value, compact = false, depth = 0 }: Props) => {
  if (value === null || value === undefined || value === '') {
    return <span className="text-gray-400">—</span>;
  }
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return primitive(value);
  }
  if (Array.isArray(value)) {
    if (value.length === 0) return <span className="text-gray-500">None</span>;
    if (compact && value.some(isRecord)) {
      return <span className="text-gray-600">{value.length.toLocaleString()} {value.length === 1 ? 'record' : 'records'}</span>;
    }
    return (
      <div className={cn('flex gap-2', value.some(isRecord) ? 'flex-col' : 'flex-wrap')}>
        {value.map((item, index) => (
          <div
            key={isRecord(item) && item.id != null ? String(item.id) : index}
            className={cn(
              isRecord(item) && 'rounded-lg border border-gray-200 bg-white p-3',
              !isRecord(item) && 'rounded-full bg-gray-100 px-2.5 py-1 text-theme-xs text-gray-700',
            )}
          >
            <IfimpValue value={item} compact={compact} depth={depth + 1} />
          </div>
        ))}
      </div>
    );
  }
  if (isRecord(value)) {
    const entries = Object.entries(value);
    if (entries.length === 0) return <span className="text-gray-500">None</span>;
    if (compact || depth > 2) {
      const identifying = value.name ?? value.title ?? value.code ?? value.reference ?? value.status;
      return identifying == null
        ? <span className="text-gray-600">{entries.length} fields</span>
        : <IfimpValue value={identifying} compact />;
    }
    return (
      <dl className="grid min-w-0 grid-cols-1 gap-x-5 gap-y-3 sm:grid-cols-2">
        {entries.map(([key, entry]) => (
          <div key={key} className={cn('min-w-0', isRecord(entry) || Array.isArray(entry) ? 'sm:col-span-2' : '')}>
            <dt className="text-[11px] font-semibold tracking-wide text-gray-500 uppercase">
              {humaniseIfimpField(key)}
            </dt>
            <dd className="mt-1 text-theme-sm text-gray-800">
              <IfimpValue value={entry} depth={depth + 1} />
            </dd>
          </div>
        ))}
      </dl>
    );
  }
  return <span>{String(value)}</span>;
};

export default IfimpValue;
