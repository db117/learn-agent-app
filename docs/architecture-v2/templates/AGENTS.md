# AGENTS.md

## Architecture Contract

This repository is a clean-slate v2 implementation.

1. Do not add backward compatibility for v1.
2. Domain State and Agent State must remain separate.
3. Learning Domain is the authority for mastery, completion and PracticeEvidence.
4. AgentScope owns Runtime concerns such as session, memory, skill, plan, MCP, permission and subagents.
5. Language Pack is a product plugin; Skill is an Agent capability.
6. UI must not consume AgentScope raw events directly.
7. Arbitrary shell execution is forbidden by default.
8. Code execution must go through ExecutionEnvironment.
9. TutorAgent is the only primary user-facing agent.
10. Subagents are workers for specialization and context isolation.
11. Do not implement future roadmap stages unless explicitly required.
12. Every task must have a narrow allowed modification scope and validation.
