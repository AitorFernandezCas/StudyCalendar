import { useEffect, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react'
import type { Task } from '../lib/taskTypes'
import { HOUR_HEIGHT, SLOT_MINUTES, minutes, clockTime, scheduleAtMinute, type TaskSchedule } from '../lib/calendarDrag'
import { timeSegments, visibleTimedTask } from '../lib/calendarPreferences'

type Preview = { task: Task; schedule: TaskSchedule; valid: boolean; keyboard: boolean }
type Gesture = { task: Task; pointerId: number; x: number; y: number; offset: number; active: boolean }
type Props = {
  days: Date[]; today: string; loading: boolean; busy: boolean; daily: boolean; mobileTimeline: boolean; tasks: Task[]
  startMinute: number; endMinute: number
  renderTask: (task: Task, handle: ReactNode) => ReactNode
  renderAllDayTask: (task: Task, handle: ReactNode) => ReactNode
  onNew: (date: string, time: string) => void
  onNewAllDay: (date: string) => void
  onMove: (task: Task, schedule: TaskSchedule) => Promise<boolean>
}
const iso = (day: Date) => `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`

const timedSchedule = (task: Task, date: string, target: number, start: number, end: number): TaskSchedule | null => {
  if (!task.allDay) return scheduleAtMinute(task, date, target, start, end)
  if (!Number.isFinite(target)) return null
  const schedule = scheduleAtMinute({ time: '00:00', endTime: '01:00' }, date, target, start, end)
  return schedule ? { ...schedule, allDay: false } : null
}

export function CalendarTimeGrid({ days, today, loading, busy, daily, mobileTimeline, tasks, startMinute, endMinute, renderTask, renderAllDayTask, onNew, onNewAllDay, onMove }: Props) {
  const rootRef = useRef<HTMLElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const gestureRef = useRef<Gesture | null>(null)
  const previewRef = useRef<Preview | null>(null)
  const [preview, setPreview] = useState<Preview | null>(null)
  const [saving, setSaving] = useState(false)
  const [now, setNow] = useState(() => new Date())
  const savingRef = useRef(false)
  const suppressClickUntil = useRef(0)
  const mounted = useRef(true)
  const range = `${days.map(iso).join(':')}:${startMinute}:${endMinute}`
  const segments = timeSegments(startMinute, endMinute)
  const gridHeight = (endMinute - startMinute) / 60 * HOUR_HEIGHT
  const positionedRange = useRef('')
  const keyboardTask = useRef<string | null>(null)
  const latest = useRef({ onMove, busy })
  latest.current = { onMove, busy }
  const currentDate = iso(now)
  const currentTime = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
  const nowMinute = now.getHours() * 60 + now.getMinutes()
  const currentTimeTop = (nowMinute - startMinute) / 60 * HOUR_HEIGHT

  useEffect(() => {
    let timer: number
    const refresh = () => {
      setNow(new Date())
      window.clearTimeout(timer)
      timer = window.setTimeout(refresh, 60_000 - Date.now() % 60_000 + 50)
    }
    const onVisible = () => { if (document.visibilityState === 'visible') refresh() }
    refresh()
    window.addEventListener('focus', refresh)
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      window.clearTimeout(timer)
      window.removeEventListener('focus', refresh)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [])

  const showPreview = (value: Preview | null) => {
    const current = previewRef.current
    if (current && value && current.task.id === value.task.id && current.valid === value.valid && current.keyboard === value.keyboard &&
      current.schedule.date === value.schedule.date && current.schedule.time === value.schedule.time && current.schedule.endTime === value.schedule.endTime && current.schedule.allDay === value.schedule.allDay) return
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
    if (schedule.date === task.date && schedule.time === task.time && schedule.endTime === task.endTime && (schedule.allDay ?? false) === task.allDay) { showPreview(null); return }
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
      const allDayColumn = Array.from(rootRef.current!.querySelectorAll<HTMLElement>('.all-day-column')).find(element => {
        const rect = element.getBoundingClientRect()
        return pointer.x >= Math.max(rect.left, viewport.left + 72) && pointer.x < Math.min(rect.right, viewport.right) &&
          pointer.y >= Math.max(rect.top, viewport.top + 70) && pointer.y < Math.min(rect.bottom, viewport.bottom, window.innerHeight)
      })
      if (allDayColumn) {
        showPreview({ task: gesture.task, schedule: { date: allDayColumn.dataset.date!, time: gesture.task.time, endTime: gesture.task.endTime, allDay: true }, valid: true, keyboard: false })
        return
      }
      const column = Array.from(rootRef.current!.querySelectorAll<HTMLElement>('.day-column')).find(element => {
        const rect = element.getBoundingClientRect()
        return pointer.x >= Math.max(rect.left, viewport.left + 72) && pointer.x < Math.min(rect.right, viewport.right)
      })
      const bottom = Math.min(viewport.bottom, window.innerHeight - (window.matchMedia('(max-width: 650px)').matches ? 76 : 0))
      const inside = pointer.y >= Math.max(0, viewport.top + 70, column?.getBoundingClientRect().top ?? 0) && pointer.y <= bottom
      const target = column && inside ? timedSchedule(gesture.task, column.dataset.date!,
        startMinute + (pointer.y - column.getBoundingClientRect().top) / HOUR_HEIGHT * 60 - gesture.offset, startMinute, endMinute) : null
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
    const scroller = scrollRef.current
    const body = rootRef.current!.querySelector<HTMLElement>('.calendar-body')!
    const header = rootRef.current!.querySelector<HTMLElement>('.calendar-head')!
    const localNow = new Date()
    const minute = localNow.getHours() * 60 + localNow.getMinutes()
    const bodyTop = body.getBoundingClientRect().top - scroller.getBoundingClientRect().top + scroller.scrollTop
    const headerHeight = header.getBoundingClientRect().height
    const visibleOffset = headerHeight + (scroller.clientHeight - headerHeight) * .4
    scroller.scrollTop = Math.max(0, bodyTop + (Math.max(startMinute, Math.min(endMinute, minute)) - startMinute) / 60 * HOUR_HEIGHT - visibleOffset)
    if (!daily && scroller.scrollWidth > scroller.clientWidth) {
      const currentDay = Array.from(rootRef.current!.querySelectorAll<HTMLElement>('.day-column')).find(column => column.dataset.date === iso(localNow))
      if (currentDay) scroller.scrollLeft = Math.max(0, currentDay.offsetLeft - 72)
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
    const column = element?.closest<HTMLElement>('.day-column, .all-day-column')
    if (!task || !column || (!task.allDay && !scheduleAtMinute(task, task.date, minutes(task.time)))) return
    showPreview(null)
    gestureRef.current = { task, pointerId: event.pointerId, x: event.clientX, y: event.clientY,
      offset: task.allDay ? 0 : startMinute + (event.clientY - column.getBoundingClientRect().top) / HOUR_HEIGHT * 60 - minutes(task.time), active: false }
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
    const schedule = base.allDay && delta === 0 ? { date: iso(days[nextIndex]), time: base.time, endTime: base.endTime, allDay: true } :
      timedSchedule(current?.task || task, iso(days[nextIndex]), minutes(base.time) + delta, startMinute, endMinute)
    if (schedule) showPreview({ task: current?.task || task, schedule, valid: true, keyboard: true })
  }

  const displayedTasks = tasks.map(task => preview?.task.id === task.id ? { ...task, ...preview.schedule, allDay: preview.schedule.allDay ?? false } : task)
  const styles = new Map<string, CSSProperties>()
  for (const day of days) {
    const group = displayedTasks.filter(task => !task.allDay && task.date === iso(day) && visibleTimedTask(task, startMinute, endMinute)).sort((a, b) => a.time.localeCompare(b.time))
    let cluster: { task: Task; lane: number }[] = []
    let laneEnds: number[] = []
    let clusterEnd = 0
    const layout = () => {
      for (const { task, lane } of cluster) {
        const start = Math.max(startMinute, minutes(task.time)), end = Math.min(endMinute, minutes(task.endTime))
        const height = (end - start) / 60 * HOUR_HEIGHT
        styles.set(task.id, { top: (start - startMinute) / 60 * HOUR_HEIGHT, height: Math.min(gridHeight - (start - startMinute) / 60 * HOUR_HEIGHT, Math.max(Math.min(52, height), height - 8)),
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

  const moveHandle = (task: Task) => <button type="button" className="calendar-task-move" data-task-move={task.id} aria-label={`Mover tarea ${task.title}`} aria-pressed={preview?.task.id === task.id} title="Arrastrar; con teclado, usa las flechas y Enter" disabled={busy || saving}
    onClick={event => event.stopPropagation()} onKeyDown={event => keyboardMove(event, task)}>↕</button>

  return <section ref={rootRef} className={`calendar time-grid ${daily ? 'day-calendar' : 'week-calendar'}${mobileTimeline ? ' show-mobile-time-grid' : ''}${preview ? ' is-dragging' : ''}`}
    aria-label={daily ? 'Calendario diario' : 'Calendario semanal'} aria-busy={busy || saving}
    onPointerDownCapture={begin} onClickCapture={event => { if (Date.now() < suppressClickUntil.current || savingRef.current || busy) { suppressClickUntil.current = 0; event.preventDefault(); event.stopPropagation() } }}>
    <p className="calendar-drag-announcement" role="status" aria-live="polite">{saving ? 'Guardando horario…' : preview ?
      `${preview.task.title} · ${new Date(preview.schedule.date + 'T12:00:00').toLocaleDateString('es-ES', { weekday: 'short', day: 'numeric', month: 'short' })} · ${preview.schedule.allDay ? 'Todo el día' : preview.schedule.time + ' — ' + preview.schedule.endTime}${preview.valid ? preview.keyboard ? ' · Enter para guardar; Esc para cancelar.' : '' : ' · Suelta dentro del calendario para moverla.'}` :
      ''}</p>
    {loading ? <div className="calendar-loading" role="status">Cargando tareas…</div> : <div className="time-grid-scroll" ref={scrollRef}>
      <div className="time-grid-content"><div className="calendar-head"><div className="timezone"><span>Hora local</span><time className="calendar-clock" dateTime={now.toISOString()} aria-label={'Hora actual: ' + currentTime}>{currentTime}</time></div>{days.map(day => <div key={iso(day)} className={'day-head ' + (iso(day) === currentDate ? 'today' : '')}><span>{day.toLocaleDateString('es-ES', { weekday: 'short' }).replace('.', '').toUpperCase()}</span><strong>{day.getDate()}</strong></div>)}</div>
        <div className="calendar-all-day" aria-label="Tareas de todo el día"><div className="all-day-label">Todo el día</div>{days.map(day => <div key={iso(day)} className="all-day-column" data-date={iso(day)} onClick={event => { if (event.target === event.currentTarget && !busy && !saving) onNewAllDay(iso(day)) }}>
          {displayedTasks.filter(task => task.allDay && task.date === iso(day)).sort((a, b) => a.title.localeCompare(b.title, 'es')).map(task => <div key={task.id} data-task-id={task.id} className={preview?.task.id === task.id ? 'moving-task' : ''}>{renderAllDayTask(task, moveHandle(task))}</div>)}
          <button className="all-day-create" disabled={busy || saving} aria-label={'Crear tarea de todo el día el ' + iso(day)} onClick={() => onNewAllDay(iso(day))}>＋</button>
        </div>)}</div>
        <div className="calendar-body" style={{ '--grid-height': `${gridHeight}px` } as CSSProperties}><div className="time-column">{segments.map(segment => <div key={segment.minute} style={{ height: segment.duration / 60 * HOUR_HEIGHT }}>{clockTime(segment.minute)}</div>)}</div>{days.map(day => <div key={iso(day)} className="day-column" data-date={iso(day)}>
          <div className="hour-lines">{segments.map(segment => <button key={segment.minute} style={{ height: segment.duration / 60 * HOUR_HEIGHT }} aria-label={`Crear tarea a las ${clockTime(segment.minute)}`} onClick={() => onNew(iso(day), clockTime(segment.minute))} />)}</div>
          {iso(day) === currentDate && nowMinute >= startMinute && nowMinute < endMinute && <div className="current-time-marker" style={{ top: currentTimeTop }} aria-label={'Ahora, ' + currentTime}>
            <span className="current-time-dot" aria-hidden="true" /><span className="current-time-label">{currentTime}</span>
          </div>}
          {displayedTasks.filter(task => !task.allDay && task.date === iso(day) && visibleTimedTask(task, startMinute, endMinute)).map(task => <div key={task.id} data-task-id={task.id} className={'positioned-task' + (preview?.task.id === task.id ? ' moving-task' : '')} style={styles.get(task.id)}>
            {renderTask(task, moveHandle(task))}
          </div>)}
        </div>)}</div>
      </div>
    </div>}
  </section>
}
