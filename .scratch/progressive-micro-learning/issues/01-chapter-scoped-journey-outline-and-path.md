# 01: Chapter-scoped Journey outline and path

**What to build:** As a learner, I can confirm a newly generated Journey outline and see my own ordered Chapters and LearnUnits in the learning path, with the deterministic diagnostic result selecting the starting Chapter and its first LearnUnit.

**Blocked by:** None (can start immediately)

**Status:** completed

- [x] A Journey outline contains one requested learning language, ordered Chapters, Chapter prerequisites, ordered LearnUnit outlines, and LearnUnit prerequisites; it contains no large detailed lessons or formal independent-check questions.
- [x] Confirming the outline persists Chapter ownership and Journey-specific LearnUnit/path records atomically; two Journeys for the same language do not share course content or progress.
- [x] Generated Chapter and LearnUnit order/prerequisite validation rejects missing ownership, duplicate identities, invalid references, cycles, and invalid outline content without leaving partial learning data.
- [x] Deterministic diagnostic evidence maps to a starting Chapter and starts its first LearnUnit without allowing an arbitrary model-selected path jump.
- [x] Journey detail and path responses expose ordered Chapters, their LearnUnits, coarse progress, and the server-selected current item through framework-neutral DTOs.
- [x] The React/TypeScript Journey view renders an expandable Chapter navigation with LearnUnit outline status and handles generation/validation failures as visible errors.
- [x] Integration and service tests prove Journey isolation, outline persistence, diagnostic starting position, invalid-output rejection, and the rendered TypeScript contract.
