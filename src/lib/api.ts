import type { Session } from '@supabase/supabase-js'

export type ApiCategory = { id: string; name: string; color: string }
export type ApiTask = { id: string; title: string; category_id: string | null; task_type: 'routine' | 'project' | 'daily'; date: string; start_time: string; end_time: string; color: string; completed: boolean }
export type ApiRoutine = { id: string; title: string; start_time: string; end_time: string; color: string; active: boolean; created_at: string; updated_at: string }
export type ApiRoutineOccurrence = ApiTask & { routine_id: string }
export type ApiBootstrap = { categories: ApiCategory[]; routines: ApiRoutine[]; tasks: ApiTask[]; routine_occurrences: ApiRoutineOccurrence[] }

const apiUrl = (import.meta.env.VITE_API_URL as string | undefined || 'http://localhost:5000').replace(/\/$/, '')

async function request<T>(path: string, session: Session, options?: RequestInit): Promise<T> {
  const response = await fetch(`${apiUrl}${path}`, { ...options, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.access_token}`, ...options?.headers } })
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(body.error || 'No se pudo completar la operación')
  return body as T
}

export const categoriesApi = {
  list: (session: Session) => request<ApiCategory[]>('/api/categories', session),
  create: (session: Session, category: Omit<ApiCategory, 'id'>) => request<ApiCategory>('/api/categories', session, { method: 'POST', body: JSON.stringify(category) }),
  update: (session: Session, id: string, category: Partial<Omit<ApiCategory, 'id'>>) => request<ApiCategory>(`/api/categories/${id}`, session, { method: 'PATCH', body: JSON.stringify(category) }),
  remove: (session: Session, id: string) => request<{ deleted: string }>(`/api/categories/${id}`, session, { method: 'DELETE' }),
}

export const tasksApi = {
  list: (session: Session, from?: string, to?: string, signal?: AbortSignal) => request<ApiTask[]>(`/api/tasks${from && to ? `?from=${from}&to=${to}` : ''}`, session, { signal }),
  create: (session: Session, task: Omit<ApiTask, 'id'>) => request<ApiTask>('/api/tasks', session, { method: 'POST', body: JSON.stringify(task) }),
  update: (session: Session, id: string, task: Partial<Omit<ApiTask, 'id'>>) => request<ApiTask>(`/api/tasks/${id}`, session, { method: 'PATCH', body: JSON.stringify(task) }),
  remove: (session: Session, id: string) => request<{ deleted: string }>(`/api/tasks/${id}`, session, { method: 'DELETE' }),
}

export const routinesApi = {
  list: (session: Session) => request<{ routines: ApiRoutine[] }>('/api/routines', session),
  create: (session: Session, routine: { title: string; start_time: string; end_time: string; color: string; active: boolean; starts_on: string }) => request<ApiRoutine>('/api/routines', session, { method: 'POST', body: JSON.stringify(routine) }),
  update: (session: Session, id: string, routine: Partial<{ title: string; start_time: string; end_time: string; color: string; active: boolean; starts_on: string }>) => request<ApiRoutine>(`/api/routines/${id}`, session, { method: 'PATCH', body: JSON.stringify(routine) }),
  remove: (session: Session, id: string) => request<{ deleted: string }>(`/api/routines/${id}`, session, { method: 'DELETE' }),
  occurrences: (session: Session, from: string, to: string, signal?: AbortSignal) => request<ApiRoutineOccurrence[]>(`/api/routines/occurrences?from=${from}&to=${to}`, session, { signal }),
  updateOccurrence: (session: Session, id: string, date: string, completed: boolean) => request<ApiRoutineOccurrence>(`/api/routines/${id}/occurrences/${date}`, session, { method: 'PATCH', body: JSON.stringify({ completed }) }),
}

export const calendarApi = {
  list: (session: Session, from: string, to: string, signal?: AbortSignal) => request<{ tasks: ApiTask[]; routine_occurrences: ApiRoutineOccurrence[] }>(`/api/calendar?from=${from}&to=${to}`, session, { signal }),
}

export const bootstrapApi = {
  load: (session: Session, from: string, to: string, signal?: AbortSignal) => request<ApiBootstrap>(`/api/bootstrap?from=${from}&to=${to}`, session, { signal }),
}
