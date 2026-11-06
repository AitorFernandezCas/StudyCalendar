# Project planning design

Status: approved by the user on 2026-11-06. The design interview is complete, with no outstanding product decisions from the interview.

Implementation was subsequently requested by the user on 2026-11-06. The local frontend, backend, migration, and regression checks implement this design. Deployment and application of the migration to the shared database remain separate operations; see DEPLOYMENT.md.

## User objective

Build project task plans with prerequisite relationships and allow tasks to be created without dates, so planning does not require scheduling.

## Accepted decisions

- Q1: Each project has one plan composed of its existing tasks and their prerequisite relationships. Preserve task identity and completion history; do not introduce separate copies of plan tasks.
- Q2: Plans can branch. A task can have multiple prerequisites, all of which must be completed before it can be completed.
- Q3: An unfinished prerequisite prevents completing a dependent task. Editing and scheduling the dependent task remain allowed.
- Q4: Prerequisites and their dependent tasks must belong to the same project.
- Q5: An undated task can be completed without assigning a date, provided its prerequisites are complete.
- Q6: Dates are optional for project tasks, including unassigned project tasks under "Sin proyecto". Daily tasks continue to require dates.
- Q7: "Todas las tareas" shows only dated tasks. Undated project tasks are excluded from that screen.
- Q8: Reopening a prerequisite is refused while it has completed dependents. For a completed A -> B -> C chain, reopen C, then B, then A; do not automatically reopen other tasks.
- Q9: A visual diagram of connected tasks is essential. A list-only plan does not satisfy the requirement.
- Q10: Scheduling remains manual. Completing prerequisites neither assigns dates nor shifts existing schedules.
- Q11: Opening a project shows a visual plan where users add tasks, click tasks to edit them, and draw arrows from prerequisite to dependent. Provide prerequisite selectors as an alternative to drawing arrows. Use automatic left-to-right layout with zoom and pan.
- Q12: Tasks created inside a project plan default to no date, with optional scheduling. Dates can later be assigned or removed without losing task identity, completion, or dependencies. Calendar creation retains the explicitly selected date; the general "Nueva tarea" action retains today's default.
- Q13: Deleting an individual linked task, moving it to another project, or converting it to a daily task requires explicitly removing its dependency links first. Refuse the operation while links remain and identify the links that must be removed.
- Q14: Deleting an entire project deletes all of its associated tasks, including dated, undated, pending, and completed tasks, and removes the plan's dependency links. Tasks do not survive under "Sin proyecto". This intentionally replaces existing project-deletion behavior and is an exception to the individual-task restriction in Q13.
- Q15: Undated tasks remain outside the daily activity chart, whose existing meaning is based on scheduled dates. They still contribute to project progress.
- Q16: "Todas las tareas" includes dated blocked tasks, labelled with their unfinished prerequisites. Its sidebar count includes all visible pending dated tasks and excludes undated tasks. Existing project-state filtering still applies.
- Q17: Reject self-dependencies, circular dependencies, and adding an unfinished prerequisite to a completed task. Reopen the completed task first, respecting Q8, before adding an unfinished prerequisite.

See [the glossary](../GLOSSARY.md) for domain terms, [ADR 0001](adr/0001-project-plan-uses-existing-tasks.md) for the plan boundary, and [ADR 0002](adr/0002-project-deletion-removes-tasks.md) for destructive project deletion.

## Behavior across screens

- `/proyectos` remains the entry point for projects, grouped by their existing active, inactive, and completed states. Opening a project exposes its visual plan, including dated, undated, pending, and completed tasks.
- Unassigned project tasks remain accessible under "Sin proyecto" in `/proyectos`, including undated tasks. They cannot have prerequisite links until assigned to a project because relationships must belong to the same project.
- `/tareas` retains pending daily tasks and pending project tasks allowed by project-state filtering, but now requires a date for a task to appear. Dated blocked tasks remain visible and cannot be marked complete.
- Calendar days show only dated tasks. The same dependency completion rule applies from the calendar, task lists, editing forms, project diagram, and API.
- Undated tasks have no calendar placement. Scheduling exposes the existing timed or all-day options; removing a schedule removes its date and time assignment. A daily task must have a valid date and schedule.
- All associated tasks contribute to project progress, regardless of schedule or blocking. Existing manual project status changes remain independent of task completion.
- Daily habits keep their separate behavior; the new optional-date and dependency features apply to project tasks.

