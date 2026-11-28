# StudyCalendar

StudyCalendar organizes daily tasks, project work, and daily habits. These terms describe project planning and daily use.

## Language

**Project plan (plan del proyecto)**:
The project's existing tasks and the prerequisite relationships between them. Feature plans organize portions of this work; prerequisite relationships stay within the project.
_Avoid_: Separate plan tasks, plan template

**Feature (funcionalidad)**:
A named piece of work within one project that groups project tasks. A task may belong to one feature or remain ungrouped within its project.
_Avoid_: Tag, branch

**Feature plan (plan de funcionalidad)**:
The tasks assigned to a feature and their prerequisite relationships, including prerequisites in other features or ungrouped work within the same project. These are the same tasks and completion history used throughout the project.
_Avoid_: Task copies, alternative plan

**Feature prerequisite (funcionalidad previa)**:
A feature that precedes another feature in the same project's plan. Task prerequisites govern whether an individual task can be completed.

**Ungrouped project work (sin funcionalidad)**:
Tasks belonging to a project but not assigned to any feature within it.
_Avoid_: Sin proyecto, unscheduled task

**Prerequisite task (tarea previa)**:
A task whose completion is required before a dependent task can be completed. A dependent task can have several prerequisite tasks, all of which are required.
_Avoid_: Previous task when it only means an earlier date or position in a list

**Dependent task (tarea dependiente)**:
A task that requires one or more prerequisite tasks to be completed.

**Blocked task (tarea bloqueada)**:
A pending task with at least one unfinished prerequisite. It can be edited and scheduled, but cannot be completed.

**Unscheduled task (tarea sin fecha)**:
A project task with no assigned calendar date, whether or not it has been completed. It may belong to a project or be unassigned.
_Avoid_: All-day task, overdue task

**All-day task (tarea de todo el día)**:
A task assigned to a calendar date without a specific time slot on that date. It remains dated work, unlike an unscheduled task.
_Avoid_: Unscheduled task, daily habit

**Timed task (tarea con hora)**:
A task assigned a start and end time on the same calendar date. Its duration is the interval between those times.
_Avoid_: Reminder time

**Reminder (recordatorio)**:
An optional notice associated with a task or daily habit, omitted when the corresponding task or day's habit is already complete. A habit's reminder time is independent of the habit itself and does not make it a scheduled calendar task.
_Avoid_: Habit schedule, calendar habit

**Habit day (día de hábitos)**:
The date to which a daily habit's completion belongs, determined by the account's time zone and configured day boundary. It does not change merely because the phone moves to another time zone.
_Avoid_: Phone date when it differs from the habit day

**Completed habit day (completada)**:
A habit day for which the routine has a completion mark.

**Missed habit day (sin completar)**:
A past habit day on which the routine was due and has no completion mark.

**Pending today (pendiente hoy)**:
A routine that is due on the current habit day and has no completion mark for that day.

**Paused habit day (en pausa)**:
A habit day during a break after a routine has started, with no completion mark for that day.

**Reminder postponement (aplazamiento de un recordatorio)**:
Moving a reminder to a later time without changing the associated task's schedule or the habit day to which it belongs.
_Avoid_: Rescheduling the task, moving the habit to another day
