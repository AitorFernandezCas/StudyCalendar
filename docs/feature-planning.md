# Feature planning design

Status: approved on 2026-11-18 by the user's request to begin implementation. Implemented locally for the web app and backend. See DEPLOYMENT.md for rollout.

## User objective

Group project tasks into features and plan each feature instead of working primarily with one whole-project plan.

## Accepted decisions

- Q1: A feature is a named piece of work within one project. Each task belongs to at most one feature. Features group existing tasks rather than creating task copies or acting as overlapping tags.
- Q2: The first implementation covers the web app and backend. Android feature support follows separately; preserve compatibility with the existing Android app.
- Q3: Project tasks may remain ungrouped, shown under "Sin funcionalidad" within their project. Existing tasks start ungrouped and preserve their identity, schedules, completion and dependency relationships until the user organizes them.
- Q4: Dependencies may cross feature boundaries within the same project, including ungrouped work. Each feature has its own diagram with external prerequisites clearly identified; existing completion, reopening and cycle rules continue to apply to the whole project graph.
- Q5: Feature progress is calculated from its tasks, with no separate manual active/inactive/completed status. A nonempty feature whose tasks are all complete displays as complete; reopening a task changes that progress. Project status continues to control overview visibility. Q11 defines the empty-feature display.
- Q6: The Spanish interface uses "Funcionalidades". Features have a required name, optional description and inherit the project's color.
- Q7: Opening a project shows its features and "Sin funcionalidad", each opening its own diagram. This replaces the whole-project diagram as the main planning view. Project progress still includes every task.
- Q8: Tasks may move between features or back to "Sin funcionalidad" within their project without removing dependencies. Identity, dates, completion and links remain intact. Moving between projects retains the existing restrictions.
- Q9: Deleting a feature preserves its tasks under "Sin funcionalidad" and preserves their dependencies. Require confirmation explaining that tasks and dependencies survive. Deleting the entire project deletes all its features, tasks and links.
- Q10: Tasks created inside a feature automatically belong to it and default to no date. The ordinary task form offers an optional feature selector after choosing a project. Calendar creation retains the selected date; creation outside a feature defaults to "Sin funcionalidad".
- Q11: Feature names are unique within their project, ignoring capitalization. Features are sorted alphabetically and may be empty. Empty features display "Sin tareas", not complete, so users may outline features before creating tasks.
- Q12: Each feature diagram contains only that feature's tasks. Label external prerequisites and dependents and provide references that open the corresponding feature or "Sin funcionalidad". The prerequisite selector includes all project tasks; completion and reopening checks always consider the entire project.
- Q13: Each feature remembers its own diagram arrangement locally in the browser. "Ver rama" focuses on tasks inside that feature and retains labelled external dependencies. Preserve the existing project arrangement for "Sin funcionalidad"; new feature diagrams start with automatic layout.
- Q14: Task details and "Todas las tareas" show the assigned feature alongside the project. Calendar cards retain their compact appearance; opening a task reveals its feature. Existing scheduling, visibility and counting rules remain unchanged.
- Q15: Feature management supports creation, renaming, description editing and deletion, with one flat level within each project. Nested features and moving whole features between projects are outside this version. Individual tasks remain movable through their task form.

## Behavior and integrity

### Projects and progress

Opening a project presents alphabetically ordered features and the "Sin funcionalidad" group. Opening either presents its task diagram. Features remain accessible when complete, and all associated tasks contribute to feature and project progress regardless of dates. Empty features show "Sin tareas". "Sin funcionalidad" is a view of ungrouped project work, not a stored feature or a synonym for "Sin proyecto". Tasks with no project cannot belong to a feature.

The project also offers a "Gráfico" view of its features. Click a card title to open its task diagram. Drag the card or use the arrow keys on its move handle. Positions are saved in the browser by account and project. Zoom, pan, reset view and automatic arrangement work like the task diagram. "Ver rama" follows both kinds of arrows and keeps its positions temporary. Returning restores the full graph or the originating feature overview and keyboard focus, depending on where the return started.

