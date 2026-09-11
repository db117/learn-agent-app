# 03: Independent check and deterministic LearnUnit completion

**What to build:** As a learner, I can enter the independent check for the current LearnUnit, submit its fixed question set, and have Java deterministically complete the LearnUnit or leave it current based on the existing pass policy.

**Blocked by:** 02: Lazy LearnUnit content and phase loop

**Status:** ready-for-agent

- [ ] The formal LearnUnit assessment is represented as `LEARN_UNIT`, and each question has immutable `INDEPENDENT` role and LearnUnit ownership.
- [ ] The Assessment fixes its question membership and order on creation; starting or reopening it never replaces the question set.
- [ ] Public assessment DTOs expose usable question configuration but never correct answers or private evaluator/runtime data.
- [ ] Existing deterministic multiple-choice and Coding evaluation boundaries and pass policy determine the result; the client cannot submit or override a score.
- [ ] A passing independent check records the Attempt, marks the LearnUnit `COMPLETED`, clears its active phase work, and lets the deterministic path select the next LearnUnit.
- [ ] A failed independent check records the Attempt and result but leaves the LearnUnit current and does not silently advance the path.
- [ ] The React/TypeScript assessment flow starts, restores, answers, submits, and renders pass/fail results while preserving the current LearnUnit context.
- [ ] Integration and assessment tests cover fixed questions, immutable history, public-answer redaction, pass/fail transitions, next-item selection, and failed non-advancement.
