import { permits } from 'shared/layout/actorPermissions';

/**
 * What this actor may do in fleet, fuel and dispatch.
 *
 * <h2>Why these exist</h2>
 *
 * Reading a register and changing it are different permissions in every one of the three matrices,
 * and the screens were not making that distinction. Every FTLMP register offered its create button
 * to anybody who could open the page, so a driver - who holds `FLEET_VEHICLE_READ` and
 * `FLEET_TRIP_READ` and nothing else that writes - was shown **Register vehicle**, **Register
 * driver** and **Plan a trip**. Pressing any of them produced `FLEET_UNAUTHORIZED_SCOPE`, which is
 * the service doing its job and the screen having wasted the operator's time to get there.
 *
 * <h2>Hidden, not disabled</h2>
 *
 * Every helper below gates a control that is hidden when the answer is false, following the rule
 * S153 paid for: **a permission denial hides the control; a state or data shortfall disables it with
 * the reason.** A driver will never hold `FLEET_VEHICLE_MANAGE`, so a greyed-out Register vehicle
 * button is a permanent question they cannot answer.
 *
 * <h2>Never the enforcement point</h2>
 *
 * The services authorise every call independently and refuse regardless of what the screen offered.
 * These decide what is *worth offering*, which is a usability question - and `permits` fails open
 * when the services could not be asked, so a failed lookup shows the control rather than hiding the
 * application.
 */

// ---- S166 fleet -------------------------------------------------------------------------------

/** Register, edit or retire a vehicle. A driver reads the register and changes nothing in it. */
export const canManageVehicles = (): boolean => permits('FLEET_VEHICLE_MANAGE');

/**
 * Register or amend a driver record.
 *
 * Worth stating because the name invites the wrong reading: this is not "am I a driver", it is "may
 * I create and amend driver records" - a personnel function. `FLEET_DRIVER` does not hold it, and a
 * driver registering themselves is exactly what it exists to prevent.
 */
export const canManageDrivers = (): boolean => permits('FLEET_DRIVER_MANAGE');

/** Plan a trip. Assigning, closing and cancelling are separate grants below. */
export const canManageTrips = (): boolean => permits('FLEET_TRIP_MANAGE');

/**
 * Confirm or defer a trip assigned to you.
 *
 * A driver's only write against the trip register, and the mirror image of the grants around it:
 * `canManageTrips` is a dispatcher's power over anybody's trip, this is one person's answer about
 * their own. The permission is necessary and not sufficient - the service also requires the
 * signed-in identity to be the driver on that trip - so a screen must check
 * {@link isMyTrip} as well before offering the control, or it offers every driver a button that
 * refuses on every trip but one.
 */
export const canAcknowledgeTrips = (): boolean => permits('FLEET_TRIP_ACKNOWLEDGE');
export const canAssignTrips = (): boolean => permits('FLEET_TRIP_ASSIGN');
export const canCloseTrips = (): boolean => permits('FLEET_TRIP_CLOSE');
export const canCancelTrips = (): boolean => permits('FLEET_TRIP_CANCEL');
/**
 * Start a trip assigned to you.
 *
 * The mirror image of `canManageTrips`, the same way `canAcknowledgeTrips` mirrors it for
 * confirmation: this is one driver's answer about their own trip, not a dispatcher's power over
 * anybody's. Necessary and not sufficient - the service also requires the signed-in identity to be
 * the driver on that trip - so a screen must check the trip is genuinely theirs (see
 * {@link canAcknowledgeTrips}'s doc) before offering the control.
 */
export const canStartOwnTrip = (): boolean => permits('FLEET_TRIP_START_OWN');
/**
 * Report your own position while driving a trip assigned to you.
 *
 * Same shape as {@link canStartOwnTrip}: necessary and not sufficient, the service also requires the
 * trip to be yours and `IN_PROGRESS`. Without a connected telematics vendor, this is the one source
 * of a live position the fleet office has.
 */
