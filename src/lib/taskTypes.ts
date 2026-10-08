export type TaskType = 'routine' | 'project' | 'daily'
export type Task = { id: string; title: string; categoryId: string | null; projectId: string | null; taskType: TaskType; date: string; time: string; endTime: string; color: string; done: boolean }
