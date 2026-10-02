import { useMemo, useState } from 'react';
import DataState from 'shared/components/DataState';
import { AuditEventResponse, EvidenceResponse, EvidenceSearchParams } from 'modules/fleet/api/dto';
import {
  EVIDENCE_RETENTION_CLASSES,
  EvidenceRetentionClass,
  humanise,
} from 'modules/fleet/api/enums';
import { auditApi, evidenceApi } from 'modules/fleet/api/fleetApi';
import {
  DriverSelect,
  TripSelect,
  VehicleSelect,
} from 'modules/fleet/components/FleetReferenceSelect';

import {
  Banner,
  Button,
  Card,
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@rfdtech/components';
import RegisterHeader from 'modules/fleet/components/RegisterHeader';
import FleetFormDialog from 'modules/fleet/components/FleetFormDialog';
import FleetTable, { CellStack, FleetColumn } from 'modules/fleet/components/FleetTable';
import StatusBadge, { tabLabel } from 'modules/fleet/components/StatusBadge';
import {
  EnumSelect,
  FileInput,
  TextAreaInput,
  TextInput,
} from 'modules/fleet/components/formFields';
import Icon from 'shared/components/Icon';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime } from 'shared/components/format';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, maxLength, required } from 'shared/validation/validators';
import { canReadAudit, canRequestEvidenceExport, canVerifyAuditChain } from '../api/access';
import { evidenceStorageReference, sha256Hex } from 'shared/evidence/fileEvidence';

type TabKey = 'evidence' | 'audit' | 'integrity';

/**
 * The records this dialog can file evidence against, and the only ones it offers.
 *
 * PascalCase because the store matches `relatedRecordType` exactly and every backend path files
 * under the entity name - `Trip`, not `TRIP`. The list is short because it is the list of registers
 * the dashboard can *resolve an identifier out of*: offering a type with no register behind it would
 * put the operator straight back to typing a UUID, which is the thing being fixed.
 *
 * Evidence for an inspection or a compliance certificate is filed by those screens' own dialogs,
 * under the vehicle it belongs to - so nothing that was reachable stops being reachable.
 */
const EVIDENCE_RECORD_TYPES = ['Vehicle', 'Driver', 'Trip'] as const;
type EvidenceRecordType = (typeof EVIDENCE_RECORD_TYPES)[number];

const EVIDENCE_RECORD_TYPE_LABELS: Record<EvidenceRecordType, string> = {
  Vehicle: 'Vehicle',
  Driver: 'Driver',
  Trip: 'Trip',
};

/** Audit records carry an optional id, so the row key falls back to position as it always did. */
interface AuditRow {
  key: string;
  record: AuditEventResponse;
}

/**
 * Evidence and audit governance.
 *
 * Evidence is reachable two ways: by its own identifier, and by the record it evidences. The second
 * is new, and is the one an operator can actually use - the identifier is a UUID that appears on no
 * paperwork, whereas the record is the thing they were looking at when they needed the evidence.
 *
 * There is still no browsable list of all evidence, and there should not be. Every read names a
 * record or an identifier, which keeps this screen from becoming an index of every incident at every
 * site that anyone with the dashboard open can page through.
 */
