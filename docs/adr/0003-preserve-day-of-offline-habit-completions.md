---
status: accepted
date: 2026-11-13
---

# Preserve the original habit day when synchronizing offline completions

Android must allow completing daily habits without connectivity. A completion recorded for Monday and synchronized on Tuesday must count toward Monday and recalculate the applicable streaks, rather than be discarded or assigned to the arrival day. This deliberately extends the current same-day-only completion contract to preserve offline work; it does not introduce unrestricted manual history editing.

The user also confirmed that a completion within a valid active period survives a later pause. A remotely deleted habit instead produces a conflict and must not be automatically recreated. Habit days use the account's Europe/Madrid time zone and configured reset time, independently of the phone's current time zone.

Queued actions have no arbitrary expiry and retain the habit day computed when recorded, even if the account's reset time changes before synchronization. They still require valid ownership, an applicable period and successful conflict/domain validation. A user tapping an old notification after its habit day ended must instead see the current app state without automatically completing either day; delayed recording and delayed delivery are different cases.

This is an accepted product decision, not an implemented API change. The technical synchronization contract is proposed in the [Android design](../android-planning.md). The existing web completion endpoint retains its current behavior until a compatible extension is designed and implemented.
