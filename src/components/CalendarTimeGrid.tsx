import { useEffect, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react'
import type { Task } from '../lib/taskTypes'
import { HOUR_HEIGHT, SLOT_MINUTES, minutes, scheduleAtMinute, type TaskSchedule } from '../lib/calendarDrag'

type Preview = { task: Task; schedule: TaskSchedule; valid: boolean; keyboard: boolean }
type Gesture = { task: Task; pointerId: number; x: number; y: number; offset: number; active: boolean }
type Props = {
  days: Date[]; today: string; loading: boolean; busy: boolean; daily: boolean; mobileTimeline: boolean; tasks: Task[]
  renderTask: (task: Task, handle: ReactNode) => ReactNode
  onNew: (date: string, time: string) => void
  onMove: (task: Task, schedule: TaskSchedule) => Promise<boolean>
}
const iso = (day: Date) => `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`
const hours = Array.from({ length: 24 }, (_, hour) => hour)

export function CalendarTimeGrid({ days, today, loading, busy, daily, mobileTimeline, tasks, renderTask, onNew, onMove }: Props) {
  const rootRef = useRef<HTMLElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const gestureRef = useRef<Gesture | null>(null)
  const previewRef = useRef<Preview | null>(null)
  const [preview, setPreview] = useState<Preview | null>(null)
  const [saving, setSaving] = useState(false)
  const savingRef = useRef(false)
  const suppressClickUntil = useRef(0)
  const mounted = useRef(true)
  const range = days.map(iso).join(':')
  const positionedRange = useRef('')
  const keyboardTask = useRef<string | null>(null)
  const latest = useRef({ onMove, busy })
  latest.current = { onMove, busy }

  const showPreview = (value: Preview | null) => {
    const current = previewRef.current
    if (current && value && current.task.id === value.task.id && current.valid === value.valid && current.keyboard === value.keyboard &&
      current.schedule.date === value.schedule.date && current.schedule.time === value.schedule.time && current.schedule.endTime === value.schedule.endTime) return
    previewRef.current = value
    setPreview(value)
  }
  const releasePointer = () => {
    const gesture = gestureRef.current
    gestureRef.current = null
    if (gesture && rootRef.current?.hasPointerCapture(gesture.pointerId)) rootRef.current.releasePointerCapture(gesture.pointerId)
  }
  const cancel = () => {
    if (gestureRef.current?.active) suppressClickUntil.current = Date.now() + 400
    releasePointer()
    showPreview(null)
  }
  const commit = async () => {
    const current = previewRef.current
    releasePointer()
    if (!current || !current.valid || savingRef.current) { showPreview(null); return }
    const { task, schedule } = current
    if (schedule.date === task.date && schedule.time === task.time && schedule.endTime === task.endTime) { showPreview(null); return }
    savingRef.current = true
    setSaving(true)
    try { await latest.current.onMove(task, schedule) } finally {
      savingRef.current = false
      if (mounted.current) { setSaving(false); showPreview(null) }
    }
  }

  useEffect(() => {
    mounted.current = true
    let frame = 0
    let pointer = { x: 0, y: 0 }
    const updateTarget = () => {
      const gesture = gestureRef.current
      const scroller = scrollRef.current
      if (!gesture?.active || !scroller) return
      const viewport = scroller.getBoundingClientRect()
      const column = Array.from(rootRef.current!.querySelectorAll<HTMLElement>('.day-column')).find(element => {
        const rect = element.getBoundingClientRect()
        return pointer.x >= Math.max(rect.left, viewport.left + 72) && pointer.x < Math.min(rect.right, viewport.right)
      })
      const bottom = Math.min(viewport.bottom, window.innerHeight - (window.matchMedia('(max-width: 650px)').matches ? 76 : 0))
      const inside = pointer.y >= Math.max(0, viewport.top + 70) && pointer.y <= bottom
      const target = column && inside ? scheduleAtMinute(gesture.task, column.dataset.date!,
        (pointer.y - column.getBoundingClientRect().top) / HOUR_HEIGHT * 60 - gesture.offset) : null
      if (target) showPreview({ task: gesture.task, schedule: target, valid: true, keyboard: false })
      else if (previewRef.current) showPreview({ ...previewRef.current, valid: false })
    }
    const autoScroll = () => {
      const scroller = scrollRef.current
      if (!gestureRef.current?.active || !scroller) return
      const rect = scroller.getBoundingClientRect()
      const bottom = Math.min(rect.bottom, window.innerHeight - (window.matchMedia('(max-width: 650px)').matches ? 76 : 0))
      if (pointer.x >= rect.left - 20 && pointer.x <= rect.right + 20 && pointer.y >= rect.top - 20 && pointer.y <= bottom + 20) {
        const speed = (value: number, low: number, high: number) => value < low + 36 ? -Math.min(12, (low + 36 - value) / 3) : value > high - 36 ? Math.min(12, (value - high + 36) / 3) : 0
        scroller.scrollBy(speed(pointer.x, rect.left + 72, rect.right), speed(pointer.y, Math.max(0, rect.top + 70), bottom))
        updateTarget()
      }
      frame = requestAnimationFrame(autoScroll)
    }
    const move = (event: PointerEvent) => {
      const gesture = gestureRef.current
      if (!gesture || gesture.pointerId !== event.pointerId) return
      pointer = { x: event.clientX, y: event.clientY }
      if (!gesture.active) {
        if (Math.hypot(pointer.x - gesture.x, pointer.y - gesture.y) < 6) return
        gesture.active = true
        rootRef.current?.setPointerCapture(gesture.pointerId)
        suppressClickUntil.current = Date.now() + 400
        frame = requestAnimationFrame(autoScroll)
      }
      event.preventDefault()
      updateTarget()
    }
    const up = (event: PointerEvent) => {
      const gesture = gestureRef.current
      if (!gesture || gesture.pointerId !== event.pointerId) return
      cancelAnimationFrame(frame)
      if (gesture.active) {
        pointer = { x: event.clientX, y: event.clientY }
        updateTarget()
        suppressClickUntil.current = Date.now() + 400
        void commit()
      } else releasePointer()
    }
    const abort = () => { cancelAnimationFrame(frame); if (!savingRef.current) cancel() }
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && (gestureRef.current || previewRef.current) && !savingRef.current) { event.preventDefault(); abort() }
    }
    const visibility = () => { if (document.visibilityState !== 'visible') abort() }
    window.addEventListener('pointermove', move, { passive: false })
    window.addEventListener('pointerup', up)
    window.addEventListener('pointercancel', abort)
    window.addEventListener('keydown', key)
    window.addEventListener('blur', abort)
    document.addEventListener('visibilitychange', visibility)
    return () => {
      mounted.current = false
      cancelAnimationFrame(frame)
      releasePointer()
      previewRef.current = null
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', up)
      window.removeEventListener('pointercancel', abort)
      window.removeEventListener('keydown', key)
      window.removeEventListener('blur', abort)
      document.removeEventListener('visibilitychange', visibility)
    }
  }, [range, daily, mobileTimeline])

  useEffect(() => { if (!savingRef.current) showPreview(null) }, [range, daily, mobileTimeline])
  useEffect(() => {
    if (previewRef.current && !savingRef.current && !tasks.some(task => task.id === previewRef.current!.task.id)) cancel()
  }, [tasks])
  useEffect(() => {
    if (loading || !scrollRef.current?.clientWidth || positionedRange.current === range) return
    const first = tasks.length ? Math.min(...tasks.map(task => minutes(task.time))) : 8 * 60
    scrollRef.current.scrollTop = Math.max(0, first / 60 * HOUR_HEIGHT - 60)
    if (!daily && scrollRef.current.scrollWidth > scrollRef.current.clientWidth) {
      const currentDay = Array.from(rootRef.current!.querySelectorAll<HTMLElement>('.day-column')).find(column => column.dataset.date === today)
      if (currentDay) scrollRef.current.scrollLeft = Math.max(0, currentDay.offsetLeft - 72)
    }
    positionedRange.current = range
  }, [range, loading, mobileTimeline, daily, today])
  useEffect(() => {
    if (preview?.keyboard) keyboardTask.current = preview.task.id
    if (!keyboardTask.current) return
    const button = Array.from(rootRef.current!.querySelectorAll<HTMLButtonElement>('[data-task-move]')).find(item => item.dataset.taskMove === keyboardTask.current)
    button?.focus({ preventScroll: true })
    if (button && scrollRef.current) {
      const rect = button.getBoundingClientRect(), viewport = scrollRef.current.getBoundingClientRect()
      const bottom = Math.min(viewport.bottom, window.innerHeight - (window.matchMedia('(max-width: 650px)').matches ? 76 : 0))
      scrollRef.current.scrollBy(rect.left < viewport.left + 72 ? rect.left - viewport.left - 84 : rect.right > viewport.right ? rect.right - viewport.right + 12 : 0,
        rect.top < viewport.top + 70 ? rect.top - viewport.top - 82 : rect.bottom > bottom ? rect.bottom - bottom + 12 : 0)
    }
    if (!preview) keyboardTask.current = null
  }, [preview])

  const begin = (event: ReactPointerEvent<HTMLElement>) => {
    if (latest.current.busy || savingRef.current || loading || event.button !== 0 || !event.isPrimary) return
    const target = event.target as HTMLElement
    const handle = target.closest('[data-task-move]')
    if ((!handle && target.closest('button, input, select, a')) || (event.pointerType !== 'mouse' && !handle)) return
    const element = target.closest<HTMLElement>('[data-task-id]')
    const task = tasks.find(item => item.id === element?.dataset.taskId)
    const column = element?.closest<HTMLElement>('.day-column')
    if (!task || !column || !scheduleAtMinute(task, task.date, minutes(task.time))) return
    showPreview(null)
    gestureRef.current = { task, pointerId: event.pointerId, x: event.clientX, y: event.clientY,
      offset: (event.clientY - column.getBoundingClientRect().top) / HOUR_HEIGHT * 60 - minutes(task.time), active: false }
  }

  const keyboardMove = (event: React.KeyboardEvent<HTMLButtonElement>, task: Task) => {
    if (busy || savingRef.current || gestureRef.current || event.altKey || event.ctrlKey || event.metaKey) return
    if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter', ' '].includes(event.key)) return
    event.preventDefault()
    const current = previewRef.current?.task.id === task.id && previewRef.current.keyboard ? previewRef.current : null
    if (current && ['Enter', ' '].includes(event.key)) { void commit(); return }
    const base = current?.schedule || task
    const index = days.findIndex(day => iso(day) === base.date)
    const nextIndex = Math.max(0, Math.min(days.length - 1, index + (event.key === 'ArrowLeft' ? -1 : event.key === 'ArrowRight' ? 1 : 0)))
    const delta = event.key === 'ArrowUp' ? -SLOT_MINUTES : event.key === 'ArrowDown' ? SLOT_MINUTES : 0
    const schedule = scheduleAtMinute(current?.task || task, iso(days[nextIndex]), minutes(base.time) + delta)
    if (schedule) showPreview({ task: current?.task || task, schedule, valid: true, keyboard: true })
  }

  const displayedTasks = tasks.map(task => preview?.task.id === task.id ? { ...task, ...preview.schedule } : task)
  const styles = new Map<string, CSSProperties>()
  for (const day of days) {
    const group = displayedTasks.filter(task => task.date === iso(day)).sort((a, b) => a.time.localeCompare(b.time))
    let cluster: { task: Task; lane: number }[] = []
    let laneEnds: number[] = []
    let clusterEnd = 0
    const layout = () => {
      for (const { task, lane } of cluster) {
        const start = minutes(task.time), duration = minutes(task.endTime) - start
        styles.set(task.id, { top: start / 60 * HOUR_HEIGHT + 6, height: Math.max(52, duration / 60 * HOUR_HEIGHT - 8),
          width: `calc(${100 / laneEnds.length}% - 7px)`, left: `calc(${lane * 100 / laneEnds.length}% + 4px)` })
      }
    }
    for (const task of group) {
      const start = minutes(task.time), end = Math.max(start + 60 / HOUR_HEIGHT * 60, minutes(task.endTime))
      if (start >= clusterEnd) { layout(); cluster = []; laneEnds = []; clusterEnd = 0 }
      const available = laneEnds.findIndex(value => value <= start)
      const lane = available < 0 ? laneEnds.length : available
      laneEnds[lane] = end
      cluster.push({ task, lane })
      clusterEnd = Math.max(clusterEnd, end)
    }
    layout()
  }

  return <section ref={rootRef} className={`calendar time-grid ${daily ? 'day-calendar' : 'week-calendar'}${mobileTimeline ? ' show-mobile-time-grid' : ''}${preview ? ' is-dragging' : ''}`}
    aria-label={daily ? 'Calendario diario' : 'Calendario semanal'} aria-busy={busy || saving}
    onPointerDownCapture={begin} onClickCapture={event => { if (Date.now() < suppressClickUntil.current || savingRef.current || busy) { suppressClickUntil.current = 0; event.preventDefault(); event.stopPropagation() } }}>
    <p className="calendar-drag-status" role="status" aria-live="polite">{saving ? 'Guardando horario…' : preview ?
      `${preview.task.title} · ${new Date(preview.schedule.date + 'T12:00:00').toLocaleDateString('es-ES', { weekday: 'short', day: 'numeric', month: 'short' })} · ${preview.schedule.time} — ${preview.schedule.endTime}${preview.valid ? preview.keyboard ? ' · Enter para guardar; Esc para cancelar.' : '' : ' · Suelta dentro del calendario para moverla.'}` :
      'Arrastra una tarea para moverla en intervalos de 15 minutos. En móvil, usa el asa ↕.'}</p>
    {loading ? <div className="calendar-loading" role="status">Cargando tareas…</div> : <div className="time-grid-scroll" ref={scrollRef}>
      <div className="time-grid-content"><div className="calendar-head"><div className="timezone">Hora local</div>{days.map(day => <div key={iso(day)} className={'day-head ' + (iso(day) === today ? 'today' : '')}><span>{day.toLocaleDateString('es-ES', { weekday: 'short' }).replace('.', '').toUpperCase()}</span><strong>{day.getDate()}</strong></div>)}</div>
        <div className="calendar-body"><div className="time-column">{hours.map(hour => <div key={hour}>{String(hour).padStart(2, '0')}:00</div>)}</div>{days.map(day => <div key={iso(day)} className="day-column" data-date={iso(day)}>
          <div className="hour-lines">{hours.map(hour => <button key={hour} aria-label={`Crear tarea a las ${hour}:00`} onClick={() => onNew(iso(day), `${String(hour).padStart(2, '0')}:00`)} />)}</div>
          {displayedTasks.filter(task => task.date === iso(day)).map(task => <div key={task.id} data-task-id={task.id} className={'positioned-task' + (preview?.task.id === task.id ? ' moving-task' : '')} style={styles.get(task.id)}>
            {renderTask(task, <button type="button" className="calendar-task-move" data-task-move={task.id} aria-label={`Mover tarea ${task.title}`} aria-pressed={preview?.task.id === task.id} title="Arrastrar; con teclado, usa las flechas y Enter" disabled={busy || saving}
              onClick={event => event.stopPropagation()} onKeyDown={event => keyboardMove(event, task)}>↕</button>)}
          </div>)}
        </div>)}</div>
      </div>
    </div>}
  </section>
}
