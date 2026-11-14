import { useEffect, useState } from 'react'
import type { Session } from '@supabase/supabase-js'
import { FormDialog } from './FormDialog'
import { sendSync, type SyncConflict } from '../lib/syncApi'

const labels: Record<string, string> = { title: 'Título', name: 'Nombre', date: 'Fecha', start_time: 'Inicio', end_time: 'Fin', completed: 'Completada', active: 'Activa', all_day: 'Todo el día', status: 'Estado', color: 'Color', project_id: 'Proyecto', category_id: 'Categoría', prerequisite_ids: 'Tareas previas', delete: 'Eliminación' }
const display = (value: unknown) => value === null || value === undefined ? 'Sin valor' : value === true ? 'Sí' : value === false ? 'No' : Array.isArray(value) ? value.join(', ') : String(value)

export function SyncConflicts({ session, onResolved }: { session: Session; onResolved: (conflict: SyncConflict) => void }) {
  const [queue, setQueue] = useState<SyncConflict[]>([])
  const [chosen, setChosen] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [visible, setVisible] = useState(true)
  const key = `studycalendar-conflicts:${session.user.id}`
  useEffect(() => {
    try { setQueue(JSON.parse(sessionStorage.getItem(key) || '[]')) } catch { setQueue([]) }
    const receive = (event: Event) => {
      const conflict = (event as CustomEvent<SyncConflict>).detail
      if (conflict.userId !== session.user.id) return
      setQueue(previous => {
        const next = [...previous.filter(item => item.operation.entity !== conflict.operation.entity || item.operation.id !== conflict.operation.id), conflict]
        sessionStorage.setItem(key, JSON.stringify(next))
        return next
      })
      setVisible(true)
    }
    window.addEventListener('studycalendar-sync-conflict', receive)
    return () => window.removeEventListener('studycalendar-sync-conflict', receive)
  }, [key, session.user.id])
  const conflict = queue[0]
  useEffect(() => { setChosen(conflict?.result.fields || []); setError('') }, [conflict?.operation.operation_id])
  if (!conflict) return null
  const { operation, result } = conflict
  const canChoose = result.reason === 'fields' && result.remote?.state
  const finish = () => {
    setQueue(previous => { const next = previous.filter(item => item.operation.operation_id !== operation.operation_id); sessionStorage.setItem(key, JSON.stringify(next)); return next })
    onResolved(conflict)
  }
  const apply = async () => {
    if (!canChoose) { finish(); return }
    const changes = Object.fromEntries(Object.entries(operation.changes).filter(([field]) => !(result.fields || []).includes(field) || chosen.includes(field)))
    if ((operation.action === 'delete' && !chosen.includes('delete')) || (operation.action !== 'delete' && Object.keys(changes).length === 0)) { finish(); return }
    setBusy(true)
    setError('')
    try { await sendSync(session, { ...operation, base: result.remote!, changes }); finish() }
    catch (failure) { setError(failure instanceof Error ? failure.message : 'No se pudo resolver. Reintenta para recuperar el resultado.') }
    finally { setBusy(false) }
  }
  if (!visible) return <button className="notice" onClick={() => setVisible(true)}>Revisar {queue.length} conflicto(s) pendiente(s)</button>
  return <FormDialog title="Revisar cambio pendiente" className="sync-conflict-modal" busy={busy} onClose={() => setVisible(false)} onSubmit={() => void apply()}>
    <p>{result.reason === 'deleted' ? 'El elemento fue eliminado. Este cambio no puede recrearlo.' : result.reason === 'availability' ? 'La disponibilidad de la rutina cambió. La marca conserva su fecha original y necesita revisión.' : canChoose ? 'Elige qué valor conservar en cada campo. Los cambios compatibles ya se combinan automáticamente.' : result.message || 'Revisa los datos antes de volver a enviarlos.'}</p>
    {operation.action === 'delete' && result.remote?.state && <section aria-label="Estado actual en Supabase">
      {['title', 'name', 'date', 'completed', 'status'].filter(field => field in result.remote!.state).map(field => <p key={field}>{labels[field] || field}: {display(result.remote!.state[field])}</p>)}
      {Array.isArray(result.remote.state.affected_tasks) && <><p>Tareas afectadas: {result.remote.state.affected_tasks.length}</p><ul>{result.remote.state.affected_tasks.map((task: { title: string; completed: boolean }, index: number) => <li key={index}>{task.title} · {task.completed ? 'Completada' : 'Pendiente'}</li>)}</ul></>}
      {typeof result.remote.state.completion_count === 'number' && <p>Días completados que se eliminarán: {result.remote.state.completion_count}</p>}
    </section>}
    {operation.entity === 'routine_completion' && <p>Día de la marca: {String(operation.context.day)}</p>}
    {canChoose ? (result.fields || []).map(field => <fieldset key={field}>
      <legend>{labels[field] || field}</legend>
      {field === 'delete' ? <p>El elemento o sus tareas cambiaron desde que confirmaste la eliminación. Revisa su estado actual antes de confirmar.</p> : <p>Web: {display(operation.changes[field])}<br />Supabase: {display(result.remote?.state[field])}</p>}
      <label><input type="radio" name={'conflict-' + field} checked={chosen.includes(field)} onChange={() => setChosen(previous => [...previous, field])} />{field === 'delete' ? 'Confirmar eliminación' : 'Conservar mi cambio'}</label>
      <label><input type="radio" name={'conflict-' + field} checked={!chosen.includes(field)} onChange={() => setChosen(previous => previous.filter(item => item !== field))} />{field === 'delete' ? 'Conservar elemento' : 'Conservar Supabase'}</label>
    </fieldset>) : <dl>{Object.entries(operation.changes).map(([field, value]) => <div key={field}><dt>{labels[field] || field}</dt><dd>{display(value)}</dd></div>)}</dl>}
    {error && <p role="alert">{error}</p>}
    <div className="modal-actions"><button type="button" disabled={busy} onClick={() => setVisible(false)}>Revisar más tarde</button><button type="submit" disabled={busy}>{canChoose && operation.action === 'delete' && chosen.includes('delete') ? 'Confirmar eliminación' : canChoose ? 'Aplicar selección' : 'Descartar este cambio'}</button></div>
  </FormDialog>
}
