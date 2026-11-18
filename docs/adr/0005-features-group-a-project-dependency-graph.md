---
status: accepted
date: 2026-11-18
---

# Features group one project dependency graph

Each project task belongs to at most one feature, and prerequisite relationships may connect tasks anywhere in the same project. This keeps diagrams small while preserving task identity and existing dependencies when work is grouped. Feature views show external links and enforce completion and reopening rules across the project graph. See the [approved feature planning design](../feature-planning.md).

This refines ADR 0001's plan boundary while preserving a single identity and completion history for every task.
