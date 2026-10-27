import { isIP } from 'node:net'

function isLocalHost(hostname) {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, '')
  if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') || !host.includes('.') && !isIP(host)) return true
  if (isIP(host) === 4) {
    const [first, second] = host.split('.').map(Number)
    return first === 0 || first === 10 || first === 127 || first === 169 && second === 254 || first === 172 && second >= 16 && second <= 31 || first === 192 && second === 168
  }
  return isIP(host) === 6 && (host === '::' || host === '::1' || /^(fc|fd|fe[89ab]|::ffff:)/.test(host))
}

function keyRole(key) {
  try {
    return JSON.parse(Buffer.from(key.split('.')[1], 'base64url').toString('utf8')).role
  } catch {
    return undefined
  }
}

export function validateProductionEnv(env) {
  const errors = []
  for (const name of ['VITE_API_URL', 'VITE_SUPABASE_URL']) {
    const value = env[name]?.trim()
    if (!value) { errors.push(`${name}: obligatoria`); continue }
    try {
      const url = new URL(value)
      if (url.protocol !== 'https:' || isLocalHost(url.hostname) || url.username || url.password || url.search || url.hash || url.pathname !== '/') {
        errors.push(`${name}: debe ser una URL HTTPS pública sin credenciales, rutas ni parámetros`)
      }
    } catch {
      errors.push(`${name}: URL no válida`)
    }
  }
  const key = env.VITE_SUPABASE_PUBLISHABLE_KEY?.trim()
  if (!key) errors.push('VITE_SUPABASE_PUBLISHABLE_KEY: obligatoria')
  else if (!/^sb_publishable_[A-Za-z0-9_-]+$/.test(key) && !(key.split('.').length === 3 && keyRole(key) === 'anon')) {
    errors.push('VITE_SUPABASE_PUBLISHABLE_KEY: usa una clave publicable o anon; nunca una clave secret o service_role')
  }
  for (const [name, value] of Object.entries(env)) {
    if (name.startsWith('VITE_') && (value?.startsWith('sb_secret_') || keyRole(value || '') === 'service_role')) {
      errors.push(`${name}: contiene una clave privilegiada que no puede publicarse`)
    }
  }
  return errors
}
