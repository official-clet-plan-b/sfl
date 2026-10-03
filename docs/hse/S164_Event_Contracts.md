# S164 event contracts

Published through the `safety_security` transactional outbox, canonical `sfl.ssemp.{name}.v1`, one message per change, committed with the change.

## Consumed by S176 (facilities): `sfl.ssemp.permit-issued.v1`, `-suspended.v1`, `-extended.v1`, `-closed.v1`
S176's `PermitEventHandler` reads exactly these fields; S164 sends them and nothing about the people on the permit.

| Field | Meaning |
|---|---|
| `permitId` | S164's permit id |
| `permitReference` | `PTW-000123` |
| `siteCode` | also the outbox `site_scope` |
| `workType` | the permit type code, for example `HOT_WORK` |
| `validFrom`, `validTo` | the validity window, ISO instants |
| `contractorReference` | the contractor, recorded, not verified |
| `originReference` | the S153 work order or S176 project |
| `occurredAt` | ISO instant; S176 applies an event only if it is not older than the last one |

`permit-issued` is sent when the last approval stage approves, and again after each resumption (a suspended permit is made current only by a fresh issue). `permit-suspended` adds `reason`. `permit-extended` carries the new `validTo`. `permit-closed` ends it. A contract test in `PermitMandatoryScenariosEndToEndTest` asserts these fields on the real payload.

## S164's own
| Event | When | Extra fields |
|---|---|---|
| `sfl.ssemp.permit-expiring.v1` | A permit still open is within the warning window of its end (default 60 minutes) | none |
| `sfl.ssemp.permit-overdue.v1` | A permit still open is past its end | none |
| `sfl.ssemp.permit-flagged.v1` | An S163 incident or an S174 emergency flagged the permit | `flagType`, `flagReference` |

## In-process contracts (same service)
| Direction | Contract | Adapter |
|---|---|---|
| S164 -> S165 | `RiskAssessmentDirectory.checkLink` | `RiskAssessmentDirectoryAdapter` |
| S164 -> S160a | `AccessZoneDirectory.find` (new) | `AccessZoneAdapter` |
| S163 -> S164 | `IncidentRiskObserver` (existing) | `IncidentPermitFlagAdapter` |
| S174 -> S164 | `EmergencyActivationObserver` (new; real activations only, never drills) | `EmergencyPermitFlagAdapter` |

`PermitArchitectureTest` fails the build if any other S164 class names a sibling, or a sibling names S164.
