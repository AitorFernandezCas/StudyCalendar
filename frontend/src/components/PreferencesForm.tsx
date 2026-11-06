import { useEffect, useId, useRef, useState } from 'react'
import { ApiError, type ApiSettings, type SettingsChanges } from '../lib/api'
import type { useSettings } from '../lib/useSettings'

type Draft = Omit<ApiSettings, 'timezone'>
const editable = ({ timezone: _timezone, ...values }: ApiSettings): Draft => values

export function PreferencesForm({ settings }: { settings: ReturnType<typeof useSettings> }) {
  const id = useId()
  const formRef = useRef<HTMLFormElement>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [fields, setFields] = useState<Record<string, string>>({})
  const [message, setMessage] = useState('')
  const [saveError, setSaveError] = useState('')
  const dirty = useRef(new Set<keyof Draft>())
  useEffect(() => {
    if (settings.data) {
      const values = editable(settings.data)
      setDraft(current => {
        if (!current) return values
        for (const name of dirty.current) Object.assign(values, { [name]: current[name] })
        return values
      })
    }
  }, [settings.data])
  if (!draft || !settings.data) return <div className="settings-card configuration-load-state" role={settings.error ? 'alert' : 'status'}>
    <h2>Preferencias</h2>
    <p>{settings.error || 'Cargando configuración…'}</p>{settings.error && <button className="cancel" onClick={() => void settings.refresh()}>Reintentar</button>}
  </div>
  const change = (name: keyof Draft, value: string) => {
    dirty.current.add(name)
    setDraft(current => current ? { ...current, [name]: value } : current)
    setFields({})
    setMessage('')
    setSaveError('')
  }
  const hasChanges = (Object.keys(draft) as (keyof Draft)[]).some(name => draft[name] !== settings.data![name])
  const timeField = (name: keyof Draft, label: string, hint?: string) => {
    const fieldId = id + '-' + name
    const describedBy = [hint && fieldId + '-hint', fields[name] && fieldId + '-error'].filter(Boolean).join(' ') || undefined
    return <div className={'preference-field' + (hint ? ' preference-row' : '')}>
      <div className="preference-field-copy"><label htmlFor={fieldId}>{label}</label>{hint && <p id={fieldId + '-hint'}>{hint}</p>}</div>
      <div className="preference-input-group">{draft[name] === '24:00' ? <output id={fieldId} className="time-output" aria-label={label} aria-describedby={describedBy}>24:00 · Medianoche</output> : <input id={fieldId} name={name} type="time" aria-label={label} step="60" required value={draft[name]} disabled={settings.saving} aria-invalid={Boolean(fields[name])} aria-describedby={describedBy} onChange={event => change(name, event.target.value)} />}{fields[name] && <span id={fieldId + '-error'} className="settings-field-error">{fields[name]}</span>}</div>
    </div>
  }
  return <form ref={formRef} className="preferences-form" aria-label="Preferencias de rutinas y calendario" onSubmit={async event => {
    event.preventDefault()
    if (draft.calendar_start_time >= draft.calendar_end_time) {
      setFields({ calendar_end_time: 'La hora de fin debe ser posterior al inicio.' })
      formRef.current?.querySelector<HTMLInputElement>('[name="calendar_end_time"]')?.focus()
      return
    }
    const changes: SettingsChanges = {}
    for (const name of dirty.current) {
      if (draft[name] !== settings.data![name]) Object.assign(changes, { [name]: draft[name] })
    }
    if (!Object.keys(changes).length) { setMessage('No hay cambios pendientes.'); return }
    setSaveError(''); setFields({}); setMessage('')
    try {
      const saved = await settings.save(changes)
      if (saved) { dirty.current.clear(); setDraft(editable(saved)); setMessage('Configuración guardada.'); }
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'No se pudo guardar la configuración.')
      if (e instanceof ApiError) setFields(e.fields)
    }
  }}>
    <section className="settings-card preferences-section" aria-labelledby={id + '-routines'}>
      <div className="configuration-card-heading"><div><h2 id={id + '-routines'}>Rutinas</h2><p className="settings-description">Elige cuándo empieza el día de tus hábitos.</p></div><span className="settings-timezone" aria-label={'Zona horaria: ' + settings.data.timezone}>{settings.data.timezone}</span></div>
      <div className="configuration-card-body">{timeField('routine_reset_time', 'Hora de reinicio del día', 'Antes de esta hora, las completaciones cuentan para el día anterior.')}
        <p className="settings-hint">El cambio se aplica al guardar. Las completaciones anteriores conservan su fecha.</p>
      </div>
    </section>
    <section className="settings-card preferences-section" aria-labelledby={id + '-calendar'}>
      <div className="configuration-card-heading"><div><h2 id={id + '-calendar'}>Calendario</h2><p className="settings-description">Adapta el horario y el comienzo de la semana.</p></div></div>
      <div className="configuration-card-body">
        <div className="settings-fields">{timeField('calendar_start_time', 'Hora de inicio')}{timeField('calendar_end_time', 'Hora de fin')}</div>
        <label className="settings-midnight"><input name="calendar_midnight" type="checkbox" checked={draft.calendar_end_time === '24:00'} disabled={settings.saving} onChange={event => change('calendar_end_time', event.target.checked ? '24:00' : '23:59')} />Terminar a medianoche (24:00)</label>
        <div className="preference-field preference-row preference-week-start"><div className="preference-field-copy"><label htmlFor={id + '-week-start'}>La semana empieza en</label><p>Se aplica a las vistas del calendario.</p></div><div className="preference-input-group"><select id={id + '-week-start'} name="week_start" aria-label="La semana empieza en" value={draft.week_start} disabled={settings.saving} aria-invalid={Boolean(fields.week_start)} aria-describedby={fields.week_start ? id + '-week-start-error' : undefined} onChange={event => change('week_start', event.target.value)}><option value="monday">Lunes</option><option value="sunday">Domingo</option></select>{fields.week_start && <span id={id + '-week-start-error'} className="settings-field-error">{fields.week_start}</span>}</div></div>
        <p className="settings-hint">Las tareas fuera del horario siguen disponibles en el mes y las demás secciones.</p>
      </div>
    </section>
    <div className="settings-save"><div className="settings-save-feedback">{saveError ? <p className="settings-field-error" role="alert">{saveError}</p> : message ? <p role="status">{message}</p> : <p role="status">{hasChanges ? 'Tienes cambios sin guardar.' : 'Sin cambios pendientes.'}</p>}</div><div className="settings-save-actions"><button type="button" className="settings-discard" disabled={settings.saving || !hasChanges} onClick={() => { dirty.current.clear(); setDraft(editable(settings.data!)); setFields({}); setSaveError(''); setMessage('Cambios descartados.') }}>Descartar</button><button type="submit" className="save" disabled={settings.saving || !hasChanges}>{settings.saving ? 'Guardando…' : 'Guardar cambios'}</button></div></div>
  </form>
}
