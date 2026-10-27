import { useEffect, useId, useMemo, useRef, useState } from 'react'
import type { Session } from '@supabase/supabase-js'
import { bootstrapApi, calendarApi, categoriesApi, projectsApi, routinesApi, tasksApi, type ApiCategory, type ApiProject, type ApiRoutine, type ApiTask, type ProjectStatus } from './lib/api'
import { CalendarTimeGrid } from './components/CalendarTimeGrid'
import type { Task, TaskType } from './lib/taskTypes'
import type { TaskSchedule } from './lib/calendarDrag'
import { ActivityHeatmap } from './components/ActivityHeatmap'
import { supabase, authCallbackFailed } from './lib/supabase'
import { taskSchedule, droppedTaskSchedule } from './lib/taskScheduling'
import { useScreenRoute, type Screen } from './lib/navigation'
import { useRoutineHabits } from './lib/useRoutineHabits'
import { useSettings } from './lib/useSettings'
import { PreferencesForm } from './components/PreferencesForm'
import { calendarWeekStart, visibleTimedTask } from './lib/calendarPreferences'
import { visibleOverviewTasks } from './lib/projectVisibility'
import { AuthPanel, PasswordRecovery } from './components/AuthPanel'
import { authErrorMessage } from './lib/authFeedback'
import { FormDialog, ColorPicker, DeleteIcon } from './components/FormDialog'
import { filterOverviewTasks, groupDailyTasks, relativeTaskDate, startWithDuration } from './lib/overview'
import { useStoredSections } from './lib/useStoredSections'
import { useLocalDate } from './lib/useLocalDate'

const projectStatuses: { value: ProjectStatus; label: string; group: string }[] = [
  { value: 'active', label: 'Activo', group: 'Activos' },
  { value: 'inactive', label: 'Inactivo', group: 'Inactivos' },
  { value: 'completed', label: 'Completado', group: 'Completados' },
]

type Category = ApiCategory
const colors = ['#7c5cff', '#ee7b6f', '#f2b84b', '#3fba91', '#4f8ff7']
const pad = (n: number) => String(n).padStart(2, '0')
const iso = (d: Date) => String(d.getFullYear()) + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
const toMinutes = (value: string) => { const [hour, minute] = value.split(':').map(Number); return hour * 60 + minute }
const fromApi = (task: ApiTask): Task => ({ id: task.id, title: task.title, categoryId: task.category_id, projectId: task.project_id, taskType: task.task_type, date: task.date, time: task.start_time.slice(0, 5), endTime: task.end_time.slice(0, 5), color: task.color, done: task.completed, allDay: task.all_day ?? false })
const toApi = (task: Omit<Task, 'id'>) => ({ title: task.title, category_id: task.categoryId, project_id: task.projectId, task_type: task.taskType, date: task.date, ...taskSchedule(task), color: task.color, completed: task.done })

const compareDayTasks = (a: Task, b: Task) => Number(b.allDay) - Number(a.allDay) || (a.allDay ? a.title.localeCompare(b.title, 'es') : a.time.localeCompare(b.time)) || a.id.localeCompare(b.id)
const taskTimeLabel = (task: Task) => task.allDay ? 'Todo el día' : task.time + ' — ' + task.endTime
const focusFormField = (form: string, name: string) => requestAnimationFrame(() => document.querySelector<HTMLInputElement | HTMLSelectElement>(form + ' [name="' + name + '"]')?.focus())

function NavigationIcon({ type }: { type: 'tasks' | 'calendar' | 'settings' | 'projects' | 'routines' }) {
  return <span className="nav-icon" aria-hidden="true"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" focusable="false">
    {type === 'tasks' ? <>
      <path d="m3 6 1.5 1.5L7 5m-4 7 1.5 1.5L7 11m-4 7 1.5 1.5L7 17" />
      <path d="M11 6h10M11 12h10M11 18h10" />
    </> : type === 'projects' ? <>
      <path d="M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" />
    </> : type === 'routines' ? <>
      <path d="M20 7a8 8 0 0 0-14-1L3 9m0-5v5h5M4 17a8 8 0 0 0 14 1l3-3m0 5v-5h-5" />
    </> : type === 'settings' ? <>
      <path d="m9.5 3-.5 2-2 1-2-.5-2.5 4 1.5 1.5v2L2.5 14.5l2.5 4 2-.5 2 1 .5 2h5l.5-2 2-1 2 .5 2.5-4-1.5-1.5v-2l1.5-1.5-2.5-4-2 .5-2-1-.5-2Z" />
      <circle cx="12" cy="12" r="3" />
    </> : <>
      <rect x="3" y="5" width="18" height="16" rx="2" />
      <path d="M7 3v4m10-4v4M3 9h18M7 13h2m4 0h2M7 17h2m4 0h2" />
    </>}
  </svg></span>
}

function MobileNavigation({ screen, onNavigate }: { screen: Screen; onNavigate: (screen: Screen) => void }) {
  return <nav className="mobile-navigation" aria-label="Navegación principal">
    {([
      { screen: 'overview', icon: 'tasks', label: 'Tareas' },
      { screen: 'calendar', icon: 'calendar', label: 'Calendario' },
      { screen: 'projects', icon: 'projects', label: 'Proyectos' },
      { screen: 'routines', icon: 'routines', label: 'Rutinas' },
    ] as const).map(item => <button key={item.screen} className={screen === item.screen ? 'active' : ''} aria-current={screen === item.screen ? 'page' : undefined} onClick={() => onNavigate(item.screen)}>
      <NavigationIcon type={item.icon} /><span>{item.label}</span>
    </button>)}
  </nav>
}

function TaskCard({ task, categoryName, onEdit, onToggle, onDelete, draggable = true, moveHandle, busy = false }: { task: Task; categoryName: string; onEdit: (task: Task) => void; onToggle: (id: string) => void; onDelete: (id: string) => void; draggable?: boolean; moveHandle?: React.ReactNode; busy?: boolean }) {
  return <article draggable={draggable && !busy} onDragStart={e => e.dataTransfer.setData('task', task.id)} onClick={e => { e.stopPropagation(); if (!busy) onEdit(task) }} className={'task-card ' + (task.done ? 'done' : '')} style={{ '--task-color': task.color } as React.CSSProperties}>
    {moveHandle}<div className="task-time">{taskTimeLabel(task)}</div>
    <button disabled={busy} draggable={draggable} className="task-title task-edit-button" title={task.title} aria-label={'Editar tarea ' + task.title} onClick={e => { e.stopPropagation(); onEdit(task) }}>{task.title}</button>
    <div className="task-meta"><span>{categoryName}</span><div className="task-actions">
      <button disabled={busy} aria-pressed={task.done} aria-label={(task.done ? 'Marcar como pendiente ' : 'Marcar como completada ') + task.title} onClick={e => { e.stopPropagation(); onToggle(task.id) }}><CompletionIcon completed={task.done} /></button>
      <button disabled={busy} className="delete-task" title="Eliminar tarea" aria-label={'Eliminar tarea ' + task.title} onClick={e => { e.stopPropagation(); onDelete(task.id) }}><DeleteIcon /></button>
    </div></div>
  </article>
}

function MobileCalendarAgenda({ days, label, hidden, today, loading, visible, categoryName, onNew, onEdit, onToggle, onDelete }: {
  days: Date[]
  label: string
  hidden: boolean
  today: string
  loading: boolean
  visible: (date: string) => Task[]
  categoryName: (id: string | null) => string
  onNew: (date: string) => void
  onEdit: (task: Task) => void
  onToggle: (id: string) => void
  onDelete: (id: string) => void
}) {
  return <section className="mobile-week-calendar" aria-label={label} hidden={hidden}>
    {loading ? <div className="calendar-loading" role="status">Cargando tareas…</div> : days.map(day => {
      const date = iso(day)
      const label = day.toLocaleDateString('es-ES', { weekday: 'long', day: 'numeric', month: 'short' })
      const dayTasks = visible(date)
      return <section key={date} className={'agenda-day ' + (date === today ? 'today' : '')}>
        <div className="agenda-day-head"><h2><time dateTime={date}>{label}</time>{date === today && <span>Hoy</span>}</h2>
          <button aria-label={'Nueva tarea para el ' + label} onClick={() => onNew(date)}>＋</button>
        </div>
        {dayTasks.length ? <div className="agenda-tasks">{dayTasks.map(task => <TaskCard key={task.id} task={task} categoryName={categoryName(task.categoryId)} onEdit={onEdit} onToggle={onToggle} onDelete={onDelete} />)}</div> : <p className="agenda-empty">Sin tareas.</p>}
      </section>
    })}
  </section>
}

function AllDayTaskCard({ task, onEdit, onToggle, onDelete, draggable = true, moveHandle, busy = false }: { task: Task; onEdit: (task: Task) => void; onToggle: (id: string) => void; onDelete: (id: string) => void; draggable?: boolean; moveHandle?: React.ReactNode; busy?: boolean }) {
  return <article draggable={draggable} onDragStart={e => e.dataTransfer.setData('task', task.id)} className={'all-day-task ' + (task.done ? 'done' : '')} style={{ '--task-color': task.color } as React.CSSProperties}>
    {moveHandle}<button disabled={busy} draggable={draggable} className="all-day-task-title" title={task.title} aria-label={'Editar tarea ' + task.title} onClick={e => { e.stopPropagation(); onEdit(task) }}>{task.title}</button>
    <div className="task-actions"><button disabled={busy} aria-pressed={task.done} aria-label={(task.done ? 'Marcar como pendiente ' : 'Marcar como completada ') + task.title} onClick={e => { e.stopPropagation(); onToggle(task.id) }}><CompletionIcon completed={task.done} /></button><button disabled={busy} className="delete-task" title="Eliminar tarea" aria-label={'Eliminar tarea ' + task.title} onClick={e => { e.stopPropagation(); onDelete(task.id) }}><DeleteIcon /></button></div>
  </article>
}

