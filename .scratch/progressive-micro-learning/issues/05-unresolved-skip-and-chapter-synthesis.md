# 05: Unresolved skip and Chapter synthesis

**What to build:** As a learner, I can explicitly skip an unresolved LearnUnit, reach a Chapter synthesis after the Chapter's LearnUnits have been attempted, and receive a Chapter result that cannot claim completion while unresolved abilities remain.

**Blocked by:** 04: Failed remediation, retry, and review debt

**Status:** completed

- [x] Explicitly skipping a whole LearnUnit records the skip, preserves any existing Attempts and review debt, leaves it unresolved/unmastered, and advances only according to deterministic path rules.
- [x] The Journey and Chapter views surface skipped phases, skipped LearnUnits, failed/unpassed LearnUnits, and review debt before synthesis starts.
- [x] A Chapter synthesis is represented as `CHAPTER_SYNTHESIS`; its questions have immutable `SYNTHESIS` role and Chapter ownership, and its question set/order is fixed for retries.
- [x] Synthesis becomes available only after the Chapter's LearnUnits have been traversed/attempted, including unresolved items; creating it does not generate unrelated course content.
- [x] A synthesis pass cannot complete a Chapter while any LearnUnit is skipped or unpassed; a Chapter completes only when all required LearnUnits and synthesis pass.
- [x] A synthesis failure records its Attempt, marks the relevant weak LearnUnits for review, and returns the first weak LearnUnit by deterministic path order without an arbitrary LLM jump.
- [x] The React/TypeScript UI provides synthesis availability, question flow, unresolved-item warnings, result handling, and navigation back to the selected weak LearnUnit.
- [x] Integration and assessment tests cover whole-unit skip, unresolved aggregation, Chapter-owned questions, synthesis retries/history, completion gating, and failure routing.
