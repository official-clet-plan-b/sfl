import { useState } from 'react';
import Button from 'shared/components/Button';
import DataState from 'shared/components/DataState';
import PageHeader from 'shared/components/PageHeader';
import SectionCard from 'shared/components/SectionCard';
import StatusChip from 'shared/components/StatusChip';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { riskAssessmentPaths } from 'shared/layout/navigation';
import type { AssessmentTemplate } from '../api/dto';
import { riskAssessmentApi } from '../api/riskAssessmentApi';
import { RiskLevelChip } from '../components/riskChips';
import { ReviewIntervalsDialog, TemplateDialog } from '../dialogs/configurationDialogs';

/**
 * S165's configuration, global rather than per site: the review cycle per risk level (S165-02) and the
 * hazard/control template library (S165-01). Readable by anyone who reads assessments; changed by
 * `RISK_ASSESSMENT_CONFIGURE` only.
 */
const RiskConfigurationPage = () => {
  const intervals = useApiQuery((signal) => riskAssessmentApi.reviewIntervals(signal), []);
  const templates = useApiQuery((signal) => riskAssessmentApi.templates(false, signal), []);
  const [editingIntervals, setEditingIntervals] = useState(false);
  const [editingTemplate, setEditingTemplate] = useState<AssessmentTemplate | 'new' | null>(null);
  const canConfigure = permits('RISK_ASSESSMENT_CONFIGURE');

  return (
    <div>
      <PageHeader title="Templates and review cycle" subtitle="How often assessments are reviewed, and the starting points they are written from." crumbs={[{ label: 'Risk assessments', to: riskAssessmentPaths.dashboard }, { label: 'Configuration' }]} />
      <div className="space-y-5">
        <SectionCard
          title="Review cycle"
          subtitle="Higher risk is reviewed at least as often as lower. Changes apply from the next publish or sign-off."
          actions={canConfigure && intervals.data ? <Button variant="outline" startIcon="edit" onClick={() => setEditingIntervals(true)}>Change</Button> : undefined}
        >
          <DataState loading={intervals.initialising} error={intervals.error} onRetry={intervals.refetch}>
            <div className="divide-y divide-gray-100">
              {(intervals.data ?? []).map((interval) => (
                <div key={interval.riskLevel} className="flex flex-wrap items-center justify-between gap-3 py-3">
                  <RiskLevelChip level={interval.riskLevel} />
                  <span className="text-theme-sm text-gray-800">Every <strong>{interval.intervalDays}</strong> days · reminder <strong>{interval.reminderLeadDays}</strong> days ahead</span>
                  <span className="text-theme-xs text-gray-500">Last changed {formatDateTime(interval.metadata.lastModifiedAt)} by {interval.metadata.lastModifiedBy}</span>
                </div>
              ))}
            </div>
          </DataState>
        </SectionCard>
        <SectionCard
          title="Template library"
          subtitle="Copied into an assessment when it is created. Editing a template never changes an existing assessment."
          actions={canConfigure ? <Button variant="primary" startIcon="plus" onClick={() => setEditingTemplate('new')}>New template</Button> : undefined}
        >
          <DataState loading={templates.initialising} error={templates.error} onRetry={templates.refetch} empty={!templates.data?.length} emptyTitle="No templates yet" emptyHint="Assessments can still be written from a blank draft.">
            <div className="space-y-3">
              {(templates.data ?? []).map((template) => (
                <div key={template.id} className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-gray-200 p-4">
                  <div>
                    <p className="font-semibold text-gray-900">{template.name}</p>
                    <p className="text-theme-xs text-gray-600">
                      {template.activityType ?? 'No activity type'} · {template.hazards.length} hazard{template.hazards.length === 1 ? '' : 's'}
                      {template.description ? ` · ${template.description}` : ''}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    <StatusChip value={template.active ? 'ACTIVE' : 'INACTIVE'} label={template.active ? 'Offered' : 'Retired'} />
                    {canConfigure && <Button size="sm" variant="outline" onClick={() => setEditingTemplate(template)}>Edit</Button>}
                  </div>
                </div>
              ))}
            </div>
          </DataState>
        </SectionCard>
      </div>
      {editingIntervals && intervals.data && <ReviewIntervalsDialog intervals={intervals.data} onClose={() => setEditingIntervals(false)} onSaved={() => { setEditingIntervals(false); intervals.refetch(); }} />}
      {editingTemplate && <TemplateDialog template={editingTemplate === 'new' ? undefined : editingTemplate} onClose={() => setEditingTemplate(null)} onSaved={() => { setEditingTemplate(null); templates.refetch(); }} />}
    </div>
  );
};

export default RiskConfigurationPage;
