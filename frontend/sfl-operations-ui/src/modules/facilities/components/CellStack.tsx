import type { ReactNode } from 'react';

/** A table cell with the identifying line over a quieter supporting one. */
const CellStack = ({ primary, secondary }: { primary: ReactNode; secondary?: ReactNode }) => (
  <div className="min-w-0">
    <div className="truncate font-semibold text-foreground">{primary}</div>
    {secondary !== undefined && secondary !== null && (
      <div className="truncate text-xs text-muted-foreground">{secondary}</div>
    )}
  </div>
);

export default CellStack;
