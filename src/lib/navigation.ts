import { useCallback, useEffect, useState } from 'react'

export type Screen = 'calendar' | 'overview' | 'projects'

export const screenPaths: Record<Screen, string> = {
  calendar: '/',
  overview: '/tareas',
  projects: '/proyectos',
}

export function screenFromPath(pathname: string): Screen {
  const path = pathname.replace(/\/+$/, '') || '/'
  if (path === screenPaths.projects) return 'projects'
  if (path === screenPaths.overview) return 'overview'
  return 'calendar'
}

export function useScreenRoute() {
  const [screen, setScreen] = useState<Screen>(() => screenFromPath(window.location.pathname))

  useEffect(() => {
    const syncRoute = () => setScreen(screenFromPath(window.location.pathname))
    window.addEventListener('popstate', syncRoute)
    syncRoute()
    return () => window.removeEventListener('popstate', syncRoute)
  }, [])

  const navigate = useCallback((nextScreen: Screen) => {
    const path = screenPaths[nextScreen]
    if (window.location.pathname !== path) window.history.pushState(null, '', path)
    setScreen(nextScreen)
  }, [])

  return [screen, navigate] as const
}
