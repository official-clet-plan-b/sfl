import { useMemo, useState } from 'react';
import { Banner, Card, CardHeader, CardTitle, MetricCard, type TableColumn } from '@rfdtech/components';
import { apiClient } from 'shared/api/client';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import PageHeading from 'modules/emergency/components/PageHeading';
import RegisterTable, { CellStack } from 'modules/emergency/components/RegisterTable';
import StatusBadge from 'modules/emergency/components/StatusBadge';
import type { SecuritySystemCode } from 'shared/layout/navigation';

type SecurityRow = Record<string, unknown> & { id?: string; status?: string; siteCode?: string };

interface SystemDefinition {
  code: SecuritySystemCode;
  title: string;
  description: string;
  endpoint: string;
  recordLabel: string;
  empty: string;
}

const DEFINITIONS: Record<SecuritySystemCode, SystemDefinition> = {
  S160a: {
    code: 'S160a',
    title: 'Physical Access Control',
    description: 'Door, zone, access exception and occupancy operations.',
    endpoint: '/api/v1/access-control/exceptions',
    recordLabel: 'Access exceptions',
    empty: 'No access exceptions are currently open.',
  },
  S161: {
    code: 'S161',
    title: 'CCTV / VMS',
    description: 'Camera health, evidence requests and analytics alerts.',
    endpoint: '/api/v1/cctv/cameras',
    recordLabel: 'Registered cameras',
    empty: 'No cameras are registered for this site.',
  },
  S162: {
    code: 'S162',
    title: 'Intrusion & Alarms',
    description: 'SOC alarm queue, panels, zones and response dispatch.',
    endpoint: '/api/v1/intrusion/alarms',
    recordLabel: 'Intrusion alarms',
    empty: 'No intrusion alarms are currently raised.',
  },
  S162a: {
    code: 'S162a',
    title: 'Fire & Life Safety',
    description: 'Life-safety events, detector coverage and compliance exceptions.',
    endpoint: '/api/v1/life-safety/compliance-exceptions',
    recordLabel: 'Compliance exceptions',
    empty: 'No life-safety compliance exceptions are open.',
  },
};

const rowsFrom = (value: unknown): SecurityRow[] => {
  if (Array.isArray(value)) return value as SecurityRow[];
  if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    for (const key of ['content', 'items', 'data', 'results']) {
      if (Array.isArray(record[key])) return record[key] as SecurityRow[];
    }
  }
  return [];
};

const display = (value: unknown): string => {
  if (value === null || value === undefined || value === '') return '-';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
};

const SecuritySystemsPage = ({ system }: { system: SecuritySystemCode }) => {
  const definition = DEFINITIONS[system];
  const [siteCode, setSiteCode] = useState(defaultSite);
  const query = useApiQuery(
    (signal) => apiClient.get<unknown>(definition.endpoint, { siteCode }, signal, 'safetySecurity'),
    [definition.endpoint, siteCode],
  );
  const rows = useMemo(() => rowsFrom(query.data), [query.data]);
  const columns = useMemo<TableColumn<SecurityRow>[]>(
    () => [
      {
        id: 'record',
        header: 'Record',
        width: 260,
        cell: ({ row }) => (
          <CellStack
            primary={display(row.name ?? row.zoneCode ?? row.cameraId ?? row.deviceRef ?? row.id)}
            secondary={display(row.siteCode ?? siteCode)}
          />
        ),
      },
      { id: 'status', header: 'Status', width: 150, cell: ({ row }) => <StatusBadge value={display(row.status ?? row.state ?? 'ACTIVE')} /> },
      { id: 'type', header: 'Type', cell: ({ row }) => display(row.type ?? row.alarmType ?? row.exceptionType ?? row.deviceType) },
      { id: 'updated', header: 'Last updated', width: 220, cell: ({ row }) => display(row.updatedAt ?? row.lastSignalAt ?? row.createdAt) },
    ],
    [siteCode],
  );

  return (
    <DataState loading={query.loading} error={query.error} onRetry={query.refetch} minHeight={280}>
      <PageHeading
        title={definition.title}
        subtitle={`${definition.code} · ${definition.description}`}
        crumbs={[{ label: 'Safety & security' }, { label: definition.title }]}
        actions={<SiteSelect value={siteCode} onChange={setSiteCode} />}
      />
      <div className="space-y-5">
        <Banner
          variant="info"
          heading={`${definition.code} operations console`}
          subtext="The screen is connected to the safety-security service and is scoped to the selected site. Actions remain governed by the service permission matrix."
        />
        <div className="grid gap-4 sm:grid-cols-3">
          <MetricCard label="Open records" value={rows.length} description={definition.recordLabel} variant="soft" />
          <MetricCard label="Site" value={siteCode} description="Current request scope" variant="soft" />
          <MetricCard label="System" value={definition.code} description="Entitlement-controlled" variant="soft" />
        </div>
        <Card>
          <CardHeader>
            <CardTitle>{definition.recordLabel}</CardTitle>
          </CardHeader>
          <RegisterTable
            paramPrefix={definition.code.toLowerCase()}
            columns={columns}
            rows={rows}
            rowKey={(row) => row.id ?? `${definition.code}-${display(row.name ?? row.zoneCode ?? row.cameraId ?? row.deviceRef)}`}
            emptyTitle={definition.empty}
            emptyDescription="This register will update when the safety-security service receives a record for the selected site."
            framed={false}
          />
        </Card>
      </div>
    </DataState>
  );
};

export default SecuritySystemsPage;
