# Repository Guidelines

## Architecture & Project Structure

StudyCalendar is a Vite + React + TypeScript frontend backed by a Flask API and Supabase.

- `src/App.tsx` contains the calendar, week/month views, all-tasks and projects views, drag-and-drop for ordinary tasks, modal forms, and routine restrictions.
- `src/lib/navigation.ts` maps the calendar, all-tasks, and projects screens to `/`, `/tareas`, and `/proyectos`, and keeps browser history navigation in sync.
- `src/lib/api.ts` is the typed client for Flask endpoints; `src/lib/supabase.ts` handles Supabase Auth and persistent sessions.
- `src/styles.css` contains the shared responsive UI styles.
- `backend/app.py` provides the Flask app factory, bearer-token validation, CORS, task/category/project/routine endpoints, and occurrence generation. `backend/wsgi.py` is the production entry point.
- `backend/test_app.py` contains Flask tests.
- `supabase/migrations/` contains versioned SQL migrations for the quoted `Task` schema. `dist/` and `node_modules/` are generated and must not be edited or committed.

## Data Model & Behavior

All application tables live in schema `"Task"`: `tasks`, `category`, `projects`, `routine`, `routine_period`, and `routine_completion`. Tasks use `category_id`, `project_id`, and `task_type` (`daily`, `project`, or `routine`). Categories and projects are user-owned; deleting a category clears `category_id`, while deleting a project clears `project_id` and leaves its tasks available as unassigned project tasks.

Projects have a name and color, are unique by case-insensitive name per user, and are managed through the authenticated `/api/projects` CRUD endpoints. Project-task associations are validated against the authenticated user's projects. Preserve row-level security and user ownership when changing the project schema or API.

The frontend uses `/` for the calendar, `/tareas` for “Todas las tareas”, and `/proyectos` for the dedicated projects view. Keep the URL and selected screen synchronized with browser back/forward navigation. “Proyectos” groups project tasks by associated project, including historical and completed tasks; tasks without an association appear under “Sin proyecto”. Project progress uses all associated tasks. “Todas las tareas” shows pending daily and project tasks, including those with past dates, and hides completed daily and project tasks. The calendar continues to show completed tasks. Routine activation is independent of task completion; preserve the routine occurrence rules below.

Routines have no category. They repeat daily through active periods; completions are stored per routine and date. Calendar occurrences are read-only except for complete/uncomplete. Routine definitions are edited, activated/deactivated, or deleted only from “Todas las tareas”. Inactive routines may show historical dates only; they must never generate today or future occurrences.

## Development Commands

From the repository root:

- `npm install` installs frontend dependencies.
- `npm run dev` starts Vite, normally on `http://localhost:5173`.
- `npm run build` runs TypeScript checks and creates `dist/`.
- `npm run preview` serves the production build locally.
- `python -m venv backend/.venv` and `pip install -r backend/requirements.txt` prepare the Flask environment.
- `python backend/app.py` starts the API on `http://localhost:5000`.
- `pytest backend/test_app.py -q` runs authentication and routine occurrence tests.

## Style, Testing & Security

Use two-space indentation, single quotes in TypeScript, functional React components, `PascalCase` for components/types, and `camelCase` for functions/variables. Keep UI copy in Spanish. There is no frontend test runner; manually verify calendar navigation, CRUD, dragging, completion, routine activation, and reload persistence, then run both build and pytest.

Use `.env.example` and `backend/.env.example` as templates. Never expose Supabase service-role/secret keys in the frontend or commit real `.env` files. Supabase tables use RLS and user ownership; preserve those policies in every migration.

## Commits & Pull Requests

Use short imperative commit messages such as `Fix inactive routine occurrences`. Pull requests should describe user-visible behavior, API/schema changes, migrations, verification commands, and screenshots for UI changes.
