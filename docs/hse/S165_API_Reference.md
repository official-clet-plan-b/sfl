# S165 Risk Assessment Library - API reference

Served by `sfl-safety-security-service` on 8092 under `/api/v1/risk-assessments`. Every response is the
`{data, error}` envelope. Locally the actor comes from the `X-SFL-*` headers; in production from the JWT.
The live contract is `/v3/api-docs` (tag "S165 Risk Assessment Library") - this page explains it.

`expectedVersion` on every change is the `recordVersion` / `metadata.version` of the record being
changed: the draft's for edit and publish, the current version's for sign-off, the flag's for defer and
complete. A stale value is `409 RISK_ASSESSMENT_RECORD_VERSION_CONFLICT`.

## Register and authoring - SRS-SFL-S165-01

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/?siteCode=` | READ | Paged. Filters `activityType`, `riskLevel`, `standing` (`DRAFT_ONLY`, `PUBLISHED`, `LAPSED`, `AWAITING_INDEPENDENT_SIGN_OFF`), `q`. Sort `currentReviewDueAt` (default), `reference`, `title`, `lastModifiedAt` (`,desc`). |
| POST | `/` | AUTHOR | Creates the assessment and draft v1. Honours `Idempotency-Key`: same key and body returns the original; same key, different body is `409 RISK_ASSESSMENT_IDEMPOTENCY_KEY_CONFLICT`. Scope needs `activityType` and/or `locationCode` (`400 RISK_ASSESSMENT_SCOPE_REQUIRED`). With `templateId` and no hazards, the template's are copied. |
| GET | `/{id}` | READ | `AssessmentDetail`: summary, every version (newest first) as `VersionView`, sign-offs, review flags. |
| GET | `/{id}/versions/{n}` | READ | One version, superseded or not. |
| PUT | `/{id}/draft` | AUTHOR | Replaces the open draft's title, summary and hazards. |
| POST | `/{id}/revisions` | AUTHOR | Opens draft vN+1 copied from the current version. `409` while a draft is open. |
| POST | `/{id}/publish` | PUBLISH | `409 RISK_ASSESSMENT_HAZARD_WITHOUT_CONTROL` with `data.hazardsWithoutControl` naming each; `409 RISK_ASSESSMENT_NO_HAZARDS`. Supersedes the prior version in the same transaction. |

`VersionView` carries what the service computes - `riskLevel`, `residualScore`, every hazard's
`inherentScore`/`inherentLevel`/`residualScore`/`residualLevel`, and `current`/`currencyReason` - so a
client never re-derives the risk matrix.

## Review cycle and sign-off - SRS-SFL-S165-02

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | `/{id}/sign-off` | SIGN_OFF | Renews the review date from the interval for the version's level. HIGH/CRITICAL by the author is `409 RISK_ASSESSMENT_INDEPENDENT_REVIEW_REQUIRED`. Signing off a lapsed version makes it current. |
| GET | `/{id}/link-check?siteCode=` | READ | The shared currency verdict: `linkable`, `verdict.reason`. Another site's assessment is `found: false`. The in-process equivalent for S164 is `RiskAssessmentDirectory`. |
| GET | `/configuration/review-intervals` | READ | Interval and reminder lead per level. |
| PUT | `/configuration/review-intervals` | CONFIGURE | Refused unless higher levels are reviewed at least as often as lower ones. |

## Analytics - SRS-SFL-S165-03

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/dashboard?siteCode=` | READ | Standing counts, current by level, due soon, open drafts, open/deferred flags, coverage gaps, top hazards. |
| GET | `/analytics/hazards?siteCode=` | ANALYTICS_READ | Hazard-type frequency across current assessments. |
| GET | `/analytics/coverage?siteCode=` | ANALYTICS_READ | Observed activity types (S176 work types, S163 incident activities) against current assessments. |
| GET | `/activity-types?siteCode=` | READ | Known activity types, for pickers. |

## Review flags - SRS-SFL-S165-04

Raised only by S163 - `POST /api/v1/incidents` with `riskAssessmentId`/`activityType`, or
`PATCH /api/v1/incidents/{id}/risk-context` (triage or investigation authority). A link naming no
assessment at the incident's site refuses the incident: `400 INCIDENT_VALIDATION_FAILED`, "The linked
risk assessment was not found at this site."

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/review-flags?siteCode=&status=` | READ | Oldest first. |
| GET | `/review-flags/{flagId}` | READ | |
| POST | `/review-flags/{flagId}/defer` | REVIEW_FLAG_MANAGE | `{reason, until (date), expectedVersion}`. Missing or past: `400 RISK_ASSESSMENT_DEFERRAL_INVALID`. Reopens when the date passes. |
| POST | `/review-flags/{flagId}/complete` | REVIEW_FLAG_MANAGE | `{findings, expectedVersion}`. Blank findings: `400 RISK_ASSESSMENT_REVIEW_FINDINGS_REQUIRED`, "Flag Dismissed Without Review". There is no dismiss endpoint. |

## Templates

| Method | Path | Permission |
|---|---|---|
| GET | `/templates?activeOnly=` | READ |
| GET | `/templates/{id}` | READ |
| POST | `/templates` | CONFIGURE |
| PUT | `/templates/{id}` | CONFIGURE - revise, or `active: false` to retire |
