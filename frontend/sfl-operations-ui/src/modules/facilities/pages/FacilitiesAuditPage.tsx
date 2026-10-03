import { useState } from 'react';
import {
  Banner,
  Button,
  Card,
  Dropdown,
  EmptyState,
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
  Table,
  TableContent,
  TableFilter,
  TableHeader,
  useBreadcrumbs,
  useTableState,
} from '@rfdtech/components';
import type { TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import type { AuditChainVerification, AuditEvent } from '../api/dto';
import { auditActions } from '../api/enums';
import type { AuditAction } from '../api/enums';
import { searchAudit, verifyAuditChain } from '../api/facilitiesApi';
import { canVerifyAuditChain } from '../api/workflow';
import StatusBadge from '../components/StatusBadge';
import { formatDateTime, humaniseCode, orDash } from '../components/facilitiesFormat';

/**
 * The audit trail, and the replay that proves it has not been altered.
 *
 * Two things here that are easy to get wrong and matter a great deal:
 *
 * **Denials are in the trail.** `AUTHORIZATION_DENIED` is offered first in the action filter, because
 * a refused attempt to read another site's estate is the event a compliance review is looking for,
 * and burying it among thirty state changes hides it.
 *
 * **The integrity result is reported honestly.** A broken chain says which record it broke at and
 * what was expected against what was found - not a red badge. SRS-SFL-S152-03 makes this an escalation
 * to compliance and security, and an escalation needs something an investigator can act on.
 */
const FacilitiesAuditPage = () => {
  const notify = useNotifier();
  useBreadcrumbs([{ label: 'Facilities', href: facilitiesPaths.dashboard }, { label: 'Audit' }]);
  // The search endpoint takes one action, so the filter is a single choice that lives in the URL.
  const { filters } = useTableState({ paramPrefix: 'audit' });
  const action = filters.action ?? '';
  const [actionValue, setActionValue] = useState(action);
  const [siteCode, setSiteCode] = useState<string>(defaultSite);
  const [verification, setVerification] = useState<AuditChainVerification | null>(null);
  const [verifying, setVerifying] = useState(false);

  const { data, loading, error, refetch } = useApiQuery(
    (signal) =>
      searchAudit(
        {
          siteCode: siteCode || undefined,
          action: (action as AuditAction) || undefined,
          limit: 200,
        },
        signal,
      ),
    [siteCode, action],
  );

  const runVerification = async () => {
    setVerifying(true);
    try {
      const result = await verifyAuditChain();
      setVerification(result);
      if (result.intact) {
        notify.notifySuccess(`Audit chain intact - ${result.recordsVerified} records verified.`);
      } else {
        notify.notifyError(
          new Error('Audit integrity check failed. Escalate to compliance and security.'),
        );
      }
      // Running the check is itself audited, so the list below has just gained a row.
      refetch();
    } catch (cause) {
      notify.notifyError(cause);
    } finally {
      setVerifying(false);
    }
  };

  const columns: TableColumn<AuditEvent>[] = [
    {
      id: 'sequenceNo',
      header: '#',
      width: 70,
      align: 'right',
      cell: ({ row }) => <span className="font-mono text-xs text-muted-foreground">{row.sequenceNo}</span>,
    },
    {
      id: 'occurredAt',
      header: 'When',
      width: 190,
      cell: ({ row }) => formatDateTime(row.occurredAt),
    },
    {
      id: 'action',
      header: 'Action',
      width: 230,
      cell: ({ row }) => (
        <StatusBadge
          value={row.action}
          tone={row.action === 'AUTHORIZATION_DENIED' ? 'blocked' : 'neutral'}
        />
      ),
    },
    {
      id: 'actor',
      header: 'Actor',
      width: 160,
      cell: ({ row }) => row.actorDisplayName || row.actorId,
    },
    {
      id: 'resource',
      header: 'Resource',
      cell: ({ row }) => (
        <span className="text-muted-foreground">
          {row.resourceType} · <span className="font-mono text-xs">{row.resourceId}</span>
        </span>
      ),
    },
    {
      id: 'site',
      header: 'Site',
      width: 100,
      align: 'right',
      cell: ({ row }) => (
        <span className="text-muted-foreground">{row.siteScope === '*' ? 'Platform' : row.siteScope}</span>
      ),
    },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Audit &amp; integrity</SectionTitle>
          <SectionDescription>
            Every state change, and the hash-chain replay that proves none was altered
          </SectionDescription>
          <SectionActions className="items-end [&_button]:whitespace-nowrap">
            <SiteSelect value={siteCode} onChange={setSiteCode} allowEmpty emptyLabel="All sites" />
            {canVerifyAuditChain() && (
              <Button variant="outline" onClick={runVerification} disabled={verifying}>
                {verifying ? 'Verifying…' : 'Verify chain'}
              </Button>
            )}
          </SectionActions>
        </SectionHeader>
      </PageSection>

      {verification && (
        <PageSection>
          <Banner
            variant={verification.intact ? 'success' : 'danger'}
            heading={
              verification.intact
                ? `Chain intact - ${verification.recordsVerified} records verified`
                : 'Audit integrity check failed. Escalate to compliance and security.'
            }
            subtext={
              verification.intact ? (
                <p>
                  Every record replays against its predecessor. Head hash{' '}
                  <span className="font-mono text-xs">{verification.headHash?.slice(0, 16)}…</span>
                </p>
              ) : (
                <div className="space-y-1">
                  <p>{orDash(verification.reason)}</p>
                  <p>
                    Broke at sequence <strong>{orDash(verification.brokenAtSequence)}</strong> after{' '}
                    {verification.recordsVerified} good records.
                  </p>
                  <p className="font-mono text-xs break-all">
                    expected {orDash(verification.expected)}
                    <br />
                    found {orDash(verification.found)}
                  </p>
                </div>
              )
            }
          />
        </PageSection>
      )}

      <PageSection>
        <SectionHeader>
          <SectionTitle>Audit trail</SectionTitle>
          <SectionDescription>Append-only and hash-chained. Most recent first.</SectionDescription>
        </SectionHeader>
        <DataState loading={false} error={error} onRetry={refetch}>
          <Table paramPrefix="audit" variant="soft">
            <Card bordered>
              <TableHeader>
                <TableFilter variant="spread">
                  <Dropdown
                    name="action"
                    aria-label="Action"
                    placeholder="All actions"
                    clearable
                    value={actionValue || null}
                    onValueChange={(next) => setActionValue(next ?? '')}
                    options={auditActions.map((value) => ({ value, label: humaniseCode(value) }))}
                  />
                </TableFilter>
              </TableHeader>
              <TableContent
                variant="soft"
                columns={columns}
                data={data?.items ?? []}
                rowKey={(event) => event.id}
                loading={loading}
                aria-label="Audit trail"
                emptyContent={
                  <EmptyState
                    title="No audit records match"
                    description="Widen the site or clear the action filter."
                  />
                }
              />
            </Card>
          </Table>
        </DataState>
      </PageSection>
    </>
  );
};

export default FacilitiesAuditPage;
