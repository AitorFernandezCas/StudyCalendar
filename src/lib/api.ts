import type { Session } from '@supabase/supabase-js'

export type ApiCategory = { id: string; name: string; color: string }
export type ApiProject = { id: string; name: string; color: string; created_at: string; updated_at: string }
export type ApiTask = { id: string; title: string; category_id: string | null; project_id: string | null; task_type: 'routine' | 'project' | 'daily'; date: string; start_time: string; end_time: string; color: string; completed: boolean }
export type ApiRoutine = { id: string; title: string; color: string; active: boolean; created_at: string; updated_at: string; due_today: boolean; completed_today: boolean; current_streak: number; max_streak: number }
export type ApiRoutineSnapshot = { date: string; timezone: string; next_day_at: string; routines: ApiRoutine[] }
export type ApiRoutineCompletion = Omit<ApiRoutineSnapshot, 'routines'> & { routine: ApiRoutine }
export type ApiBootstrap = { categories: ApiCategory[]; projects: ApiProject[]; tasks: ApiTask[] }

export class ApiError extends Error {
  constructor(message: string, public status: number) { super(message) }
}

const apiUrl = (import.meta.env.VITE_API_URL as string | undefined || 'http://localhost:5000').replace(/\/$/, '')

async function request<T>(path: string, session: Session, options?: RequestInit): Promise<T> {
  const response = await fetch(`${apiUrl}${path}`, { ...options, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.access_token}`, ...options?.headers } })
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new ApiError(body.error || 'No se pudo completar la operación', response.status)
  return body as T
}

export const categoriesApi = {
  list: (session: Session) => request<ApiCategory[]>('/api/categories', session),
  create: (session: Session, category: Omit<ApiCategory, 'id'>) => request<ApiCategory>('/api/categories', session, { method: 'POST', body: JSON.stringify(category) }),
  update: (session: Session, id: string, category: Partial<Omit<ApiCategory, 'id'>>) => request<ApiCategory>(`/api/categories/${id}`, session, { method: 'PATCH', body: JSON.stringify(category) }),
  remove: (session: Session, id: string) => request<{ deleted: string }>(`/api/categories/${id}`, session, { method: 'DELETE' }),
}

export const projectsApi = {
  list: (session: Session) => request<{ projects: ApiProject[] }>('/api/projects', session),
  create: (session: Session, project: Omit<ApiProject, 'id' | 'created_at' | 'updated_at'>) => request<ApiProject>('/api/projects', session, { method: 'POST', body: JSON.stringify(project) }),
  update: (session: Session, id: string, project: Partial<Omit<ApiProject, 'id' | 'created_at' | 'updated_at'>>) => request<ApiProject>(`/api/projects/${id}`, session, { method: 'PATCH', body: JSON.stringify(project) }),
  remove: (session: Session, id: string) => request<{ deleted: string }>(`/api/projects/${id}`, session, { method: 'DELETE' }),
}

export const tasksApi = {
  list: (session: Session, from?: string, to?: string, signal?: AbortSignal) => request<ApiTask[]>(`/api/tasks${from && to ? `?from=${from}&to=${to}` : ''}`, session, { signal }),
  create: (session: Session, task: Omit<ApiTask, 'id'>) => request<ApiTask>('/api/tasks', session, { method: 'POST', body: JSON.stringify(task) }),
  update: (session: Session, id: string, task: Partial<Omit<ApiTask, 'id'>>) => request<ApiTask>(`/api/tasks/${id}`, session, { method: 'PATCH', body: JSON.stringify(task) }),
  remove: (session: Session, id: string) => request<{ deleted: string }>(`/api/tasks/${id}`, session, { method: 'DELETE' }),
}

export const routinesApi = {
  list: (session: Session, signal?: AbortSignal) => request<ApiRoutineSnapshot>('/api/routines', session, { signal }),
  create: (session: Session, routine: { title: string; color: string; active: boolean; starts_on: string }) => request<ApiRoutine>('/api/routines', session, { method: 'POST', body: JSON.stringify(routine) }),
  update: (session: Session, id: string, routine: Partial<{ title: string; color: string; active: boolean }>) => request<ApiRoutine>(`/api/routines/${id}`, session, { method: 'PATCH', body: JSON.stringify(routine) }),
  remove: (session: Session, id: string) => request<{ deleted: string }>(`/api/routines/${id}`, session, { method: 'DELETE' }),
  complete: (session: Session, id: string, date: string, completed: boolean) => request<ApiRoutineCompletion>(`/api/routines/${id}/completion`, session, { method: 'PATCH', body: JSON.stringify({ date, completed }) }),
}

export const calendarApi = {
  list: (session: Session, from: string, to: string, signal?: AbortSignal) => request<{ tasks: ApiTask[] }>(`/api/calendar?from=${from}&to=${to}`, session, { signal }),
}

export const bootstrapApi = {
  load: (session: Session, from: string, to: string, signal?: AbortSignal) => request<ApiBootstrap>(`/api/bootstrap?from=${from}&to=${to}`, session, { signal }),
}
