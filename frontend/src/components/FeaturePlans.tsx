import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { ApiFeature, ApiFeatureLink, ApiProject } from '../lib/api'
import type { FeatureGroup } from '../lib/featureGraph'
import type { Task } from '../lib/taskTypes'
import { DeleteIcon } from './FormDialog'
import { FeatureGraph } from './FeatureGraph'
import { ProjectPlan } from './ProjectPlan'

export type FeatureActions = {
  accountId: string
  links: ApiFeatureLink[] | null
  connect: (projectId: string, source: string, target: string) => Promise<void>
  disconnect: (link: ApiFeatureLink) => Promise<void>
  create: (project: ApiProject) => void
  edit: (feature: ApiFeature) => void
  remove: (feature: ApiFeature) => void
}

type Props = {
  project: ApiProject
  features: ApiFeature[]
  tasks: Task[]
  busy: boolean
  actions: FeatureActions
  onNewTask: (project: ApiProject, featureId: string | null) => void
  onEdit: (task: Task) => void
  onToggle: (id: string) => void
  onDelete: (id: string) => void
  onLinks: (task: Task, ids: string[]) => void
}

const ungrouped = 'Sin funcionalidad'

export function FeaturePlans({ project, features, tasks, busy, actions, onNewTask, ...planActions }: Props) {
  const [selection, setSelection] = useState<{ featureId: string | null; taskId?: string }>()
  const [view, setView] = useState<'list' | 'graph'>('list')
  const section = useRef<HTMLElement>(null)
  const overviewFocus = useRef<HTMLElement | null>(null)
  const links = useMemo(() => actions.links?.filter(link => link.project_id === project.id) ?? null, [actions.links, project.id])
  const groups = useMemo(() => {
    const ordered = features.filter(feature => feature.project_id === project.id).sort((a, b) => a.name.localeCompare(b.name, 'es'))
    const byFeature = new Map<string, { name: string; tasks: Task[] }>()
    ordered.forEach(feature => byFeature.set(feature.id, { name: feature.name, tasks: [] }))
    const other: Task[] = []
    for (const task of tasks) (byFeature.get(task.featureId || '')?.tasks || other).push(task)
    const graph: FeatureGroup[] = ordered.map(feature => ({
      featureId: feature.id,
      name: feature.name,
      tasks: byFeature.get(feature.id)!.tasks,
    }))
    if (other.length) graph.push({ featureId: null, name: ungrouped, tasks: other })
    return { ordered, byFeature, other, graph }
  }, [features, project.id, tasks])
  const activeFeature = selection?.featureId ? groups.byFeature.get(selection.featureId) : undefined
  const selectedFeature = activeFeature ? groups.ordered.find(feature => feature.id === selection?.featureId) : undefined
  const active = selection?.featureId === null || Boolean(activeFeature)
  const groupTasks = activeFeature?.tasks || groups.other
  const title = activeFeature?.name || ungrouped

  useLayoutEffect(() => {
    if (active) {
      const reference = [...(section.current?.querySelectorAll<HTMLElement>('[data-task-id]') || [])]
        .find(element => element.dataset.taskId === selection?.taskId)
      const target = reference || section.current?.querySelector<HTMLElement>('[data-feature-heading]')
      target?.focus()
    } else if (overviewFocus.current) {
      const trigger = overviewFocus.current
      if (trigger.isConnected && !trigger.matches(':disabled')) trigger.focus({ preventScroll: true })
      else section.current?.querySelector<HTMLElement>('[data-feature-list-heading]')?.focus()
      overviewFocus.current = null
    }
  }, [active, selection])

  const show = (featureId: string | null, taskId?: string) => {
    if (!active) {
      const trigger = document.activeElement
      overviewFocus.current = trigger instanceof HTMLElement && section.current?.contains(trigger)
        ? trigger
        : section.current?.querySelector<HTMLElement>('[data-feature-list-heading]') ?? null
    }
    setSelection({ featureId, taskId })
  }
  const groupName = (task: Task) => groups.byFeature.get(task.featureId || '')?.name || ungrouped
  const management = (feature: ApiFeature) => <div className="project-card-actions">
    <button type="button" className="project-edit-button" disabled={busy} onClick={() => actions.edit(feature)} aria-label={'Editar funcionalidad ' + feature.name}>Editar</button>
    <button type="button" className="project-delete-button" disabled={busy} onClick={() => actions.remove(feature)} aria-label={'Eliminar funcionalidad ' + feature.name}><DeleteIcon /></button>
  </div>
  const renderGroup = (name: string, featureId: string | null, members: Task[], feature?: ApiFeature) => {
    const complete = members.filter(task => task.done).length
    return <li key={featureId || 'ungrouped'} className="feature-row">
      <button type="button" className="feature-open" disabled={busy} onClick={() => show(featureId)}>
        <span className="feature-summary"><strong>{name}</strong>{feature?.description && <span>{feature.description}</span>}</span>
        <span className={'feature-count' + (members.length && complete === members.length ? ' complete' : '')}>{members.length ? complete + '/' + members.length + ' completadas' : 'Sin tareas'}</span>
        <span aria-hidden="true">›</span>
      </button>
      {feature && management(feature)}
    </li>
  }

  return <section className="feature-plans" ref={section} style={{ '--feature-color': project.color } as React.CSSProperties}>
    {active && <>
      <button type="button" className="back-to-overview" onClick={() => setSelection(undefined)}>← Volver a funcionalidades</button>
      <div className="feature-toolbar">
        <h4 tabIndex={-1} data-feature-heading>{title}</h4>
        <div className="feature-toolbar-actions">
          {project.status === 'active' && <button type="button" className="project-add-task" disabled={busy} onClick={() => onNewTask(project, selection?.featureId || null)}>＋ Añadir tarea</button>}
          {selectedFeature && management(selectedFeature)}
        </div>
      </div>
      <ProjectPlan key={selection?.featureId || 'ungrouped'}
        storageKey={'studycalendar:project-layout:' + project.id + (selection?.featureId ? ':feature:' + selection.featureId : '')}
        tasks={groupTasks} projectTasks={tasks} planName={title} groupName={groupName}
        onOpenExternal={task => show(task.featureId && groups.byFeature.has(task.featureId) ? task.featureId : null, task.id)}
        busy={busy} {...planActions} />
    </>}
    <div hidden={active}>
      <div className="feature-toolbar">
        <h4 tabIndex={-1} data-feature-list-heading>Funcionalidades</h4>
        <div className="feature-toolbar-actions">
          <div className="feature-view-switch" role="group" aria-label="Vista de funcionalidades">
            <button type="button" aria-pressed={view === 'list'} onClick={() => setView('list')}>Lista</button>
            <button type="button" aria-pressed={view === 'graph'} onClick={() => setView('graph')}>Gráfico</button>
          </div>
          <button type="button" className="project-add-task" disabled={busy} onClick={() => actions.create(project)}>＋ Nueva funcionalidad</button>
        </div>
      </div>
      {view === 'graph' ? <FeatureGraph groups={groups.graph} links={links} busy={busy} visible={!active}
        storageKey={`studycalendar:feature-layout:${actions.accountId}:${project.id}`}
        onOpen={show} onConnect={(source, target) => actions.connect(project.id, source, target)}
        onDisconnect={actions.disconnect} /> : <ul className="feature-list">
        {groups.ordered.map(feature => renderGroup(feature.name, feature.id, groups.byFeature.get(feature.id)!.tasks, feature))}
        {renderGroup(ungrouped, null, groups.other)}
      </ul>}
    </div>
  </section>
}
