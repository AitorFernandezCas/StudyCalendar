import { useCallback, useEffect, useRef, useState } from 'react'
import type { Session } from '@supabase/supabase-js'
import { ApiError, routinesApi, type ApiRoutineSnapshot } from './api'

export const dateInTimezone = (timezone: string) => {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  const part = (type: string) => parts.find(item => item.type === type)?.value
  return `${part('year')}-${part('month')}-${part('day')}`
}

export function useRoutineHabits(session: Session) {
  const [snapshot, setSnapshot] = useState<ApiRoutineSnapshot | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [activation, setActivation] = useState<{ id: string; active: boolean } | null>(null)
  const [activationError, setActivationError] = useState<{ id: string; message: string } | null>(null)
  const [changedId, setChangedId] = useState<string | null>(null)
  const snapshotRef = useRef<ApiRoutineSnapshot | null>(null)
  const snapshotTokenRef = useRef(session.access_token)
  const activationRef = useRef(false)
  const flightRef = useRef<{ token: string; day: string; promise: Promise<void> } | null>(null)
  const requestRef = useRef<AbortController | null>(null)
  const versionRef = useRef(0)
  const lifetimeRef = useRef(0)
  const mountedRef = useRef(true)
  const tokenRef = useRef(session.access_token)
  tokenRef.current = session.access_token

  const refresh = useCallback((options?: { force?: boolean }): Promise<void> => {
    if (!mountedRef.current || tokenRef.current !== session.access_token) return Promise.resolve()
    const day = dateInTimezone(snapshotRef.current?.timezone || 'Europe/Madrid')
    const flight = flightRef.current
    if (!options?.force && flight?.token === session.access_token && flight.day === day) return flight.promise
    requestRef.current?.abort()
    const controller = new AbortController()
    requestRef.current = controller
    const version = ++versionRef.current
    setLoading(true)
    setError('')
    const promise = (async () => { try {
      const data = await routinesApi.list(session, controller.signal)
      if (version === versionRef.current && !controller.signal.aborted && tokenRef.current === session.access_token) {
        snapshotRef.current = data
        snapshotTokenRef.current = session.access_token
        setSnapshot(data)
      }
    } catch (e) {
      if (!controller.signal.aborted && version === versionRef.current) setError(e instanceof Error ? e.message : 'No se pudieron cargar las rutinas')
    } finally {
      if (!controller.signal.aborted && version === versionRef.current) { setLoading(false); flightRef.current = null }
    } })()
    flightRef.current = { token: session.access_token, day, promise }
    return promise
  }, [session.access_token])

  useEffect(() => {
    mountedRef.current = true
    snapshotRef.current = null
    activationRef.current = false
    setSnapshot(null)
    setActivation(null)
    setActivationError(null)
    setChangedId(null)
    void refresh()
    const onFocus = () => {
      if (document.visibilityState !== 'visible') return
      const current = snapshotRef.current
      const stale = current && current.date !== dateInTimezone(current.timezone)
      if (!activationRef.current || stale) void refresh()
    }
    const onVisibility = () => { if (document.visibilityState === 'visible') onFocus() }
    window.addEventListener('focus', onFocus)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      mountedRef.current = false
      lifetimeRef.current++
      requestRef.current?.abort()
      flightRef.current = null
      versionRef.current++
      window.removeEventListener('focus', onFocus)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [refresh])

  useEffect(() => {
    if (!snapshot) return
    const timer = window.setTimeout(() => { void refresh() }, Math.max(0, new Date(snapshot.next_day_at).getTime() - Date.now()) + 100)
    return () => window.clearTimeout(timer)
  }, [snapshot, refresh])

  const setActive = async (id: string, active: boolean) => {
    if (activationRef.current) return
    const current = snapshotRef.current
    if (!current || snapshotTokenRef.current !== session.access_token || current.date !== dateInTimezone(current.timezone)) { await refresh(); return }
    activationRef.current = true
    const lifetime = lifetimeRef.current
    setActivation({ id, active })
    setActivationError(null)
    setChangedId(null)
    requestRef.current?.abort()
    flightRef.current = null
    setLoading(false)
    const version = ++versionRef.current
    try {
      const updated = await routinesApi.update(session, id, { active })
      if (!mountedRef.current || tokenRef.current !== session.access_token || lifetime !== lifetimeRef.current) return
      if (version === versionRef.current && current.date === dateInTimezone(current.timezone)) {
        const next = { ...current, routines: current.routines.map(routine => routine.id === id ? updated : routine) }
        snapshotRef.current = next
        setSnapshot(next)
        setChangedId(id)
        setError('')
      } else await refresh({ force: true })
    } catch {
      if (mountedRef.current && tokenRef.current === session.access_token && lifetime === lifetimeRef.current) setActivationError({ id, message: 'No se pudo cambiar el estado de la rutina. Inténtalo de nuevo.' })
    } finally {
      if (mountedRef.current && tokenRef.current === session.access_token && lifetime === lifetimeRef.current) { activationRef.current = false; setActivation(null) }
    }
  }

  const complete = async (id: string, completed: boolean) => {
    if (!snapshot || snapshotTokenRef.current !== session.access_token || dateInTimezone(snapshot.timezone) !== snapshot.date) { await refresh(); return }
    requestRef.current?.abort()
    flightRef.current = null
    setLoading(false)
    const version = ++versionRef.current
    try {
      const result = await routinesApi.complete(session, id, snapshot.date, completed)
      if (!mountedRef.current || tokenRef.current !== session.access_token) return
      if (version === versionRef.current && result.date === dateInTimezone(result.timezone)) {
        setSnapshot(current => current ? { ...current, date: result.date, timezone: result.timezone, next_day_at: result.next_day_at, routines: current.routines.map(routine => routine.id === id ? result.routine : routine) } : null)
        if (snapshotRef.current) snapshotRef.current = { ...snapshotRef.current, date: result.date, timezone: result.timezone, next_day_at: result.next_day_at, routines: snapshotRef.current.routines.map(routine => routine.id === id ? result.routine : routine) }
      } else await refresh()
    } catch (e) {
      if (!mountedRef.current || tokenRef.current !== session.access_token) return
      if (e instanceof ApiError && (e.status === 409 || e.status === 404)) await refresh()
      throw e
    }
  }

  const ready = Boolean(snapshot && snapshotTokenRef.current === session.access_token && snapshot.date === dateInTimezone(snapshot.timezone))
  return { routines: ready ? snapshot!.routines : [], date: snapshot?.date || '', loading: loading || (!ready && !error), error, ready, refresh, complete, setActive, activation, activationError, changedId }
}
