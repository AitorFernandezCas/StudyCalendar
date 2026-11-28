import type { Session } from '@supabase/supabase-js'
import { readSynced, mutateSynced, type SyncBase } from './syncApi'
export type { SyncBase } from './syncApi'

export type ApiCategory = { _sync?: SyncBase; id: string; name: string; color: string }
export type ProjectStatus = 'active' | 'inactive' | 'completed'
export type ApiProject = { _sync?: SyncBase; id: string; name: string; color: string; status: ProjectStatus; created_at: string; updated_at: string }
export type ApiFeature = { _sync?: SyncBase; id: string; project_id: string; name: string; description: string | null }
export type ApiFeatureLink = { _sync?: SyncBase; id: string; project_id: string; source_feature_id: string; target_feature_id: string }
export type ApiTask = { _sync?: SyncBase; id: string; title: string; category_id: string | null; project_id: string | null; feature_id?: string | null; task_type: 'routine' | 'project' | 'daily'; date: string | null; start_time: string | null; end_time: string | null; prerequisite_ids?: string[]; all_day: boolean; color: string; completed: boolean }
export type ApiRoutine = { _sync?: SyncBase; id: string; title: string; color: string; active: boolean; created_at: string; updated_at: string; due_today: boolean; completed_today: boolean; current_streak: number; max_streak: number }
export type ApiRoutineSnapshot = { date: string; timezone: string; next_day_at: string; day_started_at: string; routines: ApiRoutine[] }
export type ApiRoutineHistory = {
  periods: { starts_on: string; ends_on: string | null }[]
  completed_dates: string[]
}
export type ApiSettings = { _sync?: SyncBase; routine_reset_time: string; calendar_start_time: string; calendar_end_time: string; week_start: 'monday' | 'sunday'; timezone: string }
export type SettingsChanges = Partial<Omit<ApiSettings, 'timezone' | '_sync'>>

export const settingsApi = {
  get: (session: Session, signal?: AbortSignal) => request<ApiSettings>('/api/settings', session, { signal }),
  update: (session: Session, changes: SettingsChanges, base?: SyncBase) => request<ApiSettings>('/api/settings', session, { method: 'PATCH', body: JSON.stringify(changes) }, base),
}
export type ApiRoutineCompletion = Omit<ApiRoutineSnapshot, 'routines'> & { routine: ApiRoutine }
export type ApiBootstrap = { categories: ApiCategory[]; projects: ApiProject[]; tasks: ApiTask[]; features?: ApiFeature[]; feature_links?: ApiFeatureLink[] | null }
export type ApiActivityDay = { date: string; total: number; completed: number; status: 'complete' | 'pending' | 'future' | 'empty' }
export type ApiActivitySnapshot = { year: number; date: string; timezone: string; next_day_at: string; days: ApiActivityDay[] }

export const activityApi = {
  load: (session: Session, year: number, signal?: AbortSignal) => request<ApiActivitySnapshot>(`/api/activity?year=${year}`, session, { signal }),
}

export class ApiError extends Error {
  constructor(message: string, public status: number, public fields: Record<string, string> = {}) { super(message) }
}

const apiUrl = (import.meta.env.VITE_API_URL as string | undefined || 'http://localhost:5000').replace(/\/$/, '')

