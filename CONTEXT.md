# Desktop Programming Learning Agent

This context names the learning domain and the agent-runtime concepts used by the desktop learning application.

## Language

**Skill**:
An Agent framework capability exposed to an Agent, following the framework's standard Skill metadata and loading model.
_Avoid_: LearnUnit, lesson, LearningSkill

**LearnUnit**:
A Journey-scoped unit of teaching knowledge generated for the learner's target language and goal.
_Avoid_: Skill, agent capability

**Learning Journey**:
A learner's durable progression through a target language and goal, including its curriculum, current state, and assessment history.
_Avoid_: Agent session, global course

**TutorAgent**:
The single Agent responsible for teaching interaction and explanations; it does not decide deterministic progression or final assessment outcomes.
_Avoid_: multi-agent, Learning Engine
