import { useMemo } from 'react';
import { emergencyRecordsApi } from 'modules/emergency/api/emergencyApi';
import type {
  AudienceGroup,
  EmergencyScenario,
  NotificationTemplate,
  RecipientZone,
} from 'modules/emergency/api/dto';
import { useApiQuery } from 'shared/hooks/useApiQuery';

/**
 * How many master-data records a composer needs at once.
 *
 * These four registers back select controls, not tables: an activation is composed by picking from
 * them. 200 is the service's own page cap, and a site with more templates than that has a records
 * problem a dropdown was never going to solve.
 */
const RECORD_PAGE = 200;

/**
 * The four master-data registers a site's activations are composed from.
 *
 * An activation names a scenario, a template, audience groups and recipient zones - all by id.
 * Every screen that composes or explains one therefore needs all four, and needs to turn an id back
 * into a name. Loading them once here means the composer dialog, the register and the detail screen
 * resolve the same records rather than each fetching its own copy and disagreeing about a name that
 * changed between two requests.
 *
 * Four requests, not one: the service has no combined read. They are independent, so they run
 * together rather than in sequence.
 */
export interface SiteRecords {
  templates: NotificationTemplate[];
  scenarios: EmergencyScenario[];
  audiences: AudienceGroup[];
  zones: RecipientZone[];
  loading: boolean;
  /** True until the first response of each has landed - what a form should wait for. */
  initialising: boolean;
  refetch: () => void;
  templateName: (id: string | null | undefined) => string;
  scenarioName: (id: string | null | undefined) => string;
  audienceName: (id: string) => string;
  zoneName: (id: string) => string;
  /** Recipients across the named groups - the number the service will fan out to. */
  audienceReach: (ids: string[]) => number;
  template: (id: string | null | undefined) => NotificationTemplate | undefined;
  scenario: (id: string | null | undefined) => EmergencyScenario | undefined;
}

/**
 * An empty `siteCode` loads nothing: a detail screen calls this before its record has told it the site, and
 * asking the service for "every template at no site" is a 400, not an empty list.
 */
export const useSiteRecords = (siteCode: string): SiteRecords => {
  const templates = useApiQuery(
    (signal) => (siteCode ? emergencyRecordsApi.templates({ siteCode, size: RECORD_PAGE }, signal) : Promise.resolve(undefined)),
    [siteCode],
  );
  const scenarios = useApiQuery(
    (signal) => (siteCode ? emergencyRecordsApi.scenarios({ siteCode, size: RECORD_PAGE }, signal) : Promise.resolve(undefined)),
    [siteCode],
  );
  const audiences = useApiQuery(
    (signal) => (siteCode ? emergencyRecordsApi.audienceGroups({ siteCode, size: RECORD_PAGE }, signal) : Promise.resolve(undefined)),
    [siteCode],
  );
  const zones = useApiQuery(
    (signal) => (siteCode ? emergencyRecordsApi.recipientZones({ siteCode, size: RECORD_PAGE }, signal) : Promise.resolve(undefined)),
    [siteCode],
  );

  return useMemo(() => {
    const templateList = templates.data?.content ?? [];
    const scenarioList = scenarios.data?.content ?? [];
    const audienceList = audiences.data?.content ?? [];
    const zoneList = zones.data?.content ?? [];

    const templateById = new Map(templateList.map((record) => [record.id, record]));
    const scenarioById = new Map(scenarioList.map((record) => [record.id, record]));
    const audienceById = new Map(audienceList.map((record) => [record.id, record]));
    const zoneById = new Map(zoneList.map((record) => [record.id, record]));

    // An id that resolves to nothing is shown as an id rather than as an em dash: the record it
    // points at may have been archived, and hiding that makes the activation look incomplete
    // instead of pointing at something the operator can go and look up.
    const unresolved = (id: string) => `Unknown record ${id.slice(0, 8)}`;

    return {
      templates: templateList,
      scenarios: scenarioList,
      audiences: audienceList,
      zones: zoneList,
      loading: templates.loading || scenarios.loading || audiences.loading || zones.loading,
      initialising:
        templates.initialising ||
        scenarios.initialising ||
        audiences.initialising ||
        zones.initialising,
      refetch: () => {
        templates.refetch();
        scenarios.refetch();
        audiences.refetch();
        zones.refetch();
      },
      templateName: (id) => (id ? (templateById.get(id)?.title ?? unresolved(id)) : 'None'),
      scenarioName: (id) => (id ? (scenarioById.get(id)?.name ?? unresolved(id)) : 'None'),
      audienceName: (id) => audienceById.get(id)?.name ?? unresolved(id),
      zoneName: (id) => zoneById.get(id)?.name ?? unresolved(id),
      audienceReach: (ids) =>
        ids.reduce((total, id) => total + (audienceById.get(id)?.recipientCount ?? 0), 0),
      template: (id) => (id ? templateById.get(id) : undefined),
      scenario: (id) => (id ? scenarioById.get(id) : undefined),
    };
  }, [templates, scenarios, audiences, zones]);
};