## Interaction and integrity requirements

- Use Spanish interface copy and distinguish pending tasks that are available from those blocked by unfinished prerequisites; show completed tasks distinctly.
- Provide keyboard-operable task and prerequisite controls alongside drawing interactions. Surface validation errors without silently changing dependencies or completion.
- Project deletion must state clearly that it deletes the project's tasks and request confirmation before executing the destructive operation. Cancelling leaves the project, tasks, and links intact.
- Existing tasks retain their identifiers, schedules, associations, and completion state when this feature is introduced. They acquire no implicit dependency relationships.
- Enforce ownership and dependency rules in the backend, including callers that bypass the UI. A failed or conflicting operation must not leave a partially changed plan.
- The new project-deletion requirement supersedes the current repository guideline that deleting a project preserves its tasks; update that guideline as part of implementation.

## Implementation baseline at design approval

These facts describe the existing application as inspected during the interview, before any implementation of this design.

- Task dates and start/end times are mandatory in the original SQL schema and assumed by the frontend. All-day tasks still have a date and a valid internal time interval.
- Completion is currently a boolean updated through the ordinary task API, without prerequisite checks.
- Project task lists are ordered by date and time. Project progress includes all associated tasks.
- Calendar queries are bounded by date; overview task reads can load tasks without a date-range filter.
- Activity currently uses the planned task date, not an actual completion timestamp.
- Deleting a project preserves its tasks as unassigned project tasks. Tasks can also be reassigned through editing.

## Acceptance scenarios

1. Create an undated project task. It persists in the project plan, contributes to progress, and is absent from the calendar, `/tareas`, its sidebar count, and daily activity. Complete it without scheduling it when it has no unfinished prerequisite.
2. Create A -> B and A -> C, then make D depend on both B and C. Completing A unlocks B and C. D stays blocked until both are complete. Dates are not assigned or shifted by these actions.
3. Schedule a blocked task. It appears in the calendar and, if pending and allowed by project state, `/tareas`; attempts to complete it are refused through both the UI and API.
4. Add and remove a project task's schedule. Its identifier, dependency relationships, and completion survive reload. Daily tasks cannot become undated.
5. Complete A -> B -> C. Reopening A is refused until C and then B are reopened. No other task is reopened automatically.
6. Reject self-links, cycles, cross-project links, links to another user's tasks, and adding an unfinished prerequisite to a completed task.
7. Refuse deletion, project reassignment, or conversion to daily of an individually linked task until its links are explicitly removed. An unlinked conversion to daily still requires a schedule.
8. Cancel project deletion and verify no change. Confirm deletion in a disposable fixture and verify the project, every associated task, and its links are gone while unrelated tasks and projects remain intact.
9. Keep manually unassigned undated project tasks accessible under "Sin proyecto". Keep project-state filtering and visible dated-task counts consistent after reload.
10. Verify diagram creation, editing, connection controls, error recovery, zoom/pan, and keyboard access, plus preservation of existing tasks and schedules after migration.

## Implementation and verification

The user subsequently requested implementation. The local changes include a visual project diagram, optional project schedules, prerequisite validation in the application core and PostgreSQL triggers, complete project deletion, dated-only overview filtering, and disposable-fixture support.

Prerequisites are stored as `prerequisite_ids` on the owned task row. The existing task RLS policies continue to protect reads and writes. The trigger validates ownership and project boundaries, cycles, completion, reopening, and individual deletion; it locks project rows in identifier order to serialize concurrent plan changes. The project-deletion trigger removes links and tasks in the same transaction. Individual database deletion checks run at transaction end so existing Auth account-deletion cascades can remove tasks and projects in either order without bypass flags. Reads paginate with a stable identifier tie-breaker to include dependencies beyond REST page limits.

The migration is `20261106122252_project_plans.sql`. It preserves existing fields and gives existing tasks an empty prerequisite list. It permits a wholly absent schedule only for project tasks; dated tasks retain their valid timed/all-day schedule contract. The migration was applied to the shared database on 2026-11-06 after the user confirmed a recent private backup. Render publication was explicitly deferred; the app is launched locally.

