import { useCallback, useEffect, useRef, useState } from 'react'
import type { Session } from '@supabase/supabase-js'
import { settingsApi, type ApiSettings, type SettingsChanges } from './api'

export function useSettings(session: Session) {
  const [result, setResult] = useState<{ token: string; data: ApiSettings } | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const version = useRef(0)
  const token = useRef(session.access_token)
  token.current = session.access_token
  const request = useRef<AbortController | null>(null)
  const savingRef = useRef(false)
  const refresh = useCallback(async () => {
    if (savingRef.current) return
    request.current?.abort()
    const controller = new AbortController()
    request.current = controller
    const generation = ++version.current
    setLoading(true)
    setError('')
    try {
      const data = await settingsApi.get(session, controller.signal)
      if (!controller.signal.aborted && generation === version.current && token.current === session.access_token) setResult({ token: session.access_token, data })
    } catch (e) {
      if (!controller.signal.aborted && generation === version.current) setError(e instanceof Error ? e.message : 'No se pudo cargar la configuración.')
    } finally {
      if (generation === version.current && !controller.signal.aborted) setLoading(false)
    }
  }, [session.access_token])
  useEffect(() => {
    savingRef.current = false
    setSaving(false)
    setResult(null)
    void refresh()
    const focus = () => { if (document.visibilityState === 'visible') void refresh() }
    window.addEventListener('focus', focus)
    document.addEventListener('visibilitychange', focus)
    return () => {
      version.current++
      request.current?.abort()
      window.removeEventListener('focus', focus)
      document.removeEventListener('visibilitychange', focus)
    }
  }, [refresh])
  const save = async (changes: SettingsChanges) => {
    if (savingRef.current) return
    savingRef.current = true
    setSaving(true)
    request.current?.abort()
    const generation = ++version.current
    setLoading(false)
    try {
      const data = await settingsApi.update(session, changes)
      if (generation === version.current && token.current === session.access_token) {
        setResult({ token: session.access_token, data })
        setError('')
        return data
      }
    } finally {
      if (generation === version.current) { savingRef.current = false; setSaving(false) }
    }
  }
  return { data: result?.token === session.access_token && !error ? result.data : null, loading, saving, error, refresh, save }
}
