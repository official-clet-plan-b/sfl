# S165 Risk Assessment Library - event contracts

Published by `sfl-safety-security-service` through the `safety_security` outbox and
`SafetySecurityOutboxDrainer`: exchange `sfl.events`, routing key without the `sfl.` prefix
(`ssemp.risk-assessment-published.v1`), aggregate `RiskAssessment`, aggregate id = `assessmentId`,
`siteCode` header = the assessment's site. Payloads carry ids, references and classifications only -
never hazard text or review findings, which stay in S165.

## The four reserved events - consumed by S173 today

Reserved by S173 before S165 existed; names and payloads are fixed by
[`../facilities/S173_Event_Contracts.md`](../facilities/S173_Event_Contracts.md) and reused verbatim.
`version` is a JSON number; instants are ISO-8601 text. Proven from both sides:
`RiskAssessmentMandatoryScenariosEndToEndTest` (what SSEMP writes) and
`RiskAssessmentEventsContractTest` in facilities (what S173 reads, and that it reaches the same verdict).

| Event | When | Payload |
|---|---|---|
| `sfl.ssemp.risk-assessment-published.v1` | A draft is published as the current version | `assessmentId`, `version`, `siteCode`, `riskLevel`, `reviewDueAt`, `authorId`, `signedOffBy` (null at publish), plus `reference`, `activityType`, `locationCode` |
| `sfl.ssemp.risk-assessment-superseded.v1` | The previous current version is retired - **sent before** the `published` of its successor | `assessmentId`, `version` (the superseded one) |
| `sfl.ssemp.risk-assessment-review-lapsed.v1` | The sweep records a review date passing without sign-off (once per version) | `assessmentId`, `version`, `reviewDueAt` (the date that passed) |
| `sfl.ssemp.risk-assessment-signed-off.v1` | A review sign-off renews the review date | `assessmentId`, `version`, `signedOffBy`, `reviewDueAt` (renewed) |

## New in S165

| Event | When | Payload |
|---|---|---|
| `sfl.ssemp.risk-assessment-review-due.v1` | The sweep sends the reminder, once per review date, `reminderLeadDays` ahead | `assessmentId`, `version`, `reference`, `riskLevel`, `reviewDueAt` |
| `sfl.ssemp.risk-assessment-review-flagged.v1` | An S163 incident flags the assessment for out-of-cycle review | `assessmentId`, `version` (in force at the incident), `flagId`, `trigger` (`INCIDENT`), `sourceId` (incident id), `sourceReference` |

Nothing consumes the two new events yet. The intended consumer of `review-due` is a notification path to
the HSE owner (S174 or the enterprise Comms system); until one exists, the reminder is visible on the S165
dashboard ("Due for review soon") and recorded here.

## Consumed

| Event | From | Reaction |
|---|---|---|
| `sfl.ifimp.project-registered.v1`, `sfl.ifimp.project-started.v1` | S176 | Each `workTypes` entry is recorded as an activity type observed at the site (`ConstructionActivityTypesHandler`), feeding S165-03 coverage. |

S163 incidents reach S165 in-process (same deployable) through `IncidentRiskObserver`, not over the broker.
