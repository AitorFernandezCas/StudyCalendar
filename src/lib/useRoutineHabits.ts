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
  const requestRef = useRef<AbortController | null>(null)
  const versionRef = useRef(0)
  const mountedRef = useRef(true)
  const tokenRef = useRef(session.access_token)
  tokenRef.current = session.access_token

  const refresh = useCallback(async () => {
    if (!mountedRef.current || tokenRef.current !== session.access_token) return
    requestRef.current?.abort()
    const controller = new AbortController()
    requestRef.current = controller
    const version = ++versionRef.current
    setLoading(true)
    setError('')
    try {
      const data = await routinesApi.list(session, controller.signal)
      if (version === versionRef.current && !controller.signal.aborted) setSnapshot(data)
    } catch (e) {
      if (!controller.signal.aborted && version === versionRef.current) setError(e instanceof Error ? e.message : 'No se pudieron cargar las rutinas')
    } finally {
      if (!controller.signal.aborted && version === versionRef.current) setLoading(false)
    }
  }, [session.access_token])

  useEffect(() => {
    mountedRef.current = true
    setSnapshot(null)
    void refresh()
    const onFocus = () => { void refresh() }
    const onVisibility = () => { if (document.visibilityState === 'visible') void refresh() }
    window.addEventListener('focus', onFocus)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      mountedRef.current = false
      requestRef.current?.abort()
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

  const complete = async (id: string, completed: boolean) => {
    if (!snapshot || loading || dateInTimezone(snapshot.timezone) !== snapshot.date) { await refresh(); return }
    requestRef.current?.abort()
    const version = ++versionRef.current
    try {
      const result = await routinesApi.complete(session, id, snapshot.date, completed)
      if (!mountedRef.current || tokenRef.current !== session.access_token) return
      if (version === versionRef.current && result.date === dateInTimezone(result.timezone)) {
        setSnapshot(current => current ? { ...current, date: result.date, timezone: result.timezone, next_day_at: result.next_day_at, routines: current.routines.map(routine => routine.id === id ? result.routine : routine) } : null)
      } else await refresh()
    } catch (e) {
      if (!mountedRef.current || tokenRef.current !== session.access_token) return
      if (e instanceof ApiError && (e.status === 409 || e.status === 404)) await refresh()
      throw e
    }
  }

  const ready = Boolean(snapshot && !loading && !error && snapshot.date === dateInTimezone(snapshot.timezone))
  return { routines: ready ? snapshot!.routines : [], date: snapshot?.date || '', loading: loading || (!ready && !error), error, ready, refresh, complete }
}
