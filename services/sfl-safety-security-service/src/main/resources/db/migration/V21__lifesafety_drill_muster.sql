-- Phase 2 S175-02: a drill reuses S162a's muster. A drill-opened session carries the drill's id as a plain
-- value (S175 owns its own tables; no cross-module foreign key). A real fire event never joins a drill's
-- session, so the "open session for this zone" lookup now excludes them.
ALTER TABLE safety_security.lifesafety_muster_sessions ADD COLUMN drill_id UUID;

DROP INDEX safety_security.ix_lifesafety_muster_open;
CREATE INDEX ix_lifesafety_muster_open ON safety_security.lifesafety_muster_sessions (site_code, zone_code, status)
    WHERE drill_id IS NULL;
CREATE INDEX ix_lifesafety_muster_drill ON safety_security.lifesafety_muster_sessions (drill_id)
    WHERE drill_id IS NOT NULL;