function CompletionIcon({ completed }: { completed: boolean }) {
  return <svg aria-hidden="true" focusable="false" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="12" cy="12" r="9" fill={completed ? 'currentColor' : 'none'} />
    {completed && <path d="m8 12 2.5 2.5L16 9" stroke="#fff" strokeWidth="2" />}
  </svg>
}

function ActionNotice({ notice, busy, onDismiss, inline = false }: { notice: { message: string; undo?: () => Promise<void> }; busy: boolean; onDismiss: () => void; inline?: boolean }) {
  return <div className={'action-notice' + (inline ? ' inline-notice' : '')}><p role="status">{notice.message}</p>{notice.undo && <button className="undo-action" disabled={busy} onClick={() => void notice.undo?.()}>Deshacer</button>}<button aria-label="Cerrar confirmación" onClick={onDismiss}>×</button></div>
}

function OverviewTask({ task, categoryName, projectName, onEdit, onToggle, onDelete, busy = false }: { task: Task; categoryName: string; projectName?: string; onEdit: (task: Task) => void; onToggle: (id: string) => void; onDelete: (id: string) => void; busy?: boolean }) {
  return <article className={'overview-task ' + (task.done ? 'done' : '')} style={{ '--task-color': task.color } as React.CSSProperties}>
    <button disabled={busy} className="overview-task-main overview-edit-button" aria-label={'Editar tarea ' + task.title} onClick={() => onEdit(task)}><strong title={task.title}>{task.title}</strong><small>{projectName && projectName + ' · '}<time dateTime={task.date} title={new Date(task.date + 'T12:00:00').toLocaleDateString('es-ES', { dateStyle: 'long' })}>{relativeTaskDate(task.date, iso(new Date()))}</time>{' · ' + taskTimeLabel(task)}</small></button><span className="overview-category">{categoryName}</span><div className="task-actions"><button disabled={busy} className="task-completion-button" aria-pressed={task.done} title={task.done ? 'Marcar como pendiente' : 'Marcar como completada'} aria-label={(task.done ? 'Marcar como pendiente ' : 'Marcar como completada ') + task.title} onClick={() => onToggle(task.id)}>
    <CompletionIcon completed={task.done} />
  </button><button disabled={busy} className="delete-task" title="Eliminar tarea" aria-label={'Eliminar tarea ' + task.title} onClick={() => onDelete(task.id)}><DeleteIcon /></button></div></article>
}

function RoutineStreaks({ routine }: { routine: ApiRoutine }) {
  return <span className="routine-streaks"><span>Racha actual: <b>{routine.current_streak}</b> {routine.current_streak === 1 ? 'día' : 'días'}</span><span>Máxima: <b>{routine.max_streak}</b> {routine.max_streak === 1 ? 'día' : 'días'}</span></span>
}

function RoutineLoadState({ habits }: { habits: ReturnType<typeof useRoutineHabits> }) {
  return habits.error ? <div className="routine-load-state" role="alert"><p>{habits.error}</p><button onClick={() => void habits.refresh()}>Reintentar</button></div> : <p className="overview-empty" role="status">Cargando rutinas…</p>
}

function DailyHabits({ habits, busy, onComplete }: { habits: ReturnType<typeof useRoutineHabits>; busy: boolean; onComplete: (routine: ApiRoutine) => void }) {
  if (!habits.ready) return <RoutineLoadState habits={habits} />
  const due = habits.routines.filter(routine => routine.due_today).sort((a, b) => a.title.localeCompare(b.title, 'es') || a.id.localeCompare(b.id))
  if (!due.length) return <p className="overview-empty">No hay rutinas para hoy.</p>
  return <div className="daily-habits">{habits.error && <RoutineLoadState habits={habits} />}{[false, true].map(completed => {
    const group = due.filter(routine => routine.completed_today === completed)
    return <section className="habit-group" key={String(completed)}><h4>{completed ? 'Completadas hoy' : 'Pendientes de hoy'} <span>{group.length}</span></h4>
      {group.length ? <div className="overview-list">{group.map(routine => <article className={'overview-task daily-habit ' + (completed ? 'done' : '')} key={routine.id} style={{ '--task-color': routine.color } as React.CSSProperties}>
        <div className="overview-task-main"><strong>{routine.title}</strong><RoutineStreaks routine={routine} /></div>
        <button className="habit-completion" disabled={busy} aria-pressed={completed} title={completed ? 'Marcar como pendiente' : 'Marcar como completada'} aria-label={(completed ? 'Marcar como pendiente ' : 'Marcar como completada ') + routine.title} onClick={() => onComplete(routine)}><CompletionIcon completed={completed} /></button>
      </article>)}</div> : <p className="overview-empty">{completed ? 'Todavía no hay rutinas completadas hoy.' : 'Has completado todas las rutinas de hoy.'}</p>}
    </section>
  })}</div>
}

type ProjectListProps = {
  tasks: Task[]
  projects: ApiProject[]
  categoryName: (id: string | null) => string
  onEdit: (task: Task) => void
  onToggle: (id: string) => void
  onDelete: (id: string) => void
  onNewProject: () => void
  onEditProject: (project: ApiProject) => void
  onDeleteProject: (project: ApiProject) => void
  onChangeProjectStatus?: (project: ApiProject, status: ProjectStatus) => void
  projectBusy?: boolean
}

function CollapsibleSection({ title, count, countLabel, expanded, onToggle, className, notice, children }: {
  title: string
  count: number | string
  countLabel?: string
  expanded: boolean
  onToggle: () => void
  className: string
  notice?: React.ReactNode
  children: React.ReactNode
}) {
  const contentId = useId()
  return <section className={'overview-section collapsible-section ' + className}>
    <div className="overview-section-head collapsible-section-head"><h3>
      <button className="section-toggle" aria-expanded={expanded} aria-controls={contentId} onClick={onToggle}>
        <span className="section-title">{title}</span>
        <span className="section-count" aria-label={countLabel ? count + ' ' + countLabel.toLowerCase() : undefined}>{count}</span>
        <svg className="project-chevron" aria-hidden="true" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="m6 8 4 4 4-4" /></svg>
      </button>
    </h3></div>
    {notice}
    <div id={contentId} hidden={!expanded}>{children}</div>
  </section>
}

function ProjectTaskGroups({ tasks, history, renderTask }: { tasks: Task[]; history: boolean; renderTask: (task: Task) => React.ReactNode }) {
  const pending = tasks.filter(task => !task.done)
  if (!history) return pending.length
    ? <div className="overview-list">{pending.map(renderTask)}</div>
    : <p className="overview-empty">{tasks.length ? 'No hay tareas pendientes.' : 'Aún no hay tareas asociadas.'}</p>
  const completed = tasks.filter(task => task.done)
  return <div className="project-task-groups">
    <section className="project-task-group"><h5>Pendientes <span>{pending.length}</span></h5>
      {pending.length ? <div className="overview-list">{pending.map(renderTask)}</div> : <p className="overview-empty">No hay tareas pendientes.</p>}
    </section>
    <section className="project-task-group"><h5>Completadas <span>{completed.length}</span></h5>
      {completed.length ? <div className="overview-list">{completed.map(renderTask)}</div> : <p className="overview-empty">No hay tareas completadas.</p>}
    </section>
  </div>
}

function ProjectList({ tasks, projects, categoryName, onEdit, onToggle, onDelete, onEditProject, onDeleteProject, onChangeProjectStatus, projectBusy, history = false, statusFilter, showUnassigned = true, onlyUnassigned = false, displayTasks }: ProjectListProps & { history?: boolean; statusFilter?: ProjectStatus; showUnassigned?: boolean; onlyUnassigned?: boolean; displayTasks?: Task[] }) {
  const [expandedProjects, setExpandedProjects] = useState<Set<string>>(() => new Set())
  const projectTasks = useMemo(() => tasks.filter(task => task.taskType === 'project').sort((a, b) => a.date.localeCompare(b.date) || compareDayTasks(a, b)), [tasks])
  const projectById = useMemo(() => new Map(projects.map(project => [project.id, project])), [projects])
  const displayedTasks = displayTasks ? displayTasks.filter(task => task.taskType === 'project') : projectTasks
  const unassigned = displayedTasks.filter(task => !task.projectId || !projectById.has(task.projectId))
  const listedProjects = statusFilter ? projects.filter(project => project.status === statusFilter) : projects
  const renderTask = (task: Task) => <OverviewTask key={task.id} task={task} busy={projectBusy} categoryName={categoryName(task.categoryId)} projectName={task.projectId ? projectById.get(task.projectId)?.name : 'Sin proyecto'} onEdit={onEdit} onToggle={onToggle} onDelete={onDelete} />
  return <>
    {!onlyUnassigned && (listedProjects.length ? <div className="project-list">{listedProjects.map(project => {
      const associated = projectTasks.filter(task => task.projectId === project.id)
      const completed = associated.filter(task => task.done).length
      const pending = associated.length - completed
      const expanded = expandedProjects.has(project.id)
      const contentId = 'project-content-' + project.id
      const progress = associated.length ? Math.round(completed / associated.length * 100) : 0
      return <article className="project-card" key={project.id} style={{ '--project-color': project.color } as React.CSSProperties}>
        <h4 className="project-summary-heading"><button className="project-summary" aria-expanded={expanded} aria-controls={contentId} onClick={() => setExpandedProjects(current => {
          const next = new Set(current)
          if (next.has(project.id)) next.delete(project.id)
          else next.add(project.id)
          return next
        })}>
          <span className="project-title"><i aria-hidden="true" /><span className="project-name">{project.name}</span></span>
          <span className="project-pending">{pending} {pending === 1 ? 'pendiente' : 'pendientes'}</span>
          <svg className="project-chevron" aria-hidden="true" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="m6 8 4 4 4-4" /></svg>
        </button></h4>
        {onChangeProjectStatus && <label className="project-status-control">Estado
          <select aria-label={'Estado de ' + project.name} value={project.status} disabled={projectBusy} onChange={event => onChangeProjectStatus(project, event.target.value as ProjectStatus)}>
            {projectStatuses.map(status => <option key={status.value} value={status.value}>{status.label}</option>)}
          </select>
        </label>}
        <div id={contentId} hidden={!expanded}>
          <div className="project-card-head">
            <div className="project-card-actions"><span className="project-completed" aria-label={completed + ' de ' + associated.length + ' tareas completadas'}>{completed} de {associated.length} completadas</span>
              <button className="project-edit-button" aria-label={'Editar ' + project.name} title="Editar proyecto" onClick={() => onEditProject(project)}>
                <svg aria-hidden="true" focusable="false" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <path d="m16 4 4 4M4 20l4.5-1L20 7.5a2.8 2.8 0 0 0-4-4L4.5 15Z" />
                </svg>
              </button>
              <button className="project-delete-button" aria-label={'Eliminar ' + project.name} title="Eliminar proyecto" onClick={() => onDeleteProject(project)}>
                <svg aria-hidden="true" focusable="false" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M3 6h18M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2M5 6l1 14a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1l1-14M10 10v7m4-7v7" />
                </svg>
              </button>
            </div>
          </div>
          <div className="project-progress" role="progressbar" aria-label={'Progreso de ' + project.name} aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100}><i style={{ width: progress + '%' }} /></div>
          <ProjectTaskGroups tasks={displayedTasks.filter(task => task.projectId === project.id)} history={history} renderTask={renderTask} />
        </div>
      </article>
    })}</div> : <p className="overview-empty">{statusFilter ? 'No hay proyectos ' + projectStatuses.find(status => status.value === statusFilter)?.group.toLowerCase() + '.' : 'No hay proyectos activos.'}</p>)}
    {showUnassigned && unassigned.some(task => history || !task.done) && <section className="unassigned-project"><h4>Sin proyecto</h4><ProjectTaskGroups tasks={unassigned} history={history} renderTask={renderTask} /></section>}
  </>
}

