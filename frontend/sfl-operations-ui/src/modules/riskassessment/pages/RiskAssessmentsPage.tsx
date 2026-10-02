import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { Plus } from 'lucide-react';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDate } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { riskAssessmentPaths } from 'shared/layout/navigation';
import type { AssessmentPage, AssessmentSummary } from '../api/dto';
import { riskLevels, standingFilters, type RiskLevel, type StandingFilter } from '../api/enums';
import { riskAssessmentApi } from '../api/riskAssessmentApi';
import { RiskLevelChip, StandingChip } from '../components/riskChips';
import { CreateAssessmentDialog } from '../dialogs/assessmentDialogs';
import { Button, Dropdown, PageSection, useTableState, type TableColumn } from '@rfdtech/components';
import { humanise } from 'modules/fleet/api/enums';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import RegisterTable, { CellStack, DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from 'modules/emergency/components/RegisterTable';

const standingFilterLabel: Record<StandingFilter, string> = {
  PUBLISHED: 'Published',
  DRAFT_ONLY: 'Draft only',
  AWAITING_INDEPENDENT_SIGN_OFF: 'Awaiting independent sign-off',
  LAPSED: 'Lapsed',
};

/**
 * The S165 register. Server-paginated and filtered; ordered by review date, soonest first, because the
 * question this screen answers most is "what needs signing off next". Each row's standing is the shared
 * currency rule's answer at the moment the service read it.
 */
const RiskAssessmentsPage = () => {
  const navigate = useNavigate();
  const [siteCode, setSiteCode] = useState(defaultSite);
  const { page, pageSize, setPage, filters, search } = useTableState({ paramPrefix: 'assessments', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const requested = filters.standing as StandingFilter | undefined;
  const standing: StandingFilter | '' = requested && standingFilters.includes(requested) ? requested : '';
  const riskLevel = (filters.riskLevel ?? '') as RiskLevel | '';
  const activityType = filters.activityType ?? '';
  const text = search;
  const [standingField, setStandingField] = useState(standing);
  const [riskLevelField, setRiskLevelField] = useState(riskLevel);
  const [activityTypeField, setActivityTypeField] = useState(activityType);
  const [creating, setCreating] = useState(false);

  const query = useApiQuery(
    (signal) =>
      siteCode
        ? riskAssessmentApi.search({ siteCode, standing: standing || undefined, riskLevel: riskLevel || undefined, activityType: activityType || undefined, q: text.trim() || undefined, page: page - 1, size: pageSize }, signal)
        : Promise.resolve<AssessmentPage | undefined>(undefined),
    [siteCode, standing, riskLevel, activityType, text, page, pageSize],
  );
  const activityTypes = useApiQuery(
    (signal) => (siteCode ? riskAssessmentApi.activityTypes(siteCode, signal) : Promise.resolve<string[]>([])),
    [siteCode],
  );

  const columns = useMemo<TableColumn<AssessmentSummary>[]>(() => [
    { id: 'assessment', header: 'Assessment', width: 300, cell: ({ row }) => <CellStack primary={row.reference} secondary={row.title} /> },
    { id: 'scope', header: 'Scope', width: 200, cell: ({ row }) => <CellStack primary={row.activityType ?? '-'} secondary={row.locationCode ? `Location ${row.locationCode}` : undefined} /> },
    { id: 'level', header: 'Risk', width: 110, cell: ({ row }) => <RiskLevelChip level={row.riskLevel} /> },
    { id: 'version', header: 'Version', width: 120, hideBelowLg: true, cell: ({ row }) => <CellStack primary={row.currentVersion ? `v${row.currentVersion} current` : 'Not published'} secondary={row.draftVersion ? `v${row.draftVersion} draft open` : undefined} /> },
    { id: 'due', header: 'Review due', width: 130, hideBelowLg: true, cell: ({ row }) => formatDate(row.reviewDueAt) },
    { id: 'standing', header: 'Standing', width: 220, align: 'right', cell: ({ row }) => <StandingChip standing={row.standing} /> },
  ], []);

  return (
    <>
      <PageHeading
        title="Assessment register"
        subtitle="Every assessment at the site, current or not. Superseded versions stay readable from each assessment."
        crumbs={[{ label: 'Risk assessments', to: riskAssessmentPaths.dashboard }, { label: 'Register' }]}
        actions={
          <>
            <SiteSelect label="Site" value={siteCode} onChange={(value) => { setSiteCode(value); setActivityTypeField(''); setPage(1); }} required className="w-44" />
            {permits('RISK_ASSESSMENT_AUTHOR') && <Button variant="primary" onClick={() => setCreating(true)} disabled={!siteCode}><Plus size={14} strokeWidth={1.5} aria-hidden /> New assessment</Button>}
          </>
        }
      />
      <PageSection>
        <Panel title="Assessments" subtitle="Ordered by review date, soonest first.">
          <RegisterTable
            paramPrefix="assessments"
            framed={false}
            columns={columns}
            rows={query.data?.content ?? []}
            rowKey={(row) => row.id}
            loading={query.initialising}
            onRowClick={(row) => navigate(riskAssessmentPaths.detail(row.id))}
            emptyTitle={siteCode ? 'No assessments match these filters' : 'Choose a site to see its assessments'}
            emptyDescription={siteCode ? 'Widen the standing, risk level or activity type, or clear the search.' : undefined}
            searchPlaceholder="Search reference, title or location"
            totalItems={query.data?.totalElements ?? 0}
            size={pageSize}
            filterCount={3}
            filters={<>
              <Dropdown name="standing" aria-label="Filter by standing" value={standingField || null} onValueChange={(next) => setStandingField((next ?? '') as StandingFilter | '')} options={standingFilters.map((value) => ({ value, label: standingFilterLabel[value] }))} placeholder="All standings" clearable />
              <Dropdown name="riskLevel" aria-label="Filter by risk level" value={riskLevelField || null} onValueChange={(next) => setRiskLevelField((next ?? '') as RiskLevel | '')} options={riskLevels.map((value) => ({ value, label: humanise(value) }))} placeholder="All risk levels" clearable />
              <Dropdown name="activityType" aria-label="Filter by activity type" value={activityTypeField || null} onValueChange={(next) => setActivityTypeField(next ?? '')} options={(activityTypes.data ?? []).map((value) => ({ value, label: value }))} placeholder="All activity types" clearable />
            </>}
          />
        </Panel>
      </PageSection>
      {creating && (
        <CreateAssessmentDialog
          siteCode={siteCode}
          onClose={() => setCreating(false)}
          onCreated={(detail) => { setCreating(false); query.refetch(); navigate(riskAssessmentPaths.detail(detail.assessment.id)); }}
        />
      )}
    </>
  );
};

export default RiskAssessmentsPage;
