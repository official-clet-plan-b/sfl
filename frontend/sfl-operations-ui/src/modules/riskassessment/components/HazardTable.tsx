import { humanise } from 'modules/fleet/api/enums';
import type { HazardView } from '../api/dto';
import { hazardTypeLabel } from '../api/enums';
import { RiskLevelChip } from './riskChips';


/** A version's hazards as the service scored them - read-only, every figure the service's own. */
const HazardTable = ({ hazards }: { hazards: HazardView[] }) => {
  if (hazards.length === 0) {
    return <p className="text-theme-sm text-gray-600">No hazards recorded on this version.</p>;
  }
  return (
    <div className="space-y-3">
      {hazards.map((hazard) => (
        <div key={hazard.number} className="rounded-md border border-gray-200 p-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <p className="font-semibold text-gray-900">
                #{hazard.number} {hazard.description}
              </p>
              <p className="text-theme-xs text-gray-600">
                {hazardTypeLabel[hazard.hazardType]}
                {hazard.whoAtRisk ? ` · affects ${hazard.whoAtRisk}` : ''}
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-2 text-theme-xs text-gray-700">
              <span>
                Before {hazard.inherentScore} ({humanise(hazard.inherentLikelihood)} &times; {humanise(hazard.inherentSeverity)})
              </span>
              <RiskLevelChip level={hazard.inherentLevel} />
              <span>&rarr; residual {hazard.residualScore}</span>
              <RiskLevelChip level={hazard.residualLevel} />
            </div>
          </div>
          {hazard.controls.length > 0 ? (
            <ul className="mt-3 list-disc space-y-1 pl-5 text-theme-sm text-gray-800">
              {hazard.controls.map((control, index) => (
                <li key={index}>
                  <span className="font-medium">{humanise(control.controlType)}:</span> {control.description}
                </li>
              ))}
            </ul>
          ) : (
            <p className="mt-3 text-theme-xs font-medium text-warning-800">No control measure - Hazard Without Control.</p>
          )}
        </div>
      ))}
    </div>
  );
};

export default HazardTable;
