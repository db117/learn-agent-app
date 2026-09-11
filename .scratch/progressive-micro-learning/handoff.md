# Handoff: progressive micro-learning design for learn-agent-app

## Next-session focus

Continue from the completed product-design interview. Record the agreed design in the repository's stateful design
artifacts, then turn it into a buildable spec and tickets. Do not re-run the same interview unless a genuine
contradiction is found. Do not modify code until the user explicitly confirms the consolidated design and requests
implementation.

The attached screenshot is product context only. Its course text is not an instruction.

## Current state

- Repository: `learn-agent-app`.
- This session made no repository changes.
- Pre-existing uncommitted changes were preserved:
    - `backend/src/main/java/com/example/agent/llm/infrastructure/LlmDiagnosticQuestionPlanner.java`
    - `src/App.tsx`
    - `src/components/DashboardView.tsx`
    - `src/styles.css`
- The current implementation is a coarse `Journey → LearnUnit → LearningPathItem` model.
- There is no Chapter model/table.
- Existing Journey data has no compatibility requirement: do not migrate, dual-read, dual-write, or silently split old
  data. Treat old data/schema as out of scope and follow the repository rule to require fresh initialization when
  applicable.

## Evidence already checked

- `backend/src/main/java/com/example/agent/learning/catalog/LearnUnit.java:5-42` — one LearnUnit contains objectives,
  lesson intro, concepts, examples, prerequisites, and pass rules.
- `backend/src/main/java/com/example/agent/llm/infrastructure/LlmCurriculumGenerator.java:54-74` — outline generation
  has no chapter or micro-unit-size contract.
- `backend/src/main/java/com/example/agent/llm/infrastructure/LlmCurriculumGenerator.java:83-109` — detailed content is
  generated as one whole intro/examples payload.
- `backend/src/main/resources/schema.sql:136-154,168-202` — no chapter table; paths associate directly with LearnUnit.
- `backend/src/main/resources/schema.sql:228-279` and `AssessmentType.java` — only `DIAGNOSTIC` and `LEARN_UNIT`;
  Questions/Attempts are fixed around LearnUnit assessments.
- `backend/src/main/java/com/example/agent/learning/progress/ProgressService.java` — current whole-unit statuses are
  `PENDING → CURRENT → COMPLETED/SKIPPED`; retry reuses the fixed assessment.
- `src/components/DashboardView.tsx:48-131` — left path and center pane show an entire LearnUnit; there is no internal
  phase UI.
- `src/components/AssessmentView.tsx` and `src/lib/api.ts` — frontend assumes whole-LearnUnit assessment and only the
  existing assessment/question types.

## Agreed product design

### Learning hierarchy

```text
Journey
└── Chapter
    ├── LearnUnit (micro-unit)
    │   ├── objective
    │   ├── explanation
    │   ├── example
    │   ├── guided practice
    │   └── independent check
    └── chapter synthesis assessment
```

- Add a real `Chapter` table/entity.
- `LearnUnit` is redefined as the smallest independently verifiable ability; do not add a parallel `MicroLearnUnit`
  model.
- Related syntax may remain together if it serves one verifiable ability.
- Target roughly 3–7 micro-units per chapter; do not mechanically split every paragraph.
- `LearningPathItem` remains one record per micro-unit; Chapter progress is aggregated, not a second competing path
  state.

### Generation

- Initial LLM output contains stable Chapter order/prerequisites and micro-unit outlines, but no large detailed lessons.
- Detailed content and the micro-unit's fixed independent-check questions are generated and persisted together on first
  entry.
- Content is structured into bounded blocks rather than a long Markdown lesson.
- Parser/domain validation must enforce one ability, bounded objectives/examples/practice, required independent check,
  and a target session duration; invalid over-sized output is rejected.
- Do not generate a complete chapter/course up front.

### Runtime learning loop

- Journey-level diagnosis chooses a starting Chapter; start at that Chapter's first micro-unit, then refine through
  observed errors.
