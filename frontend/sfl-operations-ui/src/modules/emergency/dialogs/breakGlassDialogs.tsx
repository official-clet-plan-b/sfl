import type { BreakGlassRequest, NotificationActivation } from 'modules/emergency/api/dto';
import { breakGlassApi } from 'modules/emergency/api/emergencyApi';
import {
  ConsequenceLine,
  ConsequencePanel,
  listChannels,
} from 'modules/emergency/components/EmergencyFields';
import { humanise } from 'modules/fleet/api/enums';
import { formatNumber } from 'shared/components/format';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { Banner } from '@rfdtech/components';
import ActionDialog from 'modules/emergency/components/ActionDialog';
import { TextField } from 'modules/emergency/components/FormFields';

interface ConfirmBreakGlassDialogProps {
  open: boolean;
  request: BreakGlassRequest;
  /** Resolved for display: the operator confirms against names, not identifiers. */
  summary: {
    scenarioName: string;
    templateTitle: string;
    templateBody: string;
    reach: number;
    zoneNames: string[];
    eligibleVia: string;
  };
  onClose: () => void;
  onSent: (activation: NotificationActivation) => void;
}

/**
 * The last step before a break-glass broadcast - `POST /activations/break-glass`.
 *
 * Everything on this dialog is there because of what the action is: a message to the whole named
 * audience, over every chosen channel, with **nobody approving it first**, which cannot be recalled
 * and which leaves an obligation behind.
 *
 * The typed confirmation is the one piece of deliberate friction in this module. It is not
 * ceremony: this is the only control in the dashboard that broadcasts to a site with no second
 * pair of eyes, and it sits one click from a page an operator may have reached in a hurry. Typing
 * the word is what separates "I meant this" from "I clicked the red button".
 */
export const ConfirmBreakGlassDialog = ({
  open,
  request,
  summary,
  onClose,
  onSent,
}: ConfirmBreakGlassDialogProps) => {
  const form = useFleetForm({
    initialValues: { confirmation: '' },
    onSubmit: async () => {
      const sent = await breakGlassApi.send(request);
      onSent(sent);
      onClose();
    },
  });

  const confirmed = form.values.confirmation.trim().toUpperCase() === 'BROADCAST';

  return (
    <ActionDialog
      open={open}
      title="Send a break-glass broadcast"
      description="No approval. No recall. Closure will be blocked until this is accounted for."
      submitLabel="Send break-glass broadcast"
      submitting={form.submitting}
      submitDisabled={!confirmed}
      formError={form.formError}
      destructive
      maxWidth="lg"
      onClose={onClose}
      onSubmit={form.submit}
    >
      <ConsequencePanel title="What goes out the moment this is confirmed" tone="warning">
        <ConsequenceLine label="Scenario" value={summary.scenarioName} />
        <ConsequenceLine label="Message" value={summary.templateTitle} />
        <ConsequenceLine label="Recipients" value={formatNumber(summary.reach)} />
        <ConsequenceLine label="Channels" value={listChannels(request.channels)} />
        <ConsequenceLine
          label="Messages"
          value={formatNumber(summary.reach * request.channels.length)}
        />
        <ConsequenceLine label="Priority" value={humanise(request.priority ?? 'CRITICAL')} />
        {summary.zoneNames.length > 0 && (
          <ConsequenceLine label="Zones" value={summary.zoneNames.join(', ')} />
        )}
        <ConsequenceLine label="Eligible via" value={summary.eligibleVia} />
      </ConsequencePanel>

      <div className="rounded-md border border-gray-200 bg-white px-4 py-3">
        <p className="text-theme-xs font-semibold tracking-wide text-gray-600 uppercase">
          Message text
        </p>
        <p className="mt-1.5 whitespace-pre-wrap text-theme-sm text-gray-900">
          {summary.templateBody}
        </p>
      </div>

      <Banner variant="warning" heading="What this leaves behind"
  subtext={<>The activation is created already live, in break-glass mode. It cannot be closed until
        somebody holding the after-action approval permission records a justification against it -
        that is the account of why approval was bypassed, and it is what an auditor will read.</>} />

      <TextField
        label="Type BROADCAST to confirm"
        value={form.values.confirmation}
        onChange={(value) => form.setValue('confirmation', value)}
        autoFocus
        placeholder="BROADCAST"
        helperText="Deliberate friction. This is the only send in the dashboard with no second approver."
      />
    </ActionDialog>
  );
};
