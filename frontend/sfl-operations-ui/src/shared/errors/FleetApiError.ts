import { ApiErrorEnvelope, FieldErrorPayload } from 'shared/api/types';

/**
 * A failed SFL API call, normalised.
 *
 * The service returns the SRS *Error States* wording verbatim for SRS-defined codes, so `message`
 * is safe to show to an operator as-is. `fieldErrors` is populated for `FLEET_VALIDATION_FAILED`
 * and drives inline field errors; `details` carries the domain exception's context map (for example
 * the readiness blockers behind `FLEET_READINESS_BLOCKED`).
 */
/** Codes that mean "this account may not do or see this". */
const ACCESS_DENIED = /UNAUTHORIZED|FORBIDDEN|NO_SCOPE|DENIED/;
const ACCESS_DENIED_MESSAGE =
  'Your account cannot open or change this. If you need it for your work, ask your fleet office or system administrator.';

export class FleetApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId: string | null;
  readonly fieldErrors: FieldErrorPayload[];
  readonly details: Record<string, unknown> | null;

  constructor(init: {
    status: number;
    code: string;
    message: string;
    correlationId?: string | null;
    fieldErrors?: FieldErrorPayload[];
    details?: Record<string, unknown> | null;
  }) {
    super(init.message);
    this.name = 'FleetApiError';
    this.status = init.status;
    this.code = init.code;
    this.correlationId = init.correlationId ?? null;
    this.fieldErrors = init.fieldErrors ?? [];
    this.details = init.details ?? null;
  }

  /** `true` when the caller can fix the request by editing form fields. */
  get isFieldValidation(): boolean {
    return this.fieldErrors.length > 0;
  }

  /** `true` when the record moved under the caller and the screen should reload before retrying. */
  get isVersionConflict(): boolean {
    return this.code === 'FLEET_RECORD_VERSION_CONFLICT' || this.status === 409;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }

  get isForbidden(): boolean {
    return this.status === 403;
  }

  /** Maps `fieldErrors` into the `{ field: message }` shape the form layer consumes. */
  toFieldErrorMap(): Record<string, string> {
    return this.fieldErrors.reduce<Record<string, string>>((accumulator, fieldError) => {
      const key = fieldError.field.includes('.')
        ? fieldError.field.slice(fieldError.field.lastIndexOf('.') + 1)
        : fieldError.field;
      if (!accumulator[key]) {
        accumulator[key] = fieldError.message;
      }
      return accumulator;
    }, {});
  }

  static fromEnvelope(status: number, error: ApiErrorEnvelope, data: unknown): FleetApiError {
    const fieldErrors = Array.isArray(data) ? (data as FieldErrorPayload[]) : [];
    const details =
      !Array.isArray(data) && data !== null && typeof data === 'object'
        ? (data as Record<string, unknown>)
        : null;

    // The code and correlation id are for support, not for the person using the screen: they go to
    // the browser console, where a support engineer can read them, and the page shows a sentence.
    if (status >= 400) {
      console.warn(`[SFL] ${error.code}${error.correlationId ? ` · correlation ${error.correlationId}` : ''}`);
    }
    const accessDenied = ACCESS_DENIED.test(error.code ?? '');
    return new FleetApiError({
      status,
      code: error.code,
      message: accessDenied ? ACCESS_DENIED_MESSAGE : error.message,
      correlationId: error.correlationId,
      fieldErrors,
      details,
    });
  }

  static transport(message: string): FleetApiError {
    return new FleetApiError({ status: 0, code: 'FLEET_TRANSPORT_FAILURE', message });
  }

  /**
   * A refusal decided in the browser, before any request was made.
   *
   * <p>Status 0 because nothing was sent. It exists so a check the client can make cheaply - a file
   * far over the size limit, say - surfaces through exactly the same error path as the service's own
   * refusal, and every form renders it the same way without knowing which end said no.
   *
   * <p>It never *replaces* a server-side check. Anything refused here is refused there too.
   */
  static validation(message: string): FleetApiError {
    return new FleetApiError({ status: 0, code: 'FLEET_CLIENT_VALIDATION', message });
  }

  /**
   * A failure that did not arrive in the SFL envelope.
   *
   * <h2>What this used to put in front of an operator</h2>
   *
   * <p>Spring's default error body (`{ timestamp, status, error, message, path }`) is the common
   * case - an exception the service does not map, so its handlers never ran. This built its message
   * out of that body, and an operator asking what rooms were free on Tuesday was shown:
   *
   * <blockquote>FLEET UNEXPECTED STATUS - The service could not complete
   * /api/v1/facilities/booking-availability/spaces: Bad Request. Check the service log for the
   * cause.</blockquote>
   *
   * <p>Three things wrong with that, and only the third is cosmetic. It names an endpoint, which is
   * not a thing the person reading it has. It instructs them to read a server log they cannot reach.
   * And it leads with an error code as a headline.
   *
   * <p>So the operator gets a sentence about what happened and what to do; the endpoint, the status
   * and the upstream wording go to the console, where whoever is debugging will look, and the
   * correlation id stays on screen because that is the one technical detail support will ask for.
   *
   * <p>None of this is a substitute for mapping the failure properly. An unmapped error is a gap in
   * the service's exception handler, and this reads as "something unexpected" precisely because
   * nothing more useful is knowable from here.
   */
  static fromUnmappedFailure(
    status: number,
    body: unknown,
    correlationId?: string | null,
  ): FleetApiError {
    const shape = (body ?? {}) as Record<string, unknown>;
    const reason = typeof shape.error === 'string' ? shape.error : undefined;
    const detail = typeof shape.message === 'string' ? shape.message : undefined;
    const path = typeof shape.path === 'string' ? shape.path : undefined;
    const summary = [reason, detail].filter(Boolean).join(' - ') || `HTTP ${status}`;

    console.error(
      `[SFL] Unmapped ${status} from ${path ?? 'the service'}: ${summary}`,
      correlationId ? `correlation ${correlationId}` : '',
    );

    return new FleetApiError({
      status,
      code: status >= 500 ? 'FLEET_SERVICE_FAILURE' : 'FLEET_UNEXPECTED_STATUS',
      message:
        status >= 500
          ? 'This could not be completed just now. Please try again in a moment - if it keeps happening, let your system administrator know.'
          : 'This could not be saved. Check the details you entered and try again.',
      correlationId,
    });
  }
}

