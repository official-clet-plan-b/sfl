# S165 Risk Assessment Library - UI gap report

The dashboard module `frontend/sfl-operations-ui/src/modules/riskassessment`, against the contract read
off the running service (playbook §4.1). Three contract gaps were found by driving the live API before the
screens were built, and fixed in the service rather than worked around in the client:

| Found | Fixed by |
|---|---|
| A version's risk level, score and per-hazard bands were derived methods, never serialised - the client would have re-derived the risk matrix | `VersionView` publishes them |
| `LinkCheck.linkable()` likewise | `linkable` is a component |
| Blank review findings were refused with Bean Validation's generic message, not "Flag Dismissed Without Review" | Annotations removed; the domain refuses |

## Open

1. **S152 location is typed, not picked.** The create dialog takes a location code as text. A picker
   would read S152 rooms from the facilities service - a cross-service read this module does not make.
2. **The detail screen's history is assembled from records**, not read from the hash-chained audit log,
   which S165 does not expose over HTTP. The screen says so.
3. **The S165 sidebar section appears on the SSEMP origin (8092) and the portal**, not on facilities
   (8091), even for the two IFIMP roles that read it - the shell scopes sections by serving platform.
   An IFIMP user links an assessment from 8092 or the portal.
4. **Three lint errors exist on `main`** in `shared/components` (`PlaceField.tsx`, `SearchInput.tsx`,
   `Modal.test.tsx`), unrelated to S165. `npm run lint` fails on them; the S165 and incident files lint
   clean.
