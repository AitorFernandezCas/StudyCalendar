import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { Task } from '../lib/taskTypes'
import { movePlanPosition, PLAN_NODE_HEIGHT, PLAN_NODE_WIDTH, planLayout, readPlanPositions, relatedTaskIds, unfinishedPrerequisites, type PlanPosition } from '../lib/projectPlan'

type Props = {
  tasks: Task[]
  storageKey: string
  busy: boolean
  onEdit: (task: Task) => void
  onToggle: (id: string) => void
  onDelete: (id: string) => void
  onLinks: (task: Task, ids: string[]) => void
}

export function ProjectPlan({ tasks, storageKey, busy, onEdit, onToggle, onDelete, onLinks }: Props) {
  const marker = useId().replace(/:/g, '')
  const [zoom, setZoom] = useState(1)
  const [connecting, setConnecting] = useState<string | null>(null)
  const [preview, setPreview] = useState<{ x: number; y: number } | null>(null)
  const [savedPositions, setSavedPositions] = useState<Record<string, PlanPosition>>(() => readPlanPositions(storageKey))
  const [focusRootId, setFocusRootId] = useState<string | null>(null)
  const [branchPositions, setBranchPositions] = useState<Record<string, PlanPosition>>({})
  const [, refreshView] = useState(0)
  const fullCamera = useRef<{ zoom: number; left: number; top: number } | null>(null)
  const pendingCamera = useRef<{ kind: 'fit' } | { kind: 'restore'; zoom: number; left: number; top: number; focusViewport?: boolean } | null>(null)
  const positions = focusRootId ? branchPositions : savedPositions
  const setPositions = focusRootId ? setBranchPositions : setSavedPositions
  const focusRoot = tasks.find(task => task.id === focusRootId)
  const visibleTasks = useMemo(() => {
    if (!focusRootId) return tasks
    const ids = relatedTaskIds(tasks, focusRootId)
    return tasks.filter(task => ids.has(task.id))
  }, [tasks, focusRootId])
  const [nodeHeights, setNodeHeights] = useState<Record<string, number>>({})
  const [moving, setMoving] = useState<string | null>(null)
  const drag = useRef<{ id: string; pointer: number; start: PlanPosition; clientX: number; clientY: number; scrollLeft: number; scrollTop: number; saved?: PlanPosition } | null>(null)
  const viewport = useRef<HTMLDivElement>(null)
  const pan = useRef<{ x: number; y: number; left: number; top: number } | null>(null)
  const nodes = useMemo(() => planLayout(visibleTasks, nodeHeights).map(node => ({ ...node, ...positions[node.task.id] })), [visibleTasks, positions, nodeHeights])
  useLayoutEffect(() => {
    const titles = [...(viewport.current?.querySelectorAll<HTMLElement>('.plan-task-title') || [])]
    const measure = () => {
      const measured = titles.filter(title => title.offsetHeight > 0).map(title => [title.dataset.taskId!, Math.max(PLAN_NODE_HEIGHT, title.offsetHeight + 25)] as const)
      setNodeHeights(current => measured.some(([id, height]) => current[id] !== height) ? { ...current, ...Object.fromEntries(measured) } : current)
    }
    measure()
    const observer = new ResizeObserver(measure)
    titles.forEach(title => observer.observe(title))
    return () => observer.disconnect()
  }, [visibleTasks])
  useEffect(() => {
    if (moving) return
    try { localStorage.setItem(storageKey, JSON.stringify(savedPositions)) } catch { /* Movement still works when browser storage is unavailable. */ }
  }, [savedPositions, storageKey, moving])
  const cancelMove = () => {
    const active = drag.current
    if (active) setPositions(current => {
      const next = { ...current }
      if (active.saved) next[active.id] = active.saved
      else delete next[active.id]
      return next
    })
    drag.current = null
    setMoving(null)
  }
  const cancelGestures = () => {
    const active = drag.current
    if (active) {
      viewport.current?.querySelectorAll('.plan-node').forEach(element => {
        if (element.hasPointerCapture(active.pointer)) element.releasePointerCapture(active.pointer)
      })
    }
    cancelMove()
    pan.current = null
    setConnecting(null)
    setPreview(null)
  }
  const fitBranch = () => {
    pendingCamera.current = { kind: 'fit' }
    refreshView(value => value + 1)
  }
  const enterFocus = (id: string) => {
    if (id === focusRootId) return
    cancelGestures()
    if (!focusRootId) fullCamera.current = { zoom, left: viewport.current?.scrollLeft || 0, top: viewport.current?.scrollTop || 0 }
    setBranchPositions({})
    setFocusRootId(id)
    fitBranch()
  }
  const exitFocus = () => {
    cancelGestures()
    const camera = fullCamera.current || { zoom: 1, left: 0, top: 0 }
    pendingCamera.current = { kind: 'restore', ...camera, focusViewport: true }
    setFocusRootId(null)
    setBranchPositions({})
    fullCamera.current = null
  }
  useEffect(() => {
    if (focusRootId && !tasks.some(task => task.id === focusRootId)) exitFocus()
  }, [focusRootId, tasks])
  // Runs after the new cards and canvas dimensions are committed. Keep pending
  // work while a project is collapsed (its viewport has no measurable size).
  useLayoutEffect(() => {
    const view = viewport.current
    let camera = pendingCamera.current
    if (!camera || !view || !view.clientWidth || !view.clientHeight) return
    if (camera.kind === 'fit') {
      if (!nodes.length) return
      // Wait for natural title measurement before fitting the branch.
      const titles = new Map([...view.querySelectorAll<HTMLElement>('.plan-task-title')].map(title => [title.dataset.taskId, title.offsetHeight]))
      if (nodes.some(node => titles.get(node.task.id) && node.height !== Math.max(PLAN_NODE_HEIGHT, titles.get(node.task.id)! + 25))) return
      const left = Math.min(...nodes.map(node => node.x))
      const top = Math.min(...nodes.map(node => node.y))
      const right = Math.max(...nodes.map(node => node.x + PLAN_NODE_WIDTH))
      const bottom = Math.max(...nodes.map(node => node.y + node.height))
      const nextZoom = Math.min(1, Math.max(.4, Math.min((view.clientWidth - 64) / (right - left), (view.clientHeight - 64) / (bottom - top))))
      camera = { kind: 'restore', zoom: nextZoom, left: Math.max(0, (left + right) / 2 * nextZoom - view.clientWidth / 2), top: Math.max(0, (top + bottom) / 2 * nextZoom - view.clientHeight / 2) }
      pendingCamera.current = camera
    }
    if (zoom !== camera.zoom) { setZoom(camera.zoom); return }
    view.scrollTo(camera.left, camera.top)
    if (camera.focusViewport) view.focus({ preventScroll: true })
    pendingCamera.current = null
  })
  const resetView = () => {
    cancelGestures()
    if (focusRootId) fitBranch()
    else { setZoom(1); pendingCamera.current = { kind: 'restore', zoom: 1, left: 0, top: 0 }; refreshView(value => value + 1) }
  }
  const arrange = () => {
    cancelGestures()
    setPositions({})
    if (focusRootId) fitBranch()
  }
  const byId = new Map(nodes.map(node => [node.task.id, node]))
  const width = Math.max(640, ...nodes.map(node => node.x + PLAN_NODE_WIDTH + 40))
  // Keep room below the last row for its expanded controls.
  const height = Math.max(340, ...nodes.map(node => node.y + node.height + 252))
  const connect = (source: string, target: Task) => {
    if (busy || source === target.id || target.prerequisiteIds?.includes(source)) return
    onLinks(target, [...(target.prerequisiteIds || []), source])
  }
  const path = (x: number, y: number, endX: number, endY: number) => `M ${x} ${y} C ${x + 55} ${y}, ${endX - 55} ${endY}, ${endX} ${endY}`
  return <section className="project-plan" aria-label="Plan visual del proyecto">
    <div className="plan-toolbar">{focusRoot && <div className="plan-focus-summary"><div role="status"><strong>Rama de «{focusRoot.title}»</strong><span>{visibleTasks.length} de {tasks.length} tareas</span></div><button onClick={exitFocus}>Ver proyecto completo</button></div>}<div className="plan-zoom"><button aria-label="Alejar plan" onClick={() => setZoom(value => Math.max(.4, value - .15))}>−</button><output>{Math.round(zoom * 100)}%</output><button aria-label="Acercar plan" onClick={() => setZoom(value => Math.min(1.6, value + .15))}>＋</button><button onClick={resetView}>Restablecer vista</button><button onClick={arrange}>Organizar automáticamente</button></div></div>
    <p className="plan-help">Pasa el cursor o enfoca una tarea para ver sus detalles. Arrastra las tarjetas para colocarlas libremente; usa las flechas en su control de movimiento. Conecta los puntos laterales para añadir tareas previas.</p>
    {!tasks.length && <p className="overview-empty">Este proyecto aún no tiene tareas.</p>}
    <div ref={viewport} className="plan-viewport" tabIndex={0} aria-label="Diagrama desplazable del proyecto" onPointerDown={event => {
      if ((event.target as HTMLElement).closest('button,select,input,label,article')) return
      const view = viewport.current!
      pan.current = { x: event.clientX, y: event.clientY, left: view.scrollLeft, top: view.scrollTop }
      view.setPointerCapture(event.pointerId)
    }} onPointerMove={event => {
      const active = drag.current
      if (active && active.pointer === event.pointerId) {
        const view = viewport.current!
        const next = movePlanPosition(active.start, event.clientX - active.clientX + view.scrollLeft - active.scrollLeft, event.clientY - active.clientY + view.scrollTop - active.scrollTop, zoom)
        setPositions(current => ({ ...current, [active.id]: next }))
        return
      }
      if (pan.current) { viewport.current!.scrollLeft = pan.current.left - event.clientX + pan.current.x; viewport.current!.scrollTop = pan.current.top - event.clientY + pan.current.y }
      if (connecting) { const rect = viewport.current!.getBoundingClientRect(); setPreview({ x: (event.clientX - rect.left + viewport.current!.scrollLeft) / zoom, y: (event.clientY - rect.top + viewport.current!.scrollTop) / zoom }) }
    }} onPointerUp={() => { drag.current = null; setMoving(null); pan.current = null; setConnecting(null); setPreview(null) }} onPointerCancel={() => { cancelMove(); pan.current = null; setConnecting(null); setPreview(null) }}>
      <div style={{ width: width * zoom, height: height * zoom }}><div className={'plan-canvas' + (connecting ? ' connecting' : '')} style={{ width, height, transform: `scale(${zoom})` }}>
        <svg className="plan-arrows" width={width} height={height} aria-hidden="true"><defs><marker id={marker} markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0 0 L8 4 L0 8Z" fill="currentColor" /></marker></defs>
          {nodes.flatMap(node => (node.task.prerequisiteIds || []).map(id => { const source = byId.get(id); return source && <path key={id + node.task.id} d={path(source.x + PLAN_NODE_WIDTH, source.y + source.height / 2, node.x, node.y + node.height / 2)} markerEnd={`url(#${marker})`} /> }))}
          {connecting && preview && byId.has(connecting) && <path className="plan-preview" d={path(byId.get(connecting)!.x + PLAN_NODE_WIDTH, byId.get(connecting)!.y + byId.get(connecting)!.height / 2, preview.x, preview.y)} />}
        </svg>
        {nodes.map(({ task, x, y, height: nodeHeight }) => {
          const blocked = unfinishedPrerequisites(task, tasks)
          const dependentsComplete = tasks.some(row => row.done && row.prerequisiteIds?.includes(task.id))
          const linked = task.prerequisiteIds?.length || tasks.some(row => row.prerequisiteIds?.includes(task.id))
          return <article key={task.id} tabIndex={0} aria-label={task.title} className={'plan-node ' + (task.done ? 'complete' : blocked.length ? 'blocked' : 'available') + (moving === task.id ? ' moving' : '') + (focusRootId === task.id ? ' branch-root' : '')} style={{ left: x, top: y, borderTopColor: task.color }} onPointerDown={event => {
            if (event.button !== 0 || ((event.target as HTMLElement).closest('button,select,input,label') && !(event.target as HTMLElement).closest('.plan-move-handle'))) return
            event.preventDefault()
            event.stopPropagation()
            const view = viewport.current!
            drag.current = { id: task.id, pointer: event.pointerId, start: { x, y }, clientX: event.clientX, clientY: event.clientY, scrollLeft: view.scrollLeft, scrollTop: view.scrollTop, saved: positions[task.id] }
            setMoving(task.id)
            setConnecting(null)
            setPreview(null)
            event.currentTarget.setPointerCapture(event.pointerId)
          }}>
            <button className="plan-move-handle" aria-label={'Mover tarea ' + task.title} title="Arrastrar para mover; flechas con teclado" onKeyDown={event => {
              if (event.key === 'Escape') { cancelMove(); return }
              const directions: Record<string, [number, number]> = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }
              const direction = directions[event.key]
              if (!direction) return
              event.preventDefault()
              const step = event.shiftKey ? 5 : 20
              setPositions(current => ({ ...current, [task.id]: movePlanPosition(current[task.id] || { x, y }, direction[0] * step, direction[1] * step) }))
            }}>⠿</button>
            <button className="plan-port plan-input" style={{ top: nodeHeight / 2 - 14 }} aria-label={'Conectar entrada de ' + task.title} disabled={busy} onPointerUp={event => { event.stopPropagation(); if (connecting) connect(connecting, task); setConnecting(null); setPreview(null) }}>●</button>
            <button className="plan-port plan-output" style={{ top: nodeHeight / 2 - 14 }} aria-label={'Arrastrar salida de ' + task.title} disabled={busy} onPointerDown={event => { event.preventDefault(); event.stopPropagation(); setConnecting(task.id) }}>●</button>
            <button className="plan-task-title" data-task-id={task.id} onClick={() => onEdit(task)}>{task.title}</button>
            <div className="plan-node-details"><div className="plan-node-details-clip"><div className="plan-node-details-content">
            <span className="plan-state">{task.done ? 'Completada' : blocked.length ? 'Bloqueada' : 'Disponible'}</span><small>{task.date || 'Sin fecha'}{task.date && task.allDay ? ' · Todo el día' : task.date ? ' · ' + task.time : ''}</small>
            <label>Tareas previas<select aria-label={'Añadir tarea previa a ' + task.title} value="" disabled={busy} onChange={event => { if (event.target.value) connect(event.target.value, task) }}><option value="">Añadir tarea previa…</option>{tasks.filter(row => row.id !== task.id && !task.prerequisiteIds?.includes(row.id)).map(row => <option key={row.id} value={row.id}>{row.title}</option>)}</select></label>
            <div className="plan-links">{(task.prerequisiteIds || []).map(id => <button disabled={busy} key={id} title={focusRootId && !byId.has(id) ? 'Quitar conexión · Tarea fuera de esta rama' : 'Quitar conexión'} onClick={() => onLinks(task, task.prerequisiteIds!.filter(value => value !== id))}>{tasks.find(row => row.id === id)?.title || 'Tarea no disponible'}{focusRootId && !byId.has(id) ? ' (fuera de rama)' : ''} ×</button>)}</div>
            <div className="plan-actions"><button aria-pressed={focusRootId === task.id} aria-label={'Ver rama de ' + task.title} onClick={() => enterFocus(task.id)}>Ver rama</button><button disabled={busy || (!task.done && blocked.length > 0) || (task.done && dependentsComplete)} title={blocked.length ? 'Completa primero las tareas previas' : dependentsComplete ? 'Reabre primero las tareas dependientes' : undefined} onClick={() => onToggle(task.id)}>{task.done ? 'Reabrir' : 'Completar'}</button><button disabled={busy} onClick={() => onEdit(task)}>Editar</button><button disabled={busy || Boolean(linked)} title={linked ? 'Retira primero las conexiones' : 'Borrar tarea'} onClick={() => onDelete(task.id)}>Borrar</button></div>
            </div></div></div>
          </article>
        })}
      </div></div>
    </div>
  </section>
}
