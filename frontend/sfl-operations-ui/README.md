# SFL Operations UI

The React/Vite operations dashboard for CLET’s Safety, Facilities & Logistics Directorate. It uses
`@rfdtech/components` v2.4.2 directly, with the library theme loaded at the application entry point.
The Fleet, Transport & Logistics screens follow the Figma reference; the remaining systems use the
same library components and visual language.

## Systems

The frontend contains screens for all 20 systems in the current programme model:

| Systems | Module |
| --- | --- |
| S152, S153 | `modules/facilities` |
| S156, S157, S158, S159 | `modules/booking` |
| S160 | `modules/visitor` |
| S160a, S161, S162, S162a | `modules/security` |
| S163 | `modules/incident` |
| S165 | `modules/riskassessment` |
| S166 | `modules/fleet` |
| S168 | `modules/fuel` |
| S169 | `modules/emergency` |
| S171 | `modules/dispatch` and `modules/me` |
| S173, S174 | `modules/facilities` |
| S176 | `modules/ifimp` |

Navigation is entitlement-aware. A role only sees the programmes and systems it is allowed to use;
the service remains the enforcement point for every request.

## Development

```bash
npm install
npm run dev
```

For a production bundle and local verification:

```bash
npm run build
npm test -- --run
npx tsc -p tsconfig.app.json --noEmit
```

The embedded deployment uses `/ui/` as its base path. Copy `.env.example` to `.env` to configure
service URLs and the local development actor. The development actor switcher is available from the
account menu in development builds.

## Structure

`src/shared` contains API, layout, navigation, validation, notifications and domain helpers that
are genuinely shared. Each system module owns its pages, dialogs and service API. UI controls and
layout primitives come from `@rfdtech/components`; module code composes those primitives directly.
