import { useEffect, useMemo, useState } from 'react'

type Task = { id: string; title: string; subject: string; date: string; time: string; endTime: string; color: string; done: boolean }
const colors = ['#7c5cff', '#ee7b6f', '#f2b84b', '#3fba91', '#4f8ff7']
const subjects = ['Matemáticas', 'Historia', 'Biología', 'Inglés', 'Programación']
const hours = Array.from({ length: 14 }, (_, i) => i + 8)
const pad = (n: number) => String(n).padStart(2, '0')
const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
const toMinutes = (value: string) => { const [hour, minute] = value.split(':').map(Number); return hour * 60 + minute }
const initialDate = new Date(2025, 9, 6)
const seed: Task[] = [
  { id: '1', title: 'Repasar derivadas', subject: 'Matemáticas', date: '2025-10-06', time: '09:00', endTime: '10:30', color: '#7c5cff', done: false },
  { id: '2', title: 'Lectura capítulo 4', subject: 'Historia', date: '2025-10-06', time: '11:30', endTime: '12:15', color: '#ee7b6f', done: false },
  { id: '3', title: 'Práctica de laboratorio', subject: 'Biología', date: '2025-10-07', time: '10:00', endTime: '12:00', color: '#3fba91', done: true },
  { id: '4', title: 'Redacción essay', subject: 'Inglés', date: '2025-10-08', time: '16:00', endTime: '17:30', color: '#f2b84b', done: false },
  { id: '5', title: 'Ejercicios de arrays', subject: 'Programación', date: '2025-10-09', time: '18:30', endTime: '20:00', color: '#4f8ff7', done: false },
]

function TaskCard({ task, onEdit, onToggle }: { task: Task; onEdit: (task: Task) => void; onToggle: (id: string) => void }) {
  return <article draggable onDragStart={e => e.dataTransfer.setData('task', task.id)} onClick={e => { e.stopPropagation(); onEdit(task) }} className={'task-card ' + (task.done ? 'done' : '')} style={{ '--task-color': task.color } as React.CSSProperties}>
    <div className="task-time">{task.time} — {task.endTime || task.time}</div><div className="task-title">{task.title}</div><div className="task-meta"><span>{task.subject}</span><button aria-label={task.done ? 'Marcar como pendiente' : 'Marcar como completada'} onClick={e => { e.stopPropagation(); onToggle(task.id) }}>{task.done ? '✓' : '○'}</button></div>
  </article>
}