/** `true` only for a real `ApiError` - a `code` and a `message`, both strings. */
export const isApiErrorEnvelope = (value: unknown): value is ApiErrorEnvelope => {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Partial<ApiErrorEnvelope>;
  return typeof candidate.code === 'string' && typeof candidate.message === 'string';
};

/**
 * Headlines an error alert. Not the error code.
 *
 * <p>`FLEET_UNEXPECTED_STATUS` rendered as "FLEET UNEXPECTED STATUS" was the first thing an operator
 * read when anything went wrong - a constant name, shouted, above a sentence explaining the actual
 * problem. It told them nothing and looked like a crash.
 *
 * <p>These titles are grouped by what the reader can do about it, which is the only distinction that
 * changes their next action: fix the form, ask for access, reload, or report it. The code itself is
 * not shown; it is logged to the console for support.
 *
 * <p>Codes are matched by suffix so the three services' prefixes (`FLEET_`, `FACILITIES_`, and the
 * SRS's own unprefixed ones) share one table rather than three that drift.
 */
const ERROR_TITLES: { match: RegExp; title: string }[] = [
  // First match wins, so the specific patterns lead. `INVALID_STATE_TRANSITION` is a state problem
  // rather than a typing one, and a looser `INVALID_` rule above this line would have claimed it and
  // told the operator to check what they entered when there was nothing wrong with it.
  {
    match: /STATE_TRANSITION|BLOCKED|LOCKED|EVIDENCE_MISSING|NOT_APPROVED|PRETRIP_INSPECTION_MISSING/,
    title: 'This cannot be done yet',
  },
  { match: ACCESS_DENIED, title: 'Not available for your account' },
  { match: /NOT_FOUND/, title: 'That record no longer exists' },
  { match: /VERSION_CONFLICT|IDEMPOTENCY/, title: 'Somebody else changed this first' },
  { match: /DUPLICATE/, title: 'That already exists' },
  { match: /VALIDATION_FAILED|CLIENT_VALIDATION|INVALID_|MISSING_/, title: 'Check what was entered' },
  { match: /TRANSPORT_FAILURE/, title: 'Could not reach the service' },
  { match: /SERVICE_FAILURE|UNEXPECTED_STATUS/, title: 'Something went wrong' },
];

/** The operator-facing headline for an error. Falls back to a neutral one, never to a code. */
export const errorLabel = (error: FleetApiError): string => {
  const code = error.code || '';
  return ERROR_TITLES.find((entry) => entry.match.test(code))?.title ?? 'Something went wrong';
};

/**
 * The technical half - deliberately no longer shown. People using SFL should read what to do, not
 * an error code and a correlation id; support finds both in the browser console.
 */
export const errorDetail = (error: FleetApiError): string | undefined => {
  // Nothing technical on screen. The code and correlation id are logged to the console when the
  // error is built (see `fromEnvelope`), which is where support reads them.
  void error;
  return undefined;
};

/** `true` for anything thrown by the API client. */
export const isFleetApiError = (value: unknown): value is FleetApiError =>
  value instanceof FleetApiError;

/** The operator-facing headline for an unknown failure. */
export const describeError = (error: unknown): string => {
  if (isFleetApiError(error)) {
    return error.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return 'An unexpected error occurred.';
};
