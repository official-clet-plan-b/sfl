-- =============================================================================================
-- S174 drill mode, for Phase 2 S175 (SRS-SFL-S175-01; ADR 0011).
--
-- A drill goes down the real notification path - same gateway, channels and audiences - so that it
-- exercises what an emergency would use. What keeps it from ever reading as one is checked at three points
-- (DrillSeparationPolicy) and held here as well:
--
--   * a template is either a drill template or not, and a drill template is never break-glass eligible;
--   * a drill template's title and body open with "DRILL - THIS IS AN EXERCISE", and a real template's
--     contain it nowhere - the SRS's "Test/Real Ambiguity", refused at setup;
--   * an activation can run in DRILL mode, which needs no approval and is never counted as live.
--
-- The marker check is NOT VALID: it holds every template created from here on without failing this
-- migration on a database whose existing real templates happen to contain the phrase. Those were written
-- before the rule existed; the service rechecks the marker at every drill send regardless.
-- =============================================================================================

ALTER TABLE emergency_notification.notification_templates
    ADD COLUMN drill BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE emergency_notification.notification_templates
    ADD CONSTRAINT ck_template_drill_not_break_glass CHECK (NOT (drill AND break_glass_eligible));

ALTER TABLE emergency_notification.notification_templates
    ADD CONSTRAINT ck_template_drill_marker CHECK (
        (drill AND upper(ltrim(title)) LIKE 'DRILL - THIS IS AN EXERCISE%'
              AND upper(ltrim(body)) LIKE 'DRILL - THIS IS AN EXERCISE%')
        OR (NOT drill AND upper(title) NOT LIKE '%DRILL - THIS IS AN EXERCISE%'
                      AND upper(body) NOT LIKE '%DRILL - THIS IS AN EXERCISE%')
    ) NOT VALID;

ALTER TABLE emergency_notification.notification_activations
    DROP CONSTRAINT ck_activation_mode;

ALTER TABLE emergency_notification.notification_activations
    ADD CONSTRAINT ck_activation_mode CHECK (mode IN ('ROUTINE', 'BREAK_GLASS', 'DEGRADED', 'DRILL'));
