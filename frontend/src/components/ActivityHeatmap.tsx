import { useEffect, useId, useRef, useState } from 'react'
import type { Session } from '@supabase/supabase-js'
import type { ApiActivityDay } from '../lib/api'
import { useActivity } from '../lib/useActivity'
import { dateInTimezone } from '../lib/useRoutineHabits'
import { useStoredSections } from '../lib/useStoredSections'
import { activityDayIndex, activityTone, summarizeActivity } from '../lib/activity'

const labels = { complete: 'Todo completado', partial: 'Avance parcial', pending: 'Sin completar', future: 'Día futuro', empty: 'Sin tareas' }
const describe = (day: ApiActivityDay) => {
  const date = new Date(`${day.date}T12:00:00`).toLocaleDateString('es-ES', { day: 'numeric', month: 'long', year: 'numeric' })
  return `${date}: ${labels[activityTone(day)]}${day.status === 'future' || !day.total ? '' : ` · ${day.completed} de ${day.total} completadas`}`
}

export function ActivityHeatmap({ session, revision }: { session: Session; revision: number }) {
  const { expanded, toggle } = useStoredSections('activity-expanded:' + session.user.id, [])
  const id = useId()
  return <section className="activity-card activity-history">
    <button className="activity-toggle" aria-expanded={expanded.has('activity')} aria-controls={id} onClick={() => toggle('activity')}>
      <span><strong>Tu actividad diaria</strong><small>Tu historial de tareas y rutinas.</small></span>
      <span className="activity-toggle-label"><span className="activity-toggle-text">{expanded.has('activity') ? 'Ocultar historial' : 'Ver historial'}</span><svg className="project-chevron" aria-hidden="true" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="m6 8 4 4 4-4" /></svg></span>
    </button>
    <div id={id} hidden={!expanded.has('activity')}>{expanded.has('activity') && <ActivityChart session={session} revision={revision} />}</div>
  </section>
}

