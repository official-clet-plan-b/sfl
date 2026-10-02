-- =============================================================================================
-- Phase 2 SSEMP foundation (SRS CLET/DTI/CL9/SFL/SRS/2026/002, Section 3.2; ADR 0010).
--
-- What S165, S164 and S175 all depend on, landed once so the system migrations that follow do not
-- each invent their own:
--
--   1. Row-level security from each Phase 2 table's first migration (CORR-06, NFR-SEC3): the
--      sfl_app role, safety_security.site_in_scope() and safety_security.apply_site_scope_policies().
--   2. Delivery-state columns on safety_security.outbox_messages, so a drainer can retry with backoff
--      and dead-letter - until now the table was written to and never read.
--
-- The inbound half needs nothing new: V1 created safety_security.inbox_messages, keyed on
-- message_id, and nothing has ever written to it.
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- 1. Row-level security.
--
-- The facilities mechanism (V14/V15 there, ADR 0007), ported rather than reinvented: the schema owner
-- keeps its bypass and runs migrations, and a separate sfl_app role carries the policies. Production
-- connects as sfl_app; development and the test suite keep connecting as the owner, so nothing that
-- works today stops working. The proof it works is a test that connects as sfl_app explicitly.
--
-- One deliberate difference from facilities: the policy is applied to an explicit list of tables, not
-- to every table carrying a site_code. Phase 1 SSEMP tables (S160 to S163, S174) are out of scope for
-- this pass - ADR 0010 - and a catalogue loop would have switched them all on at once, along with
-- every sweep that reads them. A Phase 2 migration names its own tables and calls this last.
-- ---------------------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sfl_app') THEN
        -- NOLOGIN by default: an environment that adopts this grants LOGIN and a password itself,
        -- rather than a migration inventing a credential and putting it in version control.
        CREATE ROLE sfl_app NOLOGIN;
    END IF;
END
$$;

-- STABLE, not IMMUTABLE: it reads a session setting, which is constant within a statement and not
-- across them. IMMUTABLE would let the planner cache a scope across transactions - precisely the leak
-- this design exists to avoid. Identical to facilities.site_in_scope, so SiteScopeGuc serves both.
CREATE OR REPLACE FUNCTION safety_security.site_in_scope(row_site TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT CASE
        -- Unset or empty: no rows. A policy that opened up when the application forgot to set the
        -- scope would protect nothing.
        WHEN coalesce(current_setting('app.site_scopes', true), '') = '' THEN FALSE
        WHEN current_setting('app.site_scopes', true) = '*' THEN TRUE
        WHEN '*' = ANY (string_to_array(current_setting('app.site_scopes', true), ',')) THEN TRUE
        WHEN row_site IS NULL THEN FALSE
        ELSE row_site = ANY (string_to_array(current_setting('app.site_scopes', true), ','))
    END;
$$;

COMMENT ON FUNCTION safety_security.site_in_scope(TEXT) IS
    'ADR 0007/0010. Reads app.site_scopes, set per transaction by SiteScopeGuc. Fails closed when unset.';

-- Applies the fail-closed site_scope_read policy to each named table. Safe to run any number of
-- times: ENABLE is idempotent and the policy is dropped and recreated. A named table with no
-- site_code is an error, not a skip - a table that silently stayed unprotected is the defect this
-- exists to prevent. Grants are table-by-table, so naming a table here is also what lets sfl_app use
-- it, and Phase 1 tables stay exactly as they were.
CREATE OR REPLACE FUNCTION safety_security.apply_site_scope_policies(target_tables TEXT[])
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    target TEXT;
    applied INTEGER := 0;
BEGIN
    GRANT USAGE ON SCHEMA safety_security TO sfl_app;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA safety_security TO sfl_app;

    FOREACH target IN ARRAY target_tables
    LOOP
        IF NOT EXISTS (
            SELECT 1
              FROM information_schema.columns
             WHERE table_schema = 'safety_security'
               AND table_name = target
               AND column_name = 'site_code') THEN
            RAISE EXCEPTION 'safety_security.% has no site_code column; it cannot carry the site-scope policy',
                target;
        END IF;

        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON safety_security.%I TO sfl_app', target);
        EXECUTE format('ALTER TABLE safety_security.%I ENABLE ROW LEVEL SECURITY', target);
        EXECUTE format('DROP POLICY IF EXISTS site_scope_read ON safety_security.%I', target);
        EXECUTE format(
            'CREATE POLICY site_scope_read ON safety_security.%I FOR ALL TO sfl_app '
            || 'USING (safety_security.site_in_scope(site_code::text)) '
            || 'WITH CHECK (safety_security.site_in_scope(site_code::text))',
            target);
        applied := applied + 1;
    END LOOP;
    RETURN applied;
END
$$;

COMMENT ON FUNCTION safety_security.apply_site_scope_policies(TEXT[]) IS
    'ADR 0010 / SRS-2026-002 CORR-06. Applies the fail-closed site_scope_read policy to each named table. '
    'Every Phase 2 SSEMP migration calls it last, for the tables it created.';

-- The two platform tables sfl_app needs whatever module it is serving: the outbox it writes to and the
-- audit chain. Neither carries a policy - the audit chain is read across sites to be replayed, and the
-- outbox is drained by a platform thread - so these are plain grants. The inbox likewise.
GRANT SELECT, INSERT, UPDATE ON safety_security.outbox_messages TO sfl_app;
GRANT SELECT, INSERT ON safety_security.audit_log TO sfl_app;
GRANT SELECT, INSERT ON safety_security.inbox_messages TO sfl_app;
GRANT USAGE ON SCHEMA safety_security TO sfl_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA safety_security TO sfl_app;

-- ---------------------------------------------------------------------------------------------
-- 2. Outbox delivery state.
--
-- The same four columns facilities V5 added, for the same drainer. Existing rows are PENDING and will
-- be delivered on the drainer's first tick: everything SSEMP has recorded since V1 leaves the service
-- once, in creation order. That is the intended effect, not a side effect - each of those rows was
-- written as a promise that the event would be published.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE safety_security.outbox_messages
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN last_attempt_at TIMESTAMPTZ,
    ADD COLUMN dead_lettered_at TIMESTAMPTZ;

ALTER TABLE safety_security.outbox_messages
    ADD CONSTRAINT ck_safety_security_outbox_status
        CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD_LETTERED'));

CREATE INDEX ix_safety_security_outbox_pending_next_attempt
    ON safety_security.outbox_messages (next_attempt_at)
    WHERE status = 'PENDING';
