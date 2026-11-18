import { useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type PointerEvent } from 'react'
import type { ApiFeatureLink } from '../lib/api'
import { featureBranch, featureGraphEdges, featureGraphNodes, featureLayoutNodes, type FeatureEdge, type FeatureGroup } from '../lib/featureGraph'
import { movePlanPosition, planLayout, PLAN_NODE_WIDTH, readPlanPositions, type PlanPosition } from '../lib/projectPlan'

const nodeHeight = 96
const directions: Record<string, [number, number]> = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }

type Camera = { zoom: number; left: number; top: number }
type PointerOrigin = { pointerId: number; x: number; y: number; left: number; top: number }
type Gesture =
  | (PointerOrigin & { kind: 'move'; id: string; start: PlanPosition; to?: PlanPosition })
  | (PointerOrigin & { kind: 'pan' })
  | { kind: 'connect'; pointerId: number; id: string }

function edgePath(source: PlanPosition, target: PlanPosition) {
  const fromX = source.x + PLAN_NODE_WIDTH
  const toX = target.x - 11
  const fromY = source.y + nodeHeight / 2
  const toY = target.y + nodeHeight / 2
  if (toX > fromX + 28) {
    return `M ${fromX} ${fromY} C ${fromX + 40} ${fromY}, ${toX - 40} ${toY}, ${toX} ${toY}`
  }
  // Route back below the cards so arrows always use the output and input ports.
  const lane = Math.max(source.y, target.y) + nodeHeight + 18
  const right = fromX + 28
  const left = Math.max(2, toX - 28)
  return `M ${fromX} ${fromY} Q ${right} ${fromY} ${right} ${fromY + 18} V ${lane - 8}
    Q ${right} ${lane} ${right - 8} ${lane} H ${left + 8}
    Q ${left} ${lane} ${left} ${lane - 8} V ${toY + 18} Q ${left} ${toY} ${toX} ${toY}`
}

function edgeLabel(edge: FeatureEdge) {
  if (!edge.saved) return 'por tareas'
  return edge.inferred ? 'planificación y tareas' : 'planificación'
}