async function request<T>(path: string, session: Session, options?: RequestInit, base?: SyncBase): Promise<T> {
  const synced = !options?.method || options.method === 'GET' ? await readSynced(path, session, options?.signal || undefined) : await mutateSynced(path, session, options, base)
  if (synced !== undefined) return synced as T
  const response = await fetch(`${apiUrl}${path}`, { ...options, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.access_token}`, ...options?.headers } })
  const body = await response.json().catch(() => { throw new ApiError('El servidor todavía no está disponible. Inténtalo de nuevo.', response.status) })
  if (!response.ok) throw new ApiError(body.error || 'No se pudo completar la operación', response.status, body.fields)
  return body as T
}

export const categoriesApi = {
  list: (session: Session) => request<ApiCategory[]>('/api/categories', session),
  create: (session: Session, category: Omit<ApiCategory, 'id'>) => request<ApiCategory>('/api/categories', session, { method: 'POST', body: JSON.stringify(category) }),
  update: (session: Session, id: string, category: Partial<Omit<ApiCategory, 'id'>>, base?: SyncBase) => request<ApiCategory>(`/api/categories/${id}`, session, { method: 'PATCH', body: JSON.stringify(category) }, base),
  remove: (session: Session, id: string, base?: SyncBase) => request<{ deleted: string }>(`/api/categories/${id}`, session, { method: 'DELETE' }, base),
}

export const projectsApi = {
  list: (session: Session) => request<{ projects: ApiProject[] }>('/api/projects', session),
  create: (session: Session, project: Pick<ApiProject, 'name' | 'color'> & { status?: ProjectStatus }) => request<ApiProject>('/api/projects', session, { method: 'POST', body: JSON.stringify(project) }),
  update: (session: Session, id: string, project: Partial<Omit<ApiProject, 'id' | 'created_at' | 'updated_at'>>, base?: SyncBase) => request<ApiProject>(`/api/projects/${id}`, session, { method: 'PATCH', body: JSON.stringify(project) }, base),
  remove: (session: Session, id: string, base?: SyncBase) => request<{ deleted: string }>(`/api/projects/${id}`, session, { method: 'DELETE' }, base),
}

export const tasksApi = {
  list: (session: Session, from?: string, to?: string, signal?: AbortSignal) => request<ApiTask[]>(`/api/tasks${from && to ? `?from=${from}&to=${to}` : ''}`, session, { signal }),
  create: (session: Session, task: Omit<ApiTask, 'id'>) => request<ApiTask>('/api/tasks', session, { method: 'POST', body: JSON.stringify(task) }),
  update: (session: Session, id: string, task: Partial<Omit<ApiTask, 'id'>>, base?: SyncBase) => request<ApiTask>(`/api/tasks/${id}`, session, { method: 'PATCH', body: JSON.stringify(task) }, base),
  remove: (session: Session, id: string, base?: SyncBase) => request<{ deleted: string }>(`/api/tasks/${id}`, session, { method: 'DELETE' }, base),
}

type FeatureDetails = Pick<ApiFeature, 'name' | 'description'>
export const featuresApi = {
  create: (session: Session, feature: FeatureDetails & { project_id: string }) => request<ApiFeature>('/api/project-features', session, { method: 'POST', body: JSON.stringify(feature) }),
  update: (session: Session, id: string, feature: FeatureDetails, base?: SyncBase) => request<ApiFeature>(`/api/project-features/${id}`, session, { method: 'PATCH', body: JSON.stringify(feature) }, base),
  remove: (session: Session, id: string, base?: SyncBase) => request<{ deleted: string }>(`/api/project-features/${id}`, session, { method: 'DELETE' }, base),
}

export const featureLinksApi = {
  create: (session: Session, link: Omit<ApiFeatureLink, 'id' | '_sync'>) => request<ApiFeatureLink>('/api/project-feature-links', session, { method: 'POST', body: JSON.stringify(link) }),
  remove: (session: Session, link: ApiFeatureLink) => request<{ deleted: string }>(`/api/project-feature-links/${link.id}`, session, { method: 'DELETE' }, link._sync),
}

export const routinesApi = {
  list: (session: Session, signal?: AbortSignal) => request<ApiRoutineSnapshot>('/api/routines', session, { signal }),
  history: (session: Session, id: string, signal?: AbortSignal) => request<ApiRoutineHistory>('/api/routines/' + encodeURIComponent(id) + '/history', session, { signal }),
  create: (session: Session, routine: { title: string; color: string; active: boolean; starts_on: string }) => request<ApiRoutine>('/api/routines', session, { method: 'POST', body: JSON.stringify(routine) }),
  update: (session: Session, id: string, routine: Partial<{ title: string; color: string; active: boolean }>, base?: SyncBase) => request<ApiRoutine>(`/api/routines/${id}`, session, { method: 'PATCH', body: JSON.stringify(routine) }, base),
  remove: (session: Session, id: string, base?: SyncBase) => request<{ deleted: string }>(`/api/routines/${id}`, session, { method: 'DELETE' }, base),
  complete: (session: Session, id: string, date: string, completed: boolean, base?: SyncBase) => request<ApiRoutineCompletion>(`/api/routines/${id}/completion`, session, { method: 'PATCH', body: JSON.stringify({ date, completed }) }, base),
}

export const calendarApi = {
  list: (session: Session, from: string, to: string, signal?: AbortSignal) => request<{ tasks: ApiTask[] }>(`/api/calendar?from=${from}&to=${to}`, session, { signal }),
}

export const bootstrapApi = {
  load: (session: Session, from: string, to: string, signal?: AbortSignal) => request<ApiBootstrap>(`/api/bootstrap?from=${from}&to=${to}`, session, { signal }),
}
