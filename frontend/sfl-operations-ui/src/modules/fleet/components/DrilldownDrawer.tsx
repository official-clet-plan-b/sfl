import { Link as RouterLink } from 'react-router';
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetOverlay,
  SheetPortal,
  SheetTitle,
} from '@rfdtech/components';
import { DashboardDrilldownRow } from 'modules/fleet/api/dto';
import { humanise } from 'modules/fleet/api/enums';
import { dashboardApi } from 'modules/fleet/api/fleetApi';
import DataState from 'shared/components/DataState';
import Icon from 'shared/components/Icon';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fleetPaths } from 'shared/layout/navigation';

interface DrilldownDrawerProps {
  indicator: string | null;
  siteCode?: string;
  onClose: () => void;
}

const recordLink = (resourceType: string, resourceId: string): string | null => {
  switch (resourceType) {
    case 'Vehicle':
      return fleetPaths.vehicleDetail(resourceId);
    case 'Trip':
      return fleetPaths.tripDetail(resourceId);
    default:
      return null;
  }
};

/**
 * The records behind a dashboard indicator.
 *
 * The service audits every drilldown and refuses rows the caller may not see
 * (`FLEET_DASHBOARD_RESTRICTED_DRILLDOWN`), so a refusal is surfaced as-is rather than shown as an
 * empty list. A record whose resource type has no detail page shows its identifier instead of a
 * dead link.
 */
const DrilldownDrawer = ({ indicator, siteCode, onClose }: DrilldownDrawerProps) => {
  const { data, loading, error, refetch } = useApiQuery(
    (signal) =>
      indicator
        ? dashboardApi.drilldown(indicator, { siteCode }, signal)
        : Promise.resolve<DashboardDrilldownRow[]>([]),
    [indicator, siteCode],
  );

  return (
    <Sheet open={Boolean(indicator)} onOpenChange={(next) => !next && onClose()}>
      <SheetPortal>
        <SheetOverlay />
        <SheetContent side="right" showCloseButton>
          <SheetHeader>
            <SheetTitle>{humanise(indicator)}</SheetTitle>
            <SheetDescription>Source records behind this indicator</SheetDescription>
          </SheetHeader>
          <SheetBody>
            <DataState
              loading={loading}
              error={error}
              empty={(data ?? []).length === 0}
              emptyTitle="No records"
              emptyHint="Nothing currently contributes to this indicator in your site scope."
              onRetry={refetch}
              minHeight={200}
            >
              <ul className="divide-y divide-gray-200">
                {(data ?? []).map((row) => {
                  const link = recordLink(row.resourceType, row.resourceId);
                  return (
                    <li key={`${row.resourceType}-${row.resourceId}`} className="py-3">
                      <p className="text-theme-xs opacity-70">
                        {row.resourceType} · {row.siteCode}
                      </p>
                      <p className="mt-0.5 text-theme-sm font-semibold">{row.summary}</p>
                      {link ? (
                        <RouterLink
                          to={link}
                          onClick={onClose}
                          className="mt-1 inline-flex min-h-6 items-center gap-1 text-theme-xs font-medium text-teal-700 hover:underline"
                        >
                          Open record
                          <Icon name="chevron-right" size={13} />
                        </RouterLink>
                      ) : (
                        <p className="mt-1 text-theme-xs opacity-70">{row.resourceId}</p>
                      )}
                    </li>
                  );
                })}
              </ul>
            </DataState>
          </SheetBody>
        </SheetContent>
      </SheetPortal>
    </Sheet>
  );
};

export default DrilldownDrawer;
