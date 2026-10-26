import { useState, type ReactNode } from 'react'
import { supabase } from '../lib/supabase'
import { authErrorMessage } from '../lib/authFeedback'

function AuthFrame({ children }: { children: ReactNode }) {
  return <div className="auth-screen"><div className="auth-card"><div className="brand auth-brand"><div className="brand-mark">✦</div><span>Aprovecha<span>TuTiempo</span></span></div><p className="eyebrow">TU PLAN DE ESTUDIO</p>{children}</div></div>
}

export function AuthPanel({ initialMessage = '' }: { initialMessage?: string }) {
  const [mode, setMode] = useState<'login' | 'signup' | 'reset'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState(initialMessage)
  const [busy, setBusy] = useState(false)
  const changeMode = (next: typeof mode) => { setMode(next); setMessage(''); setError(''); setPassword('') }
  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!supabase || busy) return
    setBusy(true); setMessage(''); setError('')
    try {
      const result = mode === 'reset'
        ? await supabase.auth.resetPasswordForEmail(email.trim(), { redirectTo: window.location.origin + '/' })
        : mode === 'signup'
          ? await supabase.auth.signUp({ email: email.trim(), password })
          : await supabase.auth.signInWithPassword({ email: email.trim(), password })
      if (result.error) throw result.error
      if (mode === 'reset') setMessage('Si existe una cuenta con ese email, recibirás un enlace para cambiar la contraseña. Revisa también la carpeta de spam.')
      if (mode === 'signup') setMessage('Revisa tu email para confirmar la cuenta.')
    } catch (e) { setError(authErrorMessage(e)) } finally { setBusy(false) }
  }
  return <AuthFrame>
    <h1>{mode === 'login' ? 'Bienvenido de nuevo' : mode === 'signup' ? 'Crea tu cuenta' : 'Recupera tu contraseña'}</h1>
    <p className="auth-copy">{mode === 'login' ? 'Inicia sesión para ver tus tareas.' : mode === 'signup' ? 'Guarda tu calendario y accede desde cualquier lugar.' : 'Te enviaremos un enlace para elegir una nueva contraseña.'}</p>
    <form onSubmit={submit} aria-busy={busy}>
      <label>Email<input autoComplete="email" type="email" required disabled={busy} value={email} onChange={e => setEmail(e.target.value)} placeholder="tu@email.com" /></label>
      {mode !== 'reset' && <label>Contraseña<input autoComplete={mode === 'signup' ? 'new-password' : 'current-password'} type="password" required minLength={mode === 'signup' ? 6 : undefined} disabled={busy} value={password} onChange={e => setPassword(e.target.value)} placeholder={mode === 'signup' ? 'Mínimo 6 caracteres' : 'Tu contraseña'} /></label>}
      <button className="save auth-submit" disabled={busy}>{busy ? 'Conectando…' : mode === 'login' ? 'Iniciar sesión' : mode === 'signup' ? 'Crear cuenta' : 'Enviar enlace de recuperación'}</button>
    </form>
    {error && <p className="auth-message auth-error" role="alert">{error}</p>}
    {message && <p className="auth-message" role="status">{message}</p>}
    {mode === 'login' && <button disabled={busy} className="auth-switch" onClick={() => changeMode('reset')}>He olvidado mi contraseña</button>}
    <button disabled={busy} className="auth-switch" onClick={() => changeMode(mode === 'login' ? 'signup' : 'login')}>{mode === 'login' ? '¿No tienes cuenta? Regístrate' : 'Volver a iniciar sesión'}</button>
  </AuthFrame>
}

export function PasswordRecovery({ onComplete }: { onComplete: () => void }) {
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState(false)
  return <AuthFrame><h1>{saved ? 'Contraseña actualizada' : 'Elige una nueva contraseña'}</h1>
    {saved ? <><p role="status" className="auth-copy">Tu nueva contraseña ya está guardada.</p><button className="save auth-submit" onClick={onComplete}>Ir a mis tareas</button></> : <>
      <form aria-busy={busy} onSubmit={async event => {
        event.preventDefault()
        if (!supabase || busy) return
        if (password !== confirmation) { setError('Las contraseñas no coinciden.'); return }
        setBusy(true); setError('')
        try {
          const result = await supabase.auth.updateUser({ password })
          if (result.error) throw result.error
          setSaved(true)
        } catch (e) { setError(authErrorMessage(e)) } finally { setBusy(false) }
      }}>
        <label>Nueva contraseña<input autoComplete="new-password" type="password" required minLength={6} disabled={busy} value={password} onChange={e => setPassword(e.target.value)} /></label>
        <label>Repite la contraseña<input autoComplete="new-password" type="password" required minLength={6} disabled={busy} value={confirmation} aria-invalid={Boolean(error) && password !== confirmation} onChange={e => setConfirmation(e.target.value)} /></label>
        <button className="save auth-submit" disabled={busy}>{busy ? 'Guardando…' : 'Guardar contraseña'}</button>
      </form>
      {error && <p className="auth-message auth-error" role="alert">{error}</p>}
    </>}
  </AuthFrame>
}
