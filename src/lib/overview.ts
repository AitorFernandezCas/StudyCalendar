import type { Task } from './taskTypes'

export type TaskFilters = { query: string; category: string; status: 'pending' | 'completed' | 'all'; sort: 'date' | 'date-desc' | 'title' }
const searchable = (value: string) => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('es')

export function filterOverviewTasks(tasks: Task[], filters: TaskFilters, projectNames: Map<string, string> = new Map()) {
  const query = searchable(filters.query.trim())
  return tasks.filter(task => (filters.status === 'all' || task.done === (filters.status === 'completed')) &&
    (!filters.category || (filters.category === 'none' ? !task.categoryId : task.categoryId === filters.category)) &&
    (!query || searchable(task.title + ' ' + (projectNames.get(task.projectId || '') || '')).includes(query)))
    .sort((a, b) => {
      if (filters.sort === 'title') return a.title.localeCompare(b.title, 'es') || a.id.localeCompare(b.id)
      const dateOrder = a.date.localeCompare(b.date) * (filters.sort === 'date-desc' ? -1 : 1)
      return dateOrder || Number(b.allDay) - Number(a.allDay) || a.time.localeCompare(b.time) || a.title.localeCompare(b.title, 'es') || a.id.localeCompare(b.id)
    })
}

export function groupDailyTasks(tasks: Task[], today: string) {
  return [
    { id: 'overdue', title: 'Atrasadas', tasks: tasks.filter(task => !task.done && task.date < today) },
    { id: 'today', title: 'Hoy', tasks: tasks.filter(task => !task.done && task.date === today) },
    { id: 'upcoming', title: 'Próximas', tasks: tasks.filter(task => !task.done && task.date > today) },
    { id: 'completed', title: 'Completadas', tasks: tasks.filter(task => task.done) },
  ]
}

export function relativeTaskDate(date: string, today: string) {
  const difference = Math.round((Date.parse(date + 'T12:00:00Z') - Date.parse(today + 'T12:00:00Z')) / 86400000)
  if (difference === 0) return 'Hoy'
  if (difference === 1) return 'Mañana'
  if (difference === -1) return 'Ayer'
  if (difference < 0) return 'Hace ' + -difference + ' días'
  return new Date(date + 'T12:00:00').toLocaleDateString('es-ES', { day: 'numeric', month: 'short', ...(date.slice(0, 4) !== today.slice(0, 4) ? { year: 'numeric' as const } : {}) })
}

export function startWithDuration(time: string, oldStart: string, oldEnd: string) {
  const minutes = (value: string) => Number(value.slice(0, 2)) * 60 + Number(value.slice(3, 5))
  if (!/^\d{2}:\d{2}$/.test(time)) return { time, endTime: oldEnd }
  const duration = Math.max(1, minutes(oldEnd) - minutes(oldStart)) || 60
  const end = Math.min(1439, minutes(time) + duration)
  return { time, endTime: String(Math.floor(end / 60)).padStart(2, '0') + ':' + String(end % 60).padStart(2, '0') }
}
