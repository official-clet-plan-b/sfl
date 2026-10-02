import { SelectInput, TextInput } from 'shared/components/fields';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import type { AssessmentPage } from '../api/dto';
import { standingLabel } from '../api/enums';
import { riskAssessmentApi } from '../api/riskAssessmentApi';

interface RiskContextFieldsProps {
  siteCode: string;
  riskAssessmentId: string;
  activityType: string;
  onRiskAssessmentChange: (value: string) => void;
  onActivityTypeChange: (value: string) => void;
}

/**
 * The S165-04 fields an incident carries: the assessment the work was done under, and the activity.
 *
 * The assessment is chosen from the site's published assessments - never typed as an id (playbook §9.16)
 * - and the activity is suggested from what the site actually does. Both need `RISK_ASSESSMENT_READ` to
 * offer; a reporter without it still records the activity as free text, which the service normalises and
 * matches the same way.
 */
const RiskContextFields = ({ siteCode, riskAssessmentId, activityType, onRiskAssessmentChange, onActivityTypeChange }: RiskContextFieldsProps) => {
  const canRead = permits('RISK_ASSESSMENT_READ');
  const assessments = useApiQuery(
    (signal) =>
      canRead && siteCode
        ? riskAssessmentApi.search({ siteCode, standing: 'PUBLISHED', page: 0, size: 200, sort: 'reference' }, signal)
        : Promise.resolve<AssessmentPage | undefined>(undefined),
    [canRead, siteCode],
  );
  const activityTypes = useApiQuery(
    (signal) => (canRead && siteCode ? riskAssessmentApi.activityTypes(siteCode, signal) : Promise.resolve<string[]>([])),
    [canRead, siteCode],
  );
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <TextInput
        label="Activity under way"
        value={activityType}
        maxLength={80}
        suggestions={activityTypes.data ?? []}
        onChange={onActivityTypeChange}
        helperText="e.g. Hot work. Flags every published assessment for that activity here."
      />
      {canRead ? (
        <SelectInput
          label="Risk assessment the work was under"
          value={riskAssessmentId}
          allowEmpty
          emptyLabel="Not known"
          options={(assessments.data?.content ?? []).map((assessment) => ({
            value: assessment.id,
            label: `${assessment.reference} · ${assessment.title} (${standingLabel[assessment.standing]})`,
          }))}
          helperText="Published assessments at this site. Linking one flags it for review."
          onChange={onRiskAssessmentChange}
        />
      ) : null}
    </div>
  );
};

export default RiskContextFields;
