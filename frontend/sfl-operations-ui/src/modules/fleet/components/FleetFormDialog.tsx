import { ReactNode } from 'react';
import {
  Banner,
  Button,
  Modal,
  ModalBody,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalOverlay,
  ModalPortal,
  ModalTitle,
  type ModalSize,
} from '@rfdtech/components';
import { FleetApiError, errorDetail, errorLabel } from 'shared/errors/FleetApiError';

interface FleetFormDialogProps {
  open: boolean;
  title: string;
  description?: string;
  submitLabel: string;
  submitting: boolean;
  /** Blocks submission for reasons the form itself cannot fix (readiness, eligibility, state). */
  submitDisabled?: boolean;
  formError?: FleetApiError;
  maxWidth?: ModalSize;
  destructive?: boolean;
  /**
   * A one-line read-back of what is about to be submitted, pinned above the actions.
   *
   * Outside the body on purpose. The body scrolls, so anything inside it can be off screen at the
   * moment the operator reaches the submit button - which is the one moment a summary is for. See
   * `FormSummary`.
   */
  summary?: ReactNode;
  onClose: () => void;
  onSubmit: () => void;
  children: ReactNode;
}

/**
 * The shell every fleet action dialog uses, on the library's `Modal`.
 *
 * Two guarantees: the submit button is disabled while a request is in flight, so a double click
 * cannot raise two trips; and a form-level failure is shown above the actions with the service's
 * own wording and correlation id rather than being swallowed. The dialog also refuses to close
 * while the request is in flight, so an Escape cannot orphan it.
 *
 * The `<form>` is kept, with `display: contents` so the modal's own layout is undisturbed, because
 * pressing Enter in a field has always submitted these dialogs.
 */
const FleetFormDialog = ({
  open,
  title,
  description,
  submitLabel,
  submitting,
  submitDisabled,
  formError,
  maxWidth = 'md',
  destructive,
  summary,
  onClose,
  onSubmit,
  children,
}: FleetFormDialogProps) => (
  <Modal
    open={open}
    onOpenChange={(next) => {
      if (!next && !submitting) {
        onClose();
      }
    }}
  >
    <ModalPortal>
      <ModalOverlay />
      <ModalContent showCloseButton size={maxWidth}>
        <ModalHeader>
          <ModalTitle>{title}</ModalTitle>
          {description && <ModalDescription>{description}</ModalDescription>}
        </ModalHeader>
        <form
          className="contents"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            onSubmit();
          }}
        >
          <ModalBody>
            <div className="flex flex-col gap-4">
              {children}
              {formError && (
                <Banner
                  variant={formError.isForbidden ? 'warning' : 'danger'}
                  heading={errorLabel(formError)}
                  subtext={
                    <>
                      {formError.message}
                      {errorDetail(formError) && (
                        <span className="block">{errorDetail(formError)}</span>
                      )}
                    </>
                  }
                />
              )}
            </div>
          </ModalBody>
          {summary}
          <ModalFooter>
            <Button variant="ghost" onClick={onClose} disabled={submitting}>
              Cancel
            </Button>
            <Button
              type="submit"
              variant={destructive ? 'primary-destructive' : 'primary'}
              loading={submitting}
              disabled={submitDisabled}
            >
              {submitting ? 'Working…' : submitLabel}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </ModalPortal>
  </Modal>
);

export default FleetFormDialog;
