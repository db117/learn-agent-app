# 06: Restart, revisit, and full desktop acceptance

**What to build:** As a learner, I can close and reopen the desktop app at any point in the progressive learning loop, revisit completed LearnUnits safely, and complete the full Chapter-to-synthesis flow through the React/TypeScript client.

**Blocked by:** 05: Unresolved skip and Chapter synthesis

**Status:** completed

- [x] App restart restores the Journey's Chapter, current LearnUnit, phase, skipped-phase facts, review debt, open Attempt, historical Attempts, and synthesis state from SQLite.
- [x] Resuming an interrupted open Attempt restores saved answers without replacing its Assessment or question set.
- [x] A completed LearnUnit can be revisited read-only without changing historical completion, path order, pass reason, or prior Attempts.
- [x] An explicit practice revisit may create additional feedback or Attempts, but a later practice failure never downgrades the historical completed path state.
- [x] A deterministic end-to-end desktop acceptance flow demonstrates Chapter outline, lazy LearnUnit generation, phase progression, guided practice, independent check, failed remediation/retry, recorded skip, review debt, and Chapter synthesis.
- [x] The flow works with deterministic CI fakes and keeps framework-specific AgentScope events/types out of HTTP and React boundaries.
- [x] TypeScript typecheck, lint, and production build pass alongside the backend test suite and the existing desktop checks.
- [x] Acceptance evidence confirms no old-schema fallback, no hidden completion from skips, no duplicate question history, and no WebFlux event-loop use for blocking persistence.