Create a feature prerequisite by dragging between feature ports or using "Funcionalidades previas". Saved links express planning order, including between empty features. Only task prerequisites govern task completion and reopening. Saved links use solid arrows and task-derived links use dashed arrows. If both describe the same direction, the solid arrow represents both and the target card identifies their origin. Removing the saved link reveals the dashed arrow if the task link remains. "Sin funcionalidad" appears when it has tasks and accepts movement and drill-down. Its arrows come from task prerequisites.

Feature names are required and unique within the project, ignoring capitalization; descriptions are optional. Features inherit project color and have no independent manual status. Existing project states and their visibility rules continue to apply.

### Tasks and dependencies

Feature membership organizes existing tasks without copying them. Creating within a feature preselects it and defaults to no date. General and calendar creation retain their existing date defaults and expose optional feature assignment after a project is selected. Editing retains the task's current membership and schedule.

Membership changes within a project preserve task identifiers, completion, schedules and prerequisite links. Existing project reassignment, deletion and conversion-to-daily restrictions remain in force. Membership must always match the task's current project; a task cannot retain a feature from another project or keep feature membership when converted to a daily task.

All dependency invariants remain project-wide: reject cycles and self-links, require completed prerequisites before completion, prevent reopening while completed dependents exist, and prevent adding unfinished prerequisites to completed tasks. These checks must work even when the relevant other task is outside the visible feature or branch.

### Diagrams and navigation

Display only the selected group's tasks as diagram nodes, while identifying external prerequisites and dependents with navigable references. Feature labels distinguish where external work belongs. Offer all project tasks in prerequisite selection, including ungrouped tasks, while preserving existing invalid-link validation. External references must remain usable by keyboard.

Branch focus is scoped to the selected feature or ungrouped view; external relationships remain discoverable. It must not pull unrelated feature nodes into the current diagram or weaken project-wide dependency checks. Preserve existing branch-focus behavior otherwise.

Keep saved arrangements separate for each feature and the ungrouped view. The existing project arrangement supplies the initial ungrouped arrangement, preserving user work. New feature diagrams begin with automatic layout; layouts remain browser-local. Existing move, zoom, pan, automatic arrangement and keyboard interactions continue within the selected group.

### Deletion

Feature deletion requests explicit confirmation that its tasks and links will survive. On success, every associated task becomes ungrouped in the same project. Cancellation changes nothing; a failure must not leave partial reassignment. Entire-project deletion continues to remove all of its tasks and dependency links, and also removes its features.

## Existing behavior and compatibility constraints

- The current project plan uses existing tasks and their prerequisite relationships. Dependencies are constrained to one project and enforced by Python, PostgreSQL and Kotlin. Task identity, schedules and completion history must be considered when introducing grouping.
- Android already supports project graphs and offline edits and synchronizes directly with Supabase. Compatibility therefore includes database and synchronization behavior, not only Flask endpoints. The existing client must not accidentally erase feature membership when editing tasks.
- Existing graph branch focus is a temporary view, distinct from persistent feature membership.
- Diagram positions currently use a project scope; Q13 defines how feature-scoped layouts replace that behavior.

## Implementation and rollout constraints

Implement additive persistence and API support with user ownership, same-project feature membership and case-insensitive name uniqueness enforced server-side. Keep core rules independent of HTTP and persistence SDKs, preserve sparse PATCH semantics, and keep full task reads paginated for project-wide dependency validation.

Existing clients that omit feature information when editing a task must preserve its assignment when the task remains in the same project. New tasks from clients without feature support remain ungrouped. Preserve the current Android client's project-level graphs and task operations during this staged rollout; it does not gain feature management in this version. Check direct database synchronization as well as Flask compatibility, especially offline edits, revisions and feature deletion. Do not silently erase assignments during an unrelated task edit.

Existing tasks migrate as ungrouped without changing their identifiers, schedules, completion or prerequisites. Inspect current migration history and test the new migration in an isolated database. Existing shared migrations must not be reapplied. Prepare migration and rollout documentation as part of implementation; applying migrations to the shared database and publishing remain separate operations governed by the repository's backup and deployment requirements.

## Acceptance scenarios

