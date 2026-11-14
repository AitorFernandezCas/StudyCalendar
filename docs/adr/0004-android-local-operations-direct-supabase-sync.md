---
status: accepted
date: 2026-11-13
---

# Execute Android operations locally and synchronize directly with Supabase

The personal Android application will execute its daily operations in Kotlin against persistent local data and synchronize directly with the existing Supabase project. The user prioritizes mobile functionality and independence from the Render-hosted Flask API, accepting the additional work of a synchronization contract over reusing Flask as the mobile synchronization endpoint. Kotlin and local persistence support offline operation in either alternative; bypassing Flask specifically removes Render from Android's synchronization path.

Flask initially remains the web backend. Shared business invariants must be enforced centrally in Supabase, with local validation for immediate Android feedback, and web writes must participate in conflict detection. This requires coordinated, compatible changes to database contracts and the existing backend; direct table CRUD protected only by ownership policies is not an adequate substitute for existing business validation.

This is an architectural decision, not a deployment or database change. Existing Supabase ownership protections, the shared project's settings and the repository's migration safeguards remain in force. Network access is still required to exchange changes with the web, and pending local operations can require reconciliation. See the [Android design](../android-planning.md) for scope and unresolved synchronization behavior.
