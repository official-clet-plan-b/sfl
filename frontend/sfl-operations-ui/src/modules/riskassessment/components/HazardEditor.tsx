import Button from 'shared/components/Button';
import { EnumSelect, TextInput } from 'shared/components/fields';
import type { HazardInput } from '../api/dto';
import { controlTypes, hazardTypeLabel, hazardTypes, likelihoods, severities } from '../api/enums';
import { previewLevel, previewScore } from '../api/workflow';
import { RiskLevelChip } from './riskChips';
import { emptyHazard } from './hazardForm';

interface HazardEditorProps {
  hazards: HazardInput[];
  onChange: (hazards: HazardInput[]) => void;
}

/**
 * The hazard and control editor - SRS-SFL-S165-01's "hazards, likelihood/severity rating, control
 * measures and residual risk".
 *
 * The scores shown are a preview of `RiskScore`, labelled as such: the figure that is stored, and shown
 * everywhere afterwards, is the service's. A hazard with no control is allowed in a draft and flagged
 * here, because S165-01 refuses it only at publish.
 */
const HazardEditor = ({ hazards, onChange }: HazardEditorProps) => {
  const update = (index: number, patch: Partial<HazardInput>) =>
    onChange(hazards.map((hazard, i) => (i === index ? { ...hazard, ...patch } : hazard)));
  const remove = (index: number) => onChange(hazards.filter((_, i) => i !== index));

  return (
    <div className="space-y-4">
      {hazards.length === 0 && (
        <p className="rounded-md border border-dashed border-gray-300 p-4 text-theme-sm text-gray-600">
          No hazards yet. An assessment needs at least one hazard, each with a control, before it can be published.
        </p>
      )}
      {hazards.map((hazard, index) => {
        const inherent = previewScore(hazard.inherentLikelihood, hazard.inherentSeverity);
        const residual = previewScore(hazard.residualLikelihood, hazard.residualSeverity);
        return (
          <fieldset key={index} className="rounded-md border border-gray-200 p-4">
            <legend className="px-1 text-theme-sm font-semibold text-gray-900">Hazard #{index + 1}</legend>
            <div className="grid gap-4 sm:grid-cols-2">
              <EnumSelect
                label="Hazard type"
                value={hazard.hazardType}
                options={hazardTypes}
                renderOptionLabel={(option) => hazardTypeLabel[option]}
                required
                onChange={(value) => value && update(index, { hazardType: value })}
              />
              <TextInput label="Who could be harmed" value={hazard.whoAtRisk ?? ''} maxLength={300} onChange={(value) => update(index, { whoAtRisk: value })} />
              <TextInput
                label="Hazard"
                className="sm:col-span-2"
                value={hazard.description}
                maxLength={1000}
                required
                error={!hazard.description.trim()}
                helperText={!hazard.description.trim() ? 'Describe the hazard.' : undefined}
                onChange={(value) => update(index, { description: value })}
              />
              <EnumSelect label="Likelihood before controls" value={hazard.inherentLikelihood} options={likelihoods} required onChange={(value) => value && update(index, { inherentLikelihood: value })} />
              <EnumSelect label="Severity before controls" value={hazard.inherentSeverity} options={severities} required onChange={(value) => value && update(index, { inherentSeverity: value })} />
              <EnumSelect label="Likelihood after controls" value={hazard.residualLikelihood} options={likelihoods} required onChange={(value) => value && update(index, { residualLikelihood: value })} />
              <EnumSelect label="Severity after controls" value={hazard.residualSeverity} options={severities} required onChange={(value) => value && update(index, { residualSeverity: value })} />
            </div>
            <p className="mt-3 flex flex-wrap items-center gap-2 text-theme-xs text-gray-600">
              Preview: {inherent} before controls <RiskLevelChip level={previewLevel(inherent)} /> &rarr; {residual} residual{' '}
              <RiskLevelChip level={previewLevel(residual)} />
            </p>

            <div className="mt-4 space-y-3">
              <p className="text-theme-sm font-medium text-gray-800">Control measures</p>
              {hazard.controls.length === 0 && (
                <p className="text-theme-xs font-medium text-warning-800">
                  No control yet - this hazard will stop the assessment being published (Hazard Without Control).
                </p>
              )}
              {hazard.controls.map((control, controlIndex) => (
                <div key={controlIndex} className="grid items-end gap-3 sm:grid-cols-[180px_1fr_auto]">
                  <EnumSelect
                    label="Control type"
                    value={control.controlType}
                    options={controlTypes}
                    required
                    onChange={(value) =>
                      value &&
                      update(index, {
                        controls: hazard.controls.map((c, i) => (i === controlIndex ? { ...c, controlType: value } : c)),
                      })
                    }
                  />
                  <TextInput
                    label="Control"
                    value={control.description}
                    maxLength={1000}
                    onChange={(value) =>
                      update(index, {
                        controls: hazard.controls.map((c, i) => (i === controlIndex ? { ...c, description: value } : c)),
                      })
                    }
                  />
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => update(index, { controls: hazard.controls.filter((_, i) => i !== controlIndex) })}
                  >
                    Remove
                  </Button>
                </div>
              ))}
              <div className="flex flex-wrap gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  startIcon="plus"
                  onClick={() => update(index, { controls: [...hazard.controls, { controlType: 'ADMINISTRATIVE', description: '' }] })}
                >
                  Add control
                </Button>
                <Button variant="ghost" size="sm" onClick={() => remove(index)}>
                  Remove hazard
                </Button>
              </div>
            </div>
          </fieldset>
        );
      })}
      <Button variant="outline" startIcon="plus" onClick={() => onChange([...hazards, emptyHazard()])}>
        Add hazard
      </Button>
    </div>
  );
};

export default HazardEditor;
