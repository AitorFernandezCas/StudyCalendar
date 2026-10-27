import type { ApiProject } from './api'
import type { Task } from './taskTypes'

export function visibleOverviewTasks(tasks: Task[], projects: ApiProject[]): Task[] {
  const hiddenProjects = new Set(projects.filter(project => project.status !== 'active').map(project => project.id))
  return tasks.filter(task => task.taskType !== 'project' || !task.projectId || !hiddenProjects.has(task.projectId))
}