function ActivityChart({ session, revision }: { session: Session; revision: number }) {
  const [year, setYear] = useState(() => Number(dateInTimezone('Europe/Madrid').slice(0, 4)))
  const [selected, setSelected] = useState<string | null>(null)
  const [focused, setFocused] = useState<string | null>(null)
  const { snapshot, loading, error, refresh } = useActivity(session, year, revision)
  const scrollRef = useRef<HTMLDivElement>(null)
  const positionedYear = useRef<number | null>(null)
  const titleId = useId()
  const yearId = useId()
  const keyboardId = useId()
  const offset = (new Date(year, 0, 1).getDay() + 6) % 7
  const columns = Math.ceil((offset + (snapshot?.days.length || 366)) / 7)
  const currentYear = Number((snapshot?.date || dateInTimezone('Europe/Madrid')).slice(0, 4))
  const detail = snapshot?.days.find(day => day.date === selected) || snapshot?.days.find(day => day.date === snapshot.date) || snapshot?.days[0]
  const focusDate = focused && snapshot?.days.some(day => day.date === focused) ? focused : detail?.date
  const summary = snapshot ? summarizeActivity(snapshot.days, snapshot.date) : null
  const detailIndex = snapshot?.days.findIndex(day => day.date === detail?.date) ?? -1
  const changeYear = (next: number) => { setYear(next); setSelected(null); setFocused(null) }
  const selectDay = (date: string) => {
    setSelected(date); setFocused(date)
    const viewport = scrollRef.current
    const button = viewport?.querySelector<HTMLElement>(`[data-date="${date}"]`)
    if (!viewport || !button) return
    const left = button.getBoundingClientRect().left - viewport.getBoundingClientRect().left + viewport.scrollLeft
    if (left < viewport.scrollLeft + 4) viewport.scrollLeft = Math.max(0, left - 4)
    else if (left + button.offsetWidth > viewport.scrollLeft + viewport.clientWidth - 4) viewport.scrollLeft = left + button.offsetWidth - viewport.clientWidth + 4
  }

  useEffect(() => {
    if (!snapshot) { positionedYear.current = null; return }
    if (positionedYear.current === year || !scrollRef.current) return
    const today = scrollRef.current.querySelector<HTMLElement>('[data-today="true"]')
    if (today) scrollRef.current.scrollLeft = Math.max(0, today.offsetLeft - scrollRef.current.clientWidth / 2)
    else scrollRef.current.scrollLeft = 0
    positionedYear.current = year
  }, [snapshot, year])

  return <section className="activity-chart-panel" aria-labelledby={titleId}>
    <div className="activity-heading"><div><h2 id={titleId}>Resumen del año</h2><p>Tareas personales, proyectos y rutinas.</p></div>
      <div className="activity-year"><button aria-label="Ver año anterior" disabled={year <= 1900} onClick={() => changeYear(year - 1)}>‹</button><span id={yearId}>{year}</span><button aria-label="Ver año siguiente" disabled={year >= currentYear} onClick={() => changeYear(year + 1)}>›</button><button className="activity-current-year" disabled={year === currentYear} onClick={() => changeYear(currentYear)}>Este año</button></div>
    </div>
    {error && <div className="activity-message" role="alert">{error} <button onClick={() => void refresh()}>Reintentar</button></div>}
    {!snapshot ? !error && <div className="activity-message" role="status">Cargando actividad…</div> : <div className="activity-content">
      {summary && <dl className="activity-summary"><div><dt>Completadas</dt><dd>{summary.completed}</dd></div><div><dt>Días con avances</dt><dd>{summary.daysWithProgress}</dd></div><div><dt>Días al día</dt><dd>{summary.completeDays}</dd></div></dl>}
      <div className="activity-map-caption"><p id={keyboardId} className="activity-keyboard-hint">Flechas para recorrer los días · Inicio y Fin para ir a los extremos del año.</p><span className="activity-refresh" role="status">{loading ? 'Actualizando…' : ''}</span></div>
      <div className="activity-scroll" ref={scrollRef} role="region" aria-label={`Actividad de ${year}, desliza para ver todo el año`}>
        <div className="activity-chart" style={{ '--activity-columns': columns } as React.CSSProperties}>
          <div className="activity-months">{Array.from({ length: 12 }, (_, month) => {
            const day = new Date(year, month, 1)
            const index = Math.round((Date.UTC(year, month, 1) - Date.UTC(year, 0, 1)) / 86400000)
            return <span key={month} style={{ gridColumn: Math.floor((offset + index) / 7) + 1 }}>{day.toLocaleDateString('es-ES', { month: 'short' })}</span>
          })}</div>
          <div className="activity-weekdays" aria-hidden="true"><span>Lun</span><span>Mié</span><span>Vie</span></div>
          <div className="activity-grid" role="group" aria-label="Días del año" aria-describedby={`${yearId} ${keyboardId}`}>{snapshot.days.map((day, index) => <button
            key={day.date} className={`activity-cell ${activityTone(day)}${day.date === snapshot.date ? ' today' : ''}`}
            style={{ gridColumn: Math.floor((offset + index) / 7) + 1, gridRow: (offset + index) % 7 + 1 }}
            data-date={day.date} data-today={day.date === snapshot.date} title={describe(day)} aria-label={describe(day)}
            aria-current={day.date === snapshot.date ? 'date' : undefined}
            tabIndex={day.date === focusDate ? 0 : -1} onFocus={() => setFocused(day.date)}
            onKeyDown={event => {
              const next = activityDayIndex(index, event.key, snapshot.days.length)
              if (next === null) return
              event.preventDefault()
              const date = snapshot.days[next].date
              setFocused(date); setSelected(date)
              const button = event.currentTarget.parentElement?.querySelector<HTMLButtonElement>(`[data-date="${date}"]`)
              button?.focus({ preventScroll: true }); button?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
            }} aria-pressed={day.date === detail?.date} onClick={() => selectDay(day.date)} />)}</div>
        </div>
      </div>
      <div className="activity-legend">{(['complete', 'partial', 'pending', 'empty', 'future'] as const).map(status => <span key={status}><i className={`activity-cell ${status}`} aria-hidden="true" />{labels[status]}</span>)}</div>
      {detail && <div className="activity-day-panel">
        <div className="activity-day-copy" aria-live="polite" aria-atomic="true"><time dateTime={detail.date}>{new Date(detail.date + 'T12:00:00').toLocaleDateString('es-ES', { weekday: 'long', day: 'numeric', month: 'long' })}{detail.date === snapshot.date && <span>Hoy</span>}</time>
          <strong>{detail.status === 'future' ? 'Día futuro' : detail.total ? `${detail.completed} de ${detail.total} completadas` : 'Sin tareas ni rutinas'}</strong>
          <p>{detail.status === 'future' ? 'Este día todavía no ha llegado.' : !detail.total ? 'No hay actividad prevista para este día.' : detail.total === detail.completed ? 'Todo lo previsto para este día está completado.' : `${detail.total - detail.completed} ${(detail.total - detail.completed) === 1 ? 'pendiente' : 'pendientes'} para este día.`}</p>
          {detail.total > 0 && detail.status !== 'future' && <div className={'activity-day-progress ' + activityTone(detail)} role="progressbar" aria-label={'Avance del ' + detail.date} aria-valuenow={Math.round(detail.completed / detail.total * 100)} aria-valuemin={0} aria-valuemax={100} aria-valuetext={`${detail.completed} de ${detail.total} completadas`}><i style={{ width: detail.completed / detail.total * 100 + '%' }} /></div>}
        </div>
        <div className="activity-day-navigation"><button aria-label="Ver día anterior" disabled={detailIndex <= 0} onClick={() => selectDay(snapshot.days[detailIndex - 1].date)}>‹</button><button aria-label="Ver día siguiente" disabled={detailIndex >= snapshot.days.length - 1} onClick={() => selectDay(snapshot.days[detailIndex + 1].date)}>›</button></div>
      </div>}
      <p className="activity-hint">Desliza el mapa para ver todo el año. Toca un día o usa los botones para consultar su detalle.</p>
    </div>}
  </section>
}
