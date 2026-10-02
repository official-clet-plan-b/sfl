import { ReactNode } from 'react';

/** The two-line cell the booking registers share: a strong primary value over quieter detail. */
const CellStack = ({ primary, secondary }: { primary: ReactNode; secondary?: ReactNode }) => (
  <div className="min-w-0">
    <div className="truncate font-semibold text-foreground">{primary}</div>
    {secondary !== undefined && secondary !== null && (
      <div className="truncate text-xs text-muted-foreground">{secondary}</div>
    )}
  </div>
);

export default CellStack;
