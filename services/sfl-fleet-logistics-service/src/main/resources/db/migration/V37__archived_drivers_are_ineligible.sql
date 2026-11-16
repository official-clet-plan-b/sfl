-- An archived or inactive driver can never be assigned, so their stored eligibility must say so.
--
-- V36 archived the Release 1 demo driver directly in SQL, which bypassed the application's eligibility
-- recompute and left the profile reading ELIGIBLE. Fuel reconciliation reads that stored status, so a
-- purchase against the archived driver passed the eligible-driver rule. This brings every such row in
-- line with what DriverEligibilityPolicy derives (DRIVER_NOT_ACTIVE is a blocking reason).
UPDATE fleet_logistics.driver_profile_references
   SET eligibility_status = 'INELIGIBLE',
       last_modified_at = GREATEST(now(), last_modified_at),
       version = version + 1
 WHERE lifecycle_status IN ('ARCHIVED', 'INACTIVE')
   AND eligibility_status IN ('ELIGIBLE', 'CONDITIONAL');
