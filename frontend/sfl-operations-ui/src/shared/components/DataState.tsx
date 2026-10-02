import { ReactNode } from 'react';
import { FleetApiError, errorDetail, errorLabel } from 'shared/errors/FleetApiError';
import { Banner, Button, EmptyState } from '@rfdtech/components';
import { Inbox, RefreshCw } from 'lucide-react';

interface DataStateProps {
  loading: boolean;
  error?: FleetApiError;
  empty?: boolean;
  emptyTitle?: string;
  emptyHint?: string;
  onRetry?: () => void;
  minHeight?: number;
  children: ReactNode;
}

export const Spinner = ({ size = 26 }: { size?: number }) => (
  <svg
    className="animate-spin text-primary"
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    aria-hidden="true"
  >
    <circle cx="12" cy="12" r="9.5" stroke="currentColor" strokeOpacity="0.2" strokeWidth="2.5" />
    <path d="M21.5 12A9.5 9.5 0 0 0 12 2.5" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
  </svg>
);

/**
 * The four states every screen owes the operator: loading, error, empty and content.
 *
 * Centralised so an unfinished screen cannot quietly render an empty table that looks like "no
 * vehicles" when the real answer is "the service is down". The error branch shows the service's own
 * message - SRS-defined wording - plus the correlation id, which is what support will ask for.
 */
const DataState = ({
  loading,
  error,
  empty,
  emptyTitle = 'Nothing to show',
  emptyHint,
  onRetry,
  minHeight = 220,
  children,
}: DataStateProps) => {
  if (loading) {
    return (
      <div
        className="flex flex-col items-center justify-center gap-3"
        style={{ minHeight }}
        role="status"
      >
        <Spinner />
        <p className="text-sm text-muted-foreground">Loading…</p>
      </div>
    );
  }

  if (error) {
    const detail = errorDetail(error);
    return (
      <div className="flex items-center" style={{ minHeight }}>
        <Banner
          variant={error.isForbidden ? 'warning' : 'danger'}
          heading={errorLabel(error)}
          subtext={
            <>
              <span className="block">{error.message}</span>
              {detail && <span className="mt-1 block">{detail}</span>}
            </>
          }
          action={
            onRetry ? (
              <Button size="sm" variant="outline" onClick={onRetry}>
                <RefreshCw size={14} strokeWidth={1.75} aria-hidden />
                Retry
              </Button>
            ) : undefined
          }
          className="w-full"
        />
      </div>
    );
  }

  if (empty) {
    return (
      <div className="flex items-center justify-center" style={{ minHeight }}>
        <EmptyState
          icon={<Inbox size={22} strokeWidth={1.75} />}
          title={emptyTitle}
          description={emptyHint}
        />
      </div>
    );
  }

  return <>{children}</>;
};

export default DataState;
