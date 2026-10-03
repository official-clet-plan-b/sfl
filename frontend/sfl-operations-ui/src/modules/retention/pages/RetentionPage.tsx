import { useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { Button, PageSection } from '@rfdtech/components';
import { FormDialog, NumberInput, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';
import DataState from 'shared/components/DataState';
import { formatDate } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { useSubmit } from 'shared/hooks/useSubmit';
import { permits } from 'shared/layout/actorPermissions';
import { facilitiesPaths } from 'shared/layout/navigation';
import { humanise } from 'modules/fleet/api/enums';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import { retentionApi, type RetentionPolicy } from '../api/retentionApi';

const systemNames: Record<string, string> = { S170: 'Hygiene & pest control', S172: 'Catering & cafeteria', S177: 'Lease & tenancy', S178: 'Waste & recycling', S179: 'Lost & found' };

const PolicyDialog = ({ policy, onClose, onDone }: { policy: RetentionPolicy; onClose: () => void; onDone: () => void }) => {
  const [days, setDays] = useState(String(policy.retentionDays));
  const [basis, setBasis] = useState(policy.basis);
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title={`${systemNames[policy.systemCode] ?? policy.systemCode}: ${humanise(policy.recordClass)}`}
      description={policy.action === 'ANONYMISE' ? 'After this many days personal data is anonymised: the person reference is replaced and the need is kept.' : 'After this many days evidence is reported for authorised disposal. Nothing is deleted automatically.'}
      submitLabel="Save period" submitting={submitting} submitDisabled={!Number(days) || !basis.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(() => retentionApi.set(policy, Number(days), basis.trim()), onDone)}>
      <NumberInput label="Keep for (days)" required value={days} onChange={setDays} />
      <TextAreaInput label="Basis" required value={basis} onChange={setBasis} maxLength={240} helperText="What makes this the period: a statute, a policy, a decision and its reference." />
    </FormDialog>
  );
};

/**
 * How long each class of record is kept, and what has outlived it. The periods are the placeholders the SRS says need
 * statutory confirmation; changing one is recorded in the audit trail with its basis.
 */
const RetentionPage = () => {
  const [refresh, setRefresh] = useState(0);
  const [editing, setEditing] = useState<RetentionPolicy>();
  const canManage = permits('FACILITIES_RETENTION_MANAGE');
  const bump = () => setRefresh((value) => value + 1);
  const policies = useApiQuery((signal) => retentionApi.policies(signal), [refresh]);
  const due = useApiQuery((signal) => retentionApi.due(undefined, signal), [refresh]);
  return (
    <>
      <PageHeading title="Record retention" subtitle="How long each class of evidence and personal data is kept, and what has outlived its period."
        crumbs={[{ label: 'Facilities', to: facilitiesPaths.dashboard }, { label: 'Record retention' }]}
        actions={<Button variant="outline" onClick={bump}><RefreshCw size={14} strokeWidth={1.5} aria-hidden /> Refresh</Button>} />
      <PageSection>
        <Panel title="Retention periods" subtitle="Defaults are placeholders pending statutory confirmation. Evidence past its period is reported for authorised disposal, never deleted automatically.">
          <DataState loading={policies.initialising} error={policies.error} onRetry={policies.refetch}>
            <ul className="divide-y divide-border rounded-lg border border-border">
              {(policies.data ?? []).map((p) => (
                <li key={`${p.systemCode}-${p.recordClass}`} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-sm">
                  <div>
                    <p className="font-medium">{systemNames[p.systemCode] ?? p.systemCode} · {humanise(p.recordClass)}</p>
                    <p className="text-theme-xs text-gray-600">{p.retentionDays} days · {p.action === 'ANONYMISE' ? 'anonymised afterwards' : 'reported for disposal afterwards'} · {p.basis}</p>
                  </div>
                  {canManage && <Button size="sm" variant="outline" onClick={() => setEditing(p)}>Change</Button>}
                </li>
              ))}
            </ul>
          </DataState>
        </Panel>
      </PageSection>
      <PageSection>
        <Panel title="Past their period" subtitle="Evidence whose retention period has ended. Disposal needs authorisation and is outside this screen.">
          <DataState loading={due.initialising} error={due.error} onRetry={due.refetch}>
            {(due.data ?? []).length === 0 ? <p className="text-theme-sm text-gray-600">Nothing has outlived its period.</p> : (
              <ul className="divide-y divide-border rounded-lg border border-border">
                {(due.data ?? []).map((d) => (
                  <li key={`${d.systemCode}-${d.evidenceId}`} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-sm">
                    <span>{systemNames[d.systemCode] ?? d.systemCode} · {d.reference} · {d.fileName}</span>
                    <span className="text-theme-xs text-gray-600">{d.siteCode} · {humanise(d.recordClass)} · due since {formatDate(d.dueSince)}</span>
                  </li>
                ))}
              </ul>
            )}
          </DataState>
        </Panel>
      </PageSection>
      {editing && <PolicyDialog policy={editing} onClose={() => setEditing(undefined)} onDone={() => { setEditing(undefined); bump(); }} />}
    </>
  );
};

export default RetentionPage;
