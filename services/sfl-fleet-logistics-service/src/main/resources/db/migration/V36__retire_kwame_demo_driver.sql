-- Retires the Release 1 demo driver seeded by V26 (staff reference KWAME.DRIVER, sign-in kwame.driver).
--
-- Drivers are now registered by fleet administration against the staff member's own sign-in username,
-- so a hard-coded demo driver only confuses who is who. Archived and unlinked rather than deleted: in an
-- environment where the demo driver was already given trips, logbooks or fuel records those keep their
-- history, but the profile no longer appears as an active driver and no sign-in resolves to it.
UPDATE fleet_logistics.driver_profile_references
   SET lifecycle_status = 'ARCHIVED',
       principal_subject = NULL,
       last_modified_by = 'release1.seed.retired',
       last_modified_at = GREATEST(now(), created_at),
       version = version + 1
 WHERE upper(staff_reference) = 'KWAME.DRIVER'
   AND created_by = 'release1.seed'
   AND lifecycle_status <> 'ARCHIVED';
