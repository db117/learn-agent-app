# Journey Progress Backup and Restore

Status: ready-for-agent

## Problem Statement

Learning data is stored in the app's local SQLite database, while practice and project files live in local workspaces.
There is no account-based or cloud synchronization. A learner who uses more than one device needs a simple way to move
all learning progress and local practice work between them.

## Solution

Provide manual file-based export and import. Export all Journeys, including their learning facts, practice history,
associated project data, and user workspace files. Add a stable portable identifier to each Journey so a transferred
Journey can be recognized on later imports.

On import, a matching Journey prompts before overwrite. After confirmation, replace that Journey's data and workspace.
Add Journeys that are not present on the target device, and preserve target-only Journeys. Keep the target device's
local SQLite IDs and filesystem paths.

Export and import app model configuration separately, including the API Key. Do not add password encryption. Do not
export Tutor runtime state; it can be rebuilt from learning facts and workspace contents.

## User Stories

1. As a learner, I want to export all my Journeys in one operation, so that I can move my learning progress to another
   device.
2. As a learner, I want the export to include active and archived Journeys, so that I do not lose older learning
   records.
3. As a learner, I want the export to include each Journey's LearningJourney, Chapters, LearnUnits, LearningPathItems,
   PracticeAttempts, PracticeEvidence, and associated Project domain records, so that the imported Journey retains its
   learning content and history.
4. As a learner, I want a Journey to retain the same portable identity after export and import, so that future transfers
   can recognize it.
5. As a learner, I want to import a Journey onto a device that has no copy of it, so that the Journey is added with its
   progress intact.
6. As a learner, I want the app to identify Journeys with matching portable identifiers before replacing them, so that I
   can see what an import will overwrite.
7. As a learner, I want to confirm an overwrite of a matching Journey, so that its progress and associated workspace are
   replaced only with my approval.
8. As a learner, I want to cancel an overwrite prompt without changing the target Journey, so that I can avoid an
   accidental replacement.
9. As a learner, I want Journeys that exist only on the target device to remain after import, so that importing another
   device's export does not remove local work.
10. As a learner, I want the export to include my LearningWorkspace files, so that I can resume unfinished practice on
    another device.
11. As a learner, I want the export to include files in workspaces associated with my Journeys and Projects, so that
    local project work can continue after transfer.
12. As a learner, I want workspace files restored under the target device's local paths, so that device-specific
    database IDs and folders do not break file access.
13. As a learner, I want rebuildable dependency and cache files excluded, so that exports remain focused on my work and
    do not carry generated directories.
14. As a learner, I want app model settings exported separately from Journey data, so that I can move configuration
    without replacing my learning records.
15. As a learner, I want the separate configuration export to include the model name, protocol, base URL, and API Key,
    so that Tutor can use the same model configuration on another device.
16. As a learner, I want importing the configuration package to replace the target's stored model configuration, so that
    the imported settings take effect together.
17. As a learner, I want Tutor sessions, memory, and plans excluded from transfer, so that the app can rebuild runtime
    context from the authoritative learning facts and workspace.
18. As a learner, I want an invalid or unsupported transfer file rejected before data changes, so that a failed import
    does not leave partial or corrupted progress.
19. As a learner, I want clear import results and errors, so that I know which Journeys were added, replaced, or
    rejected.
20. As a learner, I want the app to retain the same Journey identity when I export and import repeatedly, so that
    ordinary device transfers do not create duplicate Journeys.

## Implementation Decisions

- Keep synchronization local and user initiated. Do not add accounts, cloud persistence, background synchronization, or
  real-time updates.
- Add a portable UUID to Journey while retaining SQLite auto-increment IDs for local persistence and relationships.
  Generate the portable identifier for new Journeys and backfill existing Journeys once.
- Export a versioned package containing Journey-owned Domain State and its associated user files. Keep LearningPathItem
  and PracticeEvidence as the authoritative progress facts; recompute derived Mastery rather than treating it as an
  independent source.
- Include associated Project records and workspace files where present. Restore files using package-relative paths and
  remap them to the target device's local Journey and Project IDs.
- Exclude Agent Workspace and Agent State, including sessions, memory, plans, messages, and planning drafts. Exclude
  rebuildable dependency and cache directories.
- Match imported Journeys only by portable UUID. Do not infer identity from a title, goal description, or matching
  content.
- If an imported UUID matches a target Journey, show the affected Journey and request confirmation before replacing its
  Domain State and workspace. If it does not match, add it. Preserve target Journeys absent from the package.
- Keep the destination's local Learner profile and attach imported Journeys to that local Learner; do not introduce
  account identity or transfer a Learner profile.
- Expose Journey-data and model-configuration export/import as separate user actions. The model configuration package
  contains model name, protocol, base URL, and API Key, and importing it replaces the stored configuration. Do not add
  password encryption.
- Validate the package format and its contents before applying an import. Reject unsupported or invalid packages without
  changing stored state.
- Keep the feature behind the existing app/API boundary. Domain State remains authoritative, Agent State remains
  runtime-owned, and the UI does not consume AgentScope raw events.
- Architecture impact: add a Journey-level portable identity and a transfer capability without changing local Entity ID
  semantics or Domain/Agent State boundaries.

## Testing Decisions

- Use one end-to-end Playwright seam through the user-visible import/export flow, the real HTTP API, an isolated SQLite
  database, and an isolated data directory.
- Assert external behavior: exported and restored learning state, workspace files, configuration, confirmation prompts,
  cancellation, and preservation of target-only Journeys. Do not assert private archive helper calls or internal
  serialization structure.
- Cover a clean-target import, matching-Journey confirmation and overwrite, canceled overwrite, unmatched Journey
  addition, target-only Journey preservation, workspace restoration with local path remapping, separate configuration
  replacement including a test API Key, and rejection of an invalid package without partial changes.
- Exercise the frontend transfer controls, backend transfer API/application behavior, SQLite persistence, workspace
  handling, and model configuration through the same outer seam.
- Prior art: the existing Playwright Learn Mode flow exercises the UI with the real HTTP/SQLite path;
  RuntimeSkeletonTest exercises Quarkus HTTP and SQLite; WorkspaceManagerTest covers workspace file handling and
  generated-directory exclusions.

## Out of Scope

- Cloud synchronization, accounts, live updates, and automatic background transfer.
- Merging progress from independently created Journeys or fuzzy matching by title, goal, or content.
- Backup or transfer of Tutor sessions, Agent memory, plans, messages, or Agent Workspace.
- Password encryption for the model configuration export.
- Transfer of generated dependencies, caches, or arbitrary files outside managed Journey and Project workspaces.
- Implementing future Project Mode roadmap capabilities as part of this transfer feature.
- Compatibility with a v1 database or v1 transfer format.

## Further Notes

- Journeys independently created on two devices receive different portable identifiers. Importing one onto the other
  therefore adds a separate Journey; later transfers of that imported Journey match by its retained identifier.
- Existing SQLite Entity IDs remain local. The portable UUID is used only to recognize the Journey across exports.
- The transfer covers Domain State and managed workspaces; Tutor runtime context is reconstructed from those sources.
