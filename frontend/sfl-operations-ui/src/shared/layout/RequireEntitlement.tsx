import { ReactNode } from 'react';
import { useLocation } from 'react-router';
import { Link } from 'react-router';
import Icon from 'shared/components/Icon';
import {
  ProgrammeCode,
  SystemCode,
  actorProgrammes,
  actorSystems,
  entitledTo,
  entitledToSystem,
  programmes,
  systems,
} from './programmes';
import { capabilityRefusedFor, landingPath } from './navigation';
import { servesPlatform, servingPlatformName } from 'shared/platform';
import { isSystemEnabled, useFeatureFlagsVersion } from './featureFlags';

/**
 * Refuses a route the actor is not entitled to, at whichever grain fails.
 *
 * The sidebar already stops offering these destinations, but a typed address, a bookmark or a link in
 * an old email reaches them anyway. Without this the operator would get the screen and then a page of
 * `403`s from the service - technically correct, and a poor way to be told.
 *
 * **The refusal names the grain that actually failed**, because the two are different conversations.
 * A driver reaching courier and dispatch is inside their own programme and lacks one system of it, so
 * "Fleet, Transport & Logistics is not part of your work" would be false - they are looking at it. A
 * security officer reaching the fleet register is outside their programme entirely. Same page,
 * different sentence, and only one of them is true in each case.
 *
 * **This is not the enforcement point and must never be treated as one.** Every service authorises
 * every call independently: S174 refuses an unentitled actor with `EMERGENCY_UNAUTHORIZED_SCOPE`
 * whether or not this component exists. What this does is answer the question honestly and in one
 * place, instead of leaving the operator to infer it from a broken screen.
 */
const RequireEntitlement = ({
  system,
  children,
}: {
  system: SystemCode;
  children: ReactNode;
}) => {
  const target = systems[system];
  const programme = programmes[target.programme];
  const { pathname } = useLocation();
  // Re-render when a module is switched on or off while this page is open.
  useFeatureFlagsVersion();

  /*
    Not served here, before entitlement is considered.

    The sidebar no longer offers another platform's screens on this origin, but a typed address, a
    bookmark or a link in an old email still reaches them - and the screen would then load and call a
    service on another port for its data. That is where "Could not reach the Fleet & Logistics
    service at http://localhost:8093" came from on a dashboard somebody had opened to look at
    facilities: a true message about a screen that was never this origin's to serve.

    Separate from the entitlement refusal below because it is a different fact about a different
    thing. Entitlement is about the person; this is about the address. An operator entitled to fleet
    who reaches it here is not being refused - they are in the wrong place, and the sentence should
    say so rather than implying their roles are short.
  */
  if (!servesPlatform(target.programme)) {
    return <NotServedHere programme={target.programme} />;
  }

  // Switched off in this deployment - nobody's roles are the reason, so say that instead.
  if (!isSystemEnabled(system)) {
    return <SwitchedOff label={target.label} />;
  }

  if (entitledTo(target.programme) && entitledToSystem(system)) {
    /*
      Entitled to the system, but this particular screen is not this actor's work.

      The sidebar stops offering it; a typed address does not, and without this the operator would
      reach a register they cannot act on and meet a wall of 403s instead of a sentence. Checked
      after entitlement so the coarser, more useful refusal above wins when both apply.
    */
    if (capabilityRefusedFor(pathname)) {
      return <NotYourWork />;
    }
    return <>{children}</>;
  }

  // Programme first: it is the coarser failure and the more useful thing to be told.
  const programmeRefused = !entitledTo(target.programme);
  const home = landingPath();

  const heading = programmeRefused
    ? `${programme.label} is not part of your work`
    : `${target.label} is not part of your work`;

  const covered = programmeRefused
    ? actorProgrammes.map((code) => programmes[code].label)
    : actorSystems.filter(isSystemEnabled).map((code) => systems[code].label);

  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center text-center">
      <span className="mb-4 flex h-14 w-14 items-center justify-center rounded-lg bg-gray-100 text-gray-600">
        <Icon name="lock" size={26} />
      </span>
      <p className="text-title-sm font-bold text-gray-900">{heading}</p>
      <p className="mt-2 max-w-lg text-theme-sm text-gray-600">
        {programmeRefused ? (
          <>
            This screen belongs to {programme.label} - {programme.scope.toLowerCase()}.
          </>
        ) : (
          <>
            This screen belongs to {target.label}, one of the {programme.label} systems.
          </>
        )}{' '}
        Your roles cover{' '}
        {covered.length > 0
          ? covered.join(', ')
          : programmeRefused
            ? 'no SFL programme'
            : 'none of its systems'}
        . If you need it, ask for the role that carries it rather than a link to this page - the
        service would refuse the request regardless.
      </p>
      {home && (
        <Link
          to={home}
          className="mt-5 inline-flex h-11 items-center gap-2 rounded-lg bg-brand-800 px-4 text-theme-sm font-medium text-white transition-colors hover:bg-brand-700"
        >
          <Icon name="dashboard" size={17} />
          Back to your dashboard
        </Link>
      )}
    </div>
  );
};

