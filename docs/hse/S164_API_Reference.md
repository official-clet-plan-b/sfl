# S164 Permit-to-Work / Hot-Work Authorisation: API reference

Served by `sfl-safety-security-service`, base `/api/v1`. Module `gh.edu.clet.sfl.safetysecurity.permit`, migration `V23__permit_to_work.sql`. Every response is the shared `ApiResponse` envelope. Identity is the OIDC principal in production, or the `X-SFL-User`, `X-SFL-Roles` and `X-SFL-Sites` headers when security is off (dev profile only).

A refusal the SRS names is `422` with its code and the reason in `data`. Self-approval and a non-independent verifier are `403`; an unknown id `404`; a concurrent change `409`. Every transition that takes `version` answers `409 PERMIT_RECORD_VERSION_CONFLICT` if the permit changed since the caller read it.

## Permit types (`/permit-types`) - SRS S164-01
| Call | Needs | Does |
|---|---|---|
| `GET /permit-types?activeOnly=` | `PERMIT_READ` | List types |
| `POST /permit-types` | `PERMIT_CONFIGURE` | Add a type: code, name, risk level, S165 activity type, risk assessment required, two-stage, isolations required, max validity hours, competences checked |
| `PUT /permit-types/{id}` | `PERMIT_CONFIGURE` | Change one. A type of high or critical risk cannot be configured without two-stage approval (`PERMIT_TYPE_INVALID`) |

Seeded: `HOT_WORK` (high, two-stage, isolations, 8 h), `WORKING_AT_HEIGHT` (high, two-stage, 12 h), `CONFINED_SPACE` (critical, two-stage, isolations, 8 h).

## Request and submit - S164-01
| Call | Needs | Does |
|---|---|---|
| `POST /permits` | `PERMIT_REQUEST` | Start a draft: site, type, title, work, S152 location, optional S160a zone, window, S165 assessment id, contractor, supervisor, originating S153/S176 work, workers, isolations |
| `PUT /permits/{id}` | requester | Revise a draft |
| `POST /permits/{id}/workers`, `DELETE .../workers/{workerId}` | requester | Draft only |
| `POST /permits/{id}/isolations`, `DELETE .../isolations/{isolationId}` | requester | Draft only |
| `POST /permits/{id}/submit` | requester | Check S165, then route for verification. Refused `PERMIT_NO_RISK_ASSESSMENT`, `PERMIT_RISK_ASSESSMENT_NOT_CURRENT`, `PERMIT_RISK_ASSESSMENT_MISMATCH`, `PERMIT_WORKERS_REQUIRED`, `PERMIT_ISOLATIONS_REQUIRED`, `PERMIT_WINDOW_INVALID`. The refusal is recorded in the permit's history and the audit trail even though the submission is not |
| `POST /permits/{id}/cancel` | requester or `PERMIT_APPROVE` | Before issue only |

## Verification and approval - S164-02
| Call | Needs | Does |
|---|---|---|
| `POST /permits/{id}/isolations/{isolationId}/verify` | `PERMIT_VERIFY_ISOLATION`, not the requester | Verify one isolation |
| `POST /permits/{id}/verification/complete` | same | The explicit verification step approval needs |
| `POST /permits/{id}/workers/{workerId}/competency` | same | Record a competence check (competent or not, evidence, valid until) |
| `POST /permits/{id}/approve` | `PERMIT_APPROVE` for stage one, `PERMIT_SAFETY_SIGN_OFF` for stage two | Approve the stage the permit is waiting on, with conditions. Active only after the last stage. Refused `PERMIT_SELF_APPROVAL`, `PERMIT_ISOLATION_NOT_VERIFIED`, `PERMIT_COMPETENCY_EXCEPTION`, `PERMIT_STAGE_NOT_INDEPENDENT`, `PERMIT_STAGE_OUT_OF_ORDER`, `PERMIT_RISK_ASSESSMENT_NOT_CURRENT` |
| `POST /permits/{id}/reject` | the stage's grant | Reject with a reason. A rejected resumption leaves the permit suspended |

