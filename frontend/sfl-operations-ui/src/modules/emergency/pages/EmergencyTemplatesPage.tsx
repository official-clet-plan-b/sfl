import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { emergencyRecordsApi } from 'modules/emergency/api/emergencyApi';
import type { EmergencyScenario, NotificationTemplate } from 'modules/emergency/api/dto';
import { RECORD_LIFECYCLES, type RecordLifecycle } from 'modules/emergency/api/enums';
import { listChannels } from 'modules/emergency/components/EmergencyFields';
import { useSiteRecords } from 'modules/emergency/components/useSiteRecords';
import {
  CreateScenarioDialog,
  CreateTemplateDialog,
} from 'modules/emergency/dialogs/recordDialogs';
import DataState from 'shared/components/DataState';
import Icon from 'shared/components/Icon';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { useClampPage, useServerPage } from 'shared/hooks/useServerPage';
import { emergencyPaths } from 'shared/layout/navigation';
import { canManageScenarios, canManageTemplates } from 'modules/emergency/api/workflow';
import { Button, Banner, type TableColumn } from '@rfdtech/components';
import DataTable from 'shared/components/DataTable';
import Tabs from 'shared/components/Tabs';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import StatusBadge from 'modules/emergency/components/StatusBadge';
import { TextField, EnumField } from 'modules/emergency/components/FormFields';
import { CellStack } from 'modules/emergency/components/RegisterTable';

/**
 * What gets sent: notification templates and the scenarios that cite them.
 *
 * Two registers on one screen because they answer one question between them and are almost always
 * read together - a scenario is meaningless without the template it defaults to, and a template's
 * break-glass flag only matters against the scenarios that will carry it. Splitting them into two
 * sidebar entries would make an operator cross-reference by hand what belongs side by side.
 *
 * Both registers are searched, filtered and paged by the service. They used to load two hundred
 * records per site and filter them in the browser, with the search box captioned "filters the loaded
 * records" - honest about what it did, and wrong about what an operator would assume. The service has
 * accepted `search`, `lifecycle` and `breakGlassEligible` since these endpoints were written.
 *
 * The break-glass banner counts with its own filtered reads rather than by tallying a page. It is a
 * statement about the site's exposure, so counting what happened to be on screen would have
 * understated it every time the register ran past one page.
 *
 * Neither register has a lifecycle transition - a template cannot be retired through any endpoint.
 * That is still a gap.
 */
