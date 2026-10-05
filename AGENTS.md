# Repository Guidelines

## Architecture & Project Structure

StudyCalendar is a Vite + React + TypeScript frontend backed by a Flask API and Supabase.

- `src/App.tsx` contains the calendar, week/month views, all-tasks, projects, and routines views, drag-and-drop for ordinary tasks, modal forms, and routine restrictions.
- `src/lib/navigation.ts` maps the calendar, all-tasks, projects, and routines screens to `/`, `/tareas`, `/proyectos`, and `/rutinas`, and keeps browser history navigation in sync.
- `src/lib/api.ts` is the typed client for Flask endpoints; `src/lib/supabase.ts` handles Supabase Auth and persistent sessions.
- `src/lib/useRoutineHabits.ts` loads daily habit summaries independently, synchronizes completion, and refreshes at midnight and on focus.
- `src/styles.css` contains the shared responsive UI styles.
- `backend/app.py` provides the Flask app factory, bearer-token validation, CORS, task/category/project/routine endpoints, and daily habit completion. `backend/wsgi.py` is the production entry point.
- `backend/test_app.py` contains Flask API tests; `backend/test_routine_streaks.sql` verifies streak calculations and user isolation against PostgreSQL inside a rolled-back transaction.
- `supabase/migrations/` contains versioned SQL migrations for the quoted `Task` schema. `dist/` and `node_modules/` are generated and must not be edited or committed.

## Data Model & Behavior

All application tables live in schema `"Task"`: `tasks`, `category`, `projects`, `routine`, `routine_period`, and `routine_completion`. Ordinary tasks use `category_id`, `project_id`, and `task_type` (`daily` or `project`). The database retains the legacy `routine` task type, but task queries must exclude those rows and task creation must reject that type; habits use the separate routine tables. Categories and projects are user-owned; deleting a category clears `category_id`, while deleting a project clears `project_id` and leaves its tasks available as unassigned project tasks.

Projects have a name and color, are unique by case-insensitive name per user, and are managed through the authenticated `/api/projects` CRUD endpoints. Project-task associations are validated against the authenticated user's projects. Preserve row-level security and user ownership when changing the project schema or API.

The frontend uses `/` for the calendar, `/tareas` for “Todas las tareas”, `/proyectos` for the dedicated projects view, and `/rutinas` for active and inactive routines. Keep the URL and selected screen synchronized with browser back/forward navigation. “Proyectos” groups project tasks by associated project, including historical and completed tasks; tasks without an association appear under “Sin proyecto”. Project progress uses all associated tasks. “Todas las tareas” shows pending daily and project tasks, including those with past dates, and hides completed daily and project tasks. The calendar continues to show completed ordinary tasks in week and month views.

Routines are daily habits without categories or hours and never appear in the calendar. Definitions are created, edited, activated/deactivated, or deleted from `/rutinas`, grouped into active and inactive routines. Forms retain title, color, and activation; creation also accepts an initial date. “Todas las tareas” shows routines due today in alphabetically ordered “Pendientes de hoy” and “Completadas hoy” groups and allows complete/uncomplete for today only. The section heading links to `/rutinas` and counts pending habits. Both screens show “Racha actual: N días” and “Máxima: N días”. Completions are unique per routine and date. A routine is due only when active and its active period includes today; future routines are excluded. Activation is independent of completion, and pausing/reactivating preserves existing completion history and identifiers.

Streaks count consecutive calendar dates with `completed = true`, ignoring future dates until they arrive. A pending today preserves a streak ending yesterday; a missed yesterday resets it. Inactive routines show current streak zero and retain their maximum. Pauses do not add days and interrupt continuity. The maximum is the longest consecutive sequence in the applicable history, recalculated along with the current streak whenever today is completed or unmarked; it can decrease after unmarking. SQL function `"Task".routine_summaries(p_today)` calculates summaries using `SECURITY INVOKER`, authenticated execution permissions, and RLS. Preserve routine-table ownership relations and policies; do not truncate history through REST pagination or store duplicate streak counters.

### Routine API & Day Boundaries

- `GET /api/routines` returns `date`, `timezone`, `next_day_at`, and routine definitions with `due_today`, `completed_today`, `current_streak`, and `max_streak`.
- `POST /api/routines` accepts `title`, `color`, `active`, and `starts_on`; `PATCH /api/routines/<id>` manages title, color, and activation. Routine time fields are no longer supported.
- `PATCH /api/routines/<id>/completion` accepts `{date, completed}` and returns the updated summary and day metadata. Only today's date and an owned, active routine whose period includes that date are allowed. A stale date returns `409` and triggers a reload; an unavailable routine returns `404`.
- Routine summaries load independently through their API. Calendar and bootstrap responses contain no routines or `routine_occurrences`; the old occurrence endpoints and types are removed.
- `APP_TIMEZONE` defaults to `Europe/Madrid`. Backend dates and `next_day_at` are authoritative, including daylight-saving transitions. Refresh at midnight and when focus or visibility returns; invalidate old-day data while loading and provide errors with retry. Guard against stale responses after day changes or session changes.
- Coordinate frontend/backend deployment with `20261005133423_routine_habits_and_streaks.sql`, which removes routine time columns and their constraint and adds the summary function while preserving periods and completions. The old backend requires the removed columns.

## Development Commands

From the repository root:

- `npm install` installs frontend dependencies.
- `npm run dev` starts Vite, normally on `http://localhost:5173`.
- `npm run build` runs TypeScript checks and creates `dist/`.
- `npm run preview` serves the production build locally.
- `python -m venv backend/.venv` and `pip install -r backend/requirements.txt` prepare the Flask environment.
- `python backend/app.py` starts the API on `http://localhost:5000`.
- `pytest backend/test_app.py -q` runs authentication, habit API, completion, calendar exclusion, and timezone tests.
- `psql "$env:DATABASE_URL" -v ON_ERROR_STOP=1 -f backend/test_routine_streaks.sql` (PowerShell) verifies streaks, pauses/reactivation, future dates, more than 1,000 completions, and RLS isolation. Use a migrated test database and an administrative connection able to create temporary user fixtures and impersonate authenticated users; the script rolls back its fixture changes.

## Style, Testing & Security

Use two-space indentation, single quotes in TypeScript, functional React components, `PascalCase` for components/types, and `camelCase` for functions/variables. Keep UI copy in Spanish. There is no frontend test runner; manually verify calendar navigation, CRUD, dragging, and reload persistence, then run both build and pytest. For habits, verify completion/unmarking, pending counts, streak synchronization between `/tareas` and `/rutinas`, pause/reactivation, future initial dates, midnight/focus refresh, stale-date recovery, and loading/error/retry states. Confirm routines are absent from week/month calendars and time fields are absent from routine forms. Run the SQL checks when changing streak logic or routine migrations.

Use `.env.example` and `backend/.env.example` as templates; configure `APP_TIMEZONE` in the backend environment. Keep `tzdata` available for timezone support on Windows. Never expose Supabase service-role/secret keys in the frontend or commit real `.env` files. Supabase tables use RLS and user ownership; preserve those policies in every migration.

## Commits & Pull Requests

Use short imperative commit messages such as `Fix routine streak calculation`. Pull requests should describe user-visible behavior, API/schema changes, migrations and deployment ordering, verification commands, and screenshots for UI changes.
