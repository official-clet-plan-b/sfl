import { useState } from 'react';
import { useNavigate } from 'react-router';
import Button from 'shared/components/Button';
import DataState from 'shared/components/DataState';
import PageHeader from 'shared/components/PageHeader';
import SectionCard from 'shared/components/SectionCard';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import StatCard from 'shared/components/StatCard';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { riskAssessmentPaths } from 'shared/layout/navigation';
import type { RiskDashboard } from '../api/dto';
import { hazardTypeLabel, riskLevels, standingLabel, type Standing } from '../api/enums';
import { riskAssessmentApi } from '../api/riskAssessmentApi';
import HazardFrequencyChart from '../charts/HazardFrequencyChart';
import { RiskLevelChip, StandingChip } from '../components/riskChips';

const register = (standing?: string) =>
  standing ? `${riskAssessmentPaths.register}?standing=${standing}` : riskAssessmentPaths.register;

/**
 * The S165 landing: where the site's assessments stand, what is coming due, what incidents have put back
 * on the review queue and what work has nothing current covering it. Every figure is one the service
 * published from `GET /dashboard` - nothing here is counted by the client - and every card opens the
 * records behind it.
 */
const RiskAssessmentDashboardPage = () => {
  const navigate = useNavigate();
  const [siteCode, setSiteCode] = useState(defaultSite);
  const query = useApiQuery(
    (signal) => (siteCode ? riskAssessmentApi.dashboard(siteCode, signal) : Promise.resolve<RiskDashboard | undefined>(undefined)),
    [siteCode],
  );
  const data = query.data;
  const standing = (value: Standing) => data?.byStanding[value] ?? 0;

  return (
    <div>
      <PageHeader
        title="Risk assessment library"
        subtitle="Whether the site's high-risk work stands on a current, signed-off assessment."
        crumbs={[{ label: 'Safety & security' }, { label: 'Risk assessments' }]}
        actions={<Button variant="outline" startIcon="refresh" onClick={query.refetch}>Refresh</Button>}
      />
      <div className="mb-5">
        <SectionCard>
          <div className="max-w-sm">
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
          </div>
        </SectionCard>
      </div>
      {!siteCode ? (
        <SectionCard>
          <p className="text-theme-sm text-gray-600">Choose a site. Assessments, flags and coverage are all per site.</p>
        </SectionCard>
      ) : (
        <DataState loading={query.initialising} error={query.error} onRetry={query.refetch}>
          {data && (
            <div className="space-y-5">
              <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
                <StatCard label="Current" value={standing('CURRENT')} icon="check-circle" tone="good" caption={`of ${data.totalAssessments} assessments`} onClick={() => navigate(register('PUBLISHED'))} />
                <StatCard label="Lapsed" value={standing('LAPSED')} icon="alert-triangle" tone={standing('LAPSED') ? 'critical' : 'good'} caption="Review date passed without sign-off" onClick={() => navigate(register('LAPSED'))} />
                <StatCard label="Awaiting independent sign-off" value={standing('AWAITING_INDEPENDENT_SIGN_OFF')} icon="user" tone={standing('AWAITING_INDEPENDENT_SIGN_OFF') ? 'caution' : 'good'} caption="High or critical, not yet current" onClick={() => navigate(register('AWAITING_INDEPENDENT_SIGN_OFF'))} />
                <StatCard label="Due for review soon" value={data.dueSoon} icon="clock" tone={data.dueSoon ? 'caution' : 'neutral'} caption="Inside the reminder window" onClick={() => navigate(register('PUBLISHED'))} />
                <StatCard label="Open review flags" value={data.openReviewFlags} icon="flag" tone={data.openReviewFlags ? 'caution' : 'good'} caption={`${data.deferredReviewFlags} deferred`} onClick={() => navigate(riskAssessmentPaths.reviewFlags)} />
                <StatCard label="Coverage gaps" value={data.coverageGaps} icon="target" tone={data.coverageGaps ? 'critical' : 'good'} caption="Work in use, nothing current covering it" onClick={permits('RISK_ASSESSMENT_ANALYTICS_READ') ? () => navigate(riskAssessmentPaths.coverage) : undefined} />
              </div>
              <div className="grid gap-5 lg:grid-cols-2">
                <SectionCard title="Where the assessments stand" subtitle={`${data.openDrafts} with a draft open`}>
                  <div className="space-y-3">
                    {(Object.keys(standingLabel) as Standing[]).map((value) => (
                      <button key={value} type="button" className="flex w-full items-center justify-between border-b border-gray-100 pb-3 text-left last:border-0" onClick={() => navigate(register(value === 'CURRENT' ? 'PUBLISHED' : value === 'NO_PUBLISHED_VERSION' ? 'DRAFT_ONLY' : value))}>
                        <StandingChip standing={value} />
                        <strong className="tabular-nums">{standing(value)}</strong>
                      </button>
                    ))}
                  </div>
                </SectionCard>
                <SectionCard title="Current assessments by risk level">
                  <div className="space-y-3">
                    {riskLevels.map((level) => (
                      <div key={level} className="flex items-center justify-between border-b border-gray-100 pb-3 last:border-0">
                        <RiskLevelChip level={level} />
                        <strong className="tabular-nums">{data.currentByRiskLevel[level] ?? 0}</strong>
                      </div>
                    ))}
                  </div>
                </SectionCard>
              </div>
              <SectionCard title="Most frequent hazards" subtitle="Across current assessments at this site, as the service ranks them.">
                {data.topHazards.length ? (
                  <>
                    <HazardFrequencyChart hazards={data.topHazards} height={220} />
                    <ul className="mt-3 grid gap-1 text-theme-xs text-gray-600 sm:grid-cols-2">
                      {data.topHazards.map((hazard) => (
                        <li key={hazard.hazardType}>
                          {hazardTypeLabel[hazard.hazardType]}: in {hazard.assessments} assessment{hazard.assessments === 1 ? '' : 's'}, highest residual {hazard.highestResidual}
                        </li>
                      ))}
                    </ul>
                  </>
                ) : (
                  <p className="text-theme-sm text-gray-600">No current assessments at this site yet.</p>
                )}
              </SectionCard>
            </div>
          )}
        </DataState>
      )}
    </div>
  );
};

export default RiskAssessmentDashboardPage;
