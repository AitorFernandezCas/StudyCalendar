import { useEffect, useMemo, useRef, useState } from 'react'
import type { Session } from '@supabase/supabase-js'
import { bootstrapApi, calendarApi, categoriesApi, projectsApi, routinesApi, tasksApi, type ApiCategory, type ApiProject, type ApiRoutine, type ApiRoutineOccurrence, type ApiTask } from './lib/api'
import { supabase } from './lib/supabase'
import { useScreenRoute } from './lib/navigation'

type Category = ApiCategory
type TaskType = 'routine' | 'project' | 'daily'
type Task = { id: string; title: string; categoryId: string | null; projectId: string | null; taskType: TaskType; routineId?: string; date: string; time: string; endTime: string; color: string; done: boolean }
const colors = ['#7c5cff', '#ee7b6f', '#f2b84b', '#3fba91', '#4f8ff7']
const hours = Array.from({ length: 14 }, (_, i) => i + 8)
const pad = (n: number) => String(n).padStart(2, '0')
const iso = (d: Date) => String(d.getFullYear()) + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
const startOfWeek = (value: Date) => { const day = new Date(value); const offset = (day.getDay() + 6) % 7; day.setDate(day.getDate() - offset); day.setHours(0, 0, 0, 0); return day }
const toMinutes = (value: string) => { const [hour, minute] = value.split(':').map(Number); return hour * 60 + minute }
const fromApi = (task: ApiTask | ApiRoutineOccurrence): Task => ({ id: task.id, title: task.title, categoryId: task.category_id, projectId: task.project_id, taskType: task.task_type, routineId: 'routine_id' in task ? task.routine_id : undefined, date: task.date, time: task.start_time.slice(0, 5), endTime: task.end_time.slice(0, 5), color: task.color, done: task.completed })
const toApi = (task: Omit<Task, 'id'>) => ({ title: task.title, category_id: task.categoryId, project_id: task.projectId, task_type: task.taskType, date: task.date, start_time: task.time, end_time: task.endTime, color: task.color, completed: task.done })

function AuthPanel() {
  const [mode, setMode] = useState<'login' | 'signup'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const submit = async (event: React.FormEvent) => { event.preventDefault(); if (!supabase) return; setBusy(true); setMessage(''); const result = mode === 'login' ? await supabase.auth.signInWithPassword({ email, password }) : await supabase.auth.signUp({ email, password }); setBusy(false); if (result.error) setMessage(result.error.message); else setMessage(mode === 'signup' ? 'Revisa tu email para confirmar la cuenta.' : '') }
  return <div className="auth-screen"><div className="auth-card"><div className="brand auth-brand"><div className="brand-mark">✦</div><span>Study<span>Calendar</span></span></div><p className="eyebrow">TU PLAN DE ESTUDIO</p><h1>{mode === 'login' ? 'Bienvenido de nuevo' : 'Crea tu cuenta'}</h1><p className="auth-copy">{mode === 'login' ? 'Inicia sesión para ver tus tareas.' : 'Guarda tu calendario en la nube y accede desde cualquier lugar.'}</p><form onSubmit={submit}><label>Email<input type="email" required value={email} onChange={e => setEmail(e.target.value)} placeholder="tu@email.com" /></label><label>Contraseña<input type="password" required minLength={6} value={password} onChange={e => setPassword(e.target.value)} placeholder="Mínimo 6 caracteres" /></label><button className="save auth-submit" disabled={busy}>{busy ? 'Conectando…' : mode === 'login' ? 'Iniciar sesión' : 'Registrarme'}</button></form>{message && <p className="auth-message">{message}</p>}<button className="auth-switch" onClick={() => { setMode(mode === 'login' ? 'signup' : 'login'); setMessage('') }}>{mode === 'login' ? '¿No tienes cuenta? Regístrate' : '¿Ya tienes cuenta? Inicia sesión'}</button></div></div>
}

