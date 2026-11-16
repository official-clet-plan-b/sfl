import { useState } from 'react';
import { Badge, Button, Card, Notice, PageSection, SectionHeader, SectionTitle, Switch } from '@rfdtech/components';
import { useNotifier } from 'shared/components/Notifier';
import { LoadingBlock, Spinner } from 'shared/components/Spinner';
import { formatDateTime } from 'shared/components/format';
import {
  loadFeatureFlags,
  resetSystemFlag,
  setSystemEnabled,
  systemFlags,
  unreachableFeatureServices,
  useFeatureFlagsVersion,
  type FeatureService,
  type SystemFlag,
  changedByName,
} from 'shared/layout/featureFlags';
import { allProgrammes, programmes, systems, type ProgrammeCode, type SystemCode } from 'shared/layout/programmeModel';
import { isPlatformAdministrator } from 'shared/layout/programmes';

const SERVICE_NAMES: Record<FeatureService, string> = {
  fleet: 'Fleet & Logistics',
  safetySecurity: 'Safety & Security',
  facilities: 'Facilities',
};

const programmeOf = (flag: SystemFlag): ProgrammeCode | null =>
  (systems as Record<string, { programme: ProgrammeCode } | undefined>)[flag.code]?.programme ?? null;

const labelOf = (flag: SystemFlag): string =>
  (systems as Record<string, { label: string } | undefined>)[flag.code]?.label ?? flag.label;

/**
 * Switch SFL modules on and off for everyone, immediately.
 *
 * Each switch is saved in the owning service's database and takes effect on the next request - no
 * restart, no redeploy. The deployment's environment sets the default; "Reset" returns to it. A
 * switched-off module's API is refused by the service and its screens leave every user's menu.
 */
const ModulesPage = () => {
  useFeatureFlagsVersion();
  const notifier = useNotifier();
  const [pending, setPending] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const flags = systemFlags();
  const unreachable = unreachableFeatureServices();

  if (!isPlatformAdministrator) {
    return (
      <PageSection>
        <Notice variant="warning" title="Platform administrators only">
          <p className="text-sm">Only an SFL or DTI administrator can switch modules on or off.</p>
        </Notice>
      </PageSection>
    );
  }

  const run = async (flag: SystemFlag, action: () => Promise<void>, done: string) => {
    setPending(flag.code);
    try {
      await action();
      notifier.notifySuccess(done);
    } catch (error) {
      notifier.notifyError(error, 'The change could not be saved.');
    } finally {
      setPending(null);
    }
  };

  const refresh = async () => {
    setRefreshing(true);
    try {
      await loadFeatureFlags();
    } finally {
      setRefreshing(false);
    }
  };

  const grouped = allProgrammes
    .map((code) => ({ code, flags: flags.filter((flag) => programmeOf(flag) === code) }))
    .filter((group) => group.flags.length > 0);
  const other = flags.filter((flag) => programmeOf(flag) === null);

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Modules</SectionTitle>
          <Button variant="outline" onClick={() => void refresh()} disabled={refreshing}>
            {refreshing && <Spinner size={14} />}
            Refresh
          </Button>
        </SectionHeader>
        <p className="max-w-3xl text-sm text-muted-foreground">
          Switch a module off and it disappears from every user&apos;s menu and its service refuses requests
          for it - straight away, without a restart or a redeploy. The deployment sets each module&apos;s
          default; a change here overrides it until you reset it.
        </p>
      </PageSection>

      {unreachable.length > 0 && (
        <PageSection>
          <Notice variant="warning" title="Some services did not answer">
            <p className="text-sm">
              {unreachable.map((service) => SERVICE_NAMES[service]).join(', ')}{' '}
              {unreachable.length === 1 ? 'is' : 'are'} not running here, so {unreachable.length === 1 ? 'its' : 'their'}{' '}
              modules are off and cannot be changed from this screen.
            </p>
          </Notice>
        </PageSection>
      )}

      {flags.length === 0 && unreachable.length === 0 && <LoadingBlock label="Loading modules…" />}

      {[...grouped.map((group) => ({ title: programmes[group.code].label, flags: group.flags })),
        ...(other.length > 0 ? [{ title: 'Other', flags: other }] : [])].map((group) => (
        <PageSection key={group.title}>
          <h3 className="mb-3 text-theme-sm font-semibold uppercase tracking-wide text-gray-500">{group.title}</h3>
          <Card bordered>
            <ul className="divide-y divide-gray-100">
              {group.flags.map((flag) => {
                const busy = pending === flag.code;
                const needs = flag.requires
                  .map((code) => (systems as Record<string, { label: string } | undefined>)[code as SystemCode]?.label ?? code);
                return (
                  <li key={flag.code} className="flex flex-wrap items-center gap-4 px-5 py-4" data-testid={`module-${flag.code}`}>
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium text-gray-900">{labelOf(flag)}</span>
                        {flag.overridden ? (
                          <Badge variant="warning">Changed from default ({flag.defaultEnabled ? 'on' : 'off'})</Badge>
                        ) : (
                          <Badge variant="success">Deployment default</Badge>
                        )}
                      </div>
                      <p className="mt-1 text-theme-xs text-gray-500" title={flag.overridden ? (flag.updatedBy ?? undefined) : undefined}>
                        {flag.overridden && flag.updatedAt
                          ? `Changed by ${changedByName(flag.updatedBy)} on ${formatDateTime(flag.updatedAt)}.`
                          : `Set by the deployment (${SERVICE_NAMES[flag.service]} service).`}
                        {needs.length > 0 && ` Keeps ${needs.join(', ')} data available while it is on.`}
                      </p>
                    </div>
                    <div className="flex items-center gap-3">
                      {busy && <Spinner size={18} label={`Saving ${labelOf(flag)}`} />}
                      {flag.overridden && (
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={busy}
                          onClick={() => void run(flag, () => resetSystemFlag(flag), `${labelOf(flag)} reset to its default`)}
                        >
                          Reset
                        </Button>
                      )}
                      <Switch
                        checked={flag.enabled}
                        disabled={busy}
                        aria-label={`${labelOf(flag)} ${flag.enabled ? 'on' : 'off'}`}
                        label={flag.enabled ? 'On' : 'Off'}
                        labelPosition="left"
                        onCheckedChange={(checked: boolean) =>
                          void run(
                            flag,
                            () => setSystemEnabled(flag, checked),
                            `${labelOf(flag)} switched ${checked ? 'on' : 'off'}`,
                          )
                        }
                      />
                    </div>
                  </li>
                );
              })}
            </ul>
          </Card>
        </PageSection>
      ))}
    </>
  );
};

export default ModulesPage;