function ProjectsView({ onBack, ...props }: ProjectListProps & { onBack: () => void }) {
  const [expandedGroups, setExpandedGroups] = useState<Set<ProjectStatus>>(() => new Set(['active', 'inactive', 'completed']))
  return <section className="projects-view">
    <div className="projects-toolbar"><button className="back-to-overview" onClick={onBack}>← Volver a todas las tareas</button><button className="new-project-button" onClick={props.onNewProject}>＋ Nuevo proyecto</button></div>
    <p className="projects-copy">Cambia el estado de tus proyectos. Pulsa un proyecto para ver sus tareas y su historial.</p>
    <div className="project-status-sections">{projectStatuses.map(status => <CollapsibleSection key={status.value} title={status.group} count={props.projects.filter(project => project.status === status.value).length} countLabel="Proyectos" className={'project project-state-' + status.value} expanded={expandedGroups.has(status.value)} onToggle={() => setExpandedGroups(current => {
      const next = new Set(current)
      if (next.has(status.value)) next.delete(status.value)
      else next.add(status.value)
      return next
    })}>
      <ProjectList {...props} history statusFilter={status.value} showUnassigned={false} onChangeProjectStatus={(project, nextStatus) => {
        setExpandedGroups(current => new Set([...current, nextStatus]))
        props.onChangeProjectStatus?.(project, nextStatus)
      }} />
    </CollapsibleSection>)}</div>
    <ProjectList {...props} history onlyUnassigned />
  </section>
}

type RoutineListProps = {
  routines: ApiRoutine[]
  onEditRoutine: (routine: ApiRoutine) => void
  onToggleRoutine: (routine: ApiRoutine) => void
  onDeleteRoutine: (routine: ApiRoutine) => void
  busy: boolean
  activation: ReturnType<typeof useRoutineHabits>['activation']
  activationError: ReturnType<typeof useRoutineHabits>['activationError']
  changedId: string | null
  focusId: string | null
}

function RoutineList({ routines, onEditRoutine, onToggleRoutine, onDeleteRoutine, busy, activation, activationError, changedId }: RoutineListProps) {
  const sorted = useMemo(() => [...routines].sort((a, b) => a.title.localeCompare(b.title, 'es') || a.id.localeCompare(b.id)), [routines])
  return <div className="overview-list">{sorted.map(routine => <article key={routine.id} aria-busy={activation?.id === routine.id} className={'overview-task routine-definition ' + (!routine.active ? 'inactive ' : '') + (changedId === routine.id ? 'routine-arrived' : '')} style={{ '--task-color': routine.color } as React.CSSProperties}>
    <div className="routine-content"><button className="routine-edit-button overview-task-main" disabled={busy} aria-label={'Editar rutina ' + routine.title} onClick={() => onEditRoutine(routine)}><strong>{routine.title}</strong><RoutineStreaks routine={routine} /></button>{activationError?.id === routine.id && <p className="routine-action-error" role="alert">{activationError.message}</p>}</div>
    <span className="routine-status" role="status">{activation?.id === routine.id ? <><i className="routine-spinner" aria-hidden="true" />{activation.active ? 'Activando…' : 'Pausando…'}</> : routine.active ? 'Activa' : 'Inactiva'}</span>
    <div className="task-actions"><button data-routine-toggle={routine.id} disabled={busy} aria-pressed={routine.active} title={routine.active ? 'Pausar rutina' : 'Activar rutina'} aria-label={(routine.active ? 'Pausar rutina ' : 'Activar rutina ') + routine.title} onClick={() => onToggleRoutine(routine)}>{routine.active ? 'Ⅱ' : '▷'}</button><button disabled={busy} className="delete-task" title="Eliminar rutina" aria-label={'Eliminar rutina ' + routine.title} onClick={() => onDeleteRoutine(routine)}><DeleteIcon /></button></div>
  </article>)}</div>
}

function RoutinesView({ onBack, habits, userId, ...props }: RoutineListProps & { onBack: () => void; habits: ReturnType<typeof useRoutineHabits>; userId: string }) {
  const { expanded: expandedGroups, setExpanded: setExpandedGroups, toggle: toggleGroup } = useStoredSections('routine-sections:' + userId, ['true'])
  const rootRef = useRef<HTMLElement>(null)
  const restoredRef = useRef(false)
  useEffect(() => {
    if (props.busy) { restoredRef.current = false; return }
    if (!props.focusId || restoredRef.current) return
    const routine = props.routines.find(item => item.id === props.focusId)
    if (!routine) return
    if (!expandedGroups.has(String(routine.active))) {
      setExpandedGroups(current => new Set(current).add(String(routine.active)))
      return
    }
    const button = [...(rootRef.current?.querySelectorAll<HTMLButtonElement>('[data-routine-toggle]') || [])].find(item => item.dataset.routineToggle === props.focusId)
    if (button) { button.focus({ preventScroll: true }); restoredRef.current = true }
  }, [props.busy, props.focusId, props.routines, expandedGroups])
  return <section className="routines-view" ref={rootRef}>
    <button className="back-to-overview" onClick={onBack}>← Volver a todas las tareas</button>
    {habits.error && <RoutineLoadState habits={habits} />}
    <div className="routine-sections">{[true, false].map(active => {
      const routines = props.routines.filter(routine => routine.active === active)
      const pending = routines.filter(routine => routine.due_today && !routine.completed_today).length
      const totalLabel = routines.length + (routines.length === 1 ? ' rutina' : ' rutinas')
      return <CollapsibleSection key={String(active)} title={active ? 'Activas' : 'Inactivas'} className="routine" count={active ? totalLabel + ' · ' + pending + (pending === 1 ? ' pendiente' : ' pendientes') : totalLabel} expanded={expandedGroups.has(String(active))} onToggle={() => toggleGroup(String(active))}>
        {routines.length ? <RoutineList {...props} routines={routines} /> : <p className="overview-empty">{active ? 'No hay rutinas activas.' : 'No hay rutinas inactivas.'}</p>}
      </CollapsibleSection>
    })}</div>
  </section>
}

function TaskOverview({ habits, busy, onCompleteRoutine, onOpenRoutines, onOpenProjects, onNewTask, onNewRoutine, userId, today, ...props }: ProjectListProps & {
  habits: ReturnType<typeof useRoutineHabits>
  busy: boolean
  onCompleteRoutine: (routine: ApiRoutine) => void
  onOpenProjects: () => void
  onOpenRoutines: () => void
  onNewTask: () => void
  onNewRoutine: () => void
  userId: string
  today: string
}) {
  const { expanded: expandedSections, toggle: toggleSection } = useStoredSections('overview-sections:' + userId, ['daily', 'routine', 'project'])
  const activeProjects = props.projects.filter(project => project.status === 'active')
  const visibleTasks = visibleOverviewTasks(props.tasks, props.projects)
  const pendingTasks = filterOverviewTasks(visibleTasks, { query: '', category: '', status: 'pending', sort: 'date' })
  const dailyTasks = pendingTasks.filter(task => task.taskType === 'daily')
  const projectTasks = pendingTasks.filter(task => task.taskType === 'project')
  const pendingToday = visibleTasks.filter(task => !task.done && task.date === today).length
  const overdue = visibleTasks.filter(task => !task.done && task.date < today).length
  const routineCount = habits.ready ? habits.routines.filter(routine => routine.due_today && !routine.completed_today).length : null
  return <section className="task-overview">
    <div className="today-summary"><div><h2>Tu plan para hoy</h2><p>{pendingToday} {pendingToday === 1 ? 'tarea pendiente' : 'tareas pendientes'} · {routineCount === null ? 'Cargando rutinas…' : routineCount + (routineCount === 1 ? ' rutina pendiente' : ' rutinas pendientes')}</p></div>
      {overdue > 0 && <span className="overdue-summary">{overdue} {overdue === 1 ? 'tarea atrasada' : 'tareas atrasadas'}</span>}
    </div>
    <div className="overview-sections">
      <CollapsibleSection title="Tus tareas" className="daily" count={dailyTasks.length} countLabel="Tareas mostradas" expanded={expandedSections.has('daily')} onToggle={() => toggleSection('daily')}>
        {dailyTasks.length ? groupDailyTasks(dailyTasks, today).filter(group => group.tasks.length).map(group => <section className={'date-group ' + group.id} key={group.id}>
          <h4>{group.title}<span>{group.tasks.length}</span></h4><div className="overview-list">{group.tasks.map(task => <OverviewTask key={task.id} task={task} busy={busy} categoryName={props.categoryName(task.categoryId)} onEdit={props.onEdit} onToggle={props.onToggle} onDelete={props.onDelete} />)}</div>
        </section>) : <div className="empty-state"><p>No tienes tareas pendientes. Añade lo que quieras preparar.</p><button className="new-project-button" disabled={busy} onClick={onNewTask}>＋ Crear una tarea</button></div>}
      </CollapsibleSection>
      <CollapsibleSection title="Tareas de proyectos" className="project" count={projectTasks.length} countLabel="Tareas de proyecto mostradas" expanded={expandedSections.has('project')} onToggle={() => toggleSection('project')}>
        <div className="section-actions"><button className="back-to-overview" onClick={onOpenProjects}>Ver proyectos →</button><button className="new-project-button" onClick={props.onNewProject}>＋ Nuevo proyecto</button></div>
        <ProjectList {...props} projectBusy={busy} tasks={visibleTasks} displayTasks={projectTasks} projects={activeProjects} />
        {!activeProjects.length && <button className="new-project-button" disabled={busy} onClick={props.onNewProject}>＋ Crear mi primer proyecto</button>}
      </CollapsibleSection>
      <CollapsibleSection title="Rutinas de hoy" className="routine" count={routineCount ?? '—'} countLabel="Rutinas pendientes de hoy" expanded={expandedSections.has('routine')} onToggle={() => toggleSection('routine')} notice={!expandedSections.has('routine') && habits.error ? <RoutineLoadState habits={habits} /> : undefined}>
        <div className="section-actions"><button className="back-to-overview" onClick={onOpenRoutines}>Ver rutinas →</button></div>
        <DailyHabits habits={habits} busy={busy} onComplete={onCompleteRoutine} />
        {habits.ready && !habits.routines.length && <button className="new-project-button" disabled={busy} onClick={onNewRoutine}>＋ Crear mi primera rutina</button>}
      </CollapsibleSection>
    </div>
  </section>
}

