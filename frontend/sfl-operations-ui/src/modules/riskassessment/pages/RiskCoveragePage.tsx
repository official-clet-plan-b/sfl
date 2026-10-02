import { useState } from 'react';
import { useNavigate } from 'react-router';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { riskAssessmentPaths } from 'shared/layout/navigation';
import type { CoverageLine, CoverageReport, HazardFrequency } from '../api/dto';
import { riskAssessmentApi } from '../api/riskAssessmentApi';
import HazardFrequencyChart from '../charts/HazardFrequencyChart';
import { StandingChip } from '../components/riskChips';
import { CreateAssessmentDialog } from '../dialogs/assessmentDialogs';
import { Button } from '@rfdtech/components';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import Icon from 'shared/components/Icon';

/** Where each observed activity came from, in the source system's own terms. */
const sourceName: Record<string, string> = { S176: 'Construction projects (S176)', S163: 'Incidents (S163)' };

const Line = ({ line, onAssess }: { line: CoverageLine; onAssess?: () => void }) => {
  const navigate = useNavigate();
  return (
    <div className="rounded-md border border-gray-200 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="font-semibold text-gray-900">{line.activityType}</p>
        <span className="text-theme-xs text-gray-600">Used {line.occurrences} time{line.occurrences === 1 ? '' : 's'}</span>
      </div>
      <ul className="mt-2 space-y-1 text-theme-xs text-gray-700">
        {line.sources.map((source) => (
          <li key={source.sourceSystem}>
            {sourceName[source.sourceSystem] ?? source.sourceSystem}: {source.occurrences}, last {formatDateTime(source.lastSeenAt)}
            {source.lastSourceReference ? ` (${source.lastSourceReference})` : ''}
          </li>
        ))}
      </ul>
      {line.assessments.length > 0 && (
        <div className="mt-3 space-y-2">
          {line.assessments.map((assessment) => (
            <button key={assessment.id} type="button" className="flex w-full flex-wrap items-center justify-between gap-2 rounded border border-gray-100 px-3 py-2 text-left hover:bg-gray-50" onClick={() => navigate(riskAssessmentPaths.detail(assessment.id))}>
              <span className="text-theme-sm text-gray-900">{assessment.reference} · {assessment.title}</span>
              <StandingChip standing={assessment.standing} />
            </button>
          ))}
        </div>
      )}
      {onAssess && (
        <div className="mt-3">
          <Button size="sm" variant="outline" onClick={onAssess}><Icon name="plus" size={14} aria-hidden="true" />
            {line.assessments.length ? 'Write another assessment' : 'Assess this activity'}
          </Button>
        </div>
      )}
    </div>
  );
};

/**
 * SRS-SFL-S165-03: work the platform actually does at this site - S176 construction work types and the
 * activities S163 incidents happened during - against what current assessments cover. A gap whose only
 * assessment has lapsed shows that assessment, so the answer is "renew it", not "write one".
 */
const RiskCoveragePage = () => {
  const [siteCode, setSiteCode] = useState(defaultSite);
  const [assessing, setAssessing] = useState<string | null>(null);
  const navigate = useNavigate();
  const coverage = useApiQuery(
    (signal) => (siteCode ? riskAssessmentApi.coverage(siteCode, signal) : Promise.resolve<CoverageReport | undefined>(undefined)),
    [siteCode],
  );
  const hazards = useApiQuery(
    (signal) => (siteCode ? riskAssessmentApi.hazards(siteCode, signal) : Promise.resolve<HazardFrequency[]>([])),
    [siteCode],
  );
  const canAuthor = permits('RISK_ASSESSMENT_AUTHOR');
  const report = coverage.data;

  return (
    <div>
      <PageHeading title="Coverage and hazards" subtitle="Activities in use at the site with no current assessment, and the hazards the library already covers." crumbs={[{ label: 'Risk assessments', to: riskAssessmentPaths.dashboard }, { label: 'Coverage' }]} actions={<Button variant="outline" onClick={() => { coverage.refetch(); hazards.refetch(); }}><Icon name="refresh" size={14} aria-hidden="true" />Refresh</Button>} />
      <div className="mb-5">
        <Panel>
          <div className="max-w-sm">
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
          </div>
        </Panel>
      </div>
      <DataState loading={coverage.initialising} error={coverage.error} onRetry={coverage.refetch}>
        {report && (
          <div className="space-y-5">
            <Panel title={`Coverage gaps (${report.gaps.length})`} subtitle={`${report.covered} of ${report.observedActivityTypes} activity types in use have a current assessment.`}>
              {report.observedActivityTypes === 0 ? (
                <p className="text-theme-sm text-gray-600">
                  No activity has been observed at this site yet. Activity types arrive from S176 construction projects and from incidents that record what work was under way.
                </p>
              ) : report.gaps.length ? (
                <div className="grid gap-3 lg:grid-cols-2">
                  {report.gaps.map((line) => <Line key={line.activityType} line={line} onAssess={canAuthor ? () => setAssessing(line.activityType) : undefined} />)}
                </div>
              ) : (
                <p className="text-theme-sm text-gray-600">Every activity in use at this site has a current assessment.</p>
              )}
            </Panel>
            {report.coveredLines.length > 0 && (
              <Panel title={`Covered (${report.coveredLines.length})`}>
                <div className="grid gap-3 lg:grid-cols-2">
                  {report.coveredLines.map((line) => <Line key={line.activityType} line={line} />)}
                </div>
              </Panel>
            )}
          </div>
        )}
      </DataState>
      <div className="mt-5">
        <Panel title="Hazard frequency" subtitle="Hazard types across current assessments, most frequent first.">
          <DataState loading={hazards.initialising} error={hazards.error} onRetry={hazards.refetch} empty={!hazards.data?.length} emptyTitle="No current assessments" emptyHint="Hazard frequency counts current assessments only.">
            <HazardFrequencyChart hazards={hazards.data ?? []} height={Math.max(220, (hazards.data?.length ?? 0) * 34)} />
          </DataState>
        </Panel>
      </div>
      {assessing && (
        <CreateAssessmentDialog siteCode={siteCode} activityType={assessing} onClose={() => setAssessing(null)} onCreated={(detail) => { setAssessing(null); navigate(riskAssessmentPaths.detail(detail.assessment.id)); }} />
      )}
    </div>
  );
};

export default RiskCoveragePage;
