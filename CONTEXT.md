# Desktop Programming Learning Agent

This context names the learning domain and the agent-runtime concepts used by the desktop learning application.

## Language

**Skill**:
An Agent framework capability exposed to an Agent, following the framework's standard Skill metadata and loading model.
_Avoid_: LearnUnit, lesson

**LearnUnit**:
A Journey-scoped learning unit that contains curriculum position, teaching content, learning objectives, prerequisite
relationships, and assessment policy for the learner's target language and goal. It is the only user-facing
learning-unit concept; its Journey progress is represented by `LearningPathItem`, and there is no separate
`LearnerLearnUnit` concept. _Avoid_: Skill, lesson, agent capability, LearnerLearnUnit, learner-state record

**LearningPathItem**:
A Journey-specific ordered node for a LearnUnit whose status is the authoritative progress state: pending, current,
completed, or skipped. It is a path/progress record, not another learning-unit concept. _Avoid_: LearnerLearnUnit,
LearningSkill, course content

**TutorContext**:
A read-only runtime context derived from the LearningJourney, current LearnUnit, LearningPathItem, and assessment
history. It tells `TutorAgent` what the learner is studying, where they are in the Journey, what has been learned, and
what should happen next. It is not an AgentScope Skill or a separate learning entity. _Avoid_: Skill, LearnerLearnUnit,
final score authority

**TutorSession**:
A Journey-and-LearnUnit-scoped teaching conversation that preserves continuity for one learning unit without becoming
the source of learning progress or assessment truth. Re-entering the same LearnUnit reuses it; deleting it does not
delete learning data. _Avoid_: Learning Journey, AgentScope Skill, Learning State

**AgentState**:
Runtime state owned by the Agent framework for restoring a TutorSession's conversation, harness, and tool execution
context. It is separate from durable Learning State and never decides learning outcomes. _Avoid_: LearnUnit progress,
Assessment result, TutorContext authority

**TutorEvent**:
A project-owned, framework-neutral event projected from TutorAgent runtime activity for the desktop UI. It may show
assistant output, safe thinking summaries, Skill loading status, and tool progress without exposing raw AgentScope
events or internal prompts. _Avoid_: AgentEvent, raw reasoning, Skill-to-LearnUnit relationship

**Learning Journey**:
A learner's durable progression through a target language and goal, including its curriculum, current state, and assessment history.
_Avoid_: Agent session, global course

**TutorAgent**:
The single Agent responsible for teaching interaction and explanations; it does not decide deterministic progression or final assessment outcomes.
_Avoid_: multi-agent, Learning Engine

**Spring Boot WebFlux Application**:
The target application boundary for the desktop backend. It owns HTTP, SSE, SQLite access, the Learning Engine, and the
Tauri process boundary; it is not the Agent runtime and does not decide learning outcomes through the model. _Avoid_:
AgentScope Skill, TutorAgent, Learning Engine

**AgentScope Runtime**:
The only Agent runtime in the application. It owns `HarnessAgent`, `Skill`, `AgentState`, tool execution, and Agent
events; Spring Boot WebFlux owns HTTP, SSE, SQLite, and the Learning Engine. _Avoid_: Learning Engine authority,
HTTP DTOs, framework-specific events

**Native Gate**:
A future verification checkpoint for the Native executable path. Native is outside the current migration scope and does
not decide whether the macOS arm64 Spring Boot WebFlux JVM chain is accepted. _Avoid_: current migration blocker, JVM
chain acceptance, current Definition of Done

**Migration Cutover**:
The replacement of the former runtime is complete in the Spring Boot WebFlux + AgentScope JVM chain. This is a
replacement, not a compatibility or rollback phase. _Avoid_: Native Gate, dual-runtime fallback, old-data migration
