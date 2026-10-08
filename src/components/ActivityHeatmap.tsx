import { useEffect, useId, useRef, useState } from 'react'
import type { Session } from '@supabase/supabase-js'
import type { ApiActivityDay } from '../lib/api'
import { useActivity } from '../lib/useActivity'
import { dateInTimezone } from '../lib/useRoutineHabits'

const labels = { complete: 'Todo completado', pending: 'Tareas pendientes', future: 'Día futuro', empty: 'Sin tareas' }
const describe = (day: ApiActivityDay) => {
  const date = new Date(`${day.date}T12:00:00`).toLocaleDateString('es-ES', { day: 'numeric', month: 'long', year: 'numeric' })
  return `${date}: ${labels[day.status]}${day.status === 'future' || !day.total ? '' : ` · ${day.completed} de ${day.total} completadas`}`
}

export function ActivityHeatmap({ session, revision }: { session: Session; revision: number }) {
  const [year, setYear] = useState(() => Number(dateInTimezone('Europe/Madrid').slice(0, 4)))
  const [selected, setSelected] = useState<string | null>(null)
  const { snapshot, error, refresh } = useActivity(session, year, revision)
  const scrollRef = useRef<HTMLDivElement>(null)
  const positionedYear = useRef<number | null>(null)
  const titleId = useId()
  const yearId = useId()
  const offset = (new Date(year, 0, 1).getDay() + 6) % 7
  const columns = Math.ceil((offset + (snapshot?.days.length || 366)) / 7)
  const currentYear = Number((snapshot?.date || dateInTimezone('Europe/Madrid')).slice(0, 4))
  const detail = snapshot?.days.find(day => day.date === (selected || snapshot.date))

  useEffect(() => {
    if (!snapshot) { positionedYear.current = null; return }
    if (positionedYear.current === year || !scrollRef.current) return
    const today = scrollRef.current.querySelector<HTMLElement>('[data-today="true"]')
    if (today) scrollRef.current.scrollLeft = Math.max(0, today.offsetLeft - scrollRef.current.clientWidth / 2)
    else scrollRef.current.scrollLeft = 0
    positionedYear.current = year
  }, [snapshot, year])

  return <section className="activity-card" aria-labelledby={titleId}>
    <div className="activity-heading"><div><h2 id={titleId}>Tu actividad diaria</h2><p>Tareas del día, proyectos y rutinas.</p></div>
      <div className="activity-year"><button aria-label="Ver año anterior" disabled={year <= 1900} onClick={() => { setYear(year - 1); setSelected(null) }}>‹</button><span id={yearId}>{year}</span><button aria-label="Ver año siguiente" disabled={year >= currentYear} onClick={() => { setYear(year + 1); setSelected(null) }}>›</button></div>
    </div>
    {error ? <div className="activity-message" role="alert">{error} <button onClick={() => void refresh()}>Reintentar</button></div> : !snapshot ? <div className="activity-message" role="status">Cargando actividad…</div> : <>
      <div className="activity-scroll" ref={scrollRef} role="region" aria-label={`Actividad de ${year}, desliza para ver todo el año`} tabIndex={0}>
        <div className="activity-chart" style={{ '--activity-columns': columns } as React.CSSProperties}>
          <div className="activity-months">{Array.from({ length: 12 }, (_, month) => {
            const day = new Date(year, month, 1)
            const index = Math.round((Date.UTC(year, month, 1) - Date.UTC(year, 0, 1)) / 86400000)
            return <span key={month} style={{ gridColumn: Math.floor((offset + index) / 7) + 1 }}>{day.toLocaleDateString('es-ES', { month: 'short' })}</span>
          })}</div>
          <div className="activity-weekdays" aria-hidden="true"><span>Lun</span><span>Mié</span><span>Vie</span></div>
          <div className="activity-grid" aria-describedby={yearId}>{snapshot.days.map((day, index) => <button
            key={day.date} className={`activity-cell ${day.status}${day.date === snapshot.date ? ' today' : ''}`}
            style={{ gridColumn: Math.floor((offset + index) / 7) + 1, gridRow: (offset + index) % 7 + 1 }}
            data-date={day.date} data-today={day.date === snapshot.date} title={describe(day)} aria-label={describe(day)}
            aria-pressed={day.date === selected} onClick={() => setSelected(day.date)} />)}</div>
        </div>
      </div>
      <p className="activity-detail" aria-live="polite">{detail ? describe(detail) : 'Toca un día para ver su detalle.'}</p>
      <div className="activity-legend">{(['complete', 'pending', 'future', 'empty'] as const).map(status => <span key={status}><i className={`activity-cell ${status}`} aria-hidden="true" />{labels[status]}</span>)}</div>
      <p className="activity-hint">Desliza la gráfica para ver todo el año.</p>
    </>}
  </section>
}