const EvidenceAuditPage = () => {
  const { notifyError, notifySuccess } = useNotifier();
  const canExportEvidence = canRequestEvidenceExport();
  const [tab, setTab] = useState<TabKey>('evidence');
  const [lookupId, setLookupId] = useState('');
  const [recordType, setRecordType] = useState('');
  const [recordId, setRecordId] = useState('');
  /**
   * The committed search, set by the button rather than by typing.
   *
   * A record id is a UUID. Querying on every keystroke would fire three dozen requests to answer one
   * question, and the answer to all but the last would be an error.
   */
  const [criteria, setCriteria] = useState<EvidenceSearchParams | undefined>(undefined);
  const [evidence, setEvidence] = useState<EvidenceResponse | undefined>(undefined);
  const [lookupError, setLookupError] = useState<FleetApiError | undefined>(undefined);
  const [lookingUp, setLookingUp] = useState(false);
  const [recordingAccess, setRecordingAccess] = useState(false);
  const [registerOpen, setRegisterOpen] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);

  const audit = useApiQuery(
    (signal) =>
      tab === 'audit' ? auditApi.search({ size: 50 }, signal) : Promise.resolve(undefined),
    [tab],
  );

  const integrity = useApiQuery(
    (signal) => (tab === 'integrity' ? auditApi.verifyChain(signal) : Promise.resolve(undefined)),
    [tab],
  );

  const byRecord = useApiQuery(
    (signal) => (criteria ? evidenceApi.search(criteria, signal) : Promise.resolve(undefined)),
    [criteria],
  );

  const lookup = async () => {
    if (!lookupId.trim()) {
      return;
    }
    setLookingUp(true);
    setLookupError(undefined);
    try {
      const found = await evidenceApi.findById(lookupId.trim());
      setEvidence(found);
    } catch (error) {
      setEvidence(undefined);
      setLookupError(
        isFleetApiError(error) ? error : FleetApiError.transport('The lookup failed.'),
      );
    } finally {
      setLookingUp(false);
    }
  };

  const recordAccess = async () => {
    if (!evidence || recordingAccess) {
      return;
    }
    // The endpoint is not idempotent: every call appends an access entry to the audit trail, so a
    // second click while the first is in flight writes a second entry no one performed.
    setRecordingAccess(true);
    try {
      const updated = await evidenceApi.recordAccess(evidence.id);
      setEvidence(updated);
      notifySuccess('Access recorded against the evidence record.');
    } catch (error) {
      notifyError(error);
    } finally {
      setRecordingAccess(false);
    }
  };

  const auditRows = useMemo<AuditRow[]>(
    () =>
      (audit.data ?? []).map((record, index) => ({
        key: String(record.id ?? index),
        record,
      })),
    [audit.data],
  );

  const evidenceColumns = useMemo<FleetColumn<EvidenceResponse>[]>(
    () => [
      {
        key: 'file',
        header: 'File',
        width: 320,
        cell: (row) => (
          <CellStack
            primary={row.fileName}
            secondary={`${row.evidenceType} · ${row.contentType}`}
          />
        ),
      },
      {
        key: 'retention',
        header: 'Retention',
        width: 190,
        cell: (row) => (
          <div className="flex flex-wrap items-center gap-1.5">
            <StatusBadge
              value={row.retentionClass}
              label={humanise(row.retentionClass)}
              tone="neutral"
            />
            {row.legalHold && <StatusBadge value="LEGAL_HOLD" label="Legal hold" tone="blocked" />}
          </div>
        ),
      },
      {
        key: 'registered',
        header: 'Registered',
        width: 170,
        align: 'right',
        cell: (row) => (
          <span className="text-theme-xs opacity-70">{formatDateTime(row.createdAt)}</span>
        ),
      },
    ],
    [],
  );

  const auditColumns = useMemo<FleetColumn<AuditRow>[]>(
    () => [
      {
        key: 'action',
        header: 'Action',
        width: 240,
        cell: ({ record }) => (
          <CellStack
            primary={`${humanise(String(record.action ?? 'RECORD'))} · ${String(
              record.resourceType ?? '',
            )}`}
            secondary={String(record.resourceId ?? '')}
          />
        ),
      },
      {
        key: 'actor',
        header: 'Actor',
        width: 180,
        cell: ({ record }) => String(record.actorId ?? 'unknown'),
      },
      {
        key: 'siteCode',
        header: 'Site',
        width: 120,
        cell: ({ record }) => (record.siteCode ? String(record.siteCode) : '-'),
      },
      {
        key: 'occurredAt',
        header: 'Occurred',
        width: 170,
        align: 'right',
        cell: ({ record }) => (
          <span className="text-theme-xs opacity-70">
            {formatDateTime(record.occurredAt ?? null)}
          </span>
        ),
      },
    ],
    [],
  );

  const tabs = [
    { value: 'evidence' as const, label: tabLabel('Evidence') },
    ...(canReadAudit()
      ? [
          {
            value: 'audit' as const,
            label: tabLabel('Audit records', audit.data?.length),
          },
        ]
      : []),
    ...(canVerifyAuditChain()
      ? [{ value: 'integrity' as const, label: tabLabel('Chain integrity') }]
      : []),
  ];

  return (
    <>
      <RegisterHeader
        title="Evidence and audit"
        actions={
          <Button variant="primary" onClick={() => setRegisterOpen(true)}>
            <Icon name="plus" size={14} aria-hidden="true" />
            Register evidence
          </Button>
        }
      />

      <PageSection>
        <Card bordered>
          <SectionHeader className="[--clet-section-header-margin-bottom:16px] [--clet-section-header-title-size:20px]">
            <SectionTitle>Evidence register</SectionTitle>
            <SectionDescription>
              Register evidence references, request exports under approval, and verify the audit
              hash chain.
            </SectionDescription>
          </SectionHeader>
          {/*
          Two of these three tabs are separately granted, and offering them to everyone is how a
          fleet manager ended up reading `FLEET_UNAUTHORIZED_SCOPE` with a correlation id. Replaying
          the hash chain is an auditor's, compliance officer's or administrator's act; reading the
          audit trail is narrower than reading evidence. A tab nobody may open is not shown.
        */}
          <Tabs variant="pill" value={tab} onValueChange={(value) => setTab(value as TabKey)}>
            <TabsList>
              {tabs.map((entry) => (
                <TabsTrigger key={entry.value} value={entry.value}>
                  {entry.label}
                </TabsTrigger>
              ))}
            </TabsList>

            <TabsContent value="evidence">
              <div className="flex flex-col gap-5">
                <Card bordered>
                  <SectionHeader>
                    <SectionTitle>Find by record</SectionTitle>
                    <SectionDescription>
                      What is filed against a trip, an inspection, a compliance document or a
                      workflow item
                    </SectionDescription>
                  </SectionHeader>
                  {/* A grid rather than a flex row: the type field carries a helper line, and under
                    `items-end` that line pushes its neighbour out of alignment. */}
                  <div className="mt-4 grid gap-3 lg:grid-cols-[240px_minmax(0,1fr)_auto] lg:items-start">
                    <TextInput
                      label="Related record type"
                      value={recordType}
                      onChange={setRecordType}
                      placeholder="Trip"
                      helperText="As it was registered - for example Trip or Vehicle inspection."
                    />
                    <TextInput
                      label="Related record ID"
                      value={recordId}
                      onChange={setRecordId}
                      placeholder="The record's UUID"
                    />
                    <Button
                      variant="primary"
                      className="justify-self-start lg:mt-6"
                      disabled={!recordType.trim() || !recordId.trim()}
                      onClick={() =>
                        setCriteria({
                          relatedRecordType: recordType.trim(),
                          relatedRecordId: recordId.trim(),
                        })
                      }
                    >
                      <Icon name="search" size={14} aria-hidden="true" />
                      Find evidence
                    </Button>
                  </div>

                  {criteria && (
                    <div className="mt-4">
                      <FleetTable
                        paramPrefix="evidence-by-record"
                        rows={byRecord.data ?? []}
                        columns={evidenceColumns}
                        getRowId={(row) => row.id}
                        loading={byRecord.loading}
                        error={byRecord.error}
                        onRetry={byRecord.refetch}
                        onRowClick={(row) => {
                          setEvidence(row);
                          setLookupError(undefined);
                        }}
                        caption="Evidence registered against this record, with its retention class and whether it is under legal hold."
                        emptyTitle="Nothing filed against this record"
                        emptyDescription="Check the record type spelling - it is stored exactly as it was registered."
                      />
                    </div>
                  )}
                </Card>

                <Card bordered>
                  <SectionHeader>
                    <SectionTitle>Open by identifier</SectionTitle>
                    <SectionDescription>
                      When the reference id came from a closure record or an incident note
                    </SectionDescription>
                  </SectionHeader>
                  <div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-end">
                    <TextInput
                      label="Evidence reference ID"
                      value={lookupId}
                      onChange={setLookupId}
                      className="sm:max-w-[420px] sm:flex-1"
                    />
                    <Button
                      variant="outline"
                      loading={lookingUp}
                      disabled={!lookupId.trim()}
                      onClick={lookup}
                    >
                      {lookingUp ? 'Looking up…' : 'Open evidence'}
                    </Button>
                  </div>
                </Card>

                {lookupError && (
                  <Banner
                    variant={lookupError.isForbidden ? 'warning' : 'danger'}
                    heading={lookupError.message}
                    subtext={
                      lookupError.correlationId
                        ? `Correlation ID: ${lookupError.correlationId}`
                        : undefined
                    }
                  />
                )}

                {evidence && (
                  <Card bordered>
                    <SectionHeader>
                      <SectionTitle>{evidence.fileName}</SectionTitle>
                      <SectionDescription>
                        {`${evidence.relatedRecordType} ${evidence.relatedRecordId}`}
                      </SectionDescription>
                      <SectionActions>
                        <Button
                          size="sm"
                          variant="outline"
                          loading={recordingAccess}
                          onClick={recordAccess}
                        >
                          Record access
                        </Button>
                        {canExportEvidence && (
                          <Button size="sm" variant="primary" onClick={() => setExportOpen(true)}>
                            Request export
                          </Button>
                        )}
                      </SectionActions>
                    </SectionHeader>
                    <div className="mt-4 flex flex-col gap-4">
                      <div className="flex flex-wrap items-center gap-2">
                        <StatusBadge value={evidence.retentionClass} tone="neutral" />
                        {evidence.legalHold && (
                          <StatusBadge value="LEGAL_HOLD" label="Legal hold" tone="blocked" />
                        )}
                      </div>
                      <KeyValueGrid
                        items={[
                          { label: 'Evidence ID', value: evidence.id, span: 2 },
                          { label: 'Site', value: evidence.siteCode },
                          {
                            label: 'Evidence type',
                            value: evidence.evidenceType,
                          },
                          { label: 'Content type', value: evidence.contentType },
                          {
                            label: 'Storage reference',
                            value: evidence.storageReference,
                            span: 2,
                          },
                          {
                            label: 'SHA-256',
                            value: evidence.sha256Hash,
                            span: 2,
                          },
                          {
                            label: 'Retention class',
                            value: humanise(evidence.retentionClass),
                          },
                          {
                            label: 'Retention expires',
                            value: formatDateTime(evidence.retentionExpiresAt),
                          },
                          {
                            label: 'Registered by',
                            value: evidence.createdBy ?? '-',
                          },
                          {
                            label: 'Registered at',
                            value: formatDateTime(evidence.createdAt),
                          },
                          {
                            label: 'Correlation ID',
                            value: evidence.auditCorrelationId ?? '-',
                            span: 2,
                          },
                          { label: 'Record version', value: evidence.version },
                        ]}
                      />
                    </div>
                  </Card>
                )}
              </div>
            </TabsContent>

            <TabsContent value="audit">
              <FleetTable
                paramPrefix="audit"
                rows={auditRows}
                columns={auditColumns}
                getRowId={(row) => row.key}
                loading={audit.loading}
                error={audit.error}
                onRetry={audit.refetch}
                caption="Audit records"
                emptyTitle="No audit records"
                emptyDescription="Audit search requires an auditor role and a site scope."
              />
            </TabsContent>

            <TabsContent value="integrity">
              <DataState
                loading={integrity.initialising}
                error={integrity.error}
                onRetry={integrity.refetch}
                minHeight={220}
              >
                {integrity.data && (
                  <div className="flex flex-col gap-4">
                    <Banner
                      variant={integrity.data.intact ? 'success' : 'danger'}
                      heading={
                        integrity.data.intact
                          ? `Audit hash chain is intact across ${integrity.data.recordsChecked} records.`
                          : 'Audit integrity check failed. Escalate to compliance and security.'
                      }
                    />
                    <KeyValueGrid
                      items={[
                        {
                          label: 'Records checked',
                          value: integrity.data.recordsChecked,
                        },
                        {
                          label: 'First divergent sequence',
                          value: integrity.data.firstDivergentSequence ?? '-',
                        },
                        { label: 'Reason', value: integrity.data.reason ?? '-' },
                        {
                          label: 'Expected value',
                          value: integrity.data.expectedValue ?? '-',
                          span: 2,
                        },
                        {
                          label: 'Actual value',
                          value: integrity.data.actualValue ?? '-',
                          span: 2,
                        },
                        {
                          label: 'Head hash',
                          value: integrity.data.headHash ?? '-',
                          span: 2,
                        },
                      ]}
                    />
                  </div>
                )}
              </DataState>
            </TabsContent>
          </Tabs>
        </Card>
      </PageSection>

      {/* Mounted only while open, so a cancelled registration cannot reappear in the next one. */}
      {registerOpen && (
        <RegisterEvidenceDialog
          open
          onClose={() => setRegisterOpen(false)}
          onSaved={(created) => {
            notifySuccess('Evidence registered.', `Reference ID ${created.id}`);
            setEvidence(created);
            setLookupId(created.id);
          }}
        />
      )}

      {evidence && exportOpen && (
        <RequestExportDialog
          open
          evidenceId={evidence.id}
          onClose={() => setExportOpen(false)}
          onSaved={() => notifySuccess('Export requested. It needs a separate approver.')}
        />
      )}
    </>
  );
};

