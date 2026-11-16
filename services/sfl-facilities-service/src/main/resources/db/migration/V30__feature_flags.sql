-- Runtime feature flags: an administrator switches a system on or off from the dashboard, without a
-- redeploy. A row here overrides the deployment default (SFL_FEATURES_ENABLED_SYSTEMS /
-- SFL_FEATURES_DISABLED_SYSTEMS); no row means the default applies. Global, not site-scoped.
CREATE TABLE facilities.sfl_feature_flags (
    system_code VARCHAR(20)  PRIMARY KEY,
    enabled     BOOLEAN      NOT NULL,
    updated_by  VARCHAR(200) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

