import { useEffect, useRef, useState } from 'react'
import { ApiError, type ApiSettings, type SettingsChanges } from '../lib/api'
import type { useSettings } from '../lib/useSettings'

type Draft = Omit<ApiSettings, 'timezone'>
const editable = ({ timezone: _timezone, ...values }: ApiSettings): Draft => values

export function PreferencesForm({ settings }: { settings: ReturnType<typeof useSettings> }) {
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
  if (!draft || !settings.data) return <div className="settings-card" role={settings.error ? 'alert' : 'status'}>
    <p>{settings.error || 'Cargando configuración…'}</p>{settings.error && <button className="cancel" onClick={() => void settings.refresh()}>Reintentar</button>}
  </div>
  const change = (name: keyof Draft, value: string) => {
    dirty.current.add(name)
    setDraft(current => current ? { ...current, [name]: value } : current)
    setFields({})
    setMessage('')
    setSaveError('')
  }
  const timeField = (name: keyof Draft, label: string) => <label>{label}<input type="time" aria-label={label} step="60" required value={draft[name] === '24:00' ? '' : draft[name]} disabled={settings.saving || (name === 'calendar_end_time' && draft[name] === '24:00')} aria-invalid={Boolean(fields[name])} aria-describedby={fields[name] ? `setting-${name}-error` : undefined} onInput={event => change(name, event.currentTarget.value)} onChange={event => change(name, event.target.value)} />{fields[name] && <span id={`setting-${name}-error`} className="settings-field-error">{fields[name]}</span>}</label>
  return <form className="preferences-form" onSubmit={async event => {
    event.preventDefault()
    if (draft.calendar_start_time >= draft.calendar_end_time) { setFields({ calendar_end_time: 'La hora de fin debe ser posterior al inicio.' }); return }
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
    <div className="settings-card"><h2>Rutinas</h2><p className="settings-description">El día de todas tus rutinas cambia a esta hora ({settings.data.timezone}). Antes de esa hora, las completaciones cuentan para el día anterior.</p>
      {timeField('routine_reset_time', 'Hora de reinicio del día')}
      <p className="settings-hint">El cambio se aplica al guardar. Las completaciones anteriores conservan su fecha.</p>
    </div>
    <div className="settings-card"><h2>Calendario</h2><p className="settings-description">Personaliza las vistas de día y semana. Las tareas fuera del horario seguirán disponibles en el mes y las demás secciones.</p>
      <div className="settings-fields">{timeField('calendar_start_time', 'Hora de inicio')}{timeField('calendar_end_time', 'Hora de fin')}</div>
      <label className="settings-midnight"><input type="checkbox" checked={draft.calendar_end_time === '24:00'} disabled={settings.saving} onChange={event => change('calendar_end_time', event.target.checked ? '24:00' : '23:59')} />Terminar a medianoche (24:00)</label>
      <label>La semana empieza en<select aria-label="La semana empieza en" value={draft.week_start} disabled={settings.saving} onChange={event => change('week_start', event.target.value)}><option value="monday">Lunes</option><option value="sunday">Domingo</option></select>{fields.week_start && <span className="settings-field-error">{fields.week_start}</span>}</label>
    </div>
    <div className="settings-save"><button type="submit" className="save" disabled={settings.saving}>{settings.saving ? 'Guardando…' : 'Guardar cambios'}</button>{message && <p role="status">{message}</p>}{saveError && <p className="settings-field-error" role="alert">{saveError}</p>}</div>
  </form>
}
