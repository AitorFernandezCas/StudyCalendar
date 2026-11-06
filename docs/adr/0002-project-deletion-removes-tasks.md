---
status: accepted
date: 2026-11-06
---

# Deleting a project also deletes its tasks

The existing implementation keeps a deleted project's tasks under "Sin proyecto". The user explicitly chose that deleting a project instead removes all associated tasks and their dependency links, making deletion apply to the whole project plan rather than only its container. This trades recoverability of individual tasks for complete removal of the project and overrides the rule that an individual linked task must have its links removed before deletion; the interface must clearly explain the scope before confirmation.

Implemented locally after a subsequent user request. Shared database migration and deployment are pending; see the [approved specification](../project-planning.md).
