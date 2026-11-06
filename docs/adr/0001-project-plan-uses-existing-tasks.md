---
status: accepted
date: 2026-11-06
---

# A project plan uses the project's existing tasks

Project planning must support prerequisite relationships without requiring dates. The plan is the project's existing tasks and their relationships, rather than a separate entity containing its own task copies or multiple plans per project. This preserves a single task identity and completion history across planning and the existing task views, at the cost of excluding independently versioned or alternative plans from this design.

Implemented locally after a subsequent user request. Shared database migration and deployment are pending; see the [approved specification](../project-planning.md).
