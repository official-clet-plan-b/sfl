import type { Hazard, HazardInput } from '../api/dto';

/** A blank hazard for the editor - every rating starts at the middle of its scale, never at the safe end. */
export const emptyHazard = (): HazardInput => ({
  hazardType: 'OTHER',
  description: '',
  whoAtRisk: '',
  inherentLikelihood: 'POSSIBLE',
  inherentSeverity: 'MODERATE',
  residualLikelihood: 'POSSIBLE',
  residualSeverity: 'MODERATE',
  controls: [],
});

/** A stored hazard, as the editor and the draft request carry it. */
export const toHazardInput = (hazard: Hazard): HazardInput => ({
  hazardType: hazard.hazardType,
  description: hazard.description,
  whoAtRisk: hazard.whoAtRisk ?? '',
  inherentLikelihood: hazard.inherentRisk.likelihood,
  inherentSeverity: hazard.inherentRisk.severity,
  residualLikelihood: hazard.residualRisk.likelihood,
  residualSeverity: hazard.residualRisk.severity,
  controls: hazard.controls.map((control) => ({ ...control })),
});

/** What is sent: trimmed, blank optional text dropped, blank controls left out rather than refused. */
export const toRequestHazards = (hazards: HazardInput[]): HazardInput[] =>
  hazards.map((hazard) => ({
    ...hazard,
    description: hazard.description.trim(),
    whoAtRisk: hazard.whoAtRisk?.trim() || undefined,
    controls: hazard.controls
      .map((control) => ({ ...control, description: control.description.trim() }))
      .filter((control) => control.description.length > 0),
  }));

/** Every hazard needs a description before the service will take the draft at all (`@NotBlank`). */
export const hazardsIncomplete = (hazards: HazardInput[]) => hazards.some((hazard) => !hazard.description.trim());