## Monitoring, suspension, extension - S164-03
| Call | Needs | Does |
|---|---|---|
| `POST /permits/{id}/suspend` | `PERMIT_SUSPEND` (authorisers, the SOC) | Suspend an active permit. The supervisor and every worker are queued for notification in the same transaction. Emits `permit-suspended` |
| `POST /permits/{id}/resume-request` | requester or `PERMIT_SUSPEND` | Opens the next approval round; the permit is active again only when every stage approves afresh. Emits `permit-issued` |
| `POST /permits/{id}/extensions` | requester | Same currency checks as a new request. `PERMIT_EXTENSION_INVALID`, `PERMIT_EXTENSION_PENDING` |
| `POST /permits/{id}/extensions/{extensionId}/decide` | the stage's grant | Approve in the same stages as an issue; validity moves only on the last. Emits `permit-extended` |
| `POST /permits/{id}/flags/{flagId}/review` | `PERMIT_SUSPEND` | Mark an incident or emergency flag reviewed. Does not suspend |
| `POST /permits/{id}/incident-links` | `PERMIT_SUSPEND` | Link an S163 incident by hand |

## Close-out - S164-04
| Call | Needs | Does |
|---|---|---|
| `POST /permits/{id}/evidence` | requester | File evidence by reference (SHA-256, retention class SAFETY_CRITICAL) |
| `POST /permits/{id}/complete` | requester | Completion statement; needs evidence (`PERMIT_EVIDENCE_REQUIRED`, `PERMIT_COMPLETION_STATEMENT_REQUIRED`) |
| `POST /permits/{id}/isolations/{isolationId}/remove` | `PERMIT_VERIFY_ISOLATION`, not the requester | Record an isolation's removal (`PERMIT_REMOVER_NOT_INDEPENDENT`) |
| `POST /permits/{id}/close` | requester or `PERMIT_APPROVE` | `PERMIT_ISOLATION_REMOVAL_NOT_RECORDED` until every isolation has a removal. Emits `permit-closed` |

## Reading, analytics, evidence - S164-05
| Call | Needs | Does |
|---|---|---|
| `GET /permits` | `PERMIT_READ` | Search by site, type, status, contractor, dates, open or overdue only, text |
| `GET /permits/{id}` | `PERMIT_READ` | The full record: workers, competence and exceptions, isolations, approvals, extensions, suspensions and who was told, evidence, flags, escalations, history, `blockers`, the assessment's live standing, what is held unverified |
| `GET /permits/dashboard` | `PERMIT_READ` | Open permits by type and risk level, nearing expiry, overdue close-outs, isolation status per zone, competence exceptions, open flags |
| `GET /permits/analytics` | `PERMIT_ANALYTICS_READ` | Volume by type, contractor and outcome, mean hours open, incident correlation |
| `GET /permits/export?reason=` (text/csv) | `PERMIT_EXPORT` | The register with each permit's complete lifecycle on a row. A permit still open says `INCOMPLETE` on its row and in the header. Needs a reason of at least 10 characters; watermarked; audited as `PERMIT_REGISTER_EXPORTED`; capped at 2,000 permits and says so |

## Permissions
`PERMIT_READ`, `PERMIT_REQUEST`, `PERMIT_VERIFY_ISOLATION`, `PERMIT_APPROVE`, `PERMIT_SAFETY_SIGN_OFF`, `PERMIT_SUSPEND`, `PERMIT_CONFIGURE`, `PERMIT_ANALYTICS_READ`, `PERMIT_EXPORT`.

| Role | Holds |
|---|---|
| HSE manager | all |
| Facilities director | read, request, approve, suspend, analytics |
| Security director | read, safety sign-off, suspend, analytics |
| SOC operator | read, suspend |
| Facilities engineer, maintenance supervisor | read, request, verify isolation |
| Facilities manager, construction project manager, vendor technician | read, request |
| Compliance officer | read, analytics, export (changes nothing) |
| Command | read, analytics |

Independence is per person, not per role: the requester never verifies or approves their own permit, the safety sign-off is never the issuing authority, and isolation removal is never recorded by the requester.
