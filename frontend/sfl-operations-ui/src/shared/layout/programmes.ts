import { sflActor } from 'shared/api/config';
import { readActorOverride } from 'shared/dev/actorOverride';
import { isSystemEnabled } from './featureFlags';
import {
  ProgrammeCode,
  SystemCode,
  allProgrammes,
  allSystems,
  isCrossProgramme,
  parseList,
  portalLabelFor,
  programmesFor,
  systems,
  systemsFor,
} from './programmeModel';

/**
 * Programme entitlement for the **current actor**.
 *
 * The mapping itself lives in `programmeModel.ts`, which imports nothing - that separation is what
 * lets the decision "who sees what" be exercised directly instead of only through a running
 * application. This file is the thin part: read the actor's roles, apply the override, expose the
 * answer.
 *
 * The rule: **a user sees the programmes they are entitled to.** A driver or a head of fleet sees
 * fleet, fuel and dispatch - not CCTV access management, not intrusion detection, not visitor
 * badges. A manager or superadmin sees everything. ADR 0005.
 *
 * **This is a usability control, not a security control.** Until IAM is integrated there is no
 * authenticated identity, and even once there is, hiding a navigation entry protects nothing: the
 * services authorise every call independently, and S174 refuses an unentitled actor with
 * `EMERGENCY_UNAUTHORIZED_SCOPE` whether or not the sidebar ever offered it. Never treat this as
 * the enforcement point.
 */

export type { ProgrammeCode, SystemCode };
export { allProgrammes, allSystems, programmes, systems } from './programmeModel';

export const actorRoles: string[] = parseList(sflActor.roles);

/** `true` when the actor holds a role that sees every programme. */
export const isCrossProgrammeActor: boolean = isCrossProgramme(actorRoles);

/**
 * The programmes this actor may see.
 *
 * Derived from roles, because that is what IAM will do - there is no separate entitlement list to
 * drift out of step with the roles the actor already sends. The development actor switcher can narrow
 * it (dev builds only); there is no build-time override, so a production bundle always follows the
 * signed-in roles.
 */
export const actorProgrammes: ProgrammeCode[] = (() => {
  const requested = readActorOverride()?.programmes || '';
  const override = parseList(requested).filter((code): code is ProgrammeCode =>
    allProgrammes.includes(code as ProgrammeCode),
  );

  return override.length > 0 ? override : programmesFor(actorRoles);
})();

export const entitledTo = (code: ProgrammeCode): boolean => actorProgrammes.includes(code);

/**
 * The systems this actor may see, within the programmes they are entitled to.
 *
 * The finer half of the rule. FTLMP is three systems in one deployable, so programme entitlement
 * alone shows a mailroom officer the whole fleet register and a driver the courier manifests - screens
 * they can open and cannot use, because the service refuses every call behind them.
 *
 * Whether a system *exists* in this deployment is a separate question, answered at runtime by the
 * services' feature flags (see `featureFlags.ts`) - not by this list and not by a build-time variable.
 * The development actor switcher can still narrow the list to preview one system (dev builds only).
 */
export const actorSystems: SystemCode[] = (() => {
  const requested = readActorOverride()?.systems || '';
  const override = parseList(requested).filter((code): code is SystemCode =>
    allSystems.includes(code as SystemCode),
  );
  if (override.length > 0) {
    return override;
  }
  // Never wider than the programme entitlement, whatever the roles claim about systems.
  return systemsFor(actorRoles).filter((code) => actorProgrammes.includes(systems[code].programme));
})();

/**
 * Whether the actor may see a system: their roles entitle them to it **and** the deployment has it
 * switched on. Both the sidebar and the route guard go through here.
 */
export const entitledToSystem = (code: SystemCode): boolean =>
  actorSystems.includes(code) && isSystemEnabled(code);

export const portalLabel = (): string => portalLabelFor(actorProgrammes);

/**
 * Whether the actor can switch modules on and off. The services enforce the same rule; this only
 * decides whether to offer the Modules screen.
 */
export const isPlatformAdministrator: boolean = actorRoles.some(
  (role) => role === 'SFL_ADMIN' || role === 'DTI_ADMIN',
);
