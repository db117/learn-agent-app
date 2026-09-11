# 04: Failed remediation, retry, and review debt

**What to build:** As a learner, after an independent-check failure I receive one targeted remediation opportunity, can retry the same fixed Assessment, and see review debt clear only after the LearnUnit independently passes.

**Blocked by:** 03: Independent check and deterministic LearnUnit completion

**Status:** completed

- [x] A failed independent check keeps the path item current, preserves every historical Attempt, and sets `needs_review` with deterministic failure evidence.
- [x] Retrying reuses the same LearnUnit Assessment and question set while creating a new Attempt; previous answers, scores, and feedback remain readable.
- [x] A successful retry clears the related review debt and completes the LearnUnit through the same deterministic pass policy.
- [x] The TutorContext supplied to TutorAgent includes the current phase, failed ability, and remediation need, while TutorAgent cannot mutate score, review debt, or path state.
- [x] When the path would otherwise open a new LearnUnit, the Learning Engine may insert at most one targeted review task in that session; a review task is linked to an existing path item and is not a new path node or formal passing Assessment.
- [x] If several debts exist, the earliest unresolved debt by path order is selected and the others remain flagged.
- [x] The React/TypeScript result and dashboard views explain the failure, open remediation/retry, show review debt, and do not present a failed unit as passed.
- [x] Tests cover same-question-set retries, historical Attempt visibility, debt clearing, one-review-task limits, deterministic debt selection, TutorContext boundaries, and failure redaction.