export function FeatureGraph({ groups, links, busy, visible, storageKey, onOpen, onConnect, onDisconnect }: {
  groups: FeatureGroup[]
  links: ApiFeatureLink[] | null
  busy: boolean
  visible: boolean
  storageKey: string
  onOpen: (id: string | null) => void
  onConnect: (source: string, target: string) => Promise<void>
  onDisconnect: (link: ApiFeatureLink) => Promise<void>
}) {
  const id = useId()
  const viewport = useRef<HTMLDivElement>(null)
  const gesture = useRef<Gesture | null>(null)
  const fullCamera = useRef<Camera | null>(null)
  const pendingCamera = useRef<Camera | 'fit' | null>(null)
  const [, refreshCamera] = useState(0)
  const [zoom, setZoom] = useState(1)
  const [positions, setPositions] = useState<Record<string, PlanPosition>>(() => readPlanPositions(storageKey))
  const [branchPositions, setBranchPositions] = useState<Record<string, PlanPosition>>({})
  const [root, setRoot] = useState<string | null>(null)
  const [moving, setMoving] = useState<{ id: string; to: PlanPosition } | null>(null)
  const [preview, setPreview] = useState<{ id: string; to: PlanPosition } | null>(null)
  const [error, setError] = useState('')
  const data = useMemo(() => featureGraphNodes(groups), [groups])
  const edges = useMemo(() => featureGraphEdges(data, links ?? []), [data, links])
  const byId = useMemo(() => new Map(data.map(node => [node.id, node])), [data])
  const branch = root ? byId.get(root) : undefined
  const automatic = useMemo(() => {
    const layout = featureLayoutNodes(data, edges)
    const branchIds = root && layout.some(node => node.id === root) ? featureBranch(root, edges) : null
    const shown = branchIds ? layout.filter(node => branchIds.has(node.id)) : layout
    return planLayout(shown, Object.fromEntries(shown.map(node => [node.id, nodeHeight])))
  }, [data, edges, root])
  const activePositions = branch ? branchPositions : positions
  const setActivePositions = branch ? setBranchPositions : setPositions
  const nodes = automatic.map(node => ({
    ...node, ...activePositions[node.task.id], ...(moving?.id === node.task.id ? moving.to : null),
  }))
  const positioned = new Map(nodes.map(node => [node.task.id, node]))
  const width = Math.max(300, ...nodes.map(node => node.x + PLAN_NODE_WIDTH + 64))
  const height = Math.max(170, ...nodes.map(node => node.y + node.height + 186))
  const marker = id.replace(/:/g, '') + '-arrow'

  useEffect(() => {
    try { localStorage.setItem(storageKey, JSON.stringify(positions)) } catch { /* Layout still works without storage. */ }
  }, [storageKey, positions])

  const cancelGesture = useCallback(() => {
    const current = gesture.current
    gesture.current = null
    if (current && viewport.current?.hasPointerCapture(current.pointerId)) {
      viewport.current.releasePointerCapture(current.pointerId)
    }
    setMoving(null)
    setPreview(null)
  }, [])

  useEffect(() => { if (!visible || busy) cancelGesture() }, [visible, busy, cancelGesture])
  useEffect(() => {
    if (root && !branch) leaveBranch()
  }, [root, branch])

  function requestCamera(camera: Camera | 'fit') {
    pendingCamera.current = camera
    refreshCamera(value => value + 1)
  }

  useLayoutEffect(() => {
    const view = viewport.current
    let camera = pendingCamera.current
    if (!camera || !view || !view.clientWidth || !view.clientHeight) return
    if (camera === 'fit') {
      const nextZoom = Math.min(1, Math.max(.4, Math.min(view.clientWidth / width, view.clientHeight / height)))
      camera = { zoom: nextZoom, left: 0, top: 0 }
      pendingCamera.current = camera
    }
    if (camera.zoom !== zoom) { setZoom(camera.zoom); return }
    view.scrollTo(camera.left, camera.top)
    pendingCamera.current = null
  })

  function focusBranch(nodeId: string) {
    cancelGesture()
    if (!branch) fullCamera.current = { zoom, left: viewport.current?.scrollLeft ?? 0, top: viewport.current?.scrollTop ?? 0 }
    setBranchPositions({})
    setRoot(nodeId)
    requestCamera('fit')
    viewport.current?.focus({ preventScroll: true })
  }

  function leaveBranch() {
    cancelGesture()
    setRoot(null)
    setBranchPositions({})
    requestCamera(fullCamera.current ?? { zoom: 1, left: 0, top: 0 })
    fullCamera.current = null
    if (visible) viewport.current?.focus({ preventScroll: true })
  }

  function begin(event: PointerEvent, next: Gesture) {
    if (event.button !== 0 || gesture.current) return
    const view = viewport.current
    if (!view) return
    event.preventDefault()
    event.stopPropagation()
    view.focus({ preventScroll: true })
    view.setPointerCapture(event.pointerId)
    gesture.current = next
  }

  function origin(event: PointerEvent): PointerOrigin {
    return {
      pointerId: event.pointerId, x: event.clientX, y: event.clientY,
      left: viewport.current?.scrollLeft ?? 0, top: viewport.current?.scrollTop ?? 0,
    }
  }

  function movePointer(event: PointerEvent) {
    const current = gesture.current
    const view = viewport.current
    if (!view || !current || current.pointerId !== event.pointerId) return
    if (current.kind === 'move') {
      current.to = movePlanPosition(current.start,
        event.clientX - current.x + view.scrollLeft - current.left,
        event.clientY - current.y + view.scrollTop - current.top,
        zoom)
      setMoving({ id: current.id, to: current.to })
    } else if (current.kind === 'pan') {
      view.scrollTo(current.left + current.x - event.clientX, current.top + current.y - event.clientY)
    } else {
      const rect = view.getBoundingClientRect()
      setPreview({ id: current.id, to: {
        x: (event.clientX - rect.left - view.clientLeft + view.scrollLeft) / zoom,
        y: (event.clientY - rect.top - view.clientTop + view.scrollTop) / zoom,
      } })
    }
  }

  function endPointer(event: PointerEvent) {
    const current = gesture.current
    if (!current || current.pointerId !== event.pointerId) return
    if (current.kind === 'move' && current.to) {
      const to = current.to
      setActivePositions(value => ({ ...value, [current.id]: to }))
    } else if (current.kind === 'connect') {
      const target = document.elementFromPoint(event.clientX, event.clientY)?.closest<HTMLElement>('[data-feature-input]')
      if (target && viewport.current?.contains(target)) connect(current.id, target.dataset.featureInput!)
    }
    cancelGesture()
  }

  function cancelPointer(event: PointerEvent) {
    if (gesture.current?.pointerId === event.pointerId) cancelGesture()
  }

  function mutate(action: () => Promise<void>) {
    if (busy || !links) return
    setError('')
    void action().catch(failure => setError(failure instanceof Error ? failure.message : 'No se pudo guardar la conexión.'))
  }

  function connect(source: string, target: string) {
    if (!byId.get(source)?.featureId || !byId.get(target)?.featureId) return
    if (source === target) { setError('Una funcionalidad no puede depender de sí misma.'); return }
    if (edges.some(edge => edge.source === source && edge.target === target && edge.saved)) {
      setError('Las funcionalidades ya están conectadas.')
      return
    }
    mutate(() => onConnect(source, target))
  }

  if (!nodes.length) return <p className="overview-empty">Añade una funcionalidad para empezar el gráfico.</p>
  const previewSource = preview && positioned.get(preview.id)

  return <div className="feature-graph">
    <div className="plan-toolbar feature-graph-tools">
      {branch && <div className="plan-focus-summary"><div role="status"><strong>Rama de «{branch.title}»</strong><span>{nodes.length} de {data.length} funcionalidades</span></div>
        <button type="button" onClick={leaveBranch}>Ver gráfico completo</button></div>}
      <div className="plan-zoom">
        <button type="button" aria-label="Alejar gráfico" onClick={() => { cancelGesture(); setZoom(value => Math.max(.4, value - .15)) }}>−</button>
        <output>{Math.round(zoom * 100)}%</output>
        <button type="button" aria-label="Acercar gráfico" onClick={() => { cancelGesture(); setZoom(value => Math.min(1.6, value + .15)) }}>＋</button>
        <button type="button" onClick={() => { cancelGesture(); requestCamera(branch ? 'fit' : { zoom: 1, left: 0, top: 0 }) }}>Restablecer vista</button>
        <button type="button" onClick={() => { cancelGesture(); setActivePositions({}); if (branch) requestCamera('fit') }}>Organizar automáticamente</button>
      </div>
    </div>
    {links === null && <p className="feature-graph-note">Para guardar conexiones de planificación, actualiza la base de datos.</p>}
    {edges.length > 0 && <div className="feature-graph-legend" aria-label="Tipos de conexión">
      {edges.some(edge => edge.saved) && <span><i aria-hidden="true" />Planificación</span>}
      {edges.some(edge => !edge.saved) && <span><i aria-hidden="true" className="inferred" />Por tareas</span>}
    </div>}
    {error && <p className="form-error" role="alert">{error}</p>}
    <div ref={viewport} className="feature-graph-viewport" role="region" aria-label="Gráfico de funcionalidades" tabIndex={0}
      onKeyDown={event => { if (event.key === 'Escape' && gesture.current) { event.preventDefault(); cancelGesture() } }}
      onPointerDown={event => {
        if (event.pointerType !== 'mouse' || (event.target as Element).closest('button, select, article')) return
        begin(event, { ...origin(event), kind: 'pan' })
      }}
      onPointerMove={movePointer} onPointerUp={endPointer} onPointerCancel={cancelPointer} onLostPointerCapture={cancelPointer}>
      <div className="feature-graph-scale" style={{ width: width * zoom, height: height * zoom }}>
        <div className="feature-graph-canvas" style={{ width, height, transform: `scale(${zoom})` }}>
          <svg className="feature-graph-edges" width={width} height={height} aria-hidden="true">
            <defs><marker id={marker} viewBox="0 0 8 8" refX="7" refY="4" markerWidth="8" markerHeight="8" orient="auto">
              <path d="M 0 0 L 8 4 L 0 8 Z" />
            </marker></defs>
            {edges.map(edge => {
              const source = positioned.get(edge.source)
              const target = positioned.get(edge.target)
              if (!source || !target) return null
              return <path key={edge.source + '/' + edge.target} className={edge.saved ? 'saved' : 'inferred'}
                d={edgePath(source, target)} markerEnd={`url(#${marker})`}>
                <title>{source.task.title + ' → ' + target.task.title + ' · ' + edgeLabel(edge)}</title>
              </path>
            })}
            {preview && previewSource && <line className="feature-edge-preview" x1={previewSource.x + PLAN_NODE_WIDTH}
              y1={previewSource.y + nodeHeight / 2} x2={preview.to.x} y2={preview.to.y} />}
          </svg>
          {nodes.map(({ task, x, y, height: cardHeight }) => {
            const incoming = edges.filter(edge => edge.target === task.id)
            const outgoing = edges.filter(edge => edge.source === task.id)
            const candidates = task.featureId && links ? data.filter(other => other.featureId && other.id !== task.id &&
              !incoming.some(edge => edge.source === other.id && edge.saved)) : []
            const detailsId = id + '-' + task.id
            return <article className={'feature-graph-node' + (root === task.id ? ' feature-branch-root' : '')}
              aria-label={task.title} key={task.id} style={{ left: x, top: y, width: PLAN_NODE_WIDTH }}
              onPointerDown={event => {
                if ((event.target as Element).closest('button, select, label, .feature-graph-details') &&
                    !(event.target as Element).closest('.feature-graph-move')) return
                begin(event, { ...origin(event), kind: 'move', id: task.id, start: { x, y } })
              }}>
              <div className="feature-graph-card" style={{ height: cardHeight }}>
                <button type="button" className="feature-graph-move" aria-label={'Mover funcionalidad ' + task.title}
                  title="Arrastra para mover o usa las flechas" onKeyDown={event => {
                    const direction = directions[event.key]
                    if (!direction) return
                    event.preventDefault()
                    const step = event.shiftKey ? 5 : 20
                    setActivePositions(value => ({
                      ...value, [task.id]: movePlanPosition(value[task.id] ?? { x, y }, direction[0] * step, direction[1] * step),
                    }))
                  }}>⠿</button>
                {task.featureId && links && <>
                  <button type="button" className="plan-port plan-input" tabIndex={-1} style={{ top: cardHeight / 2 - 14 }}
                    data-feature-input={task.id} disabled={busy} aria-label={'Entrada de ' + task.title}>●</button>
                  <button type="button" className="plan-port plan-output" tabIndex={-1} style={{ top: cardHeight / 2 - 14 }}
                    disabled={busy} aria-label={'Salida de ' + task.title}
                    onPointerDown={event => begin(event, { kind: 'connect', pointerId: event.pointerId, id: task.id })}>●</button>
                </>}
                <button type="button" className="feature-graph-title" disabled={busy} aria-describedby={detailsId}
                  title={task.title} onClick={() => { cancelGesture(); onOpen(task.featureId) }}>{task.title}</button>
                <span className={'feature-count' + (task.total && task.completed === task.total ? ' complete' : '')}>{task.total ? task.completed + '/' + task.total + ' completadas' : 'Sin tareas'}</span>
                {task.total > 0 && <span className="feature-graph-progress" aria-hidden="true"><i style={{ width: 100 * task.completed / task.total + '%' }} /></span>}
              </div>
              <span hidden id={detailsId}>
                {incoming.map(edge => 'Depende de ' + byId.get(edge.source)?.title + ' (' + edgeLabel(edge) + '). ').join('')}
                {outgoing.map(edge => 'Precede a ' + byId.get(edge.target)?.title + ' (' + edgeLabel(edge) + '). ').join('')}
              </span>
              <div className="feature-graph-details">
                {task.featureId && links && <label>Funcionalidades previas<select aria-label={'Añadir funcionalidad previa a ' + task.title}
                  value="" disabled={busy || !candidates.length} onChange={event => connect(event.target.value, task.id)}>
                  <option value="">Añadir funcionalidad…</option>
                  {candidates.map(other => <option key={other.id} value={other.id}>{other.title}</option>)}
                </select></label>}
                {incoming.length > 0 && <ul aria-label={'Conexiones de entrada de ' + task.title}>
                  {incoming.map(edge => <li key={edge.source}>
                    <span>{byId.get(edge.source)?.title}<small>{edgeLabel(edge)}</small></span>
                    {edge.saved && <button type="button" disabled={busy} aria-label={'Quitar conexión con ' + byId.get(edge.source)?.title}
                      onClick={() => mutate(() => onDisconnect(edge.saved!))}>×</button>}
                  </li>)}
                </ul>}
                <button type="button" aria-pressed={root === task.id} aria-label={'Ver rama de ' + task.title}
                  onClick={() => focusBranch(task.id)}>Ver rama</button>
              </div>
            </article>
          })}
        </div>
      </div>
    </div>
  </div>
}
