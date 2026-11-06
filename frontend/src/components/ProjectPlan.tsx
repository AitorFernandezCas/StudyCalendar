import { useId, useMemo, useRef, useState } from 'react'
import type { Task } from '../lib/taskTypes'
import { planLayout, unfinishedPrerequisites } from '../lib/projectPlan'

type Props = {
  tasks: Task[]
  busy: boolean
  onNew: () => void
  onEdit: (task: Task) => void
  onToggle: (id: string) => void
  onDelete: (id: string) => void
  onLinks: (task: Task, ids: string[]) => void
}

export function ProjectPlan({ tasks, busy, onNew, onEdit, onToggle, onDelete, onLinks }: Props) {
  const marker = useId().replace(/:/g, '')
  const [zoom, setZoom] = useState(1)
  const [connecting, setConnecting] = useState<string | null>(null)
  const [preview, setPreview] = useState<{ x: number; y: number } | null>(null)
  const viewport = useRef<HTMLDivElement>(null)
  const pan = useRef<{ x: number; y: number; left: number; top: number } | null>(null)
  const nodes = useMemo(() => planLayout(tasks), [tasks])
  const byId = new Map(nodes.map(node => [node.task.id, node]))
  const width = Math.max(640, ...nodes.map(node => node.x + 290))
  const height = Math.max(340, ...nodes.map(node => node.y + 220))
  const connect = (source: string, target: Task) => {
    if (busy || source === target.id || target.prerequisiteIds?.includes(source)) return
    onLinks(target, [...(target.prerequisiteIds || []), source])
  }
  const path = (x: number, y: number, endX: number, endY: number) => `M ${x} ${y} C ${x + 55} ${y}, ${endX - 55} ${endY}, ${endX} ${endY}`
  return <section className="project-plan" aria-label="Plan visual del proyecto">
    <div className="plan-toolbar"><button onClick={onNew} disabled={busy}>＋ Nueva tarea sin fecha</button><div className="plan-zoom"><button aria-label="Alejar plan" onClick={() => setZoom(value => Math.max(.4, value - .15))}>−</button><output>{Math.round(zoom * 100)}%</output><button aria-label="Acercar plan" onClick={() => setZoom(value => Math.min(1.6, value + .15))}>＋</button><button onClick={() => { setZoom(1); viewport.current?.scrollTo(0, 0) }}>Restablecer</button></div></div>
    <p className="plan-help">Conecta la salida de una tarea con la entrada de la siguiente. También puedes elegir sus tareas previas en cada tarjeta. Arrastra el fondo para desplazarte.</p>
    {!tasks.length && <p className="overview-empty">Crea la primera tarea para empezar el plan. Puedes asignar fechas más adelante.</p>}
    <div ref={viewport} className="plan-viewport" tabIndex={0} aria-label="Diagrama desplazable del proyecto" onPointerDown={event => {
      if ((event.target as HTMLElement).closest('button,select,input,label,article')) return
      const view = viewport.current!
      pan.current = { x: event.clientX, y: event.clientY, left: view.scrollLeft, top: view.scrollTop }
      view.setPointerCapture(event.pointerId)
    }} onPointerMove={event => {
      if (pan.current) { viewport.current!.scrollLeft = pan.current.left - event.clientX + pan.current.x; viewport.current!.scrollTop = pan.current.top - event.clientY + pan.current.y }
      if (connecting) { const rect = viewport.current!.getBoundingClientRect(); setPreview({ x: (event.clientX - rect.left + viewport.current!.scrollLeft) / zoom, y: (event.clientY - rect.top + viewport.current!.scrollTop) / zoom }) }
    }} onPointerUp={() => { pan.current = null; setConnecting(null); setPreview(null) }} onPointerCancel={() => { pan.current = null; setConnecting(null); setPreview(null) }}>
      <div style={{ width: width * zoom, height: height * zoom }}><div className="plan-canvas" style={{ width, height, transform: `scale(${zoom})` }}>
        <svg className="plan-arrows" width={width} height={height} aria-hidden="true"><defs><marker id={marker} markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0 0 L8 4 L0 8Z" fill="currentColor" /></marker></defs>
          {nodes.flatMap(node => (node.task.prerequisiteIds || []).map(id => { const source = byId.get(id); return source && <path key={id + node.task.id} d={path(source.x + 260, source.y + 32, node.x, node.y + 32)} markerEnd={`url(#${marker})`} /> }))}
          {connecting && preview && byId.has(connecting) && <path className="plan-preview" d={path(byId.get(connecting)!.x + 260, byId.get(connecting)!.y + 32, preview.x, preview.y)} />}
        </svg>
        {nodes.map(({ task, x, y }) => {
          const blocked = unfinishedPrerequisites(task, tasks)
          const dependentsComplete = tasks.some(row => row.done && row.prerequisiteIds?.includes(task.id))
          const linked = task.prerequisiteIds?.length || tasks.some(row => row.prerequisiteIds?.includes(task.id))
          return <article key={task.id} className={'plan-node ' + (task.done ? 'complete' : blocked.length ? 'blocked' : 'available')} style={{ left: x, top: y, borderTopColor: task.color }}>
            <button className="plan-port plan-input" aria-label={'Conectar entrada de ' + task.title} disabled={busy} onPointerUp={event => { event.stopPropagation(); if (connecting) connect(connecting, task); setConnecting(null); setPreview(null) }}>●</button>
            <button className="plan-port plan-output" aria-label={'Arrastrar salida de ' + task.title} disabled={busy} onPointerDown={event => { event.preventDefault(); event.stopPropagation(); setConnecting(task.id) }}>●</button>
            <button className="plan-task-title" onClick={() => onEdit(task)}>{task.title}</button>
            <span className="plan-state">{task.done ? 'Completada' : blocked.length ? 'Bloqueada' : 'Disponible'}</span><small>{task.date || 'Sin fecha'}{task.date && task.allDay ? ' · Todo el día' : task.date ? ' · ' + task.time : ''}</small>
            <label>Tareas previas<select aria-label={'Añadir tarea previa a ' + task.title} value="" disabled={busy} onChange={event => { if (event.target.value) connect(event.target.value, task) }}><option value="">Añadir tarea previa…</option>{tasks.filter(row => row.id !== task.id && !task.prerequisiteIds?.includes(row.id)).map(row => <option key={row.id} value={row.id}>{row.title}</option>)}</select></label>
            <div className="plan-links">{(task.prerequisiteIds || []).map(id => <button disabled={busy} key={id} title="Quitar conexión" onClick={() => onLinks(task, task.prerequisiteIds!.filter(value => value !== id))}>{tasks.find(row => row.id === id)?.title || 'Tarea no disponible'} ×</button>)}</div>
            <div className="plan-actions"><button disabled={busy || (!task.done && blocked.length > 0) || (task.done && dependentsComplete)} title={blocked.length ? 'Completa primero las tareas previas' : dependentsComplete ? 'Reabre primero las tareas dependientes' : undefined} onClick={() => onToggle(task.id)}>{task.done ? 'Reabrir' : 'Completar'}</button><button disabled={busy} onClick={() => onEdit(task)}>Editar</button><button disabled={busy || Boolean(linked)} title={linked ? 'Retira primero las conexiones' : 'Borrar tarea'} onClick={() => onDelete(task.id)}>Borrar</button></div>
          </article>
        })}
      </div></div>
    </div>
  </section>
}
