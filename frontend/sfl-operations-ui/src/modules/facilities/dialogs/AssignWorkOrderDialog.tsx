import { useState } from 'react';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import type { AssignWorkOrderRequest, WorkOrder } from '../api/dto';
import { listVendors } from '../api/facilitiesApi';
import { assignAction } from '../api/workflow';
import { FormDialog, SelectInput, TextInput } from 'modules/facilities/dialogs/dialogKit';

interface AssignWorkOrderDialogProps {
  order: WorkOrder;
  onClose: () => void;
  onSubmit: (request: AssignWorkOrderRequest) => Promise<void>;
}

/**
 * Assigning and reassigning, which are the same call.
 *
 * Reassignment is not a different state - it is a change of owner that the audit trail records. So
 * there is one dialog and one endpoint, and the only thing that changes is the wording.
 *
 * ## Why the vendor list shows unassignable vendors instead of hiding them
 *
 * A vendor whose contract has expired is refused by the service. Dropping them from the list would
 * leave a supervisor wondering where a contractor they use every week has gone; showing them
 * disabled, with the service's own reason, answers the question in the place it is asked. The
 * `assignable` and `unassignableReason` fields come down the wire for exactly this, so no date
 * arithmetic happens here - a contract that expires mid-session expires for both sides at once.
 */
const AssignWorkOrderDialog = ({ order, onClose, onSubmit }: AssignWorkOrderDialogProps) => {
  const [assignedTo, setAssignedTo] = useState(order.assignedTo ?? '');
  const [vendorId, setVendorId] = useState(order.vendorId ?? '');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const vendors = useApiQuery((signal) => listVendors(order.siteCode, signal), [order.siteCode]);

  const vendor = (vendors.data ?? []).find((candidate) => candidate.id === vendorId);
  const action = assignAction(vendor);
  const missing = assignedTo.trim().length === 0;

  const submit = async () => {
    setTouched(true);
    if (missing || !action.allowed) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({
        assignedTo: assignedTo.trim(),
        vendorId: vendorId || null,
        expectedVersion: order.metadata.version,
      });
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title={order.assignedTo ? 'Reassign work order' : 'Assign work order'}
      description={`${order.workOrderNumber} - ${order.title}`}
      submitLabel={order.assignedTo ? 'Reassign' : 'Assign'}
      submitting={submitting}
      submitDisabled={!action.allowed}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <TextInput
          label="Assign to"
          value={assignedTo}
          onChange={setAssignedTo}
          onBlur={() => setTouched(true)}
          required
          maxLength={160}
          placeholder="The person who will attend"
          error={touched && missing}
          helperText={
            touched && missing
              ? 'An assignee is required.'
              : order.assignedTo
                ? `Currently ${order.assignedTo}.`
                : 'A contractor sees only the work orders assigned to them by this name.'
          }
        />

        <SelectInput
          label="Vendor"
          value={vendorId}
          onChange={setVendorId}
          allowEmpty
          emptyLabel="In house - no vendor"
          error={!action.allowed}
          options={(vendors.data ?? []).map((candidate) => ({
            value: candidate.id,
            label: candidate.assignable
              ? `${candidate.name} (${candidate.vendorCode})`
              : `${candidate.name} - unavailable`,
          }))}
          helperText={
            action.reason ??
            'A vendor with a tighter contracted response time shortens this order’s deadline.'
          }
        />

      </div>
    </FormDialog>
  );
};

export default AssignWorkOrderDialog;
