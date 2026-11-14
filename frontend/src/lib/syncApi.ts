import type { Session } from '@supabase/supabase-js'
import { ApiError } from './api'

export type SyncBase = { revision: number; state: Record<string, unknown>; context?: Record<string, unknown> }
export type SyncOperation = { protocol: 1; operation_id: string; entity: string; id: string; action: string; base: SyncBase | null; changes: Record<string, unknown>; context: Record<string, unknown> }
export type SyncResult = { status: 'applied' | 'conflict' | 'rejected'; row?: Record<string, unknown>; deleted?: string; day?: Record<string, unknown>; reason?: string; message?: string; fields?: string[]; remote?: SyncBase | null }
export type SyncConflict = { userId: string; operation: SyncOperation; result: SyncResult }
type Snapshot = { protocol: number; owner: string; downloadedAt: string; data: Record<string, Record<string, unknown>[]>; settings: Record<string, unknown>; day: Record<string, unknown>; routines: Record<string, unknown>[] }
const enabled = new Set<string>()
const apiUrl = (import.meta.env.VITE_API_URL as string | undefined || 'http://localhost:5000').replace(/\/$/, '')

async function transport(path: string, session: Session, options?: RequestInit) {
  const response = await fetch(apiUrl + path, { ...options, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.access_token}` } })
  const body = await response.json().catch(() => { throw new ApiError('El servidor todavía no está disponible. Inténtalo de nuevo.', response.status) })
  return { response, body }
}

export async function readSynced(path: string, session: Session, signal?: AbortSignal): Promise<unknown | undefined> {
  if (!/^\/api\/(settings|routines|projects|categories|tasks|calendar|bootstrap)(\?|$)/.test(path)) return undefined
  const { response, body } = await transport('/api/sync/snapshot', session, { signal })
  if (response.status === 404 || (response.status === 503 && body.sync_unavailable)) { enabled.delete(session.user.id); return undefined }
  if (!response.ok) throw new ApiError(body.error || 'No se pudieron actualizar los datos.', response.status)
  const snapshot = body as Snapshot
  if (snapshot.protocol !== 1 || snapshot.owner !== session.user.id) throw new ApiError('La respuesta no pertenece a esta cuenta o requiere actualizar la aplicación.', 409)
  enabled.add(session.user.id)
  const settings = snapshot.settings
  const rawSettings = snapshot.data.user_settings[0]
  const settingsBase = rawSettings?._sync as SyncBase | undefined
  const context = { reset_time: settings.routine_reset_time, settings_revision: settingsBase?.revision || 0, day: snapshot.day.date }
  const routines = snapshot.routines.map(row => ({ ...row, _sync: { ...(row._sync as SyncBase), state: { ...(row._sync as SyncBase).state, completed_today: row.completed_today }, context: { ...context, routine_revision: (row._sync as SyncBase).revision } } })).sort((a, b) => String((a as Record<string, unknown>).title).localeCompare(String((b as Record<string, unknown>).title), 'es'))
  const tasks = snapshot.data.tasks.filter(row => row.task_type !== 'routine')
  const params = new URLSearchParams(path.split('?')[1])
  const ranged = tasks.filter(row => (!params.get('from') || (row.date && String(row.date) >= params.get('from')!)) && (!params.get('to') || (row.date && String(row.date) <= params.get('to')!)))
  const projects = [...snapshot.data.projects].sort((a, b) => String(a.name).localeCompare(String(b.name), 'es'))
  const categories = [...snapshot.data.category].sort((a, b) => String(a.name).localeCompare(String(b.name), 'es'))
  switch (path.split('?')[0]) {
    case '/api/settings': return { ...settings, _sync: settingsBase || { revision: 0, state: { ...settings, timezone: undefined } } }
    case '/api/routines': return { ...snapshot.day, routines }
    case '/api/projects': return { projects }
    case '/api/categories': return categories
    case '/api/tasks': return ranged
    case '/api/calendar': return { tasks: ranged }
    case '/api/bootstrap': return { tasks: ranged, projects, categories }
  }
}

function canonical(key: string, value: unknown): unknown {
  if ((key === 'start_time' || key === 'end_time') && typeof value === 'string' && /^\d{2}:\d{2}$/.test(value)) return value + ':00'
  if (key === 'prerequisite_ids' && Array.isArray(value)) return [...value].sort()
  return value
}
export function intendedChanges(base: SyncBase | undefined, raw: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(raw).filter(([key, value]) => key !== '_sync' && key !== 'sync' && key !== 'starts_on' && (base === undefined || JSON.stringify(canonical(key, value)) !== JSON.stringify(canonical(key, base.state[key])))).map(([key, value]) => [key, canonical(key, value)]))
}

export async function sendSync(session: Session, intent: Omit<SyncOperation, 'protocol' | 'operation_id'>): Promise<SyncResult> {
  // An explicit retry of the same intent recovers the same operation receipt.
  // The original day/recording context is restored from sessionStorage as well.
  const stableContext = { ...intent.context, recorded_at: undefined }
  const fingerprint = JSON.stringify({ ...intent, context: stableContext })
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(fingerprint))
  const key = `studycalendar-sync:${session.user.id}:` + [...new Uint8Array(digest)].map(v => v.toString(16).padStart(2, '0')).join('')
  const saved = sessionStorage.getItem(key)
  const operation: SyncOperation = saved ? JSON.parse(saved) : { ...intent, protocol: 1, operation_id: crypto.randomUUID(), id: intent.id || crypto.randomUUID() }
  sessionStorage.setItem(key, JSON.stringify(operation))
  const { response, body } = await transport('/api/sync/apply', session, { method: 'POST', body: JSON.stringify(operation) })
  if (!response.ok) throw new ApiError(body.error || 'No se pudo enviar el cambio. Reintenta para recuperar su resultado.', response.status)
  const result = body as SyncResult
  if (!['applied', 'conflict', 'rejected'].includes(result.status)) throw new ApiError('Respuesta de sincronización no válida.', 502)
  sessionStorage.removeItem(key)
  if (result.status !== 'applied') {
    window.dispatchEvent(new CustomEvent<SyncConflict>('studycalendar-sync-conflict', { detail: { userId: session.user.id, operation, result } }))
    throw new ApiError(result.message || 'Hay cambios simultáneos. Revisa el conflicto antes de continuar.', 409)
  }
  return result
}

export async function mutateSynced(path: string, session: Session, options: RequestInit, base?: SyncBase): Promise<unknown | undefined> {
  const match = path.match(/^\/api\/(tasks|projects|categories|routines|settings)(?:\/([^/]+))?(\/completion)?$/)
  if (!match || (!base && !enabled.has(session.user.id))) return undefined
  const entity = ({ categories: 'category', routines: 'routine', settings: 'user_settings' } as Record<string, string>)[match[1]] || match[1]
  const raw = options.body ? JSON.parse(String(options.body)) as Record<string, unknown> : {}
  const action = match[3] ? 'complete' : options.method === 'DELETE' ? 'delete' : options.method === 'POST' ? 'create' : 'update'
  if (action !== 'create' && !base) throw new ApiError('Actualiza los datos antes de editar. El borrador se conserva.', 409)
  const context = { ...base?.context, recorded_at: new Date().toISOString(), ...(raw.starts_on ? { starts_on: raw.starts_on } : {}) }
  const actualBase = action === 'complete' ? { revision: 0, state: { completed: base?.state.completed_today ?? false } } : base || null
  // Routine summary state stores its definition; today's completion is supplied by the caller.
  const result = await sendSync(session, { entity: action === 'complete' ? 'routine_completion' : entity, id: match[2] || (entity === 'user_settings' ? session.user.id : ''), action, base: actualBase, changes: action === 'complete' ? { completed: raw.completed } : intendedChanges(base, raw), context: { ...context, ...(action === 'complete' ? { day: raw.date } : {}) } })
  if (action === 'delete') return { deleted: result.deleted }
  if (action === 'complete') return { ...result.day, routine: result.row }
  if (entity === 'user_settings') return { ...result.row, timezone: 'Europe/Madrid' }
  if (entity === 'routine') {
    const row = result.row!
    const prior = row._sync as SyncBase
    return { ...row, _sync: { ...prior, state: { ...prior.state, completed_today: row.completed_today }, context: { ...base?.context, routine_revision: prior.revision } } }
  }
  return result.row
}
