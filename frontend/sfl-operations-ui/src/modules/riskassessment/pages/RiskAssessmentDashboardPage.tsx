import { useState } from 'react';
import { useNavigate } from 'react-router';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { riskAssessmentPaths } from 'shared/layout/navigation';
import type { RiskDashboard } from '../api/dto';
import { hazardTypeLabel, riskLevels, standingLabel, type Standing } from '../api/enums';
import { riskAssessmentApi } from '../api/riskAssessmentApi';
import HazardFrequencyChart from '../charts/HazardFrequencyChart';
import { RiskLevelChip, StandingChip } from '../components/riskChips';
import { RefreshCw } from 'lucide-react';
import { Button, MetricCards, PageSection } from '@rfdtech/components';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import StatMetric from 'modules/emergency/components/StatMetric';

const register = (standing?: string) =>
  standing ? `${riskAssessmentPaths.register}?assessments.f_standing=${standing}` : riskAssessmentPaths.register;

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
    <>
      <PageHeading
        title="Risk assessment library"
        subtitle="Whether the site's high-risk work stands on a current, signed-off assessment."
        crumbs={[{ label: 'Safety & security' }, { label: 'Risk assessments' }]}
        actions={
          <>
            <SiteSelect label="Site" value={siteCode} onChange={setSiteCode} required className="w-44" />
            <Button variant="outline" onClick={query.refetch}><RefreshCw size={14} strokeWidth={1.5} aria-hidden /> Refresh</Button>
          </>
        }
      />
      {!siteCode ? (
        <PageSection>
          <Panel>
            <p className="text-theme-sm text-[var(--clet-text-secondary)]">Choose a site. Assessments, flags and coverage are all per site.</p>
          </Panel>
        </PageSection>
      ) : (
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <PageSection>
            <MetricCards>
              <StatMetric label="Current" value={standing('CURRENT')} icon="check-circle" tone="good" caption={`of ${data?.totalAssessments ?? 0} assessments`} loading={query.initialising} onClick={() => navigate(register('PUBLISHED'))} />
              <StatMetric label="Lapsed" value={standing('LAPSED')} icon="alert-triangle" tone={standing('LAPSED') ? 'critical' : 'good'} caption="Review date passed without sign-off" loading={query.initialising} onClick={() => navigate(register('LAPSED'))} />
              <StatMetric label="Awaiting independent sign-off" value={standing('AWAITING_INDEPENDENT_SIGN_OFF')} icon="user" tone={standing('AWAITING_INDEPENDENT_SIGN_OFF') ? 'caution' : 'good'} caption="High or critical, not yet current" loading={query.initialising} onClick={() => navigate(register('AWAITING_INDEPENDENT_SIGN_OFF'))} />
            </MetricCards>
          </PageSection>
          <PageSection>
            <MetricCards>
              <StatMetric label="Due for review soon" value={data?.dueSoon ?? 0} icon="clock" tone={data?.dueSoon ? 'caution' : 'neutral'} caption="Inside the reminder window" loading={query.initialising} onClick={() => navigate(register('PUBLISHED'))} />
              <StatMetric label="Open review flags" value={data?.openReviewFlags ?? 0} icon="flag" tone={data?.openReviewFlags ? 'caution' : 'good'} caption={`${data?.deferredReviewFlags ?? 0} deferred`} loading={query.initialising} onClick={() => navigate(riskAssessmentPaths.reviewFlags)} />
              <StatMetric label="Coverage gaps" value={data?.coverageGaps ?? 0} icon="target" tone={data?.coverageGaps ? 'critical' : 'good'} caption="Work in use, nothing current covering it" loading={query.initialising} onClick={permits('RISK_ASSESSMENT_ANALYTICS_READ') ? () => navigate(riskAssessmentPaths.coverage) : undefined} />
            </MetricCards>
          </PageSection>
          {data && (
            <>
              <PageSection>
                <div className="grid gap-5 lg:grid-cols-2">
                  <Panel title="Where the assessments stand" subtitle={`${data.openDrafts} with a draft open`}>
                    <div className="space-y-3">
                      {(Object.keys(standingLabel) as Standing[]).map((value) => (
                        <button key={value} type="button" className="flex w-full items-center justify-between border-b border-[var(--clet-border-subtle)] pb-3 text-left last:border-0" onClick={() => navigate(register(value === 'CURRENT' ? 'PUBLISHED' : value === 'NO_PUBLISHED_VERSION' ? 'DRAFT_ONLY' : value))}>
                          <StandingChip standing={value} />
                          <strong className="tabular-nums">{standing(value)}</strong>
                        </button>
                      ))}
                    </div>
                  </Panel>
                  <Panel title="Current assessments by risk level">
                    <div className="space-y-3">
                      {riskLevels.map((level) => (
                        <div key={level} className="flex items-center justify-between border-b border-[var(--clet-border-subtle)] pb-3 last:border-0">
                          <RiskLevelChip level={level} />
                          <strong className="tabular-nums">{data.currentByRiskLevel[level] ?? 0}</strong>
                        </div>
                      ))}
                    </div>
                  </Panel>
                </div>
              </PageSection>
              <PageSection>
                <Panel title="Most frequent hazards" subtitle="Across current assessments at this site, as the service ranks them.">
                  {data.topHazards.length ? (
                    <>
                      <HazardFrequencyChart hazards={data.topHazards} height={220} />
                      <ul className="mt-3 grid gap-1 text-theme-xs text-[var(--clet-text-secondary)] sm:grid-cols-2">
                        {data.topHazards.map((hazard) => (
                          <li key={hazard.hazardType}>
                            {hazardTypeLabel[hazard.hazardType]}: in {hazard.assessments} assessment{hazard.assessments === 1 ? '' : 's'}, highest residual {hazard.highestResidual}
                          </li>
                        ))}
                      </ul>
                    </>
                  ) : (
                    <p className="text-theme-sm text-[var(--clet-text-secondary)]">No current assessments at this site yet.</p>
                  )}
                </Panel>
              </PageSection>
            </>
          )}
        </DataState>
      )}
    </>
  );
};

export default RiskAssessmentDashboardPage;