function App() {
  const [weekStart, setWeekStart] = useState(initialDate)
  const [tasks, setTasks] = useState<Task[]>(() => { try { const saved = JSON.parse(localStorage.getItem('study-tasks') || 'null'); return saved ? saved.map((t: Task) => ({ ...t, endTime: t.endTime || t.time })) : seed } catch { return seed } })
  const [selected, setSelected] = useState<string | null>(null)
  const [isModal, setIsModal] = useState(false)
  const [filter, setFilter] = useState('Todas')
  const [draft, setDraft] = useState<Omit<Task, 'id'>>({ title: '', subject: 'Matemáticas', date: iso(initialDate), time: '09:00', endTime: '10:00', color: colors[0], done: false })
  useEffect(() => localStorage.setItem('study-tasks', JSON.stringify(tasks)), [tasks])
  const days = useMemo(() => Array.from({ length: 7 }, (_, i) => { const d = new Date(weekStart); d.setDate(d.getDate() + i); return d }), [weekStart])
  const weekLabel = `${weekStart.toLocaleDateString('es-ES', { month: 'short', day: 'numeric' })} — ${days[6].toLocaleDateString('es-ES', { month: 'short', day: 'numeric', year: 'numeric' })}`
  const visible = (date: string) => tasks.filter(t => t.date === date && (filter === 'Todas' || t.subject === filter)).sort((a, b) => a.time.localeCompare(b.time))
  const completed = tasks.filter(t => t.done).length
  const openNew = (date = iso(weekStart), time = '09:00') => { setSelected(null); setDraft({ title: '', subject: 'Matemáticas', date, time, endTime: `${pad(Number(time.slice(0, 2)) + 1)}:${time.slice(3)}`, color: colors[0], done: false }); setIsModal(true) }
  const edit = (task: Task) => { setSelected(task.id); setDraft({ title: task.title, subject: task.subject, date: task.date, time: task.time, endTime: task.endTime || task.time, color: task.color, done: task.done }); setIsModal(true) }
  const save = () => { if (!draft.title.trim()) return; setTasks(prev => selected ? prev.map(t => t.id === selected ? { ...t, ...draft } : t) : [...prev, { ...draft, id: crypto.randomUUID() }]); setIsModal(false) }
  const shift = (delta: number) => { const d = new Date(weekStart); d.setDate(d.getDate() + delta * 7); setWeekStart(d) }
  const toggleDone = (id: string) => setTasks(prev => prev.map(t => t.id === id ? { ...t, done: !t.done } : t))
  const moveTask = (id: string, date: string) => setTasks(prev => prev.map(t => t.id === id ? { ...t, date } : t))
  const taskStyle = (task: Task, dayTasks: Task[]) => {
    const start = toMinutes(task.time), end = Math.max(start + 30, toMinutes(task.endTime || task.time))
    const overlapping = dayTasks.filter(other => other.id !== task.id && toMinutes(other.time) < end && toMinutes(other.endTime || other.time) > start)
    const taskIndex = dayTasks.findIndex(other => other.id === task.id)
    const before = dayTasks.slice(0, taskIndex).filter(other => toMinutes(other.time) < end && toMinutes(other.endTime || other.time) > start).length
    const columns = Math.max(1, overlapping.length + 1)
    return { top: `${((start - 8 * 60) / 60) * 84 + 6}px`, height: `${Math.max(52, ((end - start) / 60) * 84 - 8)}px`, width: `calc(${100 / columns}% - 7px)`, left: `calc(${(before * 100) / columns}% + 4px)` }
  }

  return <div className="app-shell">
    <aside className="sidebar"><div className="brand"><div className="brand-mark">✦</div><span>Study<span>Calendar</span></span></div><nav><button className="nav-item active"><span>▦</span> Calendario</button><button className="nav-item"><span>✓</span> Todas las tareas <b>{tasks.length}</b></button><button className="nav-item"><span>◷</span> Próximas</button></nav><div className="side-section"><div className="side-title">MATERIAS <button>＋</button></div>{subjects.map((s, i) => <button className="subject" key={s} onClick={() => setFilter(filter === s ? 'Todas' : s)}><i style={{ background: colors[i] }} />{s}<span>{tasks.filter(t => t.subject === s).length}</span></button>)}</div><div className="side-bottom"><div className="progress-label"><span>Progreso semanal</span><strong>{Math.round((completed / Math.max(tasks.length, 1)) * 100)}%</strong></div><div className="progress"><i style={{ width: `${(completed / Math.max(tasks.length, 1)) * 100}%` }} /></div><p>{completed} de {tasks.length} tareas completadas</p><div className="user"><div className="avatar">AF</div><span>Aitor Fernández<small>Estudiante</small></span><b>⋮</b></div></div></aside>
    <main className="main"><header><div><p className="eyebrow">MI PLAN DE ESTUDIO</p><h1>Mi calendario</h1><p className="subtitle">Organiza tu semana y avanza con calma.</p></div><button className="add-button" onClick={() => openNew()}>＋ <span>Nueva tarea</span></button></header>
      <section className="toolbar"><div className="week-nav"><button onClick={() => shift(-1)}>‹</button><button onClick={() => setWeekStart(initialDate)}>Hoy</button><button onClick={() => shift(1)}>›</button><strong>{weekLabel}</strong></div><div className="view-tools"><select value={filter} onChange={e => setFilter(e.target.value)}><option>Todas</option>{subjects.map(s => <option key={s}>{s}</option>)}</select><button className="view-btn active">▦ Semana</button><button className="view-btn">▦ Mes</button></div></section>
      <section className="calendar"><div className="calendar-head"><div className="timezone">GMT +01:00</div>{days.map(d => <div key={iso(d)} className={'day-head ' + (iso(d) === iso(new Date()) ? 'today' : '')}><span>{d.toLocaleDateString('es-ES', { weekday: 'short' }).replace('.', '').toUpperCase()}</span><strong>{d.getDate()}</strong></div>)}</div><div className="calendar-body"><div className="time-column">{hours.map(hour => <div key={hour}>{pad(hour)}:00</div>)}</div>{days.map(d => { const dayTasks = visible(iso(d)); return <div key={iso(d)} className="day-column" onDragOver={e => e.preventDefault()} onDrop={e => { const id = e.dataTransfer.getData('task'); if (id) moveTask(id, iso(d)) }}><div className="hour-lines">{hours.map(hour => <button key={hour} aria-label={`Crear tarea a las ${hour}:00`} onClick={() => openNew(iso(d), `${pad(hour)}:00`)} />)}</div>{dayTasks.map(t => <TaskCard key={t.id} task={t} onEdit={edit} onToggle={toggleDone} />).map((card, i) => { const task = dayTasks[i]; return <div key={task.id} className="positioned-task" style={taskStyle(task, dayTasks)}>{card}</div> })}</div> })}</div></section><div className="tip"><span>✦</span><p><strong>Consejo de estudio</strong><br />Haz clic en una franja horaria para crear una sesión con esa hora.</p><button>×</button></div></main>
    {isModal && <div className="modal-backdrop" onClick={() => setIsModal(false)}><div className="modal" onClick={e => e.stopPropagation()}><div className="modal-head"><div><p className="eyebrow">{selected ? 'EDITAR TAREA' : 'NUEVA TAREA'}</p><h2>{selected ? 'Ajusta tu tarea' : '¿Qué quieres estudiar?'}</h2></div><button onClick={() => setIsModal(false)}>×</button></div><label>Título<input autoFocus value={draft.title} onChange={e => setDraft({ ...draft, title: e.target.value })} placeholder="Ej. Repasar tema 3" /></label><div className="form-grid"><label>Materia<select value={draft.subject} onChange={e => setDraft({ ...draft, subject: e.target.value })}>{subjects.map(s => <option key={s}>{s}</option>)}</select></label><label>Fecha<input type="date" value={draft.date} onChange={e => setDraft({ ...draft, date: e.target.value })} /></label></div><div className="form-grid"><label>Hora de inicio<input type="time" value={draft.time} onChange={e => setDraft({ ...draft, time: e.target.value })} /></label><label>Hora de finalización<input type="time" value={draft.endTime} onChange={e => setDraft({ ...draft, endTime: e.target.value })} /></label></div><label className="completed-toggle"><input type="checkbox" checked={draft.done} onChange={e => setDraft({ ...draft, done: e.target.checked })} /><span>Marcar como completada</span></label><label>Color<div className="color-picker">{colors.map(c => <button type="button" key={c} style={{ background: c }} className={draft.color === c ? 'selected' : ''} onClick={() => setDraft({ ...draft, color: c })} />)}</div></label><div className="modal-actions"><button className="cancel" onClick={() => setIsModal(false)}>Cancelar</button><button className="save" onClick={save}>Guardar tarea</button></div></div></div>}
  </div>
}
export default App