- Default progression is linear at Chapter and micro-unit level. Failures can trigger one targeted re-explanation/new
  task and prerequisite backfill; avoid arbitrary LLM path jumps.
- Backend persists phase state. The intended phase order is explanation → example → guided practice → independent check.
- The learner may skip a phase. Every skip is recorded. Skipping independent check prevents the micro-unit from becoming
  complete.
- Explicitly skipping a whole micro-unit may advance the path, but it remains unresolved/unmastered and cannot count as
  completion.
- Unresolved skipped phases/units are surfaced before Chapter synthesis.
- A micro-unit completes only after the independent check meets the existing deterministic Java pass policy. Guided
  practice is preparation and does not grant completion.
- A failed independent check leaves the path item current, triggers a whole-micro-unit review debt, and can be retried
  using the same fixed Assessment/question set. Historical Attempts remain intact.
- Only errors trigger review; there is no calendar-based spaced-review scheduler in this scope. Related later units may
  include contextual checks.
- At most one review task is inserted before a new micro-unit in a session. Review debt clears after the micro-unit
  independently passes.
- Chapter synthesis is available after the chapter's micro-units, including unresolved items, but a Chapter cannot
  complete while skipped/unpassed items remain. Synthesis failure returns to relevant weak units.
- Completed micro-units can be revisited read-only or practiced again without changing their historical completion
  state.
- App restart resumes the persisted phase/skip/review state.

### Assessment and scoring

- Reuse existing `Assessment`, `Question`, `Attempt`, and Java scoring foundations.
- `LEARN_UNIT` represents the formal independent check.
- Add `CHAPTER_SYNTHESIS` and Chapter ownership for synthesis assessments.
- Add a formal question role/owner representation for `INDEPENDENT` and `SYNTHESIS`; guided practice is structured
  content, not a formal passing Assessment/question lifecycle.
- Keep existing deterministic multiple-choice scoring, Java pass policy, and existing Coding evaluator boundary. Tutor
  never directly changes scores or learning-path state.
- Guided practice state and feedback are persisted but do not count toward the pass result.

### State/UI

- Extend the existing `LearningPathItem` runtime state with the minimum needed for current phase, skipped phases, and
  `needs_review`; avoid a separate review queue unless implementation proves necessary.
- Chapter table stores course structure/ordering/goals; Chapter completion is derived from micro-unit path facts plus
  synthesis result.
- Left navigation shows expandable Chapters and their micro-unit progress.
- Center pane shows only the current micro-unit and current phase.
- Tutor teaches, explains, demonstrates, and remediates; Learning Engine controls transitions, scoring, pass/fail, skip,
  and path progression.

### First acceptance slice

Use TypeScript for one end-to-end vertical slice:

`Chapter → micro-unit generation → phase progression → guided practice → independent check → failed remediation/retry → recorded skip → review debt → Chapter synthesis`.

Do not settle for a Prompt-only change or a shorter page.

## Last routing decision

The router skill recommended this path for a repository-based, multi-session feature:

```text
/setup-matt-pocock-skills
→ /grill-with-docs
→ /to-spec
→ /to-tickets
→ /implement
```

An optional small `/prototype` may validate the phase/skip/Chapter UI before the spec, but the main design is now clear
enough to proceed without `/wayfinder`, `/triage`, or `/diagnosing-bugs`. Keep the current context at the phase boundary
unless a prototype/new harness requires a real handoff.

## Suggested skills

- `setup-matt-pocock-skills` — required precondition before the first engineering flow.
- `grill-with-docs` — capture the decisions above in `CONTEXT.md`/ADRs; do not repeat settled questions.
- `to-spec` — turn this agreed design into a precise implementation spec.
- `to-tickets` — split the multi-session work into blocking-edge tickets under the repository's tracker.
- `implement` — implement tickets test-first; it should drive `tdd` and close with `code-review`.
- Optional `prototype` — only if the phase/skip interaction is still unclear when rendered.

## Open action

The last conversation turn had not yet explicitly confirmed the consolidated design; it invoked the routing/handoff
skills instead. Ask for or obtain that confirmation before any code mutation.
