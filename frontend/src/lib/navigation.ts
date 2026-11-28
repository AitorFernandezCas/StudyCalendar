import { useCallback, useEffect, useState } from 'react'

export type Screen = 'calendar' | 'overview' | 'projects' | 'routines' | 'settings'

export const screenPaths: Record<Screen, string> = {
  calendar: '/',
  overview: '/tareas',
  projects: '/proyectos',
  routines: '/rutinas',
  settings: '/configuracion',
}

export function routineIdFromPath(pathname: string) {
  return /^\/rutinas\/([\w-]+)\/?$/.exec(pathname)?.[1] ?? null
}

export function screenFromPath(pathname: string): Screen {
  const path = pathname.replace(/\/+$/, '') || '/'
  if (path === screenPaths.projects) return 'projects'
  if (path === screenPaths.settings) return 'settings'
  if (path === screenPaths.routines || routineIdFromPath(path)) return 'routines'
  if (path === screenPaths.overview) return 'overview'
  return 'calendar'
}

export function useScreenRoute() {
  const [path, setPath] = useState(() => window.location.pathname)

  useEffect(() => {
    const syncRoute = () => setPath(window.location.pathname)
    window.addEventListener('popstate', syncRoute)
    syncRoute()
    return () => window.removeEventListener('popstate', syncRoute)
  }, [])

  const navigate = useCallback((nextScreen: Screen) => {
    const path = screenPaths[nextScreen]
    if (window.location.pathname !== path) window.history.pushState(null, '', path)
    setPath(path)
  }, [])

  const openRoutine = useCallback((id: string) => {
    const path = screenPaths.routines + '/' + encodeURIComponent(id)
    window.history.pushState({ fromRoutineList: true }, '', path)
    setPath(path)
  }, [])

  const closeRoutine = useCallback(() => {
    if (window.history.state?.fromRoutineList) window.history.back()
    else navigate('routines')
  }, [navigate])

  return [screenFromPath(path), navigate, routineIdFromPath(path), openRoutine, closeRoutine] as const
}