/**
 * The identifier field, bound to whichever register the chosen record type names.
 *
 * One component rather than three inline ternaries so the three pickers cannot drift apart in
 * label, required-ness or error wiring. Each is scoped to the site the evidence is being filed
 * under, which is also the scope the actor is allowed to read - so an identifier that appears here
 * is one this operator could have opened anyway.
 */
const RelatedRecordSelect = ({
  recordType,
  siteCode,
  value,
  onChange,
  error,
  helperText,
  onBlur,
}: {
  recordType: EvidenceRecordType;
  siteCode: string;
  value: string;
  onChange: (value: string) => void;
  error: boolean;
  helperText: string | undefined;
  onBlur: () => void;
}) => {
  const shared = {
    label: `Related ${EVIDENCE_RECORD_TYPE_LABELS[recordType].toLowerCase()}`,
    required: true,
    siteCode,
    value,
    onChange,
    error,
    helperText,
    onBlur,
  };

  if (recordType === 'Driver') {
    return <DriverSelect {...shared} />;
  }
  if (recordType === 'Trip') {
    // Unlike the fuel forms, a trip is not optional here - the evidence has to hang off something.
    return <TripSelect {...shared} allowEmpty={false} />;
  }
  return <VehicleSelect {...shared} />;
};

/* Register evidence - POST /api/v1/fleet/evidence */
const RegisterEvidenceDialog = ({
  open,
  onClose,
  onSaved,
}: {
  open: boolean;
  onClose: () => void;
  onSaved: (evidence: EvidenceResponse) => void;
}) => {
  const form = useFleetForm({
    initialValues: {
      siteCode: defaultSite,
      // 'Vehicle', not 'VEHICLE'. The store matches the record type exactly, and every backend
      // path files under PascalCase entity names - Trip, Vehicle, ComplianceDocument. Evidence
      // registered under the old default was invisible to every picker that searches for 'Vehicle',
      // which is precisely the lookup this dialog exists to feed.
      relatedRecordType: 'Vehicle' as EvidenceRecordType,
      relatedRecordId: '',
      evidenceType: 'COMPLIANCE_DOCUMENT',
      evidenceFile: null as File | null,
      retentionClass: 'OPERATIONAL_1_YEAR' as EvidenceRetentionClass,
    },
    schema: {
      siteCode: compose(required('Site code'), maxLength('Site code', 40)),
      relatedRecordType: required('Related record type'),
      relatedRecordId: required('Related record ID'),
      evidenceType: required('Evidence type'),
      evidenceFile: required('Evidence file'),
      retentionClass: required('Retention class'),
    },
    onSubmit: async (values) => {
      if (!values.evidenceFile) {
        throw FleetApiError.transport('Choose an evidence file before registering.');
      }
      const relatedRecordType = values.relatedRecordType.trim();
      const relatedRecordId = values.relatedRecordId.trim();
      const fileName = values.evidenceFile.name;
      const contentType = values.evidenceFile.type || 'application/octet-stream';
      const storageReference = evidenceStorageReference(
        values.siteCode,
        relatedRecordType,
        relatedRecordId,
        fileName,
      );
      const sha256Hash = await sha256Hex(values.evidenceFile);
      const created = await evidenceApi.register({
        siteCode: values.siteCode.trim().toUpperCase(),
        relatedRecordType,
        relatedRecordId,
        evidenceType: values.evidenceType.trim(),
        fileName,
        contentType,
        storageReference,
        sha256Hash,
        retentionClass: values.retentionClass,
      });
      onSaved(created);
      onClose();
      form.reset();
    },
  });

  return (
    <FleetFormDialog
      open={open}
      title="Register an evidence reference"
      description="Choose the file and the dashboard records the filename, content type and SHA-256 hash automatically. This demo stores a metadata reference, not the file content."
      submitLabel="Register evidence"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="md"
      onClose={onClose}
      onSubmit={form.submit}
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <SiteSelect
          required
          value={form.values.siteCode}
          onChange={(value) => {
            form.setValue('siteCode', value);
            // The registers below are scoped to the site, so a record picked at the old one is not
            // on offer at the new one - and leaving it selected would submit an identifier the
            // operator can no longer see.
            form.setValue('relatedRecordId', '');
          }}
          {...form.fieldProps('siteCode')}
        />
        <EnumSelect
          label="Retention class"
          required
          value={form.values.retentionClass}
          options={EVIDENCE_RETENTION_CLASSES}
          onChange={(value) =>
            form.setValue(
              'retentionClass',
              (value || 'OPERATIONAL_1_YEAR') as EvidenceRetentionClass,
            )
          }
          {...form.fieldProps('retentionClass')}
        />
        {/*
          The record type chooses which register the identifier is picked from, which is the whole
          reason it is a list and not free text now. `relatedRecordType` is a plain string on the
          wire and the service will store whatever it is sent - so nothing but this control stopped
          an operator filing evidence against a record type nothing queries, under an identifier no
          record has. Both fields were free text, and the id is a UUID that appears on no paperwork:
          any value at all was accepted and only failed later, when a picker searching for real
          evidence found none.
        */}
        <EnumSelect
          label="Related record type"
          required
          value={form.values.relatedRecordType}
          options={EVIDENCE_RECORD_TYPES}
          onChange={(value) => {
            form.setValue('relatedRecordType', (value || 'Vehicle') as EvidenceRecordType);
            // The old identifier belongs to the old register. Keeping it would leave a Vehicle id
            // sitting in a field now offering drivers, and it would submit.
            form.setValue('relatedRecordId', '');
          }}
          renderOptionLabel={(option) => EVIDENCE_RECORD_TYPE_LABELS[option]}
          {...form.fieldProps('relatedRecordType')}
        />
        <RelatedRecordSelect
          recordType={form.values.relatedRecordType}
          siteCode={form.values.siteCode}
          value={form.values.relatedRecordId}
          onChange={(value) => form.setValue('relatedRecordId', value)}
          {...form.fieldProps('relatedRecordId')}
        />
        <TextInput
          label="Evidence type"
          required
          value={form.values.evidenceType}
          onChange={(value) => form.setValue('evidenceType', value)}
          {...form.fieldProps('evidenceType')}
        />
        <div className="sm:col-span-2">
          <FileInput
            label="Evidence file"
            required
            value={form.values.evidenceFile}
            accept=".pdf,.png,.jpg,.jpeg,.doc,.docx,.xlsx,.csv,text/csv,application/pdf,image/png,image/jpeg"
            onChange={(file) => form.setValue('evidenceFile', file)}
            {...form.fieldProps(
              'evidenceFile',
              'Upload or choose the supporting file; the file name, content type, storage reference and SHA-256 hash are captured for you.',
            )}
          />
        </div>
      </div>
    </FleetFormDialog>
  );
};

/* Request export - POST /api/v1/fleet/evidence/{id}/export-requests */
const RequestExportDialog = ({
  open,
  evidenceId,
  onClose,
  onSaved,
}: {
  open: boolean;
  evidenceId: string;
  onClose: () => void;
  onSaved: () => void;
}) => {
  const form = useFleetForm({
    initialValues: { reason: '' },
    schema: { reason: compose(required('Reason'), maxLength('Reason', 1000)) },
    onSubmit: async (values) => {
      await evidenceApi.requestExport(evidenceId, {
        reason: values.reason.trim(),
      });
      onSaved();
      onClose();
      form.reset();
    },
  });

  return (
    <FleetFormDialog
      open={open}
      title="Request an evidence export"
      description="Exports require a recorded reason and approval by someone other than the requester."
      submitLabel="Request export"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={form.submit}
    >
      <TextAreaInput
        label="Reason"
        required
        rows={3}
        value={form.values.reason}
        onChange={(value) => form.setValue('reason', value)}
        {...form.fieldProps('reason')}
      />
    </FleetFormDialog>
  );
};

export default EvidenceAuditPage;