1. Upgrade a project containing dated, undated, pending and completed tasks with existing links. All tasks initially appear under "Sin funcionalidad", with identifiers, schedules, completion, dependencies and the existing browser diagram arrangement preserved.
2. Create an empty feature with a name and description. It shows "Sin tareas", inherits project color and appears in alphabetical order. Reject a duplicate name with different capitalization in the same project; allow that name in another project owned by the user. Rename and edit the description, then verify persistence after reload.
3. Create an undated task from a feature. It belongs to that feature, contributes to both progress totals and stays outside the calendar and dated-only overview. Create from a calendar cell and verify its date is preserved with optional feature assignment.
4. Move tasks into a feature, between features and back to ungrouped work. Preserve all task data and dependencies, and keep project totals unchanged. Prevent invalid feature ownership or project associations through both API and database access.
5. Put prerequisite A in one feature and dependent B in another. B identifies and navigates to A's feature, remains blocked while A is unfinished and becomes completable after A completes. Reopening A is refused while B is complete, even though B is outside A's diagram. Repeat with one task ungrouped and with a focused branch.
6. Add cross-feature links using the full-project prerequisite selector. Reject cross-project links, self-links, cycles and unfinished prerequisites added to completed tasks. Preserve validation when the project exceeds a single task-read page.
7. Complete every task in a feature, then reopen an eligible task. Feature progress updates in both directions; feature visibility and project status remain unchanged. Completed and undated work still contributes to project progress.
8. Cancel feature deletion and verify no changes. Confirm deletion in a disposable fixture and verify its tasks become ungrouped with links, schedules and completion intact. Verify whole-project deletion also removes features and all associated tasks and links without touching unrelated projects.
9. Arrange two feature diagrams independently, reload and verify their saved positions. Verify branch focus, external references, keyboard operation, task editing, zoom and pan within each group. The ungrouped arrangement remains independent.
10. Verify feature labels in task details and the overview, compact calendar cards, unchanged active-project filtering and sidebar counts, and continued support for project tasks without any project.
11. Edit and synchronize a feature-assigned task using payloads from the existing Android client. An unrelated edit must retain feature membership, and new Android tasks must remain ungrouped. Verify offline synchronization after feature deletion and existing project-wide completion/reopening rules without requiring Android feature UI.
12. Verify feature CRUD and assignment loading/error/retry states, reject duplicate saves, and ensure failed or concurrent mutations cannot leave mismatched membership or partial deletion. Do not automatically retry data-changing operations.

Use frontend helper tests and build, the full backend suite, isolated database invariant/RLS/migration checks, legacy sync compatibility checks, and manual disposable-fixture UI verification. Preserve unrelated work already present in the repository.

## Relationship to previous decisions

This design supersedes the whole-project diagram as the main web planning surface in Q1/Q11 and the screen behavior of [project-planning.md](project-planning.md). It preserves existing task identity, project-wide prerequisite rules, scheduling, task completion history and project-deletion semantics. [ADR 0005](adr/0005-features-group-a-project-dependency-graph.md) refines ADR 0001's plan boundary.

## Implementation and verification

Feature records and nullable task membership are introduced by `20261118105622_project_features.sql`, applied to the shared Supabase project on 2026-11-18. An ownership-aware foreign key limits membership to the task's project and clears membership atomically on feature deletion. Protocol 1 snapshots include an optional feature collection. Updates from older clients merge only the fields they send, and a move to another project clears the old membership. Tasks and feature records use the existing revisions and operation receipts.

The isolated PGlite runner covers migration of pre-existing tasks, feature ownership, duplicate names, cross-feature dependency rules, feature and project deletion, and legacy Android-shaped sync payloads. The disposable web fixture covers independent diagrams, cross-feature navigation, branch focus, saved positions, progress, feature creation and editing, deletion confirmation, and the feature selector and label for dated tasks. Production deployment and device sync verification are pending.

Saved feature links use the additive migration `20261118223658_project_feature_links.sql`, applied to the shared database on 2026-11-18. The database serializes same-user writes, validates project ownership and rejects cycles in the saved feature graph. The protocol 1 snapshot includes `project_feature_links`. Deleting a feature or its project removes its links. See [DEPLOYMENT.md](../DEPLOYMENT.md) for rollout.

Verified in an isolated PostgreSQL 17.6 server with two connections. The second writer waited for the first to commit, then rejected the opposing link as a cycle. The disposable browser fixture covered drag, keyboard movement, port linking, arrow origins, reload, branch focus, and feature drill-down at desktop and mobile widths.
