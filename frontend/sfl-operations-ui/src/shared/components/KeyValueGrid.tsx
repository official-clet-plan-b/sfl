import { ReactNode } from 'react';
import { cn } from './cn';

export interface KeyValueItem {
  label: string;
  value: ReactNode;
  /** Marks a value the service masked, so the UI never presents a mask as the real thing. */
  masked?: boolean;
  span?: 1 | 2;
}

interface KeyValueGridProps {
  items: KeyValueItem[];
  columns?: 2 | 3 | 4;
}

const isBlank = (value: ReactNode) =>
  value === null || value === undefined || value === '' || value === '-';

const columnClasses: Record<2 | 3 | 4, string> = {
  2: 'sm:grid-cols-2',
  3: 'sm:grid-cols-2 md:grid-cols-3',
  4: 'sm:grid-cols-2 md:grid-cols-4',
};

/** Dense label/value grid used across every detail surface. */
const KeyValueGrid = ({ items, columns = 3 }: KeyValueGridProps) => (
  <dl className={cn('grid grid-cols-1 gap-x-6 gap-y-4', columnClasses[columns])}>
    {items.map((item) => (
      <div key={item.label} className={cn('min-w-0', item.span === 2 && 'sm:col-span-2')}>
        <dt className="text-xs font-semibold text-muted-foreground">{item.label}</dt>
        <dd className="mt-0.5 flex flex-wrap items-center gap-1.5">
          <span
            className={cn(
              'text-sm font-medium break-words',
              isBlank(item.value) ? 'text-muted-foreground' : 'text-foreground',
            )}
          >
            {isBlank(item.value) ? '-' : item.value}
          </span>
          {item.masked && (
            <span className="text-xs font-semibold text-warning-text">(masked)</span>
          )}
        </dd>
      </div>
    ))}
  </dl>
);

export default KeyValueGrid;