export const canReportOwnTripLocation = (): boolean => permits('FLEET_TRIP_LOCATION_REPORT_OWN');
export const canRecordInspections = (): boolean => permits('FLEET_INSPECTION_RECORD');

export const canManageWorkflow = (): boolean => permits('FLEET_WORKFLOW_MANAGE');
export const canManageCompliance = (): boolean => permits('FLEET_COMPLIANCE_MANAGE');
export const canManageServiceRecords = (): boolean => permits('FLEET_SERVICE_RECORD_MANAGE');
export const canRequestEvidenceExport = (): boolean => permits('FLEET_EVIDENCE_EXPORT_REQUEST');

/** Read the audit trail. Narrower than reading evidence: an auditor, compliance officer or admin. */
export const canReadAudit = (): boolean => permits('FLEET_AUDIT_READ');

/**
 * Replay the tamper-evident hash chain from genesis.
 *
 * <p>Held by the auditor, the compliance officer and the DTI administrator, and by no operational
 * role - a fleet manager does not audit their own service. Offering the tab regardless is how one
 * ended up reading `FLEET_UNAUTHORIZED_SCOPE` and a correlation id, which is the service refusing
 * correctly and the screen having wasted their time to get there.
 */
export const canVerifyAuditChain = (): boolean => permits('FLEET_AUDIT_INTEGRITY_CHECK');
export const canReplayIntegration = (): boolean => permits('FLEET_INTEGRATION_REPLAY');

// ---- S168 fuel --------------------------------------------------------------------------------

export const canCaptureFuel = (): boolean => permits('FUEL_TRANSACTION_CAPTURE');
/**
 * Export the transaction report.
 *
 * <p>Separate from reading the register, and the separation is the point: an auditor and a compliance
 * officer hold it, a reporting viewer does not. Reading figures on screen and walking out with a CSV
 * of every fill at a site are different acts, and the matrix has always said so.
 */
export const canExportFuelReports = (): boolean => permits('FUEL_REPORT_EXPORT');
export const canVoidFuel = (): boolean => permits('FUEL_TRANSACTION_VOID');
export const canImportFuel = (): boolean => permits('FUEL_TRANSACTION_IMPORT');
export const canManageFuelPolicies = (): boolean => permits('FUEL_POLICY_MANAGE');
export const canManageAnomalies = (): boolean => permits('FUEL_ANOMALY_MANAGE');
export const canRunReconciliation = (): boolean => permits('FUEL_RECONCILIATION_RUN');
export const canManageFuelCards = (): boolean => permits('FUEL_CARD_MANAGE');

/**
 * Create a logbook. A driver holds this - it is their own journey record.
 *
 * Reviewing one is `FUEL_LOGBOOK_REVIEW`, which they do not hold, and the two must not be collapsed:
 * a driver who could review would be approving their own submission.
 */
export const canCreateLogbooks = (): boolean => permits('FUEL_LOGBOOK_CREATE');
export const canReviewLogbooks = (): boolean => permits('FUEL_LOGBOOK_REVIEW');

// ---- S171 dispatch ----------------------------------------------------------------------------

export const canRegisterItems = (): boolean => permits('DISPATCH_ITEM_REGISTER');
export const canManageItems = (): boolean => permits('DISPATCH_ITEM_MANAGE');
export const canCreateManifests = (): boolean => permits('DISPATCH_MANIFEST_CREATE');
export const canRecordCustody = (): boolean => permits('DISPATCH_CUSTODY_RECORD');
export const canConfirmReceipt = (): boolean => permits('DISPATCH_RECEIPT_CONFIRM');
export const canReconcileReturns = (): boolean => permits('DISPATCH_RETURN_RECONCILE');
export const canRegisterInbound = (): boolean => permits('DISPATCH_INBOUND_REGISTER');
export const canDistributeInbound = (): boolean => permits('DISPATCH_INBOUND_DISTRIBUTE');
export const canManageDispatchExceptions = (): boolean => permits('DISPATCH_EXCEPTION_MANAGE');