const EmergencyTemplatesPage = () => {
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();

  const [siteCode, setSiteCode] = useState(defaultSite);
  const [tab, setTab] = useState('templates');
  const [search, setSearch] = useState('');
  const [lifecycle, setLifecycle] = useState<RecordLifecycle | ''>('');
  const [creatingTemplate, setCreatingTemplate] = useState(false);
  const [creatingScenario, setCreatingScenario] = useState(false);

  /**
   * Still loaded, for two things the registers cannot answer themselves.
   *
   * A scenario row names its default template, and the template it names may be on any page of the
   * template register - so the id has to be resolved from a list rather than from the page in front
   * of the operator. The create-scenario dialog needs the same list to offer a default.
   */
  const records = useSiteRecords(siteCode);

  const trimmed = search.trim();
  const filterKey = `${siteCode}|${trimmed}|${lifecycle}`;
  const paging = useServerPage(filterKey);

  const templateQuery = useApiQuery(
    (signal) =>
      emergencyRecordsApi.templates(
        {
          siteCode,
          search: trimmed || undefined,
          lifecycle: lifecycle || undefined,
          page: paging.page,
          size: paging.size,
        },
        signal,
      ),
    [siteCode, trimmed, lifecycle, paging.page, paging.size],
  );

  const scenarioQuery = useApiQuery(
    (signal) =>
      emergencyRecordsApi.scenarios(
        {
          siteCode,
          search: trimmed || undefined,
          lifecycle: lifecycle || undefined,
          page: paging.page,
          size: paging.size,
        },
        signal,
      ),
    [siteCode, trimmed, lifecycle, paging.page, paging.size],
  );

  const active = tab === 'templates' ? templateQuery : scenarioQuery;
  useClampPage(paging.page, active.data?.totalPages, paging.setPage);

  /**
   * The two break-glass counts, each its own filtered read.
   *
   * `size: 1` because only the total is wanted. Asking for a page and counting it would have
   * described the page.
   */
  const breakGlassTemplates = useApiQuery(
    (signal) =>
      emergencyRecordsApi.templates({ siteCode, breakGlassEligible: true, size: 1 }, signal),
    [siteCode],
  );

  const breakGlassScenarios = useApiQuery(
    (signal) =>
      emergencyRecordsApi.scenarios({ siteCode, breakGlassEligible: true, size: 1 }, signal),
    [siteCode],
  );

  const breakGlassTemplateCount = breakGlassTemplates.data?.totalElements ?? 0;
  const breakGlassScenarioCount = breakGlassScenarios.data?.totalElements ?? 0;

  const refreshAll = () => {
    templateQuery.refetch();
    scenarioQuery.refetch();
    breakGlassTemplates.refetch();
    breakGlassScenarios.refetch();
    records.refetch();
  };

  const templateColumns = useMemo<TableColumn<NotificationTemplate>[]>(
    () => [
      {
        id: 'template',
        header: 'Template',
        width: 300,
        cell: ({ row }) => (
          <CellStack primary={`${row.templateCode} · ${row.title}`} secondary={row.body} />
        ),
      },
      {
        id: 'channels',
        header: 'Channels',
        width: 220,
        cell: ({ row }) => listChannels(row.channels),
      },
      {
        id: 'breakGlass',
        header: 'Break glass',
        width: 140,
        align: 'center',
        cell: ({ row }) =>
          row.breakGlassEligible ? (
            <StatusBadge value="BREAK_GLASS" label="Eligible" tone="blocked" />
          ) : (
            <span className="text-gray-500">-</span>
          ),
      },
      {
        id: 'lifecycle',
        header: 'Lifecycle',
        width: 120,
        cell: ({ row }) => <StatusBadge value={row.lifecycle} />,
      },
      {
        id: 'created',
        header: 'Created',
        width: 160,
        align: 'right',
        cell: ({ row }) => formatDateTime(row.metadata.createdAt),
      },
    ],
    [],
  );

  const scenarioColumns = useMemo<TableColumn<EmergencyScenario>[]>(
    () => [
      {
        id: 'scenario',
        header: 'Scenario',
        width: 300,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.scenarioCode} · ${row.name}`}
            secondary={
              row.defaultTemplateId
                ? `Defaults to ${records.templateName(row.defaultTemplateId)}`
                : 'No default template'
            }
          />
        ),
      },
      {
        id: 'priority',
        header: 'Priority',
        width: 120,
        cell: ({ row }) => <StatusBadge value={row.priority} />,
      },
      {
        id: 'breakGlass',
        header: 'Break glass',
        width: 140,
        align: 'center',
        cell: ({ row }) =>
          row.breakGlassEligible ? (
            <StatusBadge value="BREAK_GLASS" label="Eligible" tone="blocked" />
          ) : (
            <span className="text-gray-500">-</span>
          ),
      },
      {
        id: 'lifecycle',
        header: 'Lifecycle',
        width: 120,
        cell: ({ row }) => <StatusBadge value={row.lifecycle} />,
      },
      {
        id: 'created',
        header: 'Created',
        width: 160,
        align: 'right',
        cell: ({ row }) => formatDateTime(row.metadata.createdAt),
      },
    ],
    [records],
  );

  return (
    <div>
      <PageHeading
        title="Templates and scenarios"
        subtitle="What a broadcast says, and the declared situations that cite it."
        crumbs={[
          { label: 'Emergency', to: emergencyPaths.dashboard },
          { label: 'Templates and scenarios' },
        ]}
        actions={
          <>
            {/* A template and a scenario are separately granted - one is wording, one is a plan. */}
            {canManageTemplates() && (
              <Button variant="primary" onClick={() => setCreatingTemplate(true)}><Icon name="plus" size={14} aria-hidden="true" />
                Create template
              </Button>
            )}
            {canManageScenarios() && (
              <Button variant="outline" onClick={() => setCreatingScenario(true)}><Icon name="plus" size={14} aria-hidden="true" />
                Create scenario
              </Button>
            )}
            <Button variant="outline" onClick={refreshAll}><Icon name="refresh" size={14} aria-hidden="true" />
              Refresh
            </Button>
          </>
        }
      />

      <div className="mb-5">
        <Panel>
          <div className="grid gap-4 sm:grid-cols-2 lg:max-w-3xl lg:grid-cols-3">
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            <TextField
              label="Search"
              value={search}
              onChange={setSearch}
              placeholder="Code, title or message text"
              helperText="Searched by the service across both registers."
            />
            <EnumField
              label="Lifecycle"
              value={lifecycle}
              options={RECORD_LIFECYCLES}
              onChange={(value) => setLifecycle(value)}
              allowEmpty
            />
          </div>
        </Panel>
      </div>

      {(breakGlassTemplateCount > 0 || breakGlassScenarioCount > 0) && (
        <Banner
          variant="warning"
          heading={`${breakGlassTemplateCount} template${breakGlassTemplateCount === 1 ? '' : 's'} and ${breakGlassScenarioCount} scenario${breakGlassScenarioCount === 1 ? '' : 's'} can bypass approval`}
          className="mb-5"
  subtext={<>A break-glass send is allowed when the template <strong>or</strong> the scenario is
          eligible. Every combination that pairs one of these with anything else is a broadcast that
          can go out to this site with nobody approving it.</>} />
      )}

      <Panel>
        <div className="px-5 pt-4">
          <Tabs
            value={tab}
            onChange={setTab}
            items={[
              {
                value: 'templates',
                label: 'Templates',
                count: templateQuery.data?.totalElements,
              },
              {
                value: 'scenarios',
                label: 'Scenarios',
                count: scenarioQuery.data?.totalElements,
              },
            ]}
          />
        </div>

        <DataState
          loading={active.initialising}
          error={active.error}
          onRetry={active.refetch}
          minHeight={300}
        >
          {tab === 'templates' ? (
            <DataTable
              rows={templateQuery.data?.content ?? []}
              columns={templateColumns}
              getRowId={(row) => row.id}
              loading={templateQuery.loading}
              onRowClick={(row) => navigate(emergencyPaths.templateDetail(row.id))}
              caption="Notification templates at this site, with their channels, break-glass eligibility, lifecycle and creation time."
              emptyMessage="No template matches this search."
              page={templateQuery.data?.page ?? paging.page}
              pageSize={templateQuery.data?.size ?? paging.size}
              totalElements={templateQuery.data?.totalElements ?? 0}
              onPageChange={paging.setPage}
              onPageSizeChange={paging.setSize}
            />
          ) : (
            <DataTable
              rows={scenarioQuery.data?.content ?? []}
              columns={scenarioColumns}
              getRowId={(row) => row.id}
              loading={scenarioQuery.loading}
              caption="Emergency scenarios at this site, with their default template, priority, break-glass eligibility, lifecycle and creation time."
              emptyMessage="No scenario matches this search."
              page={scenarioQuery.data?.page ?? paging.page}
              pageSize={scenarioQuery.data?.size ?? paging.size}
              totalElements={scenarioQuery.data?.totalElements ?? 0}
              onPageChange={paging.setPage}
              onPageSizeChange={paging.setSize}
            />
          )}
        </DataState>

        <div className="flex items-start gap-1.5 px-5 pt-2 pb-4 text-theme-xs text-gray-600">
          <Icon name="info" size={13} className="mt-0.5 shrink-0 text-teal-700" />
          <span>
            {tab === 'templates'
              ? 'A template cannot be edited or retired: the service exposes creation and reads only. Supersede one by creating its replacement.'
              : 'A scenario cannot be edited or retired either. Both registers are create-and-read, so an obsolete record stays visible and selectable.'}
          </span>
        </div>
      </Panel>

      {creatingTemplate && (
        <CreateTemplateDialog
          open
          defaultSiteCode={siteCode}
          onClose={() => setCreatingTemplate(false)}
          onSaved={(template) => {
            notifySuccess(`${template.templateCode} created.`);
            refreshAll();
          }}
        />
      )}

      {creatingScenario && (
        <CreateScenarioDialog
          open
          defaultSiteCode={siteCode}
          templates={records.templates}
          onClose={() => setCreatingScenario(false)}
          onSaved={(scenario) => {
            notifySuccess(`${scenario.scenarioCode} created.`);
            refreshAll();
          }}
        />
      )}
    </div>
  );
};

export default EmergencyTemplatesPage;
