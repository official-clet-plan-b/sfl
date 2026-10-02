# Runbook - S165 Risk Assessment Library

**Scope.** Risk assessment authoring and versioning, review sign-off and lapse, coverage analytics, and
incident-raised review flags. Lives inside `sfl-safety-security-service` (8092), package
`gh.edu.clet.sfl.safetysecurity.riskassessment`, schema `safety_security`, tables prefixed `risk_`
(migration `V19__risk_assessment_library.sql`). For whether the service is up at all, see
[`incident-response.md`](incident-response.md) first.

## Health checks

```
GET /actuator/health
GET /api/v1/risk-assessments/dashboard?siteCode=<SITE>
GET /api/v1/risk-assessments/configuration/review-intervals
```

A 200 from the dashboard means the module, its tables and its authorisation wiring work for the caller.
An empty review-interval list means V19's seed is missing, and every publish and sign-off will fail with
"No review interval is configured".

```sql
-- Where the site's assessments stand (the dashboard's figures, from the index columns).
SELECT reference, current_version, draft_version, current_risk_level, current_review_due_at,
       current_signed_off_by
  FROM safety_security.risk_assessments WHERE site_code = '<SITE>' ORDER BY current_review_due_at;

-- Lapsed right now, whether or not the sweep has announced it yet.
SELECT a.reference, v.version_number, v.review_due_at, v.review_lapsed_at
  FROM safety_security.risk_assessment_versions v JOIN safety_security.risk_assessments a ON a.id = v.assessment_id
 WHERE v.status = 'PUBLISHED' AND v.review_due_at <= now() ORDER BY v.review_due_at;

-- Open and deferred review flags, oldest first.
SELECT assessment_reference, source_reference, status, raised_at, deferred_until
  FROM safety_security.risk_assessment_review_flags
 WHERE site_code = '<SITE>' AND status <> 'CLEARED' ORDER BY raised_at;
```

## The sweep

`RiskAssessmentScheduler`, hourly (`sfl.risk-assessment.scheduling.*`). It sends each review reminder once
and records each lapse once, and reopens deferred flags whose date has passed. **A lapse takes effect at
the review date whether or not the sweep runs** - the currency rule reads the date - so a stopped sweep
delays the announcement, never the refusal. Log line: `S165 sweep: n lapsed, n reminded, n deferred flags
reopened`.

## Symptoms

| Symptom | Cause and action |
|---|---|
| S173 still refuses a higher-risk event though the assessment is current | The S165 events have not reached facilities. Check the SSEMP outbox for `sfl.ssemp.risk-assessment-%` rows not `PUBLISHED`; see [`dead-letter-recovery.md`](dead-letter-recovery.md). Also check the assessment is HIGH/CRITICAL *and* signed off by someone other than its author. |
| "A draft revision of this assessment is already open" | Someone opened a revision. There is no discard: publish it, or edit it back to the current content and publish. |
| Coverage shows no activity types | Coverage is fed by S176 events over the broker and by incidents. With the `local` transport nothing arrives from S176. Check `SFL_EMERGENCY_EVENT_TRANSPORT` / `SFL_SAFETY_SECURITY_EVENT_TRANSPORT` is `rabbitmq` and the `sfl.ssemp.inbound` queue exists. |
| An incident report fails with "The linked risk assessment was not found at this site" | The reporter picked an assessment from another site, or a stale id. Nothing was saved; report again without the link or with the right one. |
| Many SSEMP outbox rows dead-lettered just after this release | Expected if facilities had never bound its queue: see ADR 0010 §3. Replay after facilities has started against the broker. |

## Configuration

| Setting | Default |
|---|---|
| `sfl.safety-security.messaging.transport` | follows `SFL_EMERGENCY_EVENT_TRANSPORT`, else `local` |
| `sfl.safety-security.messaging.drainer-enabled` | `true` |
| `sfl.risk-assessment.scheduling.enabled` / `fixed-delay` | `true` / `PT1H` |
| Review intervals | runtime, `PUT /api/v1/risk-assessments/configuration/review-intervals` |

## Restore

S165's tables are in `safety_security`; back up and restore with that schema
([`backup-and-restore.md`](backup-and-restore.md)). After a restore, assessments whose review date passed
while down are lapsed immediately; the next sweep announces them.