function TimePicker({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  const [open, setOpen] = useState(false)
  const options = useMemo(() => Array.from({ length: 96 }, (_, index) => pad(Math.floor(index / 4)) + ':' + pad((index % 4) * 15)), [])
  return <div className="time-picker-field"><span className="time-picker-label">{label}</span><button type="button" aria-label={label} aria-expanded={open} aria-haspopup="listbox" className={'time-picker-trigger ' + (open ? 'open' : '')} onClick={() => setOpen(current => !current)}><span className="time-picker-icon">◷</span>{value}</button>{open && <div className="time-picker-menu" role="listbox" aria-label={label}>{options.map(option => <button type="button" role="option" aria-selected={option === value} className={option === value ? 'selected' : ''} key={option} onClick={() => { onChange(option); setOpen(false) }}>{option}</button>)}</div>}</div>
}

function TaskCard({ task, categoryName, onEdit, onToggle, onDelete, locked = false }: { task: Task; categoryName: string; onEdit: (task: Task) => void; onToggle: (id: string) => void; onDelete: (id: string) => void; locked?: boolean }) {
  return <article draggable={!locked} onDragStart={e => { if (!locked) e.dataTransfer.setData('task', task.id) }} onClick={e => { e.stopPropagation(); if (!locked) onEdit(task) }} className={'task-card ' + (task.done ? 'done' : '') + (locked ? ' routine-task' : '')} style={{ '--task-color': task.color } as React.CSSProperties}><div className="task-time">{task.time} — {task.endTime}</div><div className="task-title">{task.title}</div><div className="task-meta">{!locked && <span>{categoryName}</span>}<div className="task-actions"><button aria-label={task.done ? 'Marcar como pendiente' : 'Marcar como completada'} onClick={e => { e.stopPropagation(); onToggle(task.id) }}>{task.done ? '✓' : '○'}</button>{!locked && <button className="delete-task" aria-label="Borrar tarea" onClick={e => { e.stopPropagation(); onDelete(task.id) }}>×</button>}</div></div></article>
}

function OverviewTask({ task, categoryName, projectName, onEdit, onToggle, onDelete, onEditRoutine, onToggleRoutine, onDeleteRoutine, routine }: { task: Task; categoryName: string; projectName?: string; onEdit: (task: Task) => void; onToggle: (id: string) => void; onDelete: (id: string) => void; onEditRoutine?: () => void; onToggleRoutine?: () => void; onDeleteRoutine?: () => void; routine?: ApiRoutine }) {
  const isRoutine = Boolean(routine)
  return <article className={'overview-task ' + (task.done ? 'done' : '') + (isRoutine && !routine?.active ? ' inactive' : '')} style={{ '--task-color': task.color } as React.CSSProperties} onClick={() => isRoutine ? onEditRoutine?.() : onEdit(task)}><div className="overview-task-main"><strong>{task.title}</strong><small>{isRoutine ? task.time + ' — ' + task.endTime : (projectName ? projectName + ' · ' : '') + task.date + ' · ' + task.time + ' — ' + task.endTime}</small></div>{!isRoutine && <span className="overview-category">{projectName || categoryName}</span>}<div className="task-actions">{isRoutine ? <><button aria-label={routine?.active ? 'Desactivar rutina' : 'Activar rutina'} onClick={e => { e.stopPropagation(); onToggleRoutine?.() }}>{routine?.active ? '●' : '○'}</button><button className="delete-task" aria-label="Borrar rutina" onClick={e => { e.stopPropagation(); onDeleteRoutine?.() }}>×</button></> : <><button aria-label={task.done ? 'Marcar como pendiente' : 'Marcar como completada'} onClick={e => { e.stopPropagation(); onToggle(task.id) }}>{task.done ? '✓' : '○'}</button><button className="delete-task" aria-label="Borrar tarea" onClick={e => { e.stopPropagation(); onDelete(task.id) }}>×</button></>}</div></article>
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

function ProjectList({ tasks, projects, categoryName, onEdit, onToggle, onDelete, onEditProject, onDeleteProject, history = false }: ProjectListProps & { history?: boolean }) {
  const projectTasks = useMemo(() => tasks.filter(task => task.taskType === 'project').sort((a, b) => (a.date + a.time).localeCompare(b.date + b.time)), [tasks])
  const projectById = useMemo(() => new Map(projects.map(project => [project.id, project])), [projects])
  const unassigned = projectTasks.filter(task => !task.projectId || !projectById.has(task.projectId))
  const renderTask = (task: Task) => <OverviewTask key={task.id} task={task} categoryName={categoryName(task.categoryId)} projectName={task.projectId ? projectById.get(task.projectId)?.name : 'Sin proyecto'} onEdit={onEdit} onToggle={onToggle} onDelete={onDelete} />
  return <>
    {projects.length ? <div className="project-list">{projects.map(project => {
      const associated = projectTasks.filter(task => task.projectId === project.id)
      const completed = associated.filter(task => task.done).length
      const progress = associated.length ? Math.round(completed / associated.length * 100) : 0
      return <article className="project-card" key={project.id} style={{ '--project-color': project.color } as React.CSSProperties}>
        <div className="project-card-head"><div className="project-title"><i /><h4 title={project.name}>{project.name}</h4></div>
          <div className="project-card-actions"><small aria-label={completed + ' de ' + associated.length + ' tareas completadas'}>{completed}/{associated.length}</small>
            <button aria-label={'Editar ' + project.name} onClick={() => onEditProject(project)}>✎</button>
            <button aria-label={'Eliminar ' + project.name} onClick={() => onDeleteProject(project)}>×</button>
          </div>
        </div>
        <div className="project-progress" role="progressbar" aria-label={'Progreso de ' + project.name} aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100}><i style={{ width: progress + '%' }} /></div>
        <ProjectTaskGroups tasks={associated} history={history} renderTask={renderTask} />
      </article>
    })}</div> : <p className="overview-empty">Crea tu primer proyecto para empezar a organizar sus tareas.</p>}
    {unassigned.some(task => history || !task.done) && <section className="unassigned-project"><h4>Sin proyecto</h4><ProjectTaskGroups tasks={unassigned} history={history} renderTask={renderTask} /></section>}
  </>
}

function ProjectsView({ onBack, ...props }: ProjectListProps & { onBack: () => void }) {
  return <section className="projects-view">
    <div className="projects-toolbar"><button className="back-to-overview" onClick={onBack}>← Volver a todas las tareas</button><button className="new-project-button" onClick={props.onNewProject}>＋ Nuevo proyecto</button></div>
    <p className="projects-copy">Consulta las tareas pendientes y el historial de cada proyecto.</p>
    <ProjectList {...props} history />
  </section>
}

type RoutineListProps = {
  routines: ApiRoutine[]
  onEditRoutine: (routine: ApiRoutine) => void
  onToggleRoutine: (routine: ApiRoutine) => void
  onDeleteRoutine: (routine: ApiRoutine) => void
  busy: boolean
}

function RoutineList({ routines, onEditRoutine, onToggleRoutine, onDeleteRoutine, busy }: RoutineListProps) {
  const sorted = useMemo(() => [...routines].sort((a, b) => a.start_time.localeCompare(b.start_time) || a.title.localeCompare(b.title, 'es')), [routines])
  return <div className="overview-list">{sorted.map(routine => <article key={routine.id} className={'overview-task routine-definition ' + (!routine.active ? 'inactive' : '')} style={{ '--task-color': routine.color } as React.CSSProperties}>
    <button className="routine-edit-button overview-task-main" disabled={busy} aria-label={'Editar rutina ' + routine.title} onClick={() => onEditRoutine(routine)}><strong>{routine.title}</strong><small>{routine.start_time.slice(0, 5)} — {routine.end_time.slice(0, 5)}</small></button>
    <span className="routine-status">{routine.active ? 'Activa' : 'Inactiva'}</span>
    <div className="task-actions"><button disabled={busy} aria-label={(routine.active ? 'Desactivar rutina ' : 'Activar rutina ') + routine.title} onClick={() => onToggleRoutine(routine)}>{routine.active ? '●' : '○'}</button><button disabled={busy} className="delete-task" aria-label={'Borrar rutina ' + routine.title} onClick={() => onDeleteRoutine(routine)}>×</button></div>
  </article>)}</div>
}

function RoutinesView({ onBack, ...props }: RoutineListProps & { onBack: () => void }) {
  return <section className="routines-view">
    <button className="back-to-overview" onClick={onBack}>← Volver a todas las tareas</button>
    <div className="routine-sections">{[true, false].map(active => {
      const routines = props.routines.filter(routine => routine.active === active)
      return <section className="overview-section routine" key={String(active)}><div className="overview-section-head"><h3>{active ? 'Activas' : 'Inactivas'}</h3><span>{routines.length}</span></div>
        {routines.length ? <RoutineList {...props} routines={routines} /> : <p className="overview-empty">{active ? 'No hay rutinas activas.' : 'No hay rutinas inactivas.'}</p>}
      </section>
    })}</div>
  </section>
}

function TaskOverview({ routines, onEditRoutine, onToggleRoutine, onDeleteRoutine, busy, onOpenRoutines, onOpenProjects, ...props }: ProjectListProps & RoutineListProps & {
  onOpenProjects: () => void
  onOpenRoutines: () => void
}) {
  const dailyTasks = useMemo(() => props.tasks.filter(task => task.taskType === 'daily' && !task.done).sort((a, b) => (a.date + a.time).localeCompare(b.date + b.time)), [props.tasks])
  const pendingProjects = props.tasks.filter(task => task.taskType === 'project' && !task.done).length
  return <section className="task-overview">
    <div className="overview-intro"><p className="eyebrow">RESUMEN DE TAREAS</p><h2>Todas las tareas</h2><p>Consulta tus tareas pendientes y organiza tu trabajo.</p></div>
    <div className="overview-sections">
      <section className="overview-section routine"><div className="overview-section-head"><h3><button className="projects-heading-button routines-heading-button" onClick={onOpenRoutines}>Rutina <span aria-hidden="true">→</span></button></h3><span>{routines.length}</span></div>
        {routines.length ? <RoutineList routines={routines} onEditRoutine={onEditRoutine} onToggleRoutine={onToggleRoutine} onDeleteRoutine={onDeleteRoutine} busy={busy} /> : <p className="overview-empty">No hay rutinas en esta sección.</p>}
      </section>
      <section className="overview-section project"><div className="overview-section-head"><h3><button className="projects-heading-button" onClick={onOpenProjects}>Proyectos <span aria-hidden="true">→</span></button></h3>
        <div className="overview-section-tools"><span aria-label={pendingProjects + ' tareas de proyecto pendientes'}>{pendingProjects}</span><button className="new-project-button" onClick={props.onNewProject}>＋ Nuevo proyecto</button></div>
      </div><ProjectList {...props} /></section>
      <section className="overview-section daily"><div className="overview-section-head"><h3>Tareas del día</h3><span>{dailyTasks.length}</span></div>
        {dailyTasks.length ? <div className="overview-list">{dailyTasks.map(task => <OverviewTask key={task.id} task={task} categoryName={props.categoryName(task.categoryId)} onEdit={props.onEdit} onToggle={props.onToggle} onDelete={props.onDelete} />)}</div> : <p className="overview-empty">No hay tareas pendientes en esta sección.</p>}
      </section>
    </div>
  </section>
}

function CalendarApp({ session }: { session: Session }) {
  const [weekStart, setWeekStart] = useState(() => startOfWeek(new Date()))
  const [tasks, setTasks] = useState<Task[]>([])
  const [overviewTasks, setOverviewTasks] = useState<Task[]>([])
  const [overviewLoaded, setOverviewLoaded] = useState(false)
  const [overviewLoading, setOverviewLoading] = useState(false)
  const [initialDataLoaded, setInitialDataLoaded] = useState(false)
  const [routines, setRoutines] = useState<ApiRoutine[]>([])
  const [routineOccurrences, setRoutineOccurrences] = useState<Task[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [projects, setProjects] = useState<ApiProject[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [selectedRoutine, setSelectedRoutine] = useState<string | null>(null)
  const [isModal, setIsModal] = useState(false)
  const [categoryModal, setCategoryModal] = useState(false)
  const [categorySelected, setCategorySelected] = useState<string | null>(null)
  const [projectModal, setProjectModal] = useState(false)
  const [projectSelected, setProjectSelected] = useState<string | null>(null)
  const [filter, setFilter] = useState('Todas')
  const [view, setView] = useState<'week' | 'month'>('week')
  const [screen, setScreen] = useScreenRoute()
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [busyAction, setBusyAction] = useState<string | null>(null)
  const [routineActive, setRoutineActive] = useState(true)
  const [categoryDraft, setCategoryDraft] = useState({ name: '', color: colors[0] })
  const [projectDraft, setProjectDraft] = useState({ name: '', color: colors[1] })
  const [draft, setDraft] = useState<Omit<Task, 'id'>>({ title: '', categoryId: null, projectId: null, taskType: 'daily', date: iso(new Date()), time: '09:00', endTime: '10:00', color: colors[0], done: false })
  const days = useMemo(() => Array.from({ length: 7 }, (_, i) => { const d = new Date(weekStart); d.setDate(d.getDate() + i); return d }), [weekStart])
  const today = iso(new Date())
  const weekLabel = weekStart.toLocaleDateString('es-ES', { month: 'short', day: 'numeric' }) + ' — ' + days[6].toLocaleDateString('es-ES', { month: 'short', day: 'numeric', year: 'numeric' })
  const monthStart = useMemo(() => new Date(weekStart.getFullYear(), weekStart.getMonth(), 1), [weekStart])
  const monthDays = useMemo(() => { const first = new Date(monthStart); const offset = (first.getDay() + 6) % 7; first.setDate(first.getDate() - offset); return Array.from({ length: 42 }, (_, index) => { const day = new Date(first); day.setDate(first.getDate() + index); return day }) }, [monthStart])
  const monthLabel = monthStart.toLocaleDateString('es-ES', { month: 'long', year: 'numeric' })
  const visibleRange = useMemo(() => { const range = view === 'month' ? monthDays : days; return { from: iso(range[0]), to: iso(range[range.length - 1]) } }, [days, monthDays, view])
  const rangeKey = visibleRange.from + ':' + visibleRange.to
  const loadedRangeRef = useRef<string | null>(null)
  const bootstrapRangeRef = useRef<string | null>(null)
  const updateTaskState = (updater: (current: Task[]) => Task[]) => {
    setTasks(current => updater(current).filter(task => task.date >= visibleRange.from && task.date <= visibleRange.to))
    if (overviewLoaded) setOverviewTasks(updater)
  }
  useEffect(() => {
    const controller = new AbortController()
    bootstrapRangeRef.current = rangeKey
    setLoading(true)
    setOverviewLoaded(false)
    setInitialDataLoaded(false)
    setOverviewTasks([])
    void bootstrapApi.load(session, visibleRange.from, visibleRange.to, controller.signal).then(data => {
      if (controller.signal.aborted) return
      setTasks(data.tasks.map(fromApi))
      setRoutineOccurrences(data.routine_occurrences.map(fromApi))
      setCategories(data.categories)
      setProjects(data.projects)
      setRoutines(data.routines)
      setInitialDataLoaded(true)
      loadedRangeRef.current = rangeKey
      setError('')
    }).catch(e => { if (e instanceof DOMException && e.name === 'AbortError') return; setError(e instanceof Error ? e.message : 'No se pudieron cargar los datos') }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [session.access_token])
  useEffect(() => {
    if (loadedRangeRef.current === rangeKey || bootstrapRangeRef.current === rangeKey) return
    const controller = new AbortController()
    setLoading(true)
    void calendarApi.list(session, visibleRange.from, visibleRange.to, controller.signal).then(data => {
      if (controller.signal.aborted) return
      setTasks(data.tasks.map(fromApi))
      setRoutineOccurrences(data.routine_occurrences.map(fromApi))
      loadedRangeRef.current = rangeKey
    }).catch(e => { if (e instanceof DOMException && e.name === 'AbortError') return; setError(e instanceof Error ? e.message : 'No se pudieron cargar las tareas') }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [rangeKey, session.access_token])
  useEffect(() => {
    if (!initialDataLoaded || (screen !== 'overview' && screen !== 'projects') || overviewLoaded) return
    const controller = new AbortController()
    setOverviewLoading(true)
    void tasksApi.list(session, undefined, undefined, controller.signal).then(data => { if (!controller.signal.aborted) { setOverviewTasks(data.map(fromApi)); setOverviewLoaded(true) } }).catch(e => { if (e instanceof DOMException && e.name === 'AbortError') return; setError(e instanceof Error ? e.message : 'No se pudieron cargar todas las tareas') }).finally(() => { if (!controller.signal.aborted) setOverviewLoading(false) })
    return () => controller.abort()
  }, [initialDataLoaded, overviewLoaded, screen, session.access_token])
  const allTasks = useMemo(() => [...tasks, ...routineOccurrences], [routineOccurrences, tasks])
  const categoryNames = useMemo(() => new Map(categories.map(category => [category.id, category.name])), [categories])
  const categoryCounts = useMemo(() => tasks.reduce((counts, task) => counts.set(task.categoryId, (counts.get(task.categoryId) || 0) + 1), new Map<string | null, number>()), [tasks])
  const tasksByDate = useMemo(() => {
    const grouped = new Map<string, Task[]>()
    for (const task of allTasks) {
      if (filter !== 'Todas' && (filter === 'Sin categoría' ? task.categoryId !== null : task.categoryId !== filter)) continue
      const dateTasks = grouped.get(task.date) || []
      dateTasks.push(task)
      grouped.set(task.date, dateTasks)
    }
    grouped.forEach(dateTasks => dateTasks.sort((a, b) => a.time.localeCompare(b.time)))
    return grouped
  }, [allTasks, filter])
  const categoryName = (id: string | null) => (id ? categoryNames.get(id) : undefined) || 'Sin categoría'
  const visible = (date: string) => tasksByDate.get(date) || []
  const completed = useMemo(() => tasks.filter(t => t.done).length, [tasks])
  const pendingTaskCount = useMemo(() => (overviewLoaded ? overviewTasks : tasks).filter(task => !task.done).length, [overviewLoaded, overviewTasks, tasks])
  const openNew = (date = iso(weekStart), time = '09:00', taskType: TaskType = 'daily') => { setSelected(null); setSelectedRoutine(null); setRoutineActive(true); setDraft({ title: '', categoryId: taskType === 'routine' ? null : categories[0]?.id || null, projectId: null, taskType, date, time, endTime: pad(Number(time.slice(0, 2)) + 1) + ':' + time.slice(3), color: taskType === 'routine' ? colors[0] : categories[0]?.color || colors[0], done: false }); setError(''); setIsModal(true) }
  const openNewRoutine = () => openNew(iso(new Date()), '09:00', 'routine')
  const edit = (task: Task) => { if (task.routineId) return; setSelected(task.id); setSelectedRoutine(null); setDraft({ title: task.title, categoryId: task.categoryId, projectId: task.projectId, taskType: task.taskType, date: task.date, time: task.time, endTime: task.endTime, color: task.color, done: task.done }); setIsModal(true) }
  const editRoutine = (routine: ApiRoutine) => { setSelected(null); setSelectedRoutine(routine.id); setRoutineActive(routine.active); setDraft({ title: routine.title, categoryId: null, projectId: null, taskType: 'routine', date: iso(new Date()), time: routine.start_time.slice(0, 5), endTime: routine.end_time.slice(0, 5), color: routine.color, done: false }); setError(''); setIsModal(true) }
  const save = async () => {
    if (busyAction) return
    if (!draft.title.trim()) { setError('Introduce un título'); return }
    if (!draft.date) { setError('Selecciona una fecha'); return }
    if (toMinutes(draft.endTime) <= toMinutes(draft.time)) { setError('La hora de finalización debe ser posterior a la de inicio'); return }
    if (draft.taskType === 'project' && !draft.projectId) { setError('Selecciona un proyecto para esta tarea'); return }
    setBusyAction('save')
    try {
      if (draft.taskType === 'routine') {
        const payload = { title: draft.title.trim(), start_time: draft.time, end_time: draft.endTime, color: draft.color, active: routineActive, starts_on: draft.date }
        const routine = selectedRoutine ? await routinesApi.update(session, selectedRoutine, payload) : await routinesApi.create(session, payload)
        setRoutines(prev => selectedRoutine ? prev.map(item => item.id === selectedRoutine ? routine : item) : [...prev, routine])
        setIsModal(false)
        const occurrences = await routinesApi.occurrences(session, visibleRange.from, visibleRange.to)
        setRoutineOccurrences(occurrences.map(fromApi))
      } else if (selected) {
        const original = tasks.find(task => task.id === selected) || overviewTasks.find(task => task.id === selected)
        const updatePayload: Partial<Omit<ApiTask, 'id'>> = { title: draft.title.trim(), task_type: draft.taskType, project_id: draft.taskType === 'project' ? draft.projectId : null, date: draft.date, start_time: draft.time, end_time: draft.endTime, color: draft.color, completed: draft.done }
        if (!original || original.categoryId !== draft.categoryId) updatePayload.category_id = draft.categoryId
        const updated = fromApi(await tasksApi.update(session, selected, updatePayload))
        updateTaskState(prev => [...prev.filter(task => task.id !== selected), updated])
      } else {
        const created = fromApi(await tasksApi.create(session, toApi(draft)))
        updateTaskState(prev => [...prev, created])
      }
      setIsModal(false)
      setError('')
    } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo guardar la tarea') } finally { setBusyAction(null) }
  }
  const shift = (delta: number) => { const d = new Date(weekStart); d.setDate(d.getDate() + delta * 7); setWeekStart(d) }
  const shiftMonth = (delta: number) => { const d = new Date(weekStart); d.setMonth(d.getMonth() + delta); setWeekStart(d) }
  const toggleDone = async (id: string) => {
    if (busyAction) return
    const task = allTasks.find(item => item.id === id) || overviewTasks.find(item => item.id === id)
    if (!task) return
    setBusyAction('toggle:' + id)
    try {
      if (task.routineId) {
        const updated = fromApi(await routinesApi.updateOccurrence(session, task.routineId, task.date, !task.done))
        setRoutineOccurrences(prev => prev.map(item => item.id === id ? updated : item))
      } else {
        const updated = fromApi(await tasksApi.update(session, id, { completed: !task.done }))
        updateTaskState(prev => prev.map(item => item.id === id ? updated : item))
      }
    } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo actualizar la tarea') } finally { setBusyAction(null) }
  }
  const deleteTask = async (id: string) => {
    const task = allTasks.find(item => item.id === id) || overviewTasks.find(item => item.id === id)
    if (busyAction || !task || task.routineId || !window.confirm('¿Borrar “' + task.title + '”?')) return
    setBusyAction('delete:' + id)
    try { await tasksApi.remove(session, id); updateTaskState(prev => prev.filter(item => item.id !== id)); setError('') } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo borrar la tarea') } finally { setBusyAction(null) }
  }
  const moveTask = async (id: string, date: string) => {
    if (busyAction) return
    const task = allTasks.find(item => item.id === id)
    if (task?.routineId) return
    setBusyAction('move:' + id)
    try { const updated = fromApi(await tasksApi.update(session, id, { date })); updateTaskState(prev => prev.map(item => item.id === id ? updated : item)) } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo mover la tarea') } finally { setBusyAction(null) }
  }
  const toggleRoutine = async (routine: ApiRoutine) => {
    if (busyAction) return
    setBusyAction('routine:' + routine.id)
    try { const updated = await routinesApi.update(session, routine.id, { active: !routine.active }); setRoutines(prev => prev.map(item => item.id === routine.id ? updated : item)); const occurrences = await routinesApi.occurrences(session, visibleRange.from, visibleRange.to); setRoutineOccurrences(occurrences.map(fromApi)) } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo cambiar el estado de la rutina') } finally { setBusyAction(null) }
  }
  const deleteRoutine = async (routine: ApiRoutine) => {
    if (busyAction || !window.confirm('¿Borrar la rutina “' + routine.title + '”? También se eliminará su historial.')) return
    setBusyAction('routine:' + routine.id)
    try { await routinesApi.remove(session, routine.id); setRoutines(prev => prev.filter(item => item.id !== routine.id)); setRoutineOccurrences(prev => prev.filter(task => task.routineId !== routine.id)); setError('') } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo borrar la rutina') } finally { setBusyAction(null) }
  }
  const taskStyles = useMemo(() => {
    const styles = new Map<string, React.CSSProperties>()
    tasksByDate.forEach(dayTasks => dayTasks.forEach((task, taskIndex) => {
      const start = toMinutes(task.time)
      const end = Math.max(start + 30, toMinutes(task.endTime))
      const overlapping = dayTasks.filter(other => other.id !== task.id && toMinutes(other.time) < end && toMinutes(other.endTime) > start)
      const before = dayTasks.slice(0, taskIndex).filter(other => toMinutes(other.time) < end && toMinutes(other.endTime) > start).length
      const columns = Math.max(1, overlapping.length + 1)
      styles.set(task.id, { top: (((start - 8 * 60) / 60) * 84 + 6) + 'px', height: (Math.max(52, ((end - start) / 60) * 84 - 8)) + 'px', width: 'calc(' + (100 / columns) + '% - 7px)', left: 'calc(' + ((before * 100) / columns) + '% + 4px)' })
    }))
    return styles
  }, [tasksByDate])
  const openProject = (project?: ApiProject) => { setProjectSelected(project?.id || null); setProjectDraft({ name: project?.name || '', color: project?.color || colors[1] }); setProjectModal(true) }
  const saveProject = async () => {
    if (busyAction || !projectDraft.name.trim()) return
    setBusyAction('project')
    try {
      if (projectSelected) {
        const updated = await projectsApi.update(session, projectSelected, projectDraft)
        setProjects(prev => prev.map(project => project.id === projectSelected ? updated : project))
      } else {
        const created = await projectsApi.create(session, projectDraft)
        setProjects(prev => [...prev, created].sort((a, b) => a.name.localeCompare(b.name)))
      }
      setProjectModal(false)
      setError('')
    } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo guardar el proyecto') } finally { setBusyAction(null) }
  }
  const deleteProject = async (project: ApiProject) => {
    if (busyAction || !window.confirm('¿Eliminar “' + project.name + '”? Sus tareas quedarán sin proyecto.')) return
    setBusyAction('project:' + project.id)
    try { await projectsApi.remove(session, project.id); setProjects(prev => prev.filter(item => item.id !== project.id)); updateTaskState(prev => prev.map(task => task.projectId === project.id ? { ...task, projectId: null } : task)); setError('') } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo borrar el proyecto') } finally { setBusyAction(null) }
  }
  const openCategory = (category?: Category) => { setCategorySelected(category?.id || null); setCategoryDraft({ name: category?.name || '', color: category?.color || colors[0] }); setCategoryModal(true) }
  const saveCategory = async () => {
    if (busyAction || !categoryDraft.name.trim()) return
    setBusyAction('category')
    try {
      if (categorySelected) {
        const updated = await categoriesApi.update(session, categorySelected, categoryDraft)
        setCategories(prev => prev.map(category => category.id === categorySelected ? updated : category))
        updateTaskState(prev => prev.map(task => task.categoryId === categorySelected ? { ...task, color: updated.color } : task))
      } else {
        const created = await categoriesApi.create(session, categoryDraft)
        setCategories(prev => [...prev, created].sort((a, b) => a.name.localeCompare(b.name)))
      }
      setCategoryModal(false)
      setError('')
    } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo guardar la categoría') } finally { setBusyAction(null) }
  }
  const deleteCategory = async (category: Category) => {
    if (busyAction || !window.confirm('¿Eliminar “' + category.name + '”? Sus tareas pasarán a “Sin categoría”.')) return
    setBusyAction('category:' + category.id)
    try { await categoriesApi.remove(session, category.id); setCategories(prev => prev.filter(item => item.id !== category.id)); updateTaskState(prev => prev.map(task => task.categoryId === category.id ? { ...task, categoryId: null } : task)); if (filter === category.id) setFilter('Todas'); setError('') } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo borrar la categoría') } finally { setBusyAction(null) }
  }

  return <div className="app-shell"><aside className="sidebar"><div className="brand"><div className="brand-mark">✦</div><span>Study<span>Calendar</span></span></div><nav><button className={"nav-item " + (screen === 'calendar' ? 'active' : '')} onClick={() => setScreen('calendar')}><span>▦</span> Calendario</button><button className={"nav-item " + (screen === 'overview' ? 'active' : '')} onClick={() => setScreen('overview')}><span>✓</span> Todas las tareas <b>{pendingTaskCount}</b></button><button className="nav-item"><span>◷</span> Próximas</button></nav><div className="side-section"><div className="side-title">CATEGORÍAS <button aria-label="Crear categoría" onClick={() => openCategory()}>＋</button></div>{categories.map(category => <div className="category-row" key={category.id}><button className="subject" onClick={() => setFilter(filter === category.id ? 'Todas' : category.id)}><i style={{ background: category.color }} />{category.name}<span>{categoryCounts.get(category.id) || 0}</span></button><button className="category-edit" aria-label={'Editar ' + category.name} onClick={() => openCategory(category)}>✎</button><button className="category-delete" aria-label={'Eliminar ' + category.name} onClick={() => void deleteCategory(category)}>×</button></div>)}{(categoryCounts.get(null) || 0) > 0 && <button className="subject" onClick={() => setFilter(filter === 'Sin categoría' ? 'Todas' : 'Sin categoría')}><i style={{ background: '#aab0bd' }} />Sin categoría<span>{categoryCounts.get(null) || 0}</span></button>}</div><div className="side-bottom"><div className="progress-label"><span>Progreso semanal</span><strong>{Math.round((completed / Math.max(tasks.length, 1)) * 100)}%</strong></div><div className="progress"><i style={{ width: ((completed / Math.max(tasks.length, 1)) * 100) + '%' }} /></div><p>{completed} de {tasks.length} tareas completadas</p><div className="user"><div className="avatar">{session.user.email?.slice(0, 2).toUpperCase()}</div><span>{session.user.email}<small>Cuenta conectada</small></span><button className="logout" onClick={() => supabase?.auth.signOut()}>Salir</button></div></div></aside><main className="main"><header><div><p className="eyebrow">MI PLAN DE ESTUDIO</p><h1>{screen === 'routines' ? 'Rutina' : screen === 'projects' ? 'Proyectos' : screen === 'overview' ? 'Todas las tareas' : 'Mi calendario'}</h1><p className="subtitle">{screen === 'routines' ? 'Organiza tus rutinas activas e inactivas.' : screen === 'projects' ? 'Todo el trabajo de tus proyectos, con su historial.' : 'Organiza tu semana y avanza con calma.'}</p></div><button className="add-button" aria-label={screen === 'routines' ? 'Nueva rutina' : 'Nueva tarea'} disabled={Boolean(busyAction) || (screen === 'routines' && !initialDataLoaded)} onClick={() => screen === 'routines' ? openNewRoutine() : openNew(screen === 'projects' ? iso(new Date()) : iso(weekStart), '09:00', screen === 'projects' ? 'project' : 'daily')}>＋ <span>{screen === 'routines' ? 'Nueva rutina' : 'Nueva tarea'}</span></button></header>{error && <div className="error-banner">{error}<button onClick={() => setError('')}>×</button></div>}{screen === 'calendar' ? <><section className="toolbar"><div className="week-nav"><button onClick={() => view === 'month' ? shiftMonth(-1) : shift(-1)}>‹</button><button onClick={() => setWeekStart(new Date(2025, 9, 6))}>Hoy</button><button onClick={() => view === 'month' ? shiftMonth(1) : shift(1)}>›</button><strong>{view === 'month' ? monthLabel : weekLabel}</strong></div><div className="view-tools"><select value={filter} onChange={e => setFilter(e.target.value)}><option value="Todas">Todas</option>{categories.map(category => <option value={category.id} key={category.id}>{category.name}</option>)}<option value="Sin categoría">Sin categoría</option></select><button className={"view-btn " + (view === 'week' ? 'active' : '')} onClick={() => setView('week')}>▦ Semana</button><button className={"view-btn " + (view === 'month' ? 'active' : '')} onClick={() => setView('month')}>▦ Mes</button></div></section>{view === 'week' ? <section className="calendar"><div className="calendar-head"><div className="timezone">GMT +01:00</div>{days.map(d => <div key={iso(d)} className={'day-head ' + (iso(d) === today ? 'today' : '')}><span>{d.toLocaleDateString('es-ES', { weekday: 'short' }).replace('.', '').toUpperCase()}</span><strong>{d.getDate()}</strong></div>)}</div>{loading ? <div className="calendar-loading">Cargando tareas…</div> : <div className="calendar-body"><div className="time-column">{hours.map(hour => <div key={hour}>{pad(hour)}:00</div>)}</div>{days.map(d => { const dayTasks = visible(iso(d)); return <div key={iso(d)} className="day-column" onDragOver={e => e.preventDefault()} onDrop={e => { const id = e.dataTransfer.getData('task'); if (id) void moveTask(id, iso(d)) }}><div className="hour-lines">{hours.map(hour => <button key={hour} aria-label={'Crear tarea a las ' + hour + ':00'} onClick={() => openNew(iso(d), pad(hour) + ':00')} />)}</div>{dayTasks.map(task => <div key={task.id} className="positioned-task" style={taskStyles.get(task.id)}><TaskCard task={task} categoryName={categoryName(task.categoryId)} onEdit={edit} onToggle={toggleDone} onDelete={deleteTask} locked={Boolean(task.routineId)} /></div>)}</div>})}</div>}</section> : <section className="calendar month-calendar"><div className="month-weekdays">{['LUN', 'MAR', 'MIÉ', 'JUE', 'VIE', 'SÁB', 'DOM'].map(day => <div key={day}>{day}</div>)}</div><div className="month-grid">{monthDays.map(day => { const dayTasks = visible(iso(day)); const inMonth = day.getMonth() === monthStart.getMonth(); return <div key={iso(day)} className={'month-day ' + (!inMonth ? 'outside ' : '') + (iso(day) === today ? 'today' : '')} onDragOver={e => e.preventDefault()} onDrop={e => { const id = e.dataTransfer.getData('task'); if (id) void moveTask(id, iso(day)) }} onDoubleClick={() => openNew(iso(day))}><button className="month-day-number" onClick={() => openNew(iso(day))}>{day.getDate()}</button><div className="month-tasks">{dayTasks.slice(0, 4).map(task => <button key={task.id} className={'month-task ' + (task.done ? 'done' : '')} style={{ '--task-color': task.color } as React.CSSProperties} onClick={e => { e.stopPropagation(); task.routineId ? void toggleDone(task.id) : edit(task) }}><span>{task.time}</span> {task.title}</button>)}{dayTasks.length > 4 && <span className="month-more">+{dayTasks.length - 4} más</span>}</div></div>})}</div></section>}<div className="tip"><span>✦</span><p><strong>Consejo de estudio</strong><br />Haz clic en una franja horaria para crear una sesión con esa hora.</p><button>×</button></div></> : screen === 'routines' ? (initialDataLoaded ? <RoutinesView routines={routines} onBack={() => setScreen('overview')} onEditRoutine={editRoutine} onToggleRoutine={toggleRoutine} onDeleteRoutine={deleteRoutine} busy={Boolean(busyAction)} /> : <div className="calendar-loading">{error ? 'No se pudieron cargar las rutinas.' : 'Cargando rutinas…'}</div>) : (overviewLoading || !overviewLoaded || !initialDataLoaded) ? <div className="calendar-loading">{error ? 'No se pudieron cargar las tareas.' : 'Cargando tareas…'}</div> : screen === 'projects' ? <ProjectsView tasks={overviewTasks} projects={projects} categoryName={categoryName} onBack={() => setScreen('overview')} onNewProject={() => openProject()} onEditProject={openProject} onDeleteProject={deleteProject} onEdit={edit} onToggle={toggleDone} onDelete={deleteTask} /> : <TaskOverview busy={Boolean(busyAction)} onOpenRoutines={() => setScreen('routines')} onOpenProjects={() => setScreen('projects')} tasks={overviewTasks} routines={routines} projects={projects} categoryName={categoryName} onNewProject={() => openProject()} onEditProject={openProject} onDeleteProject={deleteProject} onEdit={edit} onToggle={toggleDone} onDelete={deleteTask} onEditRoutine={editRoutine} onToggleRoutine={toggleRoutine} onDeleteRoutine={deleteRoutine} />}</main>{isModal && <div className="modal-backdrop" onClick={() => setIsModal(false)}><div className={"modal " + (draft.taskType === 'routine' ? 'routine-modal' : '')} onClick={e => e.stopPropagation()}><div className="modal-head"><div><p className="eyebrow">{draft.taskType === 'routine' ? (selectedRoutine ? 'EDITAR RUTINA' : 'NUEVA RUTINA') : selected ? 'EDITAR TAREA' : 'NUEVA TAREA'}</p><h2>{draft.taskType === 'routine' ? (selectedRoutine ? 'Editar rutina' : 'Nueva rutina') : selected ? 'Ajusta tu tarea' : '¿Qué quieres estudiar?'}</h2></div><button onClick={() => setIsModal(false)}>×</button></div>{error && <div className="error-banner" role="alert">{error}</div>}<label>Título<input autoFocus value={draft.title} onChange={e => setDraft({ ...draft, title: e.target.value })} placeholder="Ej. Repasar tema 3" /></label><div className="form-grid">{draft.taskType !== 'routine' && <label>Categoría<select value={draft.categoryId || ''} onChange={e => { const category = categories.find(item => item.id === e.target.value); setDraft({ ...draft, categoryId: e.target.value || null, color: category?.color || draft.color }) }}>{categories.map(category => <option key={category.id} value={category.id}>{category.name}</option>)}<option value="">Sin categoría</option></select></label>}<label>Tipo de tarea<select value={draft.taskType} disabled={Boolean(selectedRoutine) || screen === 'routines'} onChange={e => setDraft({ ...draft, taskType: e.target.value as TaskType })}><option value="daily">Tareas del día</option><option value="routine">Rutina</option><option value="project">Proyectos</option></select></label>{draft.taskType === 'project' && <label>Proyecto<select value={draft.projectId || ''} onChange={e => { const project = projects.find(item => item.id === e.target.value); setDraft({ ...draft, projectId: e.target.value || null, color: project?.color || draft.color }) }}><option value="">{projects.length ? 'Selecciona un proyecto' : 'Crea primero un proyecto'}</option>{projects.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}</select></label>}<label>{draft.taskType === 'routine' ? 'Fecha inicial' : 'Fecha'}<input type="date" disabled={Boolean(selectedRoutine)} value={draft.date} onChange={e => setDraft({ ...draft, date: e.target.value })} /></label></div><div className="form-grid"><TimePicker label="Hora de inicio" value={draft.time} onChange={time => setDraft({ ...draft, time })} /><TimePicker label="Hora de finalización" value={draft.endTime} onChange={endTime => setDraft({ ...draft, endTime })} /></div>{draft.taskType !== 'routine' && <label className="completed-toggle"><input type="checkbox" checked={draft.done} onChange={e => setDraft({ ...draft, done: e.target.checked })} /><span>Marcar como completada</span></label>}{draft.taskType === 'routine' && <label className="completed-toggle"><input type="checkbox" checked={routineActive} onChange={e => setRoutineActive(e.target.checked)} /><span>Rutina activa</span></label>}<label>Color<div className="color-picker">{colors.map(c => <button type="button" key={c} style={{ background: c }} className={draft.color === c ? 'selected' : ''} onClick={() => setDraft({ ...draft, color: c })} />)}</div></label><div className="modal-actions"><button className="cancel" onClick={() => setIsModal(false)}>Cancelar</button><button className="save" disabled={busyAction === 'save'} onClick={() => void save()}>{busyAction === 'save' ? 'Guardando…' : draft.taskType === 'routine' ? 'Guardar rutina' : 'Guardar tarea'}</button></div></div></div>}{categoryModal && <div className="modal-backdrop" onClick={() => setCategoryModal(false)}><div className="modal category-modal" onClick={e => e.stopPropagation()}><div className="modal-head"><div><p className="eyebrow">{categorySelected ? 'EDITAR CATEGORÍA' : 'NUEVA CATEGORÍA'}</p><h2>{categorySelected ? 'Ajusta la categoría' : 'Nueva categoría'}</h2></div><button onClick={() => setCategoryModal(false)}>×</button></div><label>Nombre<input autoFocus value={categoryDraft.name} onChange={e => setCategoryDraft({ ...categoryDraft, name: e.target.value })} placeholder="Ej. Física" /></label><label>Color<div className="color-picker">{colors.map(c => <button type="button" key={c} style={{ background: c }} className={categoryDraft.color === c ? 'selected' : ''} onClick={() => setCategoryDraft({ ...categoryDraft, color: c })} />)}</div></label><div className="modal-actions"><button className="cancel" onClick={() => setCategoryModal(false)}>Cancelar</button><button className="save" disabled={Boolean(busyAction)} onClick={() => void saveCategory()}>{busyAction === 'category' ? 'Guardando…' : 'Guardar categoría'}</button></div></div></div>}{projectModal && <div className="modal-backdrop" onClick={() => setProjectModal(false)}><div className="modal project-modal" onClick={e => e.stopPropagation()}><div className="modal-head"><div><p className="eyebrow">{projectSelected ? 'EDITAR PROYECTO' : 'NUEVO PROYECTO'}</p><h2>{projectSelected ? 'Ajusta el proyecto' : 'Crea un proyecto'}</h2></div><button onClick={() => setProjectModal(false)}>×</button></div><label>Nombre<input autoFocus value={projectDraft.name} onChange={e => setProjectDraft({ ...projectDraft, name: e.target.value })} placeholder="Ej. Preparar oposición" /></label><label>Color<div className="color-picker">{colors.map(c => <button type="button" key={c} style={{ background: c }} className={projectDraft.color === c ? 'selected' : ''} onClick={() => setProjectDraft({ ...projectDraft, color: c })} />)}</div></label><div className="modal-actions"><button className="cancel" onClick={() => setProjectModal(false)}>Cancelar</button><button className="save" disabled={busyAction === 'project'} onClick={() => void saveProject()}>{busyAction === 'project' ? 'Guardando…' : 'Guardar proyecto'}</button></div></div></div>}</div>
}

function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [checking, setChecking] = useState(true)
  useEffect(() => { if (!supabase) { setChecking(false); return } supabase.auth.getSession().then(({ data }) => { setSession(data.session); setChecking(false) }); const { data: listener } = supabase.auth.onAuthStateChange((_event, nextSession) => setSession(nextSession)); return () => listener.subscription.unsubscribe() }, [])
  if (!supabase) return <div className="auth-screen"><div className="auth-card"><h1>Configuración pendiente</h1><p className="auth-copy">Copia las variables de Supabase desde <code>.env.example</code> a <code>.env</code> para conectar la aplicación.</p></div></div>
  if (checking) return <div className="auth-screen"><div className="auth-card"><p>Cargando sesión…</p></div></div>
  return session ? <CalendarApp session={session} /> : <AuthPanel />
}

export default App