/** A system the deployment's feature flags have switched off. */
const SwitchedOff = ({ label }: { label: string }) => {
  const home = landingPath();
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center text-center">
      <span className="mb-4 flex h-14 w-14 items-center justify-center rounded-lg bg-gray-100 text-gray-600">
        <Icon name="lock" size={26} />
      </span>
      <p className="text-title-sm font-bold text-gray-900">{label} is not switched on</p>
      <p className="mt-2 max-w-lg text-theme-sm text-gray-600">
        A system administrator has switched this module off for everyone. Your roles are not the
        reason you are seeing this - ask them if you expected it to be available.
      </p>
      {home && (
        <Link
          to={home}
          className="mt-5 inline-flex h-11 items-center gap-2 rounded-lg bg-brand-800 px-4 text-theme-sm font-medium text-white transition-colors hover:bg-brand-700"
        >
          <Icon name="dashboard" size={17} />
          Back to your dashboard
        </Link>
      )}
    </div>
  );
};

/**
 * What a screen belonging to another platform shows on this origin.
 *
 * Deliberately not phrased as a refusal. Nothing is being withheld from the operator: this service
 * serves one platform and the screen belongs to another, so the honest answer names both and points
 * at the portal, which serves all three.
 */
const NotServedHere = ({ programme }: { programme: ProgrammeCode }) => {
  const target = programmes[programme];
  const home = landingPath();

  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center text-center">
      <span className="mb-4 flex h-14 w-14 items-center justify-center rounded-lg bg-gray-100 text-gray-600">
        <Icon name="lock" size={26} />
      </span>
      <p className="text-title-sm font-bold text-gray-900">
        {target.label} is not served here
      </p>
      <p className="mt-2 max-w-lg text-theme-sm text-gray-600">
        This is the {servingPlatformName()} service, and it serves only its own screens. {target.label}{' '}
        runs on its own service, and the unified portal serves all of them together. Your roles are
        not the reason you are seeing this.
      </p>
      {home && (
        <Link
          to={home}
          className="mt-5 inline-flex h-11 items-center gap-2 rounded-lg bg-brand-800 px-4 text-theme-sm font-medium text-white transition-colors hover:bg-brand-700"
        >
          <Icon name="dashboard" size={17} />
          Back to your dashboard
        </Link>
      )}
    </div>
  );
};

/**
 * A screen inside the actor's own systems that is nonetheless not their job.
 *
 * Distinct from both other refusals: the programme is theirs and the system is theirs, so saying
 * either is "not part of your work" would be false. What they lack is the doing of this particular
 * thing - a technician at the work-order register, a driver at the vehicle register.
 */
const NotYourWork = () => {
  const home = landingPath();
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center text-center">
      <span className="mb-4 flex h-14 w-14 items-center justify-center rounded-lg bg-gray-100 text-gray-600">
        <Icon name="lock" size={26} />
      </span>
      <p className="text-title-sm font-bold text-gray-900">This screen is not part of your work</p>
      <p className="mt-2 max-w-lg text-theme-sm text-gray-600">
        You can reach the system it belongs to, but nothing on this screen is yours to do. The
        service would refuse the actions on it regardless, so this is the same answer arriving
        sooner. If you need it, ask for the role that carries the work rather than a link.
      </p>
      {home && (
        <Link
          to={home}
          className="mt-5 inline-flex h-11 items-center gap-2 rounded-lg bg-brand-800 px-4 text-theme-sm font-medium text-white transition-colors hover:bg-brand-700"
        >
          <Icon name="dashboard" size={17} />
          Back to your dashboard
        </Link>
      )}
    </div>
  );
};

export default RequireEntitlement;

/**
 * What an actor with no programme at all sees.
 *
 * Distinct from the refusal above: nothing has been asked for and nothing is being withheld - the
 * account simply carries no role this dashboard recognises. Saying so is better than an empty shell
 * that reads as a broken deployment.
 */
export const NoProgrammePage = () => (
  <div className="flex min-h-[60vh] flex-col items-center justify-center text-center">
    <span className="mb-4 flex h-14 w-14 items-center justify-center rounded-lg bg-gray-100 text-gray-600">
      <Icon name="user" size={26} />
    </span>
    <p className="text-title-sm font-bold text-gray-900">No programme is assigned to you</p>
    <p className="mt-2 max-w-lg text-theme-sm text-gray-600">
      SFL covers four programmes - facilities and infrastructure, safety and security, fleet and
      logistics, and asset visibility. Your roles grant none of them, so there is nothing to show.
      Ask for the role that covers the work you do.
    </p>
  </div>
);
