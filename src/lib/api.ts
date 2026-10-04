import type { Session } from '@supabase/supabase-js'

export type ApiCategory = { id: string; name: string; color: string }
export type ApiTask = { id: string; title: string; category_id: string | null; date: string; start_time: string; end_time: string; color: string; completed: boolean }

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
  list: (session: Session) => request<ApiTask[]>('/api/tasks', session),
  create: (session: Session, task: Omit<ApiTask, 'id'>) => request<ApiTask>('/api/tasks', session, { method: 'POST', body: JSON.stringify(task) }),
  update: (session: Session, id: string, task: Partial<Omit<ApiTask, 'id'>>) => request<ApiTask>(`/api/tasks/${id}`, session, { method: 'PATCH', body: JSON.stringify(task) }),
  remove: (session: Session, id: string) => request<{ deleted: string }>(`/api/tasks/${id}`, session, { method: 'DELETE' }),
}
