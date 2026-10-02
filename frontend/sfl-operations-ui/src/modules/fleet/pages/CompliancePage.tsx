import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import {
  ComplianceDocumentResponse,
  DashboardDrilldownRow,
  VehicleResponse,
} from 'modules/fleet/api/dto';
import {
  COMPLIANCE_DOCUMENT_TYPES,
  ComplianceDocumentType,
  humanise,
} from 'modules/fleet/api/enums';
import { dashboardApi, vehiclesApi } from 'modules/fleet/api/fleetApi';
import { Banner, Button, MetricCard, PageSection } from '@rfdtech/components';
import FleetTable, {
  CellStack,
  FilterDropdown,
  FilterField,
  FleetColumn,
  useRegisterState,
} from 'modules/fleet/components/FleetTable';
import MetricGroup from 'modules/fleet/components/MetricGroup';
import Panel from 'modules/fleet/components/Panel';
import RegisterHeader from 'modules/fleet/components/RegisterHeader';
import StatusBadge from 'modules/fleet/components/StatusBadge';
import { DateField } from 'modules/fleet/components/formFields';
import Icon from 'shared/components/Icon';
import { formatDate, formatDaysRemaining, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fleetPaths } from 'shared/layout/navigation';
import { EvidenceFileActions } from 'shared/components/EvidenceFileField';
import { useNotifier } from 'shared/components/Notifier';

/**
 * How many documents the search asks for.
 *
 * A real limit on a real query now, not a fan-out ceiling. The service clamps at 500.
 */
const SEARCH_LIMIT = 200;

interface DocumentRow {
  document: ComplianceDocumentResponse;
  /**
   * The vehicle the document belongs to, when it could be resolved.
   *
   * The search returns documents, and a document carries a `vehicleId` but no registration number.
   * The site's vehicles are fetched **once** and indexed, so a row can name its vehicle without a
   * request per document. Null when the vehicle is outside the fetched page - the document is still
   * shown, because a compliance exposure does not stop mattering because a lookup missed.
   */
  vehicle: VehicleResponse | null;
}

/** What one search produced, and whether the service had more to give. */
interface DocumentSet {
  rows: DocumentRow[];
  truncated: boolean;
}

type TabKey = 'expiring' | 'service' | 'all';

/** Expiry urgency is the one thing an operator reads first, so it carries a tone of its own. */
const expiryClass = (daysUntilExpiry: number): string => {
  if (daysUntilExpiry < 0) {
    return 'text-error-700';
  }
  return daysUntilExpiry < 30 ? 'text-warning-700' : 'opacity-70';
};

/**
 * Compliance and service exposure across the fleet.
 *
 * Documents come from `GET /vehicles/compliance-documents` - one query, filtered and ordered by the
 * service. This screen used to fan out over the first fifty active vehicles in scope and say so on
 * the page: correct for a small fleet and quietly wrong for any other, because a document on the
 * fifty-first vehicle simply was not there.
 *
 * The authoritative expired count is still the dashboard indicator. That is not a hedge - the
 * indicator is computed server-side over the whole scope and reconciled against its source, so it
 * remains the number to plan against even now that the list beside it is complete.
 */
