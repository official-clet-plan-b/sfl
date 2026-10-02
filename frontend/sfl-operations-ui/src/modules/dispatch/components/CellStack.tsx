import type { ReactNode } from 'react';

/** Two-line cell: a strong primary value with quieter supporting detail underneath. */
const CellStack = ({ primary, secondary }: { primary: ReactNode; secondary?: ReactNode }) => (
  <div className="min-w-0">
    <div className="truncate font-semibold text-foreground">{primary}</div>
    {secondary !== undefined && secondary !== null && (
      <div className="truncate text-xs text-muted-foreground">{secondary}</div>
    )}
  </div>
);

export default CellStack;
