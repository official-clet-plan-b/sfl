import type { ReactNode } from 'react';
import { Card, CardActions, CardHeader, CardTitle } from '@rfdtech/components';

interface PanelProps {
  title?: string;
  subtitle?: string;
  actions?: ReactNode;
  className?: string;
  children: ReactNode;
}

/**
 * A titled work surface: a library `Card` whose header carries the title, one quiet line of
 * explanation and the section's own actions.
 */
const Panel = ({ title, subtitle, actions, className, children }: PanelProps) => (
  <Card className={className}>
    {(title || actions) && (
      <CardHeader>
        <div className="min-w-0">
          {title && <CardTitle>{title}</CardTitle>}
          {subtitle && (
            <p className="mt-0.5 text-theme-xs text-[var(--clet-text-secondary)]">{subtitle}</p>
          )}
        </div>
        {actions && <CardActions>{actions}</CardActions>}
      </CardHeader>
    )}
    {children}
  </Card>
);

export default Panel;
