# Repository Guidelines

## Project Structure & Module Organization

This repository contains a Vite-powered React and TypeScript study calendar.

- `src/App.tsx` contains the main application UI, task state, calendar behavior, and modal form.
- `src/main.tsx` is the browser entry point.
- `src/styles.css` contains the application-wide layout, responsive styles, and component styling.
- `index.html` defines the Vite HTML shell and document metadata.
- `vite.config.ts` and `tsconfig*.json` contain build and TypeScript configuration.
- `dist/` is generated build output and must not be edited manually.
- `node_modules/` contains installed dependencies and is not committed.

There is currently no dedicated test directory. If tests are added, place them next to the related module or under `src/__tests__/`.

## Build, Test, and Development Commands

Run commands from the repository root:

- `npm install` installs dependencies from `package-lock.json`.
- `npm run dev` starts the local Vite development server with hot reload.
- `npm run build` runs TypeScript project checks and creates a production build in `dist/`.
- `npm run preview` serves the production build locally for a final manual check.

No test runner or lint script is configured yet. Verify UI changes manually in the browser and always run `npm run build` before submitting work.

## Coding Style & Naming Conventions

Use 2-space indentation and single quotes in TypeScript. Prefer functional React components, hooks, and strongly typed data structures. Use `PascalCase` for components and types, `camelCase` for variables and functions, and descriptive kebab-case only for file names when appropriate. Keep user-facing text in Spanish to match the existing interface. Reuse existing CSS class naming patterns and keep responsive behavior intact.

## Testing Guidelines

There is no automated testing framework or coverage requirement at present. For changes affecting task creation, editing, dragging, completion, persistence, or filtering, manually verify the full interaction in the browser and confirm that a page reload preserves tasks through `localStorage`.

## Commit & Pull Request Guidelines

The repository has no commit history yet, so no established commit convention exists. Use short imperative messages, for example `Add task completion state` or `Fix calendar drag behavior`. Pull requests should explain the user-visible change, list verification commands, mention any persistence or data-shape changes, and include screenshots or a short recording for visual or interaction changes.

## Security & Configuration Tips

Task data is stored locally in the browser; do not add secrets or credentials to source files. Keep generated files and local configuration out of commits according to `.gitignore`.
