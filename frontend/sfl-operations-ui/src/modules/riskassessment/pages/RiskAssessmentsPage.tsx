import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import Button from 'shared/components/Button';
import DataTable, { CellStack, type Column } from 'shared/components/DataTable';
import PageHeader from 'shared/components/PageHeader';
import SectionCard from 'shared/components/SectionCard';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { EnumSelect, SelectInput, TextInput } from 'shared/components/fields';
import { formatDate } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { riskAssessmentPaths } from 'shared/layout/navigation';
import type { AssessmentPage, AssessmentSummary } from '../api/dto';
import { riskLevels, standingFilters, type RiskLevel, type StandingFilter } from '../api/enums';
import { riskAssessmentApi } from '../api/riskAssessmentApi';
import { RiskLevelChip, StandingChip } from '../components/riskChips';
import { CreateAssessmentDialog } from '../dialogs/assessmentDialogs';

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
  const [searchParams] = useSearchParams();
  const requested = searchParams.get('standing') as StandingFilter | null;
  const [siteCode, setSiteCode] = useState(defaultSite);
  const [standing, setStanding] = useState<StandingFilter | ''>(requested && standingFilters.includes(requested) ? requested : '');
  const [riskLevel, setRiskLevel] = useState<RiskLevel | ''>('');
  const [activityType, setActivityType] = useState(searchParams.get('activityType') ?? '');
  const [text, setText] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [creating, setCreating] = useState(false);

  const query = useApiQuery(
    (signal) =>
      siteCode
        ? riskAssessmentApi.search({ siteCode, standing: standing || undefined, riskLevel: riskLevel || undefined, activityType: activityType || undefined, q: text.trim() || undefined, page, size }, signal)
        : Promise.resolve<AssessmentPage | undefined>(undefined),
    [siteCode, standing, riskLevel, activityType, text, page, size],
  );
  const activityTypes = useApiQuery(
    (signal) => (siteCode ? riskAssessmentApi.activityTypes(siteCode, signal) : Promise.resolve<string[]>([])),
    [siteCode],
  );

  const columns = useMemo<Column<AssessmentSummary>[]>(() => [
    { key: 'assessment', header: 'Assessment', width: 300, cell: (row) => <CellStack primary={row.reference} secondary={row.title} /> },
    { key: 'scope', header: 'Scope', width: 200, cell: (row) => <CellStack primary={row.activityType ?? '-'} secondary={row.locationCode ? `Location ${row.locationCode}` : undefined} /> },
    { key: 'level', header: 'Risk', width: 110, cell: (row) => <RiskLevelChip level={row.riskLevel} /> },
    { key: 'version', header: 'Version', width: 120, hideBelowLg: true, cell: (row) => <CellStack primary={row.currentVersion ? `v${row.currentVersion} current` : 'Not published'} secondary={row.draftVersion ? `v${row.draftVersion} draft open` : undefined} /> },
    { key: 'due', header: 'Review due', width: 130, hideBelowLg: true, cell: (row) => formatDate(row.reviewDueAt) },
    { key: 'standing', header: 'Standing', width: 220, align: 'right', cell: (row) => <StandingChip standing={row.standing} /> },
  ], []);

  const reset = () => setPage(0);
  return (
    <div>
      <PageHeader
        title="Assessment register"
        subtitle="Every assessment at the site, current or not. Superseded versions stay readable from each assessment."
        crumbs={[{ label: 'Risk assessments', to: riskAssessmentPaths.dashboard }, { label: 'Register' }]}
        actions={permits('RISK_ASSESSMENT_AUTHOR') ? <Button variant="primary" startIcon="plus" onClick={() => setCreating(true)} disabled={!siteCode}>New assessment</Button> : undefined}
      />
      <SectionCard title="Assessments" subtitle="Ordered by review date, soonest first." flush>
        <div className="grid gap-4 px-5 py-4 sm:grid-cols-2 xl:grid-cols-5">
          <SiteSelect value={siteCode} onChange={(value) => { setSiteCode(value); setActivityType(''); reset(); }} required />
          <EnumSelect label="Standing" value={standing} options={standingFilters} allowEmpty renderOptionLabel={(option) => standingFilterLabel[option]} onChange={(value) => { setStanding(value); reset(); }} />
          <EnumSelect label="Risk level" value={riskLevel} options={riskLevels} allowEmpty onChange={(value) => { setRiskLevel(value); reset(); }} />
          <SelectInput label="Activity type" value={activityType} allowEmpty options={(activityTypes.data ?? []).map((value) => ({ value, label: value }))} onChange={(value) => { setActivityType(value); reset(); }} />
          <TextInput label="Search" value={text} placeholder="Reference, title or location" onChange={(value) => { setText(value); reset(); }} />
        </div>
        <DataTable
          rows={query.data?.content ?? []}
          columns={columns}
          getRowId={(row) => row.id}
          loading={query.loading}
          onRowClick={(row) => navigate(riskAssessmentPaths.detail(row.id))}
          caption="Risk assessments"
          page={page}
          pageSize={size}
          totalElements={query.data?.totalElements ?? 0}
          onPageChange={setPage}
          onPageSizeChange={(value) => { setSize(value); reset(); }}
          emptyMessage={siteCode ? 'No assessments match these filters.' : 'Choose a site to see its assessments.'}
        />
      </SectionCard>
      {creating && (
        <CreateAssessmentDialog
          siteCode={siteCode}
          onClose={() => setCreating(false)}
          onCreated={(detail) => { setCreating(false); query.refetch(); navigate(riskAssessmentPaths.detail(detail.assessment.id)); }}
        />
      )}
    </div>
  );
};

export default RiskAssessmentsPage;
