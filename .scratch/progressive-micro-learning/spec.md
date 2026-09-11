# Progressive Micro-Learning

Status: ready-for-agent

## Problem Statement

当前 Learning Journey 直接连接一组完整的 LearnUnit。一个 LearnUnit 同时承载大段教学正文、示例、评估和整个学习状态，导致学习者无法在短会话中恢复到具体阶段，也无法区分“看过内容”“练习过”和“已经独立掌握”。Chapter 级目标、未解决的跳过项和失败后的 review debt 也没有稳定的领域状态。

## Solution

把每个 Learning Journey 组织为 `Journey → Chapter → LearnUnit`。Chapter 聚合相关能力，LearnUnit 表示一个可独立验证的最小能力，并按 explanation → example → guided practice → independent check 的阶段循环学习。

初次生成只保存 Journey 专属的 Chapter 顺序、前置关系和 LearnUnit outline。学习者首次进入某个 LearnUnit 时，系统才生成并持久化结构化教学内容以及固定的 independent check 题目。Java Learning Engine 持有阶段、跳过、评分、通过、重试、review debt、Chapter synthesis 和路径状态；TutorAgent 只负责解释、示范、辅导和针对错误进行 remediation。

Chapter 的 LearnUnit 可以被全部尝试后进入 Chapter synthesis，即使其中存在跳过或未通过项；但只有所有 LearnUnit 都通过且 synthesis 通过时，Chapter 才能完成。失败会保留历史 Attempt、标记 review debt，并按确定性规则返回相关的薄弱 LearnUnit。

## User Stories

1. As a learner, I want each Learning Journey to have its own Chapters, so that two Journeys with the same target language can follow different courses.
2. As a learner, I want each Chapter to show an ordered set of LearnUnits, so that I understand the intended progression toward the Chapter goal.
3. As a learner, I want a LearnUnit to teach one independently verifiable ability, so that a short session has a clear outcome.
4. As a learner, I want to see a concise objective and target session duration before starting a LearnUnit, so that I can decide whether to begin it now.
5. As a learner, I want the initial Journey draft to show Chapter and LearnUnit outlines without generating every lesson, so that course creation remains fast and bounded.
6. As a learner, I want the generated outline to preserve Chapter order and prerequisites, so that later learning does not depend on an unstable model reorder.
7. As a learner, I want the system to reject an outline that contains empty, duplicated, cyclic, or oversized learning content, so that invalid model output cannot become my course.
8. As a learner, I want detailed content to be generated only when I first enter a LearnUnit, so that unused lessons do not consume model work or storage.
9. As a learner, I want the generated explanation, examples, guided practice, and independent check to be saved together, so that restarting the app does not create a different lesson or question set.
10. As a learner, I want teaching content to be presented as bounded blocks, so that I do not receive one unmanageable Markdown lesson.
11. As a learner, I want to move through explanation, example, guided practice, and independent check in order, so that the lesson has a predictable rhythm.
12. As a learner, I want to skip an individual phase, so that I can move past material I already understand while retaining an accurate history.
13. As a learner, I want skipping the independent check to leave the LearnUnit unresolved, so that skipping cannot be mistaken for mastery.
14. As a learner, I want to skip an entire LearnUnit explicitly, so that I can continue the Journey while seeing that the ability remains unmastered.
15. As a learner, I want every skipped phase and skipped LearnUnit to be visible in the Journey, so that progress does not hide what I have not completed.
16. As a learner, I want guided practice to provide persisted feedback without changing my pass result, so that practice prepares me without replacing formal verification.
17. As a learner, I want the independent check to use a fixed set of questions, so that retry results remain comparable and historical Attempts remain meaningful.
18. As a learner, I want a passing independent check to complete only that LearnUnit, so that completion reflects deterministic evidence for the ability.
19. As a learner, I want a failed independent check to keep the LearnUnit current and create review debt, so that the system gives me a concrete remediation path instead of silently advancing.
20. As a learner, I want a failed LearnUnit to be retryable with the same Assessment and questions, so that a retry tests learning rather than question replacement.
21. As a learner, I want at most one targeted review task before a new LearnUnit in a session, so that remediation is useful without turning the path into an unbounded review queue.
22. As a learner, I want review debt to clear only after the related LearnUnit independently passes, so that reading a new explanation alone does not claim mastery.
23. As a learner, I want the next LearnUnit to be selected linearly unless deterministic prerequisites require otherwise, so that the model cannot make arbitrary path jumps.
24. As a learner, I want an initial diagnostic to choose a starting Chapter and its first LearnUnit, so that I begin near my current ability without losing the Chapter structure.
25. As a learner, I want a Chapter synthesis to become available after its LearnUnits have been attempted, including unresolved items, so that I can see the Chapter-level result without the system hiding weaknesses.
26. As a learner, I want a Chapter to remain incomplete when any LearnUnit is skipped or unpassed, even if synthesis passes, so that the Chapter result reflects all required abilities.
27. As a learner, I want synthesis failure to return me to the relevant weak LearnUnits, so that remediation is tied to evidence rather than a random model decision.
28. As a learner, I want to revisit a completed LearnUnit in read-only mode, so that I can review its content without changing its historical completion state.
29. As a learner, I want to practice a completed LearnUnit again, so that additional practice does not downgrade an earlier completion or erase its historical Attempts.
30. As a learner, I want the app to restore my current Chapter, LearnUnit, phase, skip records, review debt, open Attempt, and historical results after restart, so that an interrupted short session is recoverable.
31. As a learner, I want the left navigation to expand Chapters and show each LearnUnit's progress, so that I can understand both local and overall Journey status.
32. As a learner, I want the center pane to show only the current LearnUnit and phase, so that the interface matches the bounded learning session.
33. As a learner, I want explicit controls for advancing, skipping, retrying, and entering synthesis, so that every progress-changing action is understandable.
34. As a learner, I want model-generation errors to be shown as actionable failures, so that the app never substitutes an unrelated old course or silently creates incomplete content.
35. As a learner, I want assessment questions to expose no correct answer in the UI response, so that the client cannot bypass the independent check.