function SettingsView({ categories, ready, loading, error, busy, onEdit, onDelete, onRetry }: {
  categories: Category[]
  ready: boolean
  loading: boolean
  error: string
  busy: boolean
  onEdit: (category: Category) => void
  onDelete: (category: Category) => void
  onRetry: () => void
}) {
  return <section className="settings-view" aria-label="Gestión de categorías"><div className="settings-card">
    <h2>Categorías</h2><p className="settings-description">Organiza tus tareas con nombres y colores.</p>
    {!ready ? loading ? <p role="status" className="settings-empty">Cargando categorías…</p> : <div className="settings-empty" role="alert"><p>{error || 'No se pudieron cargar las categorías.'}</p><button className="cancel" onClick={onRetry}>Reintentar</button></div>
      : categories.length ? <ul className="settings-categories">{[...categories].sort((a, b) => a.name.localeCompare(b.name, 'es')).map(category => <li key={category.id}>
        <div className="settings-category-name"><i style={{ background: category.color }} aria-hidden="true" /><span>{category.name}</span></div>
        <div className="settings-category-actions"><button disabled={busy} aria-label={'Editar categoría ' + category.name} onClick={() => onEdit(category)}>Editar</button><button className="settings-delete" disabled={busy} aria-label={'Eliminar categoría ' + category.name} onClick={() => onDelete(category)}>Eliminar</button></div>
      </li>)}</ul> : <p className="settings-empty">Todavía no tienes categorías. Añade la primera con «Nueva categoría».</p>}
  </div></section>
}

