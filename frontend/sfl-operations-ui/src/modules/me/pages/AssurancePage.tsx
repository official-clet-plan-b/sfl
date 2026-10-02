import { Link } from 'react-router';
import { PageSection } from '@rfdtech/components';
import PageHeading from 'modules/dispatch/components/PageHeading';
import Panel from 'modules/dispatch/components/Panel';
import { Callout } from 'modules/dispatch/components/formKit';
import { facilitiesPaths, fleetPaths } from 'shared/layout/navigation';

/**
 * One assurance landing for the Auditor / Compliance / Data Protection Officer - SRS §2.3.
 *
 * ## Why one view and not four
 *
 * `COMPLIANCE_OFFICER` is in `crossProgrammeRoles`, so it is entitled to every system by design.
 * That is precisely the argument for a consolidated landing: a compliance officer's question is "is
 * the record trustworthy", and answering it by visiting four per-module audit screens in turn makes
 * the platform's shape the compliance officer's problem.
 *
 * ## Why it links rather than duplicates
 *
 * Each service owns its own hash chain, its own evidence register and its own denial records, and
 * they must not be merged behind one query - that would put this dashboard in the position of
 * asserting a single chain where there are four independent ones, which is exactly the claim an
 * auditor must not be handed. So this page is a directory with the caveats attached, not a
 * synthesised view.
 *
 * The one thing it does assert is the caveat itself: verifying facilities does not verify fleet, and
 * a green tick on one chain says nothing about another.
 *
 * ## A surprise worth stating in the open
 *
 * `FACILITIES_AUDIT_INTEGRITY_CHECK` is **not** held by `FACILITIES_DIRECTOR`, and that is correct -
 * an integrity failure escalates *to* compliance, so compliance runs the check. It surprised the
 * S153 pass enough to be written down, so it is written down here too rather than being discovered
 * again by a director who cannot find the button.
 */
const AssurancePage = () => (
  <>
    <PageHeading
      title="Audit & evidence"
      subtitle="Chain verification, evidence and denial records across every system"
      crumbs={[{ label: 'Audit & evidence' }]}
    />

    <PageSection>
      <Callout tone="info" title="Four chains, not one">
        Each service hash-chains its own audit log independently. Verifying facilities says nothing
        about fleet, and there is deliberately no combined check - a single green tick over four
        separate chains would be a claim this dashboard has no standing to make.
      </Callout>
    </PageSection>

    <Panel title="Facilities, maintenance and booking">
      <ul className="space-y-2 text-sm">
        <li>
          <Link className="text-primary underline" to={facilitiesPaths.audit}>
            Audit &amp; integrity
          </Link>
          <span className="text-muted-foreground">
            {' '}- replay the chain, search records, read authorisation denials. Needs
            {' '}<code>FACILITIES_AUDIT_INTEGRITY_CHECK</code> to run the verification, which
            compliance holds and the facilities director deliberately does not.
          </span>
        </li>
      </ul>
    </Panel>

    <Panel title="Fleet, fuel and dispatch">
      <ul className="space-y-2 text-sm">
        <li>
          <Link className="text-primary underline" to={fleetPaths.evidence}>
            Evidence &amp; audit
          </Link>
          <span className="text-muted-foreground">
            {' '}- governed evidence, export requests with a recorded justification and recipient,
            and the fleet chain.
          </span>
        </li>
      </ul>
    </Panel>

    <PageSection>
      <Callout tone="warning" title="Evidence export is a separate authorised act">
        Exporting evidence is not reading it. Each export records who asked, why, and who received it,
        and that record is itself auditable - so an export made to answer a question becomes part of
        the trail the next question is asked against.
      </Callout>
    </PageSection>
  </>
);

export default AssurancePage;
