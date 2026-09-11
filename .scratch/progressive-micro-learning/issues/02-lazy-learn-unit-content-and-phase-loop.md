# 02: Lazy LearnUnit content and phase loop

**What to build:** As a learner, I can enter the current LearnUnit and complete a short, resumable teaching loop whose structured content and independent-check definitions are generated only on first entry.

**Blocked by:** 01: Chapter-scoped Journey outline and path

**Status:** completed

- [x] Entering a LearnUnit generates and persists bounded explanation, example, guided-practice, and independent-check content together with the fixed independent-check question definitions.
- [x] Invalid or oversized generated content, missing independent check, multiple abilities, invalid duration, or mismatched outline identity fails atomically without partial content or question records.
- [x] A LearnUnit exposes the persisted phase in the fixed order `EXPLANATION → EXAMPLE → GUIDED_PRACTICE → INDEPENDENT_CHECK` and rejects commands for a non-current phase.
- [x] Advancing or explicitly skipping a phase persists the new phase and a durable skip fact; skipping independent check never marks the LearnUnit complete.
- [x] Guided-practice interaction and feedback are persisted without creating a formal Assessment, Attempt, score, or completion result.
- [x] Reloading the Journey or current LearnUnit returns the same generated content, current phase, skipped phases, and allowed actions.
- [x] The center React/TypeScript pane shows only the current LearnUnit and phase, provides accessible advance/skip controls, and makes the unresolved independent check visible.
- [x] Tests cover lazy generation, bounded-content validation, atomic failure, phase ordering, phase skips, guided-practice persistence, and API/UI state restoration.