## Implementation Decisions

- The durable hierarchy is `Learning Journey → Chapter → LearnUnit → LearningPathItem`. A Chapter belongs to exactly one Journey; a LearnUnit belongs to exactly one Journey and one Chapter. There is no separate `MicroLearnUnit` model; “micro-unit” is a size description for `LearnUnit`.
- A Chapter stores its Journey ownership, stable order, goal, description, and prerequisite Chapter references. A Chapter contains roughly 3–7 LearnUnits as a generation target, not a reason to mechanically split prose.
- A LearnUnit stores stable outline identity and the smallest independently verifiable ability. Its content is structured into bounded explanation, example, guided-practice, and independent-check blocks, plus objectives, prerequisite references, assessment policy, and target session duration.
- `LearningPathItem` remains the only Journey-specific progress node for a LearnUnit. Its existing status values remain the authoritative coarse state (`PENDING`, `CURRENT`, `COMPLETED`, `SKIPPED`); the minimum additional state is current phase, skipped phases, `needs_review`, and persisted guided-practice feedback. Chapter progress is an aggregation and does not introduce a competing path state.
- The phase order is fixed: `EXPLANATION → EXAMPLE → GUIDED_PRACTICE → INDEPENDENT_CHECK`. The engine accepts explicit advance and skip actions only for the current phase. A skipped phase is recorded as a workflow fact; skipping independent check prevents LearnUnit completion.
- Whole-LearnUnit skip is explicit, preserves the path node and history, does not set a pass reason or mastery score, and can advance the linear path. A pending or current LearnUnit with an open formal Attempt cannot be skipped until that Attempt is closed. A skipped or failed unit remains unresolved for Chapter completion.
- Initial outline generation returns only one requested language, stable Chapter structure, Chapter prerequisites, LearnUnit outlines, LearnUnit prerequisites, and enough objective/size metadata to render the draft. It must not return large detailed lessons or formal independent-check questions.
- First entry into a LearnUnit generates and validates detailed content and the fixed independent-check question definitions in one logical operation. The generated content must preserve the outline identity and contain exactly one ability, bounded blocks, at least one independent-check question, and a target session duration. The initial MVP bounds are 1–3 objectives, 1–3 examples, 1–3 guided-practice tasks, and a target duration of 5–15 minutes; values outside these bounds are rejected.
- Invalid LLM output fails the request and leaves no partial Chapter, LearnUnit content, Question, or Assessment state. There is no fallback to a previous catalog, a global language catalog, or a different generation route.
- The existing deterministic Java scoring and LearnUnit pass policy remain authoritative. Guided practice may save feedback and completion of a phase, but it cannot update a LearnUnit to `COMPLETED`.
- `LEARN_UNIT` continues to identify the formal independent check. `CHAPTER_SYNTHESIS` is added for the Chapter-level Assessment. A formal Question has immutable role and ownership: `INDEPENDENT` questions belong to one LearnUnit, while `SYNTHESIS` questions belong to one Chapter. Assessment-to-question membership and order are fixed when the Assessment is created.
- Question definitions remain insert-only and soft-deletable. Prompt, answer, points, rubric, role, and ownership cannot be updated after creation. Every Attempt continues to reference the fixed question set used at its creation, and historical Attempts remain readable.
- The Chapter synthesis is created lazily when the Chapter becomes eligible and its question set is fixed for retries. Synthesis questions must identify the Chapter abilities they cover so deterministic results can mark relevant weak LearnUnits for review. A synthesis pass alone cannot complete a Chapter while any LearnUnit is skipped or unpassed.
- A failed independent check keeps the current path item current, preserves the fixed Assessment, increments historical Attempt facts, and marks `needs_review`. The next eligible new LearnUnit may be preceded by one review task. If multiple debts exist, the engine chooses the earliest unresolved debt by path order; other debts remain flagged. Review tasks do not become new path nodes and do not clear debt; the related independent check must pass.
- A failed synthesis marks the relevant LearnUnits for review and returns the server-selected first weak LearnUnit. The engine does not allow the model to arbitrarily jump to an unrelated Chapter or unit.
- Completed LearnUnits support read-only revisit. An explicit practice revisit may create additional Attempts and feedback, but a later failed practice does not downgrade the historical `COMPLETED` path state; the original completion and all Attempts remain visible.
- Journey diagnosis may use LLM-selected questions, but Java validates question structure and coverage and deterministically maps diagnostic evidence to a starting Chapter and its first LearnUnit. LLM failure is an error, not a fallback to an existing question bank.
- The Learning Engine owns all phase transitions, skip records, linear progression, review debt, score/pass updates, Chapter eligibility, and Chapter completion. TutorAgent receives a framework-neutral TutorContext containing the current Chapter, LearnUnit, phase, errors, and next deterministic action, but cannot directly mutate learning facts.
- HTTP responses expose only framework-neutral DTOs. Journey detail includes Chapters, aggregated Chapter progress, ordered LearnUnits, path facts, unresolved items, and synthesis availability. Current LearnUnit responses include the current phase, structured content, allowed actions, review state, and fixed-assessment availability. Assessment responses expose public question configuration without correct answers or private model/runtime data.
- New phase, review, skip, and synthesis commands use existing application error semantics: malformed requests are client errors, commands invalid for the persisted state are conflicts, and evaluation failures are unprocessable results. Blocking JDBC and AgentState work remains isolated from the WebFlux event loop.
- The React/Tauri UI remains a thin client of these DTOs. The first implementation slice is a real TypeScript vertical slice through schema, Java Learning Engine, HTTP, and React; it is not a prompt-only update or a static mock page.
- The current fixed backend address, SQLite-only persistence, Spring Boot WebFlux application boundary, AgentScope TutorAgent boundary, and macOS arm64 JVM target remain unchanged. Native Image and other platform acceptance remain outside this feature.

