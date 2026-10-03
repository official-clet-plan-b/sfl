import { apiClient } from 'shared/api/client';
import type { CapaCounts, CorrectiveAction, IncidentDashboard, IncidentEvidence, IncidentPage, IncidentSearchParams, SecurityIncident } from './dto';

const service = 'safetySecurity' as const;
const base = '/api/v1/incidents';

export const incidentApi = {
  search: (params: IncidentSearchParams, signal?: AbortSignal) => apiClient.get<IncidentPage>(base, params, signal, service),
  get: (id: string, signal?: AbortSignal) => apiClient.get<SecurityIncident>(`${base}/${id}`, undefined, signal, service),
  evidence: (id: string, signal?: AbortSignal) => apiClient.get<IncidentEvidence[]>(`${base}/${id}/evidence`, undefined, signal, service),
  correctiveActions: (id: string, signal?: AbortSignal) => apiClient.get<CorrectiveAction[]>(`${base}/${id}/corrective-actions`, undefined, signal, service),
  dashboard: (siteCode: string, signal?: AbortSignal) => apiClient.get<IncidentDashboard>(`${base}/dashboard`, { siteCode }, signal, service),
  correctiveActionSummary: (siteCode: string, signal?: AbortSignal) => apiClient.get<CapaCounts>(`${base}/corrective-actions/summary`, { siteCode }, signal, service),
  report: (body: { siteCode: string; source: string; anonymous: boolean; reporterId?: string; reporterContact?: string; description: string; nearMiss: boolean; riskAssessmentId?: string; activityType?: string }) => apiClient.post<SecurityIncident>(base, body, { service }),
  /** Phase 2 S165-04: saving it flags the linked assessment, or every published one for the activity, for review. */
  recordRiskContext: (id: string, body: { riskAssessmentId?: string; activityType?: string; expectedVersion: number }) => apiClient.patch<SecurityIncident>(`${base}/${id}/risk-context`, body, { service }),
  triage: (id: string, body: unknown) => apiClient.patch<SecurityIncident>(`${base}/${id}/triage`, body, { service }),
  investigate: (id: string, body: unknown) => apiClient.patch<SecurityIncident>(`${base}/${id}/investigation`, body, { service }),
  attachEvidence: (id: string, body: unknown) => apiClient.post<IncidentEvidence>(`${base}/${id}/evidence`, body, { service, idempotent: false }),
  openCapa: (id: string, body: unknown) => apiClient.post<CorrectiveAction>(`${base}/${id}/corrective-actions`, body, { service, idempotent: false }),
  transitionCapa: (incidentId: string, capaId: string, body: unknown) => apiClient.patch<CorrectiveAction>(`${base}/${incidentId}/corrective-actions/${capaId}`, body, { service }),
  close: (id: string, closureNotes: string, expectedVersion: number) => apiClient.patch<SecurityIncident>(`${base}/${id}/closure`, { closureNotes, expectedVersion }, { service }),
};