export function CalendarApp({ session, initialNotice }: { session: Session; initialNotice?: string }) {
  const settings = useSettings(session)
  const firstDay = settings.data?.week_start || 'monday'
  const calendarStart = toMinutes(settings.data?.calendar_start_time || '00:00')
  const calendarEnd = toMinutes(settings.data?.calendar_end_time || '24:00')
  const [calendarDate, setCalendarDate] = useState(() => new Date())
  const weekStart = useMemo(() => calendarWeekStart(calendarDate, firstDay), [calendarDate, firstDay])
  const [headerCompact, setHeaderCompact] = useState(() => window.scrollY > 56)
  const [tasks, setTasks] = useState<Task[]>([])
  const [overviewTasks, setOverviewTasks] = useState<Task[]>([])
  const [overviewLoaded, setOverviewLoaded] = useState(false)
  const [overviewLoading, setOverviewLoading] = useState(false)
  const [initialDataLoaded, setInitialDataLoaded] = useState(false)
  const [bootstrapAttempt, setBootstrapAttempt] = useState(0)
  const habits = useRoutineHabits(session, settings.data?.routine_reset_time)
  const routines = habits.routines
  const [categories, setCategories] = useState<Category[]>([])
  const [projects, setProjects] = useState<ApiProject[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [selectedRoutine, setSelectedRoutine] = useState<string | null>(null)
  const [isModal, setIsModal] = useState(false)
  const [categoryModal, setCategoryModal] = useState(false)
  const [categoryError, setCategoryError] = useState('')
  const [projectError, setProjectError] = useState('')
  const [taskError, setTaskError] = useState('')
  const [notice, setNotice] = useState<{ message: string; undo?: () => Promise<void> } | null>(initialNotice ? { message: initialNotice } : null)
  const [categorySelected, setCategorySelected] = useState<string | null>(null)
  const [projectModal, setProjectModal] = useState(false)
  const [dayAgenda, setDayAgenda] = useState<string | null>(null)
  const [projectSelected, setProjectSelected] = useState<string | null>(null)
  const [filter, setFilter] = useState('Todas')
  const [view, setView] = useState<'day' | 'week' | 'month'>('week')
  const [mobileTimeline, setMobileTimeline] = useState(false)
  const [screen, setScreen] = useScreenRoute()
  const [loading, setLoading] = useState(true)
  const [calendarLoading, setCalendarLoading] = useState(false)
  const [slowConnection, setSlowConnection] = useState(false)
  const [readErrors, setReadErrors] = useState({ bootstrap: '', calendar: '', overview: '' })
  const [error, setError] = useState('')
  const [busyAction, setBusyAction] = useState<string | null>(null)
  const [activityRevision, setActivityRevision] = useState(0)
  const [routineFocusId, setRoutineFocusId] = useState<string | null>(null)
  const [routineActive, setRoutineActive] = useState(true)
  const [categoryDraft, setCategoryDraft] = useState({ name: '', color: colors[0] })
  const [projectDraft, setProjectDraft] = useState({ name: '', color: colors[1] })
  const [draft, setDraft] = useState<Omit<Task, 'id'>>({ title: '', categoryId: null, projectId: null, taskType: 'daily', date: iso(new Date()), time: '09:00', endTime: '10:00', color: colors[0], done: false, allDay: true })
  const days = useMemo(() => Array.from({ length: 7 }, (_, i) => { const d = new Date(weekStart); d.setDate(d.getDate() + i); return d }), [weekStart])
  const today = useLocalDate()
  const weekLabel = weekStart.toLocaleDateString('es-ES', { month: 'short', day: 'numeric' }) + ' — ' + days[6].toLocaleDateString('es-ES', { month: 'short', day: 'numeric', year: 'numeric' })
  const monthStart = useMemo(() => new Date(calendarDate.getFullYear(), calendarDate.getMonth(), 1), [calendarDate])
  const monthDays = useMemo(() => { const first = calendarWeekStart(monthStart, firstDay); return Array.from({ length: 42 }, (_, index) => { const day = new Date(first); day.setDate(first.getDate() + index); return day }) }, [monthStart, firstDay])
  const monthLabel = monthStart.toLocaleDateString('es-ES', { month: 'long', year: 'numeric' })
  const dayLabel = calendarDate.toLocaleDateString('es-ES', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
  const displayedDays = useMemo(() => view === 'day' ? [calendarDate] : days, [calendarDate, days, view])
  const visibleRange = useMemo(() => { const range = view === 'month' ? monthDays : displayedDays; return { from: iso(range[0]), to: iso(range[range.length - 1]) } }, [displayedDays, monthDays, view])
  const currentRangeRef = useRef(visibleRange)
  currentRangeRef.current = visibleRange
  const rangeKey = visibleRange.from + ':' + visibleRange.to
  const loadedRangeRef = useRef<string | null>(null)
  const bootstrapRangeRef = useRef<string | null>(null)
  const isReading = loading || calendarLoading || overviewLoading || habits.loading || settings.loading
  const readError = Object.values(readErrors).filter(Boolean).join(' ')
  const retryReads = () => {
    setReadErrors({ bootstrap: '', calendar: '', overview: '' })
    setBootstrapAttempt(attempt => attempt + 1)
    if (habits.error) void habits.refresh()
    if (settings.error) void settings.refresh()
  }
  useEffect(() => {
    setSlowConnection(false)
    if (!isReading) return
    const timer = window.setTimeout(() => setSlowConnection(true), 10_000)
    return () => window.clearTimeout(timer)
  }, [isReading, session.access_token])
  const updateTaskState = (updater: (current: Task[]) => Task[]) => {
    setActivityRevision(value => value + 1)
    setTasks(current => updater(current).filter(task => task.date >= currentRangeRef.current.from && task.date <= currentRangeRef.current.to))
    if (overviewLoaded) setOverviewTasks(updater)
  }
  useEffect(() => {
    const updateHeaderCompact = () => {
      const scrollTop = Math.max(0, window.scrollY)
      // Separate thresholds keep small scroll adjustments from reversing the transition.
      setHeaderCompact(current => current ? scrollTop > 12 : scrollTop > 56)
    }
    window.addEventListener('scroll', updateHeaderCompact, { passive: true })
    updateHeaderCompact()
    return () => window.removeEventListener('scroll', updateHeaderCompact)
  }, [])
  useEffect(() => {
    const controller = new AbortController()
    bootstrapRangeRef.current = rangeKey
    setLoading(true)
    setReadErrors({ bootstrap: '', calendar: '', overview: '' })
    setOverviewLoading(false)
    setOverviewLoaded(false)
    setInitialDataLoaded(false)
    setOverviewTasks([])
    void bootstrapApi.load(session, visibleRange.from, visibleRange.to, controller.signal).then(data => {
      if (controller.signal.aborted) return
      if (currentRangeRef.current.from + ':' + currentRangeRef.current.to === rangeKey) {
        setTasks(data.tasks.filter(task => task.task_type !== 'routine').map(fromApi))
        loadedRangeRef.current = rangeKey
      }
      setCategories(data.categories)
      setProjects(data.projects)
      setInitialDataLoaded(true)
      setError('')
    }).catch(e => { if (controller.signal.aborted) return; setReadErrors(current => ({ ...current, bootstrap: e instanceof Error ? e.message : 'No se pudieron cargar los datos' })) }).finally(() => { if (!controller.signal.aborted) { bootstrapRangeRef.current = null; setLoading(false) } })
    return () => controller.abort()
  }, [session.access_token, bootstrapAttempt])
  useEffect(() => {
    if (loadedRangeRef.current === rangeKey || bootstrapRangeRef.current === rangeKey) return
    const controller = new AbortController()
    setCalendarLoading(true)
    setReadErrors(current => ({ ...current, calendar: '' }))
    void calendarApi.list(session, visibleRange.from, visibleRange.to, controller.signal).then(data => {
      if (controller.signal.aborted) return
      setTasks(data.tasks.filter(task => task.task_type !== 'routine').map(fromApi))
      loadedRangeRef.current = rangeKey
    }).catch(e => { if (controller.signal.aborted) return; setReadErrors(current => ({ ...current, calendar: e instanceof Error ? e.message : 'No se pudieron cargar las tareas' })) }).finally(() => { if (!controller.signal.aborted) setCalendarLoading(false) })
    return () => { controller.abort(); setCalendarLoading(false) }
  }, [rangeKey, session.access_token])
  useEffect(() => {
    if (!initialDataLoaded || overviewLoaded) return
    const controller = new AbortController()
    setOverviewLoading(true)
    setReadErrors(current => ({ ...current, overview: '' }))
    void tasksApi.list(session, undefined, undefined, controller.signal).then(data => { if (!controller.signal.aborted) { setOverviewTasks(data.map(fromApi)); setOverviewLoaded(true) } }).catch(e => { if (controller.signal.aborted) return; setReadErrors(current => ({ ...current, overview: e instanceof Error ? e.message : 'No se pudieron cargar todas las tareas' })) }).finally(() => { if (!controller.signal.aborted) setOverviewLoading(false) })
    return () => { controller.abort(); setOverviewLoading(false) }
  }, [initialDataLoaded, overviewLoaded, session.access_token])
  const allTasks = tasks
  const categoryNames = useMemo(() => new Map(categories.map(category => [category.id, category.name])), [categories])
  const tasksByDate = useMemo(() => {
    const grouped = new Map<string, Task[]>()
    for (const task of allTasks) {
      if (filter !== 'Todas' && (filter === 'Sin categoría' ? task.categoryId !== null : task.categoryId !== filter)) continue
      const dateTasks = grouped.get(task.date) || []
      dateTasks.push(task)
      grouped.set(task.date, dateTasks)
    }
    grouped.forEach(dateTasks => dateTasks.sort(compareDayTasks))
    return grouped
  }, [allTasks, filter])
  const categoryName = (id: string | null) => (id ? categoryNames.get(id) : undefined) || 'Sin categoría'
  const visible = (date: string) => tasksByDate.get(date) || []
  const pendingTaskCount = habits.ready && overviewLoaded ? visibleOverviewTasks(overviewTasks, projects).filter(task => !task.done).length + routines.filter(routine => routine.due_today && !routine.completed_today).length : '…'
  const openNew = (date = iso(new Date()), time = '09:00', taskType: TaskType = 'daily', allDay = true) => {
    if (busyAction || !initialDataLoaded) return
    const endMinutes = Math.min(toMinutes(time) + 60, 23 * 60 + 45)
    setSelected(null)
    setSelectedRoutine(null)
    setRoutineActive(true)
    setDraft({ title: '', categoryId: taskType === 'routine' ? null : categories[0]?.id || null, projectId: null, taskType, date, time, endTime: pad(Math.floor(endMinutes / 60)) + ':' + pad(endMinutes % 60), color: taskType === 'routine' ? colors[0] : categories[0]?.color || colors[0], done: false, allDay })
    setTaskError('')
    setIsModal(true)
  }
  const openNewRoutine = () => { if (habits.ready) openNew(habits.date, '09:00', 'routine') }
  const edit = (task: Task) => { setSelected(task.id); setSelectedRoutine(null); setDraft({ title: task.title, categoryId: task.categoryId, projectId: task.projectId, taskType: task.taskType, date: task.date, time: task.time, endTime: task.endTime, color: task.color, done: task.done, allDay: task.allDay }); setTaskError(''); setIsModal(true) }
  const editRoutine = (routine: ApiRoutine) => { setSelected(null); setSelectedRoutine(routine.id); setRoutineActive(routine.active); setDraft({ title: routine.title, categoryId: null, projectId: null, taskType: 'routine', date: habits.date, time: '09:00', endTime: '10:00', color: routine.color, done: false, allDay: false }); setTaskError(''); setIsModal(true) }
  const save = async () => {
    if (busyAction) return
    const invalid = (message: string, name: string) => { setTaskError(message); focusFormField('.task-modal', name) }
    if (!draft.title.trim()) { invalid('Introduce un título.', 'title'); return }
    if (!draft.date) { invalid('Selecciona una fecha.', 'date'); return }
    if (draft.taskType !== 'routine' && !draft.allDay) {
      const validTime = (time: string) => /^([01]\d|2[0-3]):[0-5]\d$/.test(time)
      if (!validTime(draft.time)) { invalid('Introduce una hora de inicio válida.', 'start'); return }
      if (!validTime(draft.endTime) || toMinutes(draft.endTime) <= toMinutes(draft.time)) { invalid('La hora de finalización debe ser posterior al inicio, dentro del mismo día.', 'end'); return }
    }
    if (draft.taskType === 'project' && !draft.projectId) { invalid('Selecciona un proyecto activo o crea uno nuevo.', 'project'); return }
    const chosenProject = projects.find(project => project.id === draft.projectId)
    const original = tasks.find(task => task.id === selected) || overviewTasks.find(task => task.id === selected)
    if (draft.taskType === 'project' && chosenProject?.status !== 'active' && original?.projectId !== draft.projectId) { invalid('Selecciona un proyecto activo.', 'project'); return }
    setTaskError('')
    setBusyAction('save')
    try {
      if (draft.taskType === 'routine') {
        const payload = { title: draft.title.trim(), color: draft.color, active: routineActive }
        if (selectedRoutine) await routinesApi.update(session, selectedRoutine, payload)
        else await routinesApi.create(session, { ...payload, starts_on: draft.date })
        setIsModal(false)
        await habits.refresh()
        setActivityRevision(value => value + 1)
      } else if (selected) {
        const updatePayload: Partial<Omit<ApiTask, 'id'>> = { title: draft.title.trim(), task_type: draft.taskType, project_id: draft.taskType === 'project' ? draft.projectId : null, date: draft.date, ...taskSchedule(draft), color: draft.color, completed: draft.done }
        if (!original || original.categoryId !== draft.categoryId) updatePayload.category_id = draft.categoryId
        const updated = fromApi(await tasksApi.update(session, selected, updatePayload))
        updateTaskState(prev => [...prev.filter(task => task.id !== selected), updated])
      } else {
        const created = fromApi(await tasksApi.create(session, toApi(draft)))
        updateTaskState(prev => [...prev, created])
      }
      setIsModal(false)
      setError('')
    } catch (e) { setTaskError(e instanceof Error ? e.message : 'No se pudo guardar la tarea'); focusFormField('.task-modal', 'title') } finally { setBusyAction(null) }
  }
  const shift = (delta: number) => { const d = new Date(calendarDate); d.setDate(d.getDate() + delta * (view === 'day' ? 1 : 7)); setCalendarDate(d) }
  const shiftMonth = (delta: number) => { const d = new Date(calendarDate); const day = d.getDate(); d.setDate(1); d.setMonth(d.getMonth() + delta); const last = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate(); d.setDate(Math.min(day, last)); setCalendarDate(d) }
  const setTaskCompletion = async (task: Task, completed: boolean, offerUndo = true) => {
    if (busyAction) return
    const trigger = document.activeElement
    setBusyAction('toggle:' + task.id)
    try {
      const updated = fromApi(await tasksApi.update(session, task.id, { completed }))
      updateTaskState(prev => prev.map(item => item.id === task.id ? updated : item))
      setNotice({ message: offerUndo ? '«' + task.title + '» ' + (completed ? 'completada.' : 'marcada como pendiente.') : 'Cambio deshecho.', undo: offerUndo ? () => setTaskCompletion(updated, task.done, false) : undefined })
      if (offerUndo) requestAnimationFrame(() => { if (trigger instanceof HTMLElement && !trigger.isConnected) document.querySelector<HTMLButtonElement>('.undo-action')?.focus({ preventScroll: true }) })
      setError('')
    } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo actualizar la tarea') } finally { setBusyAction(null) }
  }
  const toggleDone = async (id: string) => {
    const task = allTasks.find(item => item.id === id) || overviewTasks.find(item => item.id === id)
    if (task) await setTaskCompletion(task, !task.done)
  }
  const deleteTask = async (id: string) => {
    const task = allTasks.find(item => item.id === id) || overviewTasks.find(item => item.id === id)
    if (busyAction || !task || !window.confirm('¿Eliminar “' + task.title + '”?')) return
    setBusyAction('delete:' + id)
    try { await tasksApi.remove(session, id); updateTaskState(prev => prev.filter(item => item.id !== id)); setError('') } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo eliminar la tarea') } finally { setBusyAction(null) }
  }
  const moveTask = async (id: string, date: string, destination?: 'allDay' | number) => {
    const task = allTasks.find(item => item.id === id) || overviewTasks.find(item => item.id === id)
    if (!task) return
    const schedule = destination === 'allDay' ? { all_day: true } : typeof destination === 'number' ? droppedTaskSchedule(task, destination) : {}
    if (busyAction) return
    setBusyAction('move:' + id)
    try { const updated = fromApi(await tasksApi.update(session, id, { date, ...schedule })); updateTaskState(prev => [...prev.filter(item => item.id !== id), updated]) } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo mover la tarea') } finally { setBusyAction(null) }
  }
  const rescheduleTask = async (task: Task, schedule: TaskSchedule): Promise<boolean> => {
    if (busyAction) return false
    setBusyAction('move:' + task.id)
    setError('')
    try {
      const updated = fromApi(await tasksApi.update(session, task.id, { date: schedule.date, start_time: schedule.time, end_time: schedule.endTime, all_day: schedule.allDay ?? false }))
      updateTaskState(prev => [...prev.filter(item => item.id !== task.id), updated])
      return true
    } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo guardar el nuevo horario'); return false } finally { setBusyAction(null) }
  }
  const toggleRoutine = async (routine: ApiRoutine) => {
    if (busyAction) return
    setRoutineFocusId(document.activeElement instanceof HTMLElement && document.activeElement.dataset.routineToggle === routine.id ? routine.id : null)
    setBusyAction('routine:' + routine.id)
    try { await habits.setActive(routine.id, !routine.active); setActivityRevision(value => value + 1) } finally { setBusyAction(null) }
  }
  const deleteRoutine = async (routine: ApiRoutine) => {
    if (busyAction || !window.confirm('¿Eliminar la rutina “' + routine.title + '”? También se eliminará su historial.')) return
    setBusyAction('routine:' + routine.id)
    try { await routinesApi.remove(session, routine.id); await habits.refresh(); setActivityRevision(value => value + 1); setError('') } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo eliminar la rutina') } finally { setBusyAction(null) }
  }
  const setRoutineCompletion = async (routine: ApiRoutine, completed: boolean, offerUndo = true) => {
    if (busyAction || !habits.ready) return
    const trigger = document.activeElement
    setBusyAction('completion:' + routine.id)
    setError('')
    try {
      const saved = await habits.complete(routine.id, completed)
      if (!saved) { setNotice(null); setError('Se han actualizado las rutinas. Revisa su estado antes de volver a intentarlo.'); return }
      setActivityRevision(value => value + 1)
      setNotice({ message: offerUndo ? '«' + routine.title + '» ' + (completed ? 'completada.' : 'marcada como pendiente.') : 'Cambio deshecho.', undo: offerUndo ? () => setRoutineCompletion({ ...routine, completed_today: completed }, routine.completed_today, false) : undefined })
      if (offerUndo) requestAnimationFrame(() => { if (trigger instanceof HTMLElement && !trigger.isConnected) document.querySelector<HTMLButtonElement>('.undo-action')?.focus({ preventScroll: true }) })
    } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo guardar el cumplimiento') } finally { setBusyAction(null) }
  }
  const completeRoutine = (routine: ApiRoutine) => setRoutineCompletion(routine, !routine.completed_today)

  const openProject = (project?: ApiProject) => { if (busyAction) return; setProjectSelected(project?.id || null); setProjectDraft({ name: project?.name || '', color: project?.color || colors[1] }); setProjectError(''); setProjectModal(true) }
  const saveProject = async () => {
    if (busyAction) return
    if (!projectDraft.name.trim()) { setProjectError('Introduce un nombre para el proyecto.'); focusFormField('.project-modal', 'name'); return }
    setProjectError('')
    setBusyAction('project')
    const payload = { ...projectDraft, name: projectDraft.name.trim() }
    try {
      if (projectSelected) {
        const updated = await projectsApi.update(session, projectSelected, payload)
        setProjects(prev => prev.map(project => project.id === projectSelected ? updated : project))
      } else {
        const created = await projectsApi.create(session, payload)
        setProjects(prev => [...prev, created].sort((a, b) => a.name.localeCompare(b.name)))
        if (isModal && draft.taskType === 'project') setDraft(current => ({ ...current, projectId: created.id, color: created.color }))
      }
      setProjectModal(false)
      setError('')
    } catch (e) { setProjectError(e instanceof Error ? e.message : 'No se pudo guardar el proyecto'); focusFormField('.project-modal', 'name') } finally { setBusyAction(null) }
  }
  const deleteProject = async (project: ApiProject) => {
    if (busyAction || !window.confirm('¿Eliminar “' + project.name + '”? Sus tareas quedarán sin proyecto.')) return
    setBusyAction('project:' + project.id)
    try { await projectsApi.remove(session, project.id); setProjects(prev => prev.filter(item => item.id !== project.id)); updateTaskState(prev => prev.map(task => task.projectId === project.id ? { ...task, projectId: null } : task)); setError('') } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo eliminar el proyecto') } finally { setBusyAction(null) }
  }
  const changeProjectStatus = async (project: ApiProject, status: ProjectStatus) => {
    if (busyAction || project.status === status) return
    setBusyAction('project-status:' + project.id)
    setError('')
    try {
      const updated = await projectsApi.update(session, project.id, { status })
      setProjects(current => current.map(item => item.id === project.id ? updated : item))
    } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo cambiar el estado del proyecto') } finally { setBusyAction(null) }
  }
  const openCategory = (category?: Category) => { if (busyAction || !initialDataLoaded) return; setCategorySelected(category?.id || null); setCategoryDraft({ name: category?.name || '', color: category?.color || colors[0] }); setCategoryError(''); setCategoryModal(true) }
  const closeCategory = () => { if (!busyAction) setCategoryModal(false) }
  const saveCategory = async () => {
    if (busyAction) return
    if (!categoryDraft.name.trim()) { setCategoryError('Introduce un nombre para la categoría.'); focusFormField('.category-modal', 'name'); return }
    setCategoryError('')
    setBusyAction('category')
    try {
      if (categorySelected) {
        const updated = await categoriesApi.update(session, categorySelected, { ...categoryDraft, name: categoryDraft.name.trim() })
        setCategories(prev => prev.map(category => category.id === categorySelected ? updated : category))
        updateTaskState(prev => prev.map(task => task.categoryId === categorySelected ? { ...task, color: updated.color } : task))
      } else {
        const created = await categoriesApi.create(session, { ...categoryDraft, name: categoryDraft.name.trim() })
        setCategories(prev => [...prev, created].sort((a, b) => a.name.localeCompare(b.name)))
      }
      setCategoryModal(false)
      setError('')
    } catch (e) {
      const message = e instanceof Error ? e.message : 'No se pudo guardar la categoría'
      setCategoryError(message === 'A category with this name already exists' ? 'Ya existe una categoría con este nombre.' : message)
      focusFormField('.category-modal', 'name')
    } finally { setBusyAction(null) }
  }
  const deleteCategory = async (category: Category) => {
    if (busyAction || !window.confirm('¿Eliminar “' + category.name + '”? Sus tareas pasarán a “Sin categoría”.')) return
    setBusyAction('category:' + category.id)
    try { await categoriesApi.remove(session, category.id); setCategories(prev => prev.filter(item => item.id !== category.id)); updateTaskState(prev => prev.map(task => task.categoryId === category.id ? { ...task, categoryId: null } : task)); if (filter === category.id) setFilter('Todas'); setError('') } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo eliminar la categoría') } finally { setBusyAction(null) }
  }

  const modalBusy = Boolean(busyAction)
  const closeTask = () => { if (!modalBusy) setIsModal(false) }
  const closeProject = () => { if (!modalBusy) setProjectModal(false) }
  const selectedProject = projects.find(project => project.id === draft.projectId)
  const selectableProjects = projects.filter(project => project.status === 'active' || (Boolean(selected) && project.id === draft.projectId))
  const taskTitle = draft.taskType === 'routine' ? (selectedRoutine ? 'Editar rutina' : 'Nueva rutina') : selected ? 'Editar tarea' : 'Nueva tarea'
  const newAction = screen === 'settings' ? 'Nueva categoría' : screen === 'routines' ? 'Nueva rutina' : 'Nueva tarea'
  return <div className="app-shell">
    <a className="skip-link" href="#main-content">Ir al contenido</a>
    <aside className="sidebar">
      <div className="brand"><div className="brand-mark">✦</div><span>Aprovecha<span>TuTiempo</span></span></div>
      <nav aria-label="Navegación principal">{([
        { screen: 'overview', icon: 'tasks', label: 'Todas las tareas' },
        { screen: 'calendar', icon: 'calendar', label: 'Calendario' },
        { screen: 'projects', icon: 'projects', label: 'Proyectos' },
        { screen: 'routines', icon: 'routines', label: 'Rutinas' },
      ] as const).map(item => <button key={item.screen} title={item.label} className={'nav-item ' + (screen === item.screen ? 'active' : '')} aria-current={screen === item.screen ? 'page' : undefined} onClick={() => setScreen(item.screen)}>
        <NavigationIcon type={item.icon} /><span className="nav-label">{item.label}</span>{item.screen === 'overview' && <b aria-label={pendingTaskCount === '…' ? 'Cargando pendientes' : pendingTaskCount + ' pendientes'}>{pendingTaskCount}</b>}
      </button>)}</nav>
      <div className="side-bottom">
        <button className={'nav-item settings-nav ' + (screen === 'settings' ? 'active' : '')} aria-current={screen === 'settings' ? 'page' : undefined} title="Configuración" onClick={() => setScreen('settings')}><NavigationIcon type="settings" /><span className="nav-label">Configuración</span></button>
        <div className="user"><div className="avatar">{session.user.email?.slice(0, 2).toUpperCase()}</div><span title={session.user.email}>{session.user.email}<small>Cuenta conectada</small></span><button className="logout" onClick={() => supabase?.auth.signOut()}>Salir</button></div>
      </div>
    </aside>
    <main className="main" id="main-content" tabIndex={-1}>
      <header className={headerCompact ? 'sticky-header compact' : 'sticky-header'}>
        <div><h1>{screen === 'settings' ? 'Configuración' : screen === 'routines' ? 'Rutinas' : screen === 'projects' ? 'Proyectos' : screen === 'overview' ? 'Todas las tareas' : 'Mi calendario'}</h1>
          {(screen === 'settings' || screen === 'routines' || screen === 'projects') && <p className="subtitle">{screen === 'settings' ? 'Personaliza tus rutinas, calendario y categorías.' : screen === 'routines' ? 'Organiza tus rutinas activas e inactivas.' : 'Organiza tus proyectos y consulta sus tareas.'}</p>}
        </div>
        <div className="header-actions">
          <button className="mobile-settings-button" aria-label={screen === 'settings' ? 'Volver a todas las tareas' : 'Configuración'} title={screen === 'settings' ? 'Volver a todas las tareas' : 'Configuración'} onClick={() => setScreen(screen === 'settings' ? 'overview' : 'settings')}>{screen === 'settings' ? <span aria-hidden="true">←</span> : <NavigationIcon type="settings" />}</button>
          <button className="add-button" aria-label={newAction} title={newAction} disabled={modalBusy || !initialDataLoaded || (screen === 'routines' && !habits.ready)} onClick={() => screen === 'settings' ? openCategory() : screen === 'routines' ? openNewRoutine() : openNew(today, '09:00', screen === 'projects' ? 'project' : 'daily')}>＋ <span>{newAction}</span></button>
        </div>
      </header>
      {isReading && slowConnection && <div className="connection-notice" role="status">La conexión está tardando más de lo habitual.</div>}
      {readError && <div className="error-banner read-error-banner" role="alert"><span>{readError}</span><button disabled={loading || calendarLoading || overviewLoading || modalBusy} onClick={retryReads}>Reintentar carga</button></div>}
      {error && <div className="error-banner" role="alert">{error}<button aria-label="Cerrar mensaje de error" onClick={() => setError('')}>×</button></div>}
      {screen === 'settings' ? <>
        <div className="mobile-account"><span>{session.user.email}</span><button onClick={() => supabase?.auth.signOut()}>Cerrar sesión</button></div>
        <div className="settings-view"><PreferencesForm key={session.user.id} settings={settings} /></div>
        <SettingsView categories={categories} ready={initialDataLoaded} loading={loading} error={readError || error} busy={modalBusy} onEdit={openCategory} onDelete={category => void deleteCategory(category)} onRetry={retryReads} />
      </> : screen === 'calendar' ? (!settings.data ? <div className="settings-card" role={settings.error ? 'alert' : 'status'}><p>{settings.error || 'Cargando configuración…'}</p>{settings.error && <button className="cancel" onClick={() => void settings.refresh()}>Reintentar</button>}</div> : <>
        <section className="toolbar">
          <div className="week-nav">
            <button aria-label={view === 'day' ? 'Día anterior' : view === 'month' ? 'Mes anterior' : 'Semana anterior'} onClick={() => view === 'month' ? shiftMonth(-1) : shift(-1)}>‹</button>
            <button onClick={() => setCalendarDate(new Date())}>Hoy</button>
            <button aria-label={view === 'day' ? 'Día siguiente' : view === 'month' ? 'Mes siguiente' : 'Semana siguiente'} onClick={() => view === 'month' ? shiftMonth(1) : shift(1)}>›</button>
            <strong>{view === 'day' ? dayLabel : view === 'month' ? monthLabel : weekLabel}</strong>
          </div>
          <div className="view-tools">
            <select aria-label="Filtrar por categoría" value={filter} onChange={e => setFilter(e.target.value)}><option value="Todas">Todas las categorías</option>{categories.map(category => <option value={category.id} key={category.id}>{category.name}</option>)}<option value="Sin categoría">Sin categoría</option></select>
            {(['day', 'week', 'month'] as const).map(mode => <button key={mode} className={'view-btn ' + (view === mode ? 'active' : '')} aria-pressed={view === mode} onClick={() => setView(mode)}>{mode === 'day' ? 'Día' : mode === 'week' ? 'Semana' : 'Mes'}</button>)}
          </div>
        </section>
        {view !== 'month' ? <>
          <div className="mobile-calendar-layout" role="group" aria-label="Presentación del calendario"><button aria-pressed={!mobileTimeline} onClick={() => setMobileTimeline(false)}>Agenda</button><button aria-pressed={mobileTimeline} onClick={() => setMobileTimeline(true)}>Horario</button></div>
          <MobileCalendarAgenda days={displayedDays} label={view === 'day' ? 'Agenda diaria' : 'Agenda semanal'} hidden={mobileTimeline} today={today} loading={loading || calendarLoading} visible={date => visible(date).filter(task => visibleTimedTask(task, calendarStart, calendarEnd))} categoryName={categoryName} onNew={date => openNew(date)} onEdit={edit} onToggle={toggleDone} onDelete={deleteTask} />
          <CalendarTimeGrid startMinute={calendarStart} endMinute={calendarEnd} days={displayedDays} today={today} daily={view === 'day'} mobileTimeline={mobileTimeline} loading={loading || calendarLoading} busy={modalBusy} tasks={Array.from(tasksByDate.values()).flat()} onNew={(date, time) => openNew(date, time, 'daily', false)} onMove={rescheduleTask} onNewAllDay={date => openNew(date, '09:00', 'daily', true)}
            renderAllDayTask={(task, handle) => <AllDayTaskCard task={task} busy={modalBusy} onEdit={edit} onToggle={toggleDone} onDelete={deleteTask} draggable={false} moveHandle={handle} />}
            renderTask={(task, handle) => <TaskCard task={task} busy={modalBusy} categoryName={categoryName(task.categoryId)} onEdit={edit} onToggle={toggleDone} onDelete={deleteTask} draggable={false} moveHandle={handle} />} />
        </> : <section className="calendar month-calendar" aria-label="Calendario mensual">
          <div className="month-weekdays">{(firstDay === 'monday' ? ['LUN', 'MAR', 'MIÉ', 'JUE', 'VIE', 'SÁB', 'DOM'] : ['DOM', 'LUN', 'MAR', 'MIÉ', 'JUE', 'VIE', 'SÁB']).map(day => <div key={day}>{day}</div>)}</div>
          <div className="month-grid">{monthDays.map(day => {
            const date = iso(day)
            const dayTasks = visible(date)
            const inMonth = day.getMonth() === monthStart.getMonth()
            const dateLabel = day.toLocaleDateString('es-ES', { day: 'numeric', month: 'long' })
            return <div key={date} className={'month-day ' + (!inMonth ? 'outside ' : '') + (date === today ? 'today' : '')} onDragOver={e => e.preventDefault()} onDrop={e => { const id = e.dataTransfer.getData('task'); if (id) void moveTask(id, date) }} onDoubleClick={() => openNew(date)}>
              <button className="month-day-number" aria-label={'Ver tareas del ' + dateLabel} onClick={() => setDayAgenda(date)}>{day.getDate()}</button>
              <div className="month-tasks">{dayTasks.slice(0, 4).map(task => <button key={task.id} draggable={!modalBusy} onDragStart={e => e.dataTransfer.setData('task', task.id)} title={taskTimeLabel(task) + ' — ' + task.title} aria-label={taskTimeLabel(task) + ' — ' + task.title} className={'month-task ' + (task.allDay ? 'month-task-all-day ' : '') + (task.done ? 'done' : '')} style={{ '--task-color': task.color } as React.CSSProperties} onClick={() => edit(task)}><span>{task.allDay ? 'Todo el día' : task.time}</span> {task.title}</button>)}
                {dayTasks.length > 4 && <button className="month-more" aria-label={'Ver las ' + dayTasks.length + ' tareas del ' + dateLabel} onClick={() => setDayAgenda(date)}>+{dayTasks.length - 4} más</button>}
              </div>
            </div>
          })}</div>
        </section>}
      </>) : screen === 'routines' ? (habits.ready ? <RoutinesView userId={session.user.id} habits={habits} activation={habits.activation} activationError={habits.activationError} changedId={habits.changedId} focusId={routineFocusId} routines={routines} onBack={() => setScreen('overview')} onEditRoutine={editRoutine} onToggleRoutine={toggleRoutine} onDeleteRoutine={deleteRoutine} busy={modalBusy} /> : <RoutineLoadState habits={habits} />)
        : (overviewLoading || !overviewLoaded || !initialDataLoaded) ? <div className="calendar-loading" role="status">{readError || error ? 'No se pudieron cargar las tareas.' : 'Cargando tareas…'}</div>
        : screen === 'projects' ? <ProjectsView tasks={overviewTasks} projects={projects} onChangeProjectStatus={changeProjectStatus} projectBusy={modalBusy} categoryName={categoryName} onBack={() => setScreen('overview')} onNewProject={() => openProject()} onEditProject={openProject} onDeleteProject={deleteProject} onEdit={edit} onToggle={toggleDone} onDelete={deleteTask} />
        : <>
          <TaskOverview userId={session.user.id} today={today} onNewTask={() => openNew(today)} onNewRoutine={openNewRoutine} habits={habits} onCompleteRoutine={routine => void completeRoutine(routine)} busy={modalBusy} onOpenRoutines={() => setScreen('routines')} onOpenProjects={() => setScreen('projects')} tasks={overviewTasks} projects={projects} categoryName={categoryName} onNewProject={() => openProject()} onEditProject={openProject} onDeleteProject={deleteProject} onEdit={edit} onToggle={toggleDone} onDelete={deleteTask} />
          <ActivityHeatmap key={session.user.id} session={session} revision={activityRevision} />
        </>}
    </main>
    <MobileNavigation screen={screen} onNavigate={setScreen} />
    {notice && !dayAgenda && !isModal && !projectModal && !categoryModal && <ActionNotice notice={notice} busy={modalBusy} onDismiss={() => setNotice(null)} />}
    {dayAgenda && <FormDialog className="day-agenda-modal" title={new Date(dayAgenda + 'T12:00:00').toLocaleDateString('es-ES', { weekday: 'long', day: 'numeric', month: 'long' })} eyebrow="AGENDA DEL DÍA" busy={modalBusy} onClose={() => setDayAgenda(null)} onSubmit={() => {}}>
      {notice && <ActionNotice notice={notice} busy={modalBusy} onDismiss={() => setNotice(null)} inline />}
      {visible(dayAgenda).length ? <div className="overview-list">{visible(dayAgenda).map(task => <OverviewTask key={task.id} task={task} busy={modalBusy} categoryName={categoryName(task.categoryId)} onEdit={edit} onToggle={toggleDone} onDelete={deleteTask} />)}</div> : <p className="overview-empty">No hay tareas para este día con la categoría seleccionada.</p>}
      <div className="modal-actions"><button type="button" className="cancel" disabled={modalBusy} onClick={() => setDayAgenda(null)}>Cerrar</button><button type="button" className="save" disabled={modalBusy} onClick={() => openNew(dayAgenda)}>＋ Nueva tarea</button></div>
    </FormDialog>}
    {isModal && <FormDialog className={'task-modal ' + (draft.taskType === 'routine' ? 'routine-modal' : '')} title={taskTitle} eyebrow={selected || selectedRoutine ? 'EDITAR' : 'CREAR'} busy={modalBusy} onClose={closeTask} onSubmit={() => void save()}>
      {taskError && <p className="form-error" role="alert" id="task-form-error">{taskError}</p>}
      <fieldset className="form-fields" disabled={modalBusy}>
        <label>Título<input name="title" required value={draft.title} aria-invalid={Boolean(taskError) && !draft.title.trim()} aria-describedby={taskError ? 'task-form-error' : undefined} onChange={e => { setTaskError(''); setDraft({ ...draft, title: e.target.value }) }} placeholder={draft.taskType === 'routine' ? 'Ej. Leer 20 minutos' : 'Ej. Repasar tema 3'} /></label>
        <div className="form-grid">
          {draft.taskType !== 'routine' && <label>Categoría<select value={draft.categoryId || ''} onChange={e => { const category = categories.find(item => item.id === e.target.value); setDraft({ ...draft, categoryId: e.target.value || null, color: category?.color || draft.color }) }}><option value="">Sin categoría</option>{categories.map(category => <option key={category.id} value={category.id}>{category.name}</option>)}</select></label>}
          {draft.taskType !== 'routine' && <label>Tipo de tarea<select value={draft.taskType} onChange={e => { setTaskError(''); setDraft({ ...draft, taskType: e.target.value as TaskType }) }}><option value="daily">Tarea personal</option><option value="project">Tarea de proyecto</option></select></label>}
          {draft.taskType === 'project' && <div className="project-field"><label>Proyecto<select name="project" required value={draft.projectId || ''} aria-describedby="project-field-hint" onChange={e => { const project = projects.find(item => item.id === e.target.value); setTaskError(''); setDraft({ ...draft, projectId: e.target.value || null, color: project?.color || draft.color }) }}><option value="">Selecciona un proyecto activo</option>{selectableProjects.map(project => <option key={project.id} value={project.id}>{project.name}{project.status === 'active' ? '' : ' · ' + projectStatuses.find(status => status.value === project.status)?.label}</option>)}</select></label>
            <p className="form-hint" id="project-field-hint">{selectedProject && selectedProject.status !== 'active' ? 'Este proyecto está ' + (selectedProject.status === 'inactive' ? 'inactivo' : 'completado') + '. Sus tareas se consultan en Proyectos y Calendario.' : selectableProjects.length ? 'Puedes crear un proyecto sin perder esta tarea.' : 'Crea tu primer proyecto para asociar esta tarea.'}</p>
            <button type="button" className="inline-create" onClick={() => openProject()}>＋ Crear proyecto</button>
          </div>}
          {!selectedRoutine && <label>{draft.taskType === 'routine' ? 'Fecha inicial' : 'Fecha'}<input name="date" type="date" required value={draft.date} onChange={e => { setTaskError(''); setDraft({ ...draft, date: e.target.value }) }} /></label>}
        </div>
        {draft.taskType !== 'routine' && <label className="completed-toggle"><input type="checkbox" checked={draft.allDay} onChange={e => setDraft({ ...draft, allDay: e.target.checked })} /><span>Todo el día</span></label>}
        {draft.taskType !== 'routine' && !draft.allDay && <><div className="form-grid schedule-fields">
          <label>Hora de inicio<input name="start" type="time" step="60" required value={draft.time} onChange={e => { setTaskError(''); setDraft({ ...draft, ...startWithDuration(e.target.value, draft.time, draft.endTime) }) }} /></label>
          <label>Hora de finalización<input name="end" type="time" step="60" required value={draft.endTime} onChange={e => { setTaskError(''); setDraft({ ...draft, endTime: e.target.value }) }} /></label>
        </div><p className="form-hint">Al cambiar el inicio se conserva la duración hasta las 23:59. Las tareas terminan el mismo día.</p></>}
        {draft.taskType === 'routine' && <label className="completed-toggle"><input type="checkbox" checked={routineActive} onChange={e => setRoutineActive(e.target.checked)} /><span>Rutina activa</span></label>}
        {draft.taskType === 'routine' && <ColorPicker colors={colors} value={draft.color} onChange={color => setDraft({ ...draft, color })} />}
        <div className="modal-actions"><button type="button" className="cancel" onClick={closeTask}>Cancelar</button><button type="submit" className="save">{busyAction === 'save' ? 'Guardando…' : draft.taskType === 'routine' ? 'Guardar rutina' : 'Guardar tarea'}</button></div>
      </fieldset>
    </FormDialog>}
    {categoryModal && <FormDialog className="category-modal" title={categorySelected ? 'Editar categoría' : 'Nueva categoría'} eyebrow={categorySelected ? 'EDITAR' : 'CREAR'} busy={modalBusy} onClose={closeCategory} onSubmit={() => void saveCategory()}>
      <fieldset className="form-fields" disabled={modalBusy}>
        <label>Nombre<input name="name" required value={categoryDraft.name} aria-invalid={Boolean(categoryError)} aria-describedby={categoryError ? 'category-form-error' : undefined} onChange={e => { setCategoryError(''); setCategoryDraft({ ...categoryDraft, name: e.target.value }) }} placeholder="Ej. Física" /></label>
        {categoryError && <p id="category-form-error" className="form-error" role="alert">{categoryError}</p>}
        <ColorPicker colors={colors} value={categoryDraft.color} onChange={color => setCategoryDraft({ ...categoryDraft, color })} />
        <div className="modal-actions"><button type="button" className="cancel" onClick={closeCategory}>Cancelar</button><button type="submit" className="save">{busyAction === 'category' ? 'Guardando…' : 'Guardar categoría'}</button></div>
      </fieldset>
    </FormDialog>}
    {projectModal && <FormDialog className="project-modal" title={projectSelected ? 'Editar proyecto' : 'Nuevo proyecto'} eyebrow={projectSelected ? 'EDITAR' : 'CREAR'} busy={modalBusy} onClose={closeProject} onSubmit={() => void saveProject()}>
      <fieldset className="form-fields" disabled={modalBusy}>
        <label>Nombre<input name="name" required value={projectDraft.name} aria-invalid={Boolean(projectError)} aria-describedby={projectError ? 'project-form-error' : undefined} onChange={e => { setProjectError(''); setProjectDraft({ ...projectDraft, name: e.target.value }) }} placeholder="Ej. Preparar oposición" /></label>
        {projectError && <p id="project-form-error" className="form-error" role="alert">{projectError}</p>}
        <ColorPicker colors={colors} value={projectDraft.color} onChange={color => setProjectDraft({ ...projectDraft, color })} />
        <div className="modal-actions"><button type="button" className="cancel" onClick={closeProject}>Cancelar</button><button type="submit" className="save">{busyAction === 'project' ? 'Guardando…' : 'Guardar proyecto'}</button></div>
      </fieldset>
    </FormDialog>}
  </div>
}

function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [checking, setChecking] = useState(true)
  const [recovery, setRecovery] = useState(false)
  const [sessionError, setSessionError] = useState(authCallbackFailed ? 'El enlace no es válido o ha caducado. Solicita un nuevo email de recuperación.' : '')
  useEffect(() => {
    if (!supabase) { setChecking(false); return }
    let mounted = true
    const { data: listener } = supabase.auth.onAuthStateChange((event, nextSession) => {
      if (!mounted) return
      setSession(nextSession)
      if (event === 'PASSWORD_RECOVERY') {
        setRecovery(true)
        try { sessionStorage.setItem('password-recovery-user', nextSession?.user.id || '') } catch { /* Recovery works without storage. */ }
      }
      if (event === 'SIGNED_OUT') {
        setRecovery(false)
        try { sessionStorage.removeItem('password-recovery-user') } catch { /* No storage to clear. */ }
      }
    })
    void supabase.auth.getSession().then(({ data, error }) => {
      if (!mounted) return
      if (error) setSessionError(authErrorMessage(error))
      setSession(data.session)
      try { if (data.session && sessionStorage.getItem('password-recovery-user') === data.session.user.id) setRecovery(true) } catch { /* Use the recovery event when storage is unavailable. */ }
    }).catch(e => { if (mounted) setSessionError(authErrorMessage(e)) }).finally(() => { if (mounted) setChecking(false) })
    return () => { mounted = false; listener.subscription.unsubscribe() }
  }, [])
  if (!supabase) return <div className="auth-screen"><div className="auth-card"><h1>Configuración pendiente</h1><p className="auth-copy">Copia las variables de Supabase desde <code>.env.example</code> a <code>.env</code> para conectar la aplicación.</p></div></div>
  if (checking) return <div className="auth-screen"><div className="auth-card"><p>Cargando sesión…</p></div></div>
  if (session && recovery) return <PasswordRecovery onComplete={() => {
    try { sessionStorage.removeItem('password-recovery-user') } catch { /* No storage to clear. */ }
    window.history.replaceState(null, '', '/tareas')
    setRecovery(false)
  }} />
  return session ? <CalendarApp key={session.user.id} session={session} initialNotice={authCallbackFailed ? sessionError : undefined} /> : <AuthPanel initialMessage={sessionError} />
}

export default App
