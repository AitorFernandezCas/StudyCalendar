import { useEffect, useRef, useState } from 'react'
import type { Session } from '@supabase/supabase-js'
import { routinesApi, type ApiRoutine, type ApiRoutineHistory } from '../lib/api'

const dayFormat = new Intl.DateTimeFormat('es-ES', { dateStyle: 'full', timeZone: 'UTC' })
const summaryDateFormat = new Intl.DateTimeFormat('es-ES', { dateStyle: 'medium', timeZone: 'UTC' })
const monthFormat = new Intl.DateTimeFormat('es-ES', { month: 'long', year: 'numeric', timeZone: 'UTC' })
const formatDay = (date: string) => dayFormat.format(new Date(date))
const weekdays = ['Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb', 'Dom']
const statuses = {
  complete: { label: 'Completada', symbol: '✓' },
  missed: { label: 'Sin completar', symbol: '×' },
  paused: { label: 'En pausa', symbol: '–' },
  pending: { label: 'Pendiente hoy', symbol: '○' },
}

function dayStatus(date: string, today: string, routine: ApiRoutine, history: ApiRoutineHistory, completed: Set<string>, first?: string) {
  if (date > today) return
  if (date === today ? routine.completed_today : completed.has(date)) return 'complete'
  if (date === today && routine.due_today) return 'pending'
  if (!first || date < first) return
  if (date < today && history.periods.some(period => period.starts_on <= date && (!period.ends_on || period.ends_on >= date))) return 'missed'
  return 'paused'
}

