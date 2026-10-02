import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import DataState from 'shared/components/DataState';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import { exportEvidence, getEvidence, setEvidenceLegalHold } from '../api/facilitiesApi';
import { exportEvidenceAction } from '../api/workflow';
import ExportEvidenceDialog from '../dialogs/ExportEvidenceDialog';
import TransitionNoteDialog from '../dialogs/TransitionNoteDialog';
import { formatDate, formatDateTime, humaniseCode, orDash } from '../components/facilitiesFormat';
import { Banner, Button } from '@rfdtech/components';
import PageHeading from 'modules/emergency/components/PageHeading';
import TitledSection from '../components/TitledSection';
import StatusBadge from '../components/StatusBadge';

/**
 * One piece of evidence - SRS-SFL-S153-03.
 *
 * ## Metadata only, and deliberately so
 *
 * There is no preview and no download button, because this service has never held the file. It holds
 * where the file is, what it hashed to when CLET accepted it, who put it there and how long it must
 * be kept. That is the architecture standard's "evidence by reference", and it is what makes the
 * hash meaningful: if the stored object no longer hashes to this value, the object changed after
 * acceptance, and the audit trail can say so with a date.
 *
 * ## Export is an act, not a stronger read
 *
 * It takes its own permission, held only by reviewers, and requires a recorded reason and a named
 * recipient. The service audits it **before** handing anything back, so a reason recorded after a
 * successful export is not a thing that can happen.
 *
 * ## A legal hold suspends disposal without losing the classification
 *
 * Setting one leaves the retention class alone. Modelling a hold as a reclassification would lose
 * the original decision the moment the hold was applied, leaving nothing to return to when it is
 * lifted.
 */
const EvidenceDetailPage = () => {
  const { evidenceId = '' } = useParams();
  const navigate = useNavigate();
  const notify = useNotifier();
  const [exporting, setExporting] = useState(false);
  const [holding, setHolding] = useState(false);

  const evidence = useApiQuery((signal) => getEvidence(evidenceId, signal), [evidenceId]);
  const canExport = exportEvidenceAction();

  return (
    <>
      <DataState
        loading={evidence.loading}
        error={evidence.error}
        onRetry={evidence.refetch}
        minHeight={240}
      >
        {evidence.data && (
          <>
            <PageHeading
              title={orDash(evidence.data.fileName) === '-' ? 'Evidence' : evidence.data.fileName!}
              subtitle={`${humaniseCode(evidence.data.evidenceType)} · attached by ${evidence.data.uploadedBy}`}
              crumbs={[
                { label: 'Facilities', to: facilitiesPaths.dashboard },
                { label: 'Work orders', to: facilitiesPaths.workOrders },
                { label: 'Evidence' },
              ]}
              actions={
                <div className="flex flex-wrap gap-2">
                  <Button
                    variant="outline"
                    onClick={() =>
                      navigate(facilitiesPaths.workOrderDetail(evidence.data!.workOrderId))
                    }
                  >
                    Open the work order
                  </Button>
                  {canExport.allowed && (
                    <>
                      <Button variant="outline" onClick={() => setHolding(true)}>
                        {evidence.data.legalHold ? 'Lift legal hold' : 'Place legal hold'}
                      </Button>
                      <Button variant="primary" onClick={() => setExporting(true)}>
                        Export
                      </Button>
                    </>
                  )}
                </div>
              }
            />

            <div className="space-y-5">
              {evidence.data.legalHold && (
                <Banner variant="warning" heading="Under legal hold" subtext="Disposal is suspended indefinitely. The retention class below is unchanged and applies again once the hold is lifted." />
              )}

              {!evidence.data.supportsClosure && (
                <Banner variant="info" heading="Does not count towards closure" subtext="An invoice proves money was spent, not that the work was done. Closure evidence exists to prove the second thing." />
              )}

              <TitledSection title="Evidence record">
                <KeyValueGrid
                  items={[
                    { label: 'Type', value: humaniseCode(evidence.data.evidenceType) },
                    { label: 'File name', value: orDash(evidence.data.fileName) },
                    { label: 'Media type', value: orDash(evidence.data.mediaType) },
                    {
                      label: 'Size',
                      value: evidence.data.sizeBytes
                        ? `${Math.round(evidence.data.sizeBytes / 1024)} KB`
                        : '-',
                    },
                    {
                      label: 'Retention',
                      value: (
                        <StatusBadge
                          value={humaniseCode(evidence.data.retentionClass)}
                          tone="neutral"
                        />
                      ),
                    },
                    {
                      label: 'Disposal eligible',
                      value: evidence.data.disposalEligibleFrom
                        ? formatDate(evidence.data.disposalEligibleFrom)
                        : 'Suspended by a legal hold',
                    },
                    { label: 'Attached by', value: evidence.data.uploadedBy },
                    { label: 'Attached', value: formatDateTime(evidence.data.uploadedAt) },
                    { label: 'Site', value: evidence.data.siteCode },
                  ]}
                />
                {evidence.data.notes && (
                  <p className="mt-3 whitespace-pre-line text-theme-sm text-gray-800">
                    {evidence.data.notes}
                  </p>
                )}
              </TitledSection>

              <TitledSection
                title="Where the file is"
                description="This service stores the reference and the digest, never the bytes"
              >
                <dl className="space-y-3">
                  <div>
                    <dt className="text-theme-xs font-medium text-gray-500">Reference</dt>
                    <dd className="mt-0.5 break-all font-mono text-theme-sm text-gray-800">
                      {evidence.data.fileReference}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-theme-xs font-medium text-gray-500">SHA-256 at acceptance</dt>
                    <dd className="mt-0.5 break-all font-mono text-theme-xs text-gray-800">
                      {evidence.data.contentHash}
                    </dd>
                  </div>
                </dl>
                <p className="mt-3 text-theme-xs text-gray-500">
                  If the stored object no longer hashes to this value, it changed after CLET accepted
                  it.
                </p>
              </TitledSection>
            </div>
          </>
        )}
      </DataState>

      {exporting && evidence.data && (
        <ExportEvidenceDialog
          evidence={evidence.data}
          onClose={() => setExporting(false)}
          onSubmit={async (request) => {
            const grant = await exportEvidence(evidenceId, request);
            setExporting(false);
            notify.notifySuccess(`Export approved for ${grant.recipient} and recorded in the audit trail.`);
          }}
        />
      )}

      {holding && evidence.data && (
        <TransitionNoteDialog
          title={evidence.data.legalHold ? 'Lift legal hold' : 'Place legal hold'}
          description={orDash(evidence.data.fileName)}
          label="Why"
          placeholder="e.g. Litigation hold LH-4 opened by legal."
          note="A hold suspends disposal without changing the retention class, so the original classification survives it being lifted."
          submitLabel={evidence.data.legalHold ? 'Lift hold' : 'Place hold'}
          onClose={() => setHolding(false)}
          onSubmit={async (reason) => {
            await setEvidenceLegalHold(evidenceId, {
              legalHold: !evidence.data!.legalHold,
              reason,
            });
            setHolding(false);
            notify.notifySuccess(
              evidence.data!.legalHold ? 'Legal hold lifted.' : 'Legal hold placed.',
            );
            evidence.refetch();
          }}
        />
      )}
    </>
  );
};

export default EvidenceDetailPage;
