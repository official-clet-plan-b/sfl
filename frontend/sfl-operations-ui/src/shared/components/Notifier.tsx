import { PropsWithChildren, createContext, useContext, useMemo } from 'react';
import { ToastProvider, Toaster, useToast } from '@rfdtech/components';
import { describeError, isFleetApiError, errorDetail } from 'shared/errors/FleetApiError';
import Icon from './Icon';

interface NotifierContextValue {
  notifySuccess: (message: string, detail?: string) => void;
  notifyInfo: (message: string, detail?: string) => void;
  /** Renders the service's message plus its correlation id, which support will ask for. */
  notifyError: (error: unknown, fallback?: string) => void;
}

const NotifierContext = createContext<NotifierContextValue | undefined>(undefined);

/** Failures stay on screen twice as long as confirmations, because they are the ones worth reading. */
const SUCCESS_MS = 5000;
const FAILURE_MS = 10000;

const NotifierBridge = ({ children }: PropsWithChildren) => {
  const { toast } = useToast();

  const value = useMemo<NotifierContextValue>(
    () => ({
      notifySuccess: (message, detail) =>
        toast({
          title: message,
          description: detail,
          variant: 'success',
          icon: <Icon name="check-circle" size={18} />,
          duration: SUCCESS_MS,
        }),
      notifyInfo: (message, detail) =>
        toast({
          title: message,
          description: detail,
          icon: <Icon name="info" size={18} />,
          duration: SUCCESS_MS,
        }),
      notifyError: (error, fallback) => {
        const forbidden = isFleetApiError(error) && error.isForbidden;
        toast({
          title: fallback ?? describeError(error),
          description: isFleetApiError(error) ? errorDetail(error) : undefined,
          variant: forbidden ? 'warning' : 'error',
          icon: <Icon name={forbidden ? 'alert-triangle' : 'alert-circle'} size={18} />,
          duration: FAILURE_MS,
        });
      },
    }),
    [toast],
  );

  return <NotifierContext.Provider value={value}>{children}</NotifierContext.Provider>;
};

/**
 * Global, non-blocking feedback for anything that is not a field error.
 *
 * Field-level problems belong on the field (see `useFleetForm`); this is for the rest - a
 * successful transition, an authorisation refusal, a service that cannot be reached. The toasts are
 * the CLET library's; this keeps the dashboard's own vocabulary (`notifyError` takes a service error
 * and renders its message plus correlation id) on top of them.
 */
export const NotifierProvider = ({ children }: PropsWithChildren) => (
  <ToastProvider>
    <NotifierBridge>{children}</NotifierBridge>
    <Toaster />
  </ToastProvider>
);

export const useNotifier = (): NotifierContextValue => {
  const context = useContext(NotifierContext);
  if (!context) {
    throw new Error('useNotifier must be used inside a NotifierProvider');
  }
  return context;
};