Verification covers frontend layout and visibility helpers, the production build, backend HTTP and core regressions, and isolated PostgreSQL migration/invariant/RLS/deletion/Auth-cascade/rollback checks. The local UI fixture verifies undated creation, connecting tasks, completion unlocking, reverse reopening, and overview exclusion. The fixture does not establish production availability or real multi-connection concurrency performance; repeat concurrent writer checks against isolated PostgreSQL before rollout. The SQL test and runner are `backend/test_project_plans.sql` and `frontend/scripts/test-project-plans-db.mjs`.

Rollout requires the migration before this application version, a private backup, and coordinated backend/frontend deployment. Follow DEPLOYMENT.md; schema deployment is not automatic.


## Manual diagram arrangement — 2026-11-06

The user subsequently requested moving tasks freely without creating dependency links. Cards can be dragged by their non-interactive surface or movement control; that control also supports arrow keys (20 units) and Shift+arrow keys (5 units). Movement accounts for zoom and scrolling, keeps cards inside the canvas, and redraws existing arrows. Escape or pointer cancellation restores a cancelled drag.

Positions persist per project in this browser's local storage, without updating task dates, completion, or prerequisites. They are not synchronized across devices. Automatic layout remains the initial layout and can be restored with "Organizar automáticamente"; "Restablecer vista" resets only zoom and scroll.


## Compact diagram cards — 2026-11-06

The user requested name-only cards that expand on hover. The collapsed node is 220 × 48 pixels; automatic rows are closer together. Hover and keyboard focus reveal the full title, status, schedule, prerequisites, movement/connection controls, and actions. Expanded cards overlay neighbouring nodes without moving their positions or connection anchors. Saved manual positions remain valid.


Project task scheduling is controlled solely through the optional date selector. Choosing a date schedules the task; clearing the selector leaves it undated. The separate "Asignar fecha" / "Quitar fecha" button was removed at the user's request. Daily tasks and routine initial dates remain required.

The diagram toolbar no longer duplicates task creation with a separate "Nueva tarea sin fecha" action. Use the project's existing "Añadir tarea" action and its optional date selector.


## Focus on a branch — 2026-11-06

Each expanded card offers "Ver rama". The view contains the selected task, its transitive prerequisites, and its transitive dependents; the two traversals stay separate, excluding sideways branches and additional inputs of downstream tasks. For A -> B -> D and X -> D, focusing B shows A/B/D but hides X. D still displays X in its prerequisite details, marked as outside the branch, and all task rules and editing controls use the complete project collection.

The toolbar identifies "Rama de «task name»", visible/total task counts, and "Ver proyecto completo". The selected root has a distinct outline. Choosing another visible task's branch recomputes the result against the complete project. Editing via the task name continues to work.

Focused branches use their own compact automatic layout and in-memory manual positions. Entry and "Restablecer vista" fit the branch using 40–100% zoom; "Organizar automáticamente" resets only the temporary branch arrangement. Leaving restores full-project positions, zoom and scroll. Focus and temporary moves are never written to saved project positions or to the API. Focus survives collapsing/reopening the project but resets on page reload. Dependency edits recalculate membership, and deleting or moving the root out of the project automatically restores the full view. Changing views cancels active movement and connection gestures.

Verification includes pure traversal tests for branching, shared inputs, switching roots, graph edits, completed and isolated tasks, missing references, malformed cycles, long chains, and hidden-prerequisite blocking. Manual disposable-fixture checks cover layout/camera restoration, temporary dragging, focused arrangement, keyboard entry/exit, root deletion and focus reset after reload. The task-name editor callback remains unchanged. No backend, API or migration change is needed; this feature is local only and is not published to Render.

Branch focus verification passed: 34 frontend helper tests, 183 backend regressions, and the production build. A six-task disposable localhost fixture verified strict branch membership, hidden-prerequisite blocking, switching roots, temporary pointer/keyboard movement, focused auto-arrangement, full-layout/zoom restoration, collapse/reopen persistence, dependency edits, root deletion, and reload reset. The fixture services were stopped afterward; the real local app remains available. See `docs/screenshots/branch-focus.jpg` for the fixture screenshot.


## Full task names without hover — 2026-11-06

Collapsed cards keep their fixed width but wrap the entire task name and grow vertically to fit it, including long unbroken words. Details and actions still appear only on hover or keyboard focus, using the existing smooth transition. Natural title heights are measured without zoom scaling; automatic row spacing, connection anchors, canvas bounds and focused-branch fitting use each card's collapsed height. Manual positions remain intact, and dragging no longer forces long-name cards back to one line.
