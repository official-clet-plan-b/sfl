import type { ReactNode } from 'react';
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
import { type FleetApiError, errorDetail, errorLabel } from 'shared/errors/FleetApiError';

type DialogWidth = 'sm' | 'md' | 'lg' | 'xl';

const sizes: Record<DialogWidth, ModalSize> = { sm: 'md', md: 'lg', lg: '2xl', xl: '2xl' };

interface ActionDialogProps {
  open: boolean;
  title: string;
  description?: string;
  submitLabel: string;
  submitting: boolean;
  /** Blocks submission for reasons the form itself cannot fix (readiness, eligibility, state). */
  submitDisabled?: boolean;
  formError?: FleetApiError;
  maxWidth?: DialogWidth;
  destructive?: boolean;
  /**
   * A one-line read-back of what is about to be submitted, pinned above the actions.
   *
   * Outside `children` on purpose. The body scrolls, so anything inside it can be off screen at the
   * moment the operator reaches the submit button - which is the one moment a summary is for.
   */
  summary?: ReactNode;
  onClose: () => void;
  onSubmit: () => void;
  children: ReactNode;
}

const FOCUSABLE_FIELD =
  'input:not([type="hidden"]):not([disabled]):not([readonly]), textarea:not([disabled]):not([readonly]), [role="combobox"]:not([disabled])';

/**
 * The library `Modal` composed as an action dialog.
 *
 * Two guarantees: the submit button is disabled while a request is in flight, so a double click
 * cannot raise two writes - and the dialog cannot be dismissed mid-request, so a stray click does
 * not leave the operator unsure whether the write landed; and a form-level failure is shown above
 * the actions with the service's own wording and correlation id rather than being swallowed.
 *
 * A real `<form>` sits inside the content so Enter submits from any single-line field, which the
 * library's own button-driven composition does not give.
 *
 * Focus lands on the first form field rather than on the close button, where Enter would discard
 * the form.
 */
const ActionDialog = ({
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
}: ActionDialogProps) => (
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
      <ModalContent
        showCloseButton
        size={sizes[maxWidth]}
        {...(description ? {} : { 'aria-describedby': undefined })}
        onOpenAutoFocus={(event) => {
          const field = (event.currentTarget as HTMLElement).querySelector<HTMLElement>(FOCUSABLE_FIELD);
          if (field) {
            event.preventDefault();
            field.focus({ preventScroll: true });
          }
        }}
      >
        <form
          noValidate
          className="flex min-h-0 flex-1 flex-col"
          onSubmit={(event) => {
            event.preventDefault();
            onSubmit();
          }}
        >
          <ModalHeader>
            <ModalTitle>{title}</ModalTitle>
            {description && <ModalDescription>{description}</ModalDescription>}
          </ModalHeader>

          <ModalBody className="space-y-5">
            {children}

            {formError && (
              <Banner
                variant={formError.isForbidden ? 'warning' : 'danger'}
                heading={errorLabel(formError)}
                subtext={
                  <>
                    {formError.message}
                    {errorDetail(formError) && (
                      <span className="mt-1 block">{errorDetail(formError)}</span>
                    )}
                  </>
                }
              />
            )}
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
              loadingLabel="Working…"
              disabled={submitDisabled}
            >
              {submitLabel}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </ModalPortal>
  </Modal>
);

export default ActionDialog;
