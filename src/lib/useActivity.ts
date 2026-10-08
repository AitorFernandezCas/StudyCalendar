import { useCallback, useEffect, useRef, useState } from 'react'
import type { Session } from '@supabase/supabase-js'
import { activityApi, type ApiActivitySnapshot } from './api'
import { dateInTimezone } from './useRoutineHabits'

export function useActivity(session: Session, year: number, revision: number) {
  const [result, setResult] = useState<{ key: string; snapshot: ApiActivitySnapshot } | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const controllerRef = useRef<AbortController | null>(null)
  const key = `${session.access_token}:${year}:${revision}`
  const currentKey = useRef(key)
  currentKey.current = key

  const refresh = useCallback(async () => {
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    setLoading(true)
    setError('')
    try {
      const snapshot = await activityApi.load(session, year, controller.signal)
      if (controller.signal.aborted || currentKey.current !== key) return
      if (snapshot.date !== dateInTimezone(snapshot.timezone)) throw new Error('Ha cambiado el día. Vuelve a cargar la gráfica.')
      setResult({ key, snapshot })
    } catch (e) {
      if (!controller.signal.aborted && currentKey.current === key) setError(e instanceof Error ? e.message : 'No se pudo cargar la actividad')
    } finally {
      if (!controller.signal.aborted && currentKey.current === key) setLoading(false)
    }
  }, [key, session.access_token, year])

  useEffect(() => {
    void refresh()
    const onFocus = () => { if (document.visibilityState === 'visible') void refresh() }
    window.addEventListener('focus', onFocus)
    document.addEventListener('visibilitychange', onFocus)
    return () => {
      controllerRef.current?.abort()
      window.removeEventListener('focus', onFocus)
      document.removeEventListener('visibilitychange', onFocus)
    }
  }, [refresh])

  useEffect(() => {
    if (!result || result.key !== key) return
    const timer = window.setTimeout(() => { void refresh() }, Math.max(0, new Date(result.snapshot.next_day_at).getTime() - Date.now()) + 100)
    return () => window.clearTimeout(timer)
  }, [result, key, refresh])

  const snapshot = !loading && !error && result?.key === key && result.snapshot.date === dateInTimezone(result.snapshot.timezone) ? result.snapshot : null
  return { snapshot, loading, error, refresh }
}
