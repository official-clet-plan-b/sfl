import { useState } from 'react';
import { FleetApiError } from 'shared/errors/FleetApiError';

/**
 * Runs one write and keeps the dialog's own state: in flight, or the service's refusal in its own words.
 * The dialog stays open on a failure so the operator can read it and correct the form.
 */
export const useSubmit = () => {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<FleetApiError>();

  const run = async (write: () => Promise<unknown>, onDone: () => void): Promise<void> => {
    setSubmitting(true);
    setError(undefined);
    try {
      await write();
      onDone();
    } catch (cause) {
      setError(cause instanceof FleetApiError
        ? cause
        : new FleetApiError({ status: 0, code: 'UNKNOWN', message: 'The request could not be completed.' }));
    } finally {
      setSubmitting(false);
    }
  };

  return { submitting, error, run };
};