## Testing Decisions

- The primary acceptance seam is one Spring Boot `WebTestClient` flow backed by the real SQLite schema and deterministic generator/evaluator fakes. It must exercise the observable end-to-end path: Chapter outline persistence, lazy LearnUnit generation, phase advance and skip, guided-practice feedback, independent-check pass/fail, fixed-question retry, review debt, whole-unit skip, and Chapter synthesis.
- The integration flow must assert both HTTP DTOs and durable facts after each action. A passing response is insufficient if a phase skip, Attempt, review debt, unresolved unit, Chapter synthesis result, or workflow transition was not persisted.
- Existing Learning Engine seams remain the unit-test seam for deterministic state rules. Add cases for phase-order enforcement, repeated/idempotent commands, skipped independent checks, open-Attempt protection, failed retry, completed-unit practice, linear advancement, Chapter aggregation, and synthesis routing.
- Existing curriculum service seams remain the validation seam. Add cases for missing Chapter ownership, duplicate or cyclic prerequisites, one-ability/content-bound violations, oversized blocks, missing independent checks, mismatched generated identity, partial-write prevention, Journey isolation, and generation failure without fallback.
- Existing Assessment service/scoring seams remain the assessment seam. Add cases for `CHAPTER_SYNTHESIS`, immutable role/owner validation, fixed question membership, soft-deleted question history, deterministic pass policy, open Attempt restoration, retry history, and the absence of correct answers in public DTOs.
- Existing WebFlux tests remain the HTTP boundary prior art. No new test framework or component-test dependency is introduced for React; `pnpm typecheck`, lint, and build verify the TypeScript contract, and the vertical-slice acceptance includes the rendered phase/Chapter interactions.
- Every non-trivial state branch gets at least one regression test at the highest practical seam. Tests assert external behavior and persisted domain facts rather than private helper structure or AgentScope internal message types.

## Out of Scope

- Migration, dual-read, dual-write, fallback, or compatibility support for the old Journey/LearnUnit schema or old database files. A legacy schema requires fresh initialization.
- A separate `MicroLearnUnit` domain model, a global course catalog shared by Journeys, or a second review-queue persistence model unless the minimal path state proves insufficient.
- Calendar-based spaced repetition, scheduled notifications, or a background review scheduler. Review debt is error-triggered only.
- Arbitrary LLM-selected path jumps, LLM-controlled scores, LLM-controlled pass/fail, or TutorAgent-owned learning state.
- MCP, RAG, Monaco implementation, automatic updates, and changes to the AgentScope runtime boundary.
- Native Image, Windows/Linux/other macOS architecture acceptance, and Native Skill resource adaptation.
- A complete course or Chapter lesson generated before the learner enters it.
- A new guided-practice Assessment/question lifecycle. Guided practice remains structured content and persisted feedback; only independent checks and Chapter synthesis are formal Assessments.

## Further Notes

- The first demoable slice must visibly run `Chapter → LearnUnit generation → phase progression → guided practice → independent check → failed remediation/retry → recorded skip → review debt → Chapter synthesis` in the React/TypeScript client against the Java/WebFlux backend.
- Deterministic CI uses fake generation, scoring, and evaluation data. macOS arm64 end-to-end acceptance may use the configured real OpenAI provider, but any LLM generation failure remains a visible failure.
- The feature should preserve the existing stable Assessment/Attempt history model while making ownership explicit. Any schema change that would make historical Attempts ambiguous is a design failure.
