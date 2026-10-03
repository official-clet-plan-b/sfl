import { useNavigate } from 'react-router';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { drillPaths, incidentPaths } from 'shared/layout/navigation';
import Panel from 'modules/emergency/components/Panel';
import { drillApi } from 'modules/drill/api/drillApi';
import { incidentApi } from '../api/incidentApi';
import type { CapaCounts } from '../api/dto';

const AGE_LABELS = ['0-30 days', '31-60', '61-90', 'Over 90'];

/**
 * The HSE dashboard's corrective actions, from incidents (S163) and drills (S175) side by side - one lifecycle,
 * two sources (SRS-SFL-S175-03). Each column is the owning service's own count; a column the actor may not read,
 * or whose service did not answer, says so rather than showing zero.
 */
const CorrectiveActionPanel = ({ siteCode }: { siteCode: string }) => {
  const navigate = useNavigate();
  const showDrills = permits('DRILL_READ');
  const incidents = useApiQuery((signal) => incidentApi.correctiveActionSummary(siteCode, signal), [siteCode]);
  const drills = useApiQuery(
    (signal) => (showDrills ? drillApi.correctiveActionSummary(siteCode, signal) : Promise.resolve(undefined)),
    [siteCode, showDrills],
  );

  const rows: { label: string; pick: (c: CapaCounts) => number; alert?: boolean }[] = [
    { label: 'Open', pick: (c) => c.open },
    { label: 'Overdue', pick: (c) => c.overdue, alert: true },
    { label: 'Verified', pick: (c) => c.verified },
    { label: 'Cancelled', pick: (c) => c.cancelled },
    ...AGE_LABELS.map((label, i) => ({ label: `Open ${label}${i === 0 ? '' : ' days'}`, pick: (c: CapaCounts) => c.ageing[i] ?? 0 })),
  ];
  const cell = (counts: CapaCounts | undefined, failed: boolean, pick: (c: CapaCounts) => number, alert?: boolean) => {
    if (failed) return <span className="text-gray-500">unavailable</span>;
    if (!counts) return <span className="text-gray-500">-</span>;
    const value = pick(counts);
    return <strong className={`tabular-nums ${alert && value ? 'text-[var(--clet-error-text)]' : ''}`}>{value}</strong>;
  };

  return (
    <Panel title="Corrective actions" subtitle="Incidents and drills, one lifecycle - tracked to verified closure.">
      <table className="w-full text-theme-sm">
        <thead>
          <tr className="border-b border-[var(--clet-border-subtle)] text-left text-theme-xs text-gray-600">
            <th className="pb-2 font-medium" scope="col">Status</th>
            <th className="pb-2 text-right font-medium" scope="col">
              <button type="button" className="hover:underline" onClick={() => navigate(incidentPaths.cases)}>Incidents</button>
            </th>
            {showDrills && (
              <th className="pb-2 text-right font-medium" scope="col">
                <button type="button" className="hover:underline" onClick={() => navigate(drillPaths.register)}>Drills</button>
              </th>
            )}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.label} className="border-b border-[var(--clet-border-subtle)] last:border-0">
              <th scope="row" className="py-2 text-left font-normal">{row.label}</th>
              <td className="py-2 text-right">{cell(incidents.data, Boolean(incidents.error), row.pick, row.alert)}</td>
              {showDrills && <td className="py-2 text-right">{cell(drills.data, Boolean(drills.error), row.pick, row.alert)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </Panel>
  );
};

export default CorrectiveActionPanel;
