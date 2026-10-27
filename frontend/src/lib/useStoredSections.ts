import { useEffect, useState } from 'react'

export function useStoredSections(key: string, defaults: string[]) {
  const [expanded, setExpanded] = useState<Set<string>>(() => {
    try {
      const saved: unknown = JSON.parse(localStorage.getItem(key) || 'null')
      if (Array.isArray(saved) && saved.every(value => typeof value === 'string')) return new Set(saved)
    } catch { /* Storage may be unavailable. */ }
    return new Set(defaults)
  })
  useEffect(() => { try { localStorage.setItem(key, JSON.stringify([...expanded])) } catch { /* Keep the view usable without storage. */ } }, [key, expanded])
  const toggle = (section: string) => setExpanded(current => {
    const next = new Set(current)
    if (next.has(section)) next.delete(section)
    else next.add(section)
    return next
  })
  return { expanded, setExpanded, toggle }
}