export default function RoutineDetail({ session, routine, today, weekStart, busy, onEdit, onComplete }: {
  session: Session
  routine: ApiRoutine
  today: string
  weekStart: 'monday' | 'sunday'
  busy: boolean
  onEdit: () => void
  onComplete: () => void
}) {
  const [history, setHistory] = useState<ApiRoutineHistory>()
  const [error, setError] = useState(false)
  const [slow, setSlow] = useState(false)
  const [retry, setRetry] = useState(0)
  const [browsedMonth, setBrowsedMonth] = useState<string>()
  const [selected, setSelected] = useState<string>()
  const heading = useRef<HTMLHeadingElement>(null)
  const currentMonth = today.slice(0, 7)

  useEffect(() => { heading.current?.focus() }, [])

  useEffect(() => {
    const controller = new AbortController()
    setError(false)
    setSlow(false)
    const timer = window.setTimeout(() => setSlow(true), 10_000)
    void routinesApi.history(session, routine.id, controller.signal).then(data => {
      if (!controller.signal.aborted) setHistory(data)
    }).catch(() => {
      if (!controller.signal.aborted) setError(true)
    }).finally(() => {
      window.clearTimeout(timer)
    })
    return () => {
      controller.abort()
      window.clearTimeout(timer)
    }
  }, [session.access_token, routine, today, retry])

  const completed = new Set(history?.completed_dates.filter(date => date < today))
  // Today's summary reflects writes as soon as they return.
  if (routine.completed_today) completed.add(today)
  let lastCompleted = ''
  for (const date of completed) {
    if (date > lastCompleted) lastCompleted = date
  }
  const first = [...(history?.periods.map(period => period.starts_on) ?? []), ...completed].sort()[0]
  const firstMonth = first && first < today ? first.slice(0, 7) : currentMonth
  const month = browsedMonth && browsedMonth >= firstMonth && browsedMonth <= currentMonth ? browsedMonth : currentMonth
  const monthStart = new Date(month + '-01')
  const weekdayOffset = weekStart === 'monday' ? 1 : 0
  const blanks = (monthStart.getUTCDay() + 7 - weekdayOffset) % 7
  const monthEnd = new Date(monthStart)
  monthEnd.setUTCMonth(monthStart.getUTCMonth() + 1, 0)
  const labels = weekStart === 'monday' ? weekdays : [weekdays[6], ...weekdays.slice(0, 6)]
  const shift = (offset: number) => {
    const next = new Date(monthStart)
    next.setUTCMonth(next.getUTCMonth() + offset)
    setBrowsedMonth(next.toISOString().slice(0, 7))
    setSelected(undefined)
  }
  const selectedStatus = history && selected ? dayStatus(selected, today, routine, history, completed, first) : undefined

  return <section className="routine-detail">
    <div className="routine-detail-heading">
      <h2 ref={heading} tabIndex={-1}>{routine.title}</h2>
      <button className="routine-detail-action" disabled={busy} onClick={onEdit}>Editar</button>
    </div>
    <dl className="routine-detail-stats">
      <div><dt>Racha actual</dt><dd>{routine.current_streak} {routine.current_streak === 1 ? 'día' : 'días'}</dd></div>
      <div><dt>Racha máxima</dt><dd>{routine.max_streak} {routine.max_streak === 1 ? 'día' : 'días'}</dd></div>
      <div className="routine-detail-last-completion">
        <dt>Último día completado</dt>
        <dd>{lastCompleted ? <time dateTime={lastCompleted}>{summaryDateFormat.format(new Date(lastCompleted))}</time>
          : history ? 'Aún sin completados' : error ? 'No disponible' : 'Cargando…'}</dd>
      </div>
    </dl>
    {routine.due_today && <button className={routine.completed_today ? 'routine-detail-action' : 'routine-detail-action primary'} disabled={busy} onClick={onComplete}>
      {routine.completed_today ? 'Marcar hoy como pendiente' : 'Completar hoy'}
    </button>}
    {error && <div className="routine-load-state" role="alert"><p>No se pudo cargar el historial.</p><button onClick={() => setRetry(value => value + 1)}>Reintentar</button></div>}
    {!history && !error && <p role="status">{slow ? 'La conexión está tardando más de lo habitual' : 'Cargando historial…'}</p>}
    {history && <>
      {!first && <p>Esta rutina aún no tiene historial.</p>}
      {first && first > today && <p>Empieza el {formatDay(first)}.</p>}
      <div className="routine-history">
        <div className="routine-history-nav">
          <button aria-label="Mes anterior" disabled={month <= firstMonth} onClick={() => shift(-1)}>‹</button>
          <h3 aria-live="polite">{monthFormat.format(monthStart)}</h3>
          <button aria-label="Mes siguiente" disabled={month >= currentMonth} onClick={() => shift(1)}>›</button>
        </div>
        <div className="routine-history-grid" role="group" aria-label="Historial mensual">
          {labels.map(label => <span className="routine-history-weekday" key={label}>{label}</span>)}
          {Array.from({ length: blanks }, (_, index) => <span key={'empty-' + index} aria-hidden="true" />)}
          {Array.from({ length: monthEnd.getUTCDate() }, (_, index) => {
            const day = index + 1
            const date = month + '-' + String(day).padStart(2, '0')
            const status = dayStatus(date, today, routine, history, completed, first)
            if (!status) return <span key={date} className="routine-history-day" aria-label={formatDay(date)}>{day}</span>
            return <button key={date} className={'routine-history-day ' + status} aria-label={formatDay(date) + ', ' + statuses[status].label}
              aria-current={date === today ? 'date' : undefined} aria-pressed={date === selected} onClick={() => setSelected(date)}>
              {day}<span aria-hidden="true">{statuses[status].symbol}</span>
            </button>
          })}
        </div>
        <ul className="routine-history-legend" aria-label="Estados del historial">
          {Object.entries(statuses).map(([key, status]) => <li key={key}><span className={key} aria-hidden="true">{status.symbol}</span>{status.label}</li>)}
        </ul>
        {selected && selectedStatus && <p role="status">{formatDay(selected)} · {statuses[selectedStatus].label}</p>}
      </div>
    </>}
  </section>
}
