import { BarChart } from 'shared/charts/Charts';
import { chartColors } from 'shared/charts/palette';
import type { HazardFrequency } from '../api/dto';
import { hazardTypeLabel } from '../api/enums';

/**
 * SRS-SFL-S165-03: how often each hazard type appears across the site's current assessments, most
 * frequent first - the service's ordering and counts, drawn as they came. Horizontal, because the
 * category names are long and a reader scans them, not the axis.
 */
const HazardFrequencyChart = ({ hazards, height = 280 }: { hazards: HazardFrequency[]; height?: number }) => (
  <BarChart
    height={height}
    horizontal
    integerAxis
    showLegend={false}
    categories={hazards.map((hazard) => hazardTypeLabel[hazard.hazardType])}
    series={[{ name: 'Hazards across current assessments', data: hazards.map((hazard) => hazard.occurrences), color: chartColors.navy }]}
  />
);

export default HazardFrequencyChart;