const CompliancePage = () => {
  const navigate = useNavigate();
  const { notifyError } = useNotifier();
  const [tab, setTab] = useState<TabKey>('expiring');
  const state = useRegisterState('compliance');
  const { filters, setFilter } = state;
  const siteCode = state.site;
  const documentType = (filters.type ?? '') as ComplianceDocumentType | '';
  const expiringBefore = filters.expiringBefore ?? '';
  const filtered = Boolean(documentType || expiringBefore);

  const snapshot = useApiQuery(
    (signal) => dashboardApi.operations({ siteCode: siteCode || undefined }, signal),
    [siteCode],
  );

  const expiredDrilldown = useApiQuery(
    (signal) =>
      dashboardApi.drilldown('EXPIRED_COMPLIANCE', { siteCode: siteCode || undefined }, signal),
    [siteCode],
  );

  const serviceDrilldown = useApiQuery(
    (signal) => dashboardApi.drilldown('SERVICE_DUE', { siteCode: siteCode || undefined }, signal),
    [siteCode],
  );

  /**
   * One search, plus one vehicle page to name the rows.
   *
   * Two requests where there used to be fifty-one, and the answer is the site's whole compliance
   * position rather than the part of it that happened to sit on the first fifty vehicles.
   */
  const documents = useApiQuery(
    async (signal): Promise<DocumentSet> => {
      const [documentList, vehiclePage] = await Promise.all([
        vehiclesApi.searchComplianceDocuments(
          {
            documentType: documentType || undefined,
            expiringBefore: expiringBefore || undefined,
            size: SEARCH_LIMIT,
          },
          signal,
        ),
        vehiclesApi.search({ siteCode: siteCode || undefined, size: 200 }, signal),
      ]);
      const byId = new Map(vehiclePage.content.map((vehicle) => [vehicle.id, vehicle]));
      const rows = documentList
        // The search is scoped to the actor's own sites, which can be wider than the one site this
        // screen is showing, so the chosen site is applied here.
        .filter((document) => !siteCode || document.siteCode === siteCode)
        .map((document) => ({
          document,
          vehicle: byId.get(document.vehicleId) ?? null,
        }));
      // Measured before the site filter, because the cap applies to what the service returned.
      return { rows, truncated: documentList.length >= SEARCH_LIMIT };
    },
    [siteCode, documentType, expiringBefore],
  );

  const rows = documents.data?.rows ?? [];
  // Kept separate from `visibleRows` so the tab count means the same thing on every tab.
  const expiringRows = rows.filter(
    (row) => row.document.daysUntilExpiry < 60 || row.document.status !== 'ACTIVE',
  );
  const wanted = state.search.trim().toLowerCase();
  const visibleRows = (tab === 'expiring' ? expiringRows : rows).filter(
    (row) =>
      !wanted ||
      [
        row.vehicle?.registrationNumber,
        row.document.documentReference,
        row.document.issuingAuthority,
      ].some((value) => value?.toLowerCase().includes(wanted)),
  );

  const documentColumns = useMemo<FleetColumn<DocumentRow>[]>(
    () => [
      {
        key: 'vehicle',
        header: 'Vehicle',
        width: 180,
        cell: (row) =>
          row.vehicle ? (
            <CellStack
              primary={row.vehicle.registrationNumber}
              secondary={`${row.vehicle.make} ${row.vehicle.model}`}
            />
          ) : (
            // The document is real even when its vehicle is outside the fetched page; showing the
            // shortened id is more use than hiding the exposure.
            <CellStack
              primary={`Vehicle ${row.document.vehicleId.slice(0, 8)}`}
              secondary={row.document.siteCode}
            />
          ),
      },
      {
        key: 'document',
        header: 'Document',
        width: 220,
        cell: (row) => (
          <div className="min-w-0">
            <div className="flex min-w-0 flex-wrap items-center gap-2">
              <span className="font-semibold">{humanise(row.document.documentType)}</span>
              {row.document.mandatory && (
                <StatusBadge value="MANDATORY" label="Mandatory" tone="accent" />
              )}
            </div>
            <div className="truncate text-theme-xs opacity-70">{row.document.issuingAuthority}</div>
          </div>
        ),
      },
      {
        key: 'reference',
        header: 'Reference',
        width: 140,
        cell: (row) => row.document.documentReference,
      },
      {
        key: 'expiry',
        header: 'Expires',
        width: 160,
        cell: (row) => (
          <CellStack
            primary={formatDate(row.document.expiresOn)}
            secondary={
              <span className={expiryClass(row.document.daysUntilExpiry)}>
                {formatDaysRemaining(row.document.daysUntilExpiry)}
              </span>
            }
          />
        ),
      },
      {
        key: 'effect',
        header: 'Effect on readiness',
        width: 170,
        cell: (row) =>
          row.document.mandatory && row.document.status !== 'ACTIVE' ? (
            'Withheld from assignment'
          ) : (
            <span className="opacity-70">None until expiry</span>
          ),
      },
      {
        key: 'status',
        header: 'Status',
        width: 130,
        cell: (row) => <StatusBadge value={row.document.status} />,
      },
      {
        key: 'file',
        header: 'File',
        width: 150,
        cell: (row) =>
          row.document.evidenceId ? (
            // Stops the row's own click handler from navigating to the vehicle when the intent was
            // to open the certificate.
            <div onClick={(event) => event.stopPropagation()} role="presentation">
              <EvidenceFileActions
                evidenceId={row.document.evidenceId}
                fileName={`${row.document.documentReference}`}
                compact
                onError={notifyError}
              />
            </div>
          ) : (
            <span className="text-theme-xs opacity-70">Not attached</span>
          ),
      },
    ],
    [notifyError],
  );

  const drilldownColumns = useMemo<FleetColumn<DashboardDrilldownRow>[]>(
    () => [
      {
        key: 'summary',
        header: 'Record',
        width: 320,
        cell: (row) => <span className="font-medium">{row.summary}</span>,
      },
      {
        key: 'siteCode',
        header: 'Site',
        width: 120,
        cell: (row) => <span className="text-theme-xs opacity-70">{row.siteCode}</span>,
      },
    ],
    [],
  );

  const refreshAll = () => {
    snapshot.refetch();
    documents.refetch();
    expiredDrilldown.refetch();
    serviceDrilldown.refetch();
  };

  const indicators = snapshot.data?.indicators;
  const tabs = [
    {
      value: 'expiring',
      label: 'Expiring and expired',
      count: documents.data ? expiringRows.length : undefined,
    },
    { value: 'service', label: 'Service exposure', count: serviceDrilldown.data?.length },
    { value: 'all', label: 'All documents', count: documents.data?.rows.length },
  ];

  return (
    <>
      <RegisterHeader
        title="Compliance and service"
        siteCode={siteCode}
        onSiteChange={state.setSite}
        actions={
          <Button variant="outline" aria-label="Refresh" title="Refresh" onClick={refreshAll}>
            <Icon name="refresh" size={14} aria-hidden="true" />
          </Button>
        }
      />

      <MetricGroup>
        <MetricCard
          variant="soft"
          loading={snapshot.initialising}
          label="Expired"
          value={indicators?.expiredCompliance ?? 0}
          description="Whole scope, past expiry"
        />
        <MetricCard
          variant="soft"
          loading={documents.initialising}
          label="Expiring within 60 days"
          value={expiringRows.filter((row) => row.document.status === 'ACTIVE').length}
          description="Renew before they lapse"
        />
        <MetricCard
          variant="soft"
          loading={snapshot.initialising}
          label="Service due or overdue"
          value={indicators?.serviceDue ?? 0}
          description="Whole scope"
        />
        <MetricCard
          variant="soft"
          loading={snapshot.initialising}
          label="Vehicles withheld"
          value={indicators?.readinessBlockers ?? 0}
          description="Not ready for any trip"
        />
      </MetricGroup>

      {documents.data?.truncated && (
        <PageSection>
          <Banner
            variant="warning"
            heading={`The search returned its maximum of ${SEARCH_LIMIT} documents, so there are more than are listed here.`}
            subtext="Narrow it with a document type, a status or an expiry date - the counts above are computed server-side over the whole scope and stay right either way."
          />
        </PageSection>
      )}

      <PageSection>
        {tab === 'service' ? (
          <FleetTable
            paramPrefix="service-exposure"
            rows={serviceDrilldown.data ?? []}
            columns={drilldownColumns}
            getRowId={(row) => `${row.resourceType}-${row.resourceId}`}
            loading={serviceDrilldown.loading}
            error={serviceDrilldown.error}
            onRetry={serviceDrilldown.refetch}
            onRowClick={(row) => navigate(fleetPaths.vehicleDetail(row.resourceId))}
            caption="Vehicles due or overdue for service"
            heading={{
              title: 'Compliance documents',
              description:
                'Expiring documents and service exposure across the vehicles in your site scope.',
            }}
            tabs={tabs}
            tab={tab}
            onTabChange={(value) => setTab(value as TabKey)}
            emptyTitle="No vehicles due for service"
            emptyDescription="Nothing in this scope is due or overdue."
          />
        ) : (
          <FleetTable
            paramPrefix="compliance"
            rows={visibleRows}
            columns={documentColumns}
            getRowId={(row) => row.document.id}
            loading={documents.loading}
            error={documents.error}
            onRetry={documents.refetch}
            // The document's own `vehicleId` is always present; the resolved vehicle is not.
            onRowClick={(row) => navigate(fleetPaths.vehicleDetail(row.document.vehicleId))}
            caption="Compliance documents"
            heading={{
              title: 'Compliance documents',
              description:
                'Expiring documents and service exposure across the vehicles in your site scope.',
            }}
            tabs={tabs}
            tab={tab}
            onTabChange={(value) => setTab(value as TabKey)}
            searchPlaceholder="Search vehicle or reference"
            spreadFilters
            filters={
              <>
                <FilterDropdown
                  name="type"
                  label="Document type"
                  value={documentType}
                  onChange={(value) => setFilter('type', value)}
                  options={COMPLIANCE_DOCUMENT_TYPES.map((value) => ({
                    value,
                    label: humanise(value),
                  }))}
                />
                <FilterField name="expiringBefore" value={expiringBefore}>
                  <DateField
                    label="Expiring before"
                    value={expiringBefore}
                    onChange={(value) => setFilter('expiringBefore', value)}
                  />
                </FilterField>
              </>
            }
            emptyTitle={tab === 'expiring' ? 'Nothing expiring soon' : 'No compliance documents'}
            emptyDescription={
              tab === 'expiring'
                ? 'Nothing in this scope expires within 60 days or is already expired.'
                : filtered
                  ? 'No document matches these filters.'
                  : 'Register compliance documents from a vehicle record.'
            }
          />
        )}
      </PageSection>

      <Panel
        title="Expired documents (whole scope)"
        description="Server-computed drilldown behind the dashboard indicator"
      >
        <FleetTable
          paramPrefix="expired-documents"
          rows={expiredDrilldown.data ?? []}
          columns={drilldownColumns}
          getRowId={(row) => `${row.resourceType}-${row.resourceId}`}
          loading={expiredDrilldown.loading}
          error={expiredDrilldown.error}
          onRetry={expiredDrilldown.refetch}
          caption="Documents past their expiry date"
          emptyTitle="No expired documents"
          emptyDescription="Nothing in your scope is past its expiry date."
        />
      </Panel>

      {snapshot.data && (
        <p className="text-theme-xs opacity-70">
          {formatNumber(snapshot.data.reconciliation.complianceDocuments)} compliance documents
          across {formatNumber(snapshot.data.reconciliation.vehicles)} vehicles in scope. Figures
          are checked against the vehicle register each time this page loads.
        </p>
      )}
    </>
  );
};

export default CompliancePage;
