import { useEffect, useId, useRef, type ReactNode } from 'react'

export function FormDialog({ title, eyebrow, className = '', busy = false, onClose, onSubmit, children }: {
  title: string; eyebrow?: string; className?: string; busy?: boolean
  onClose: () => void; onSubmit: () => void; children: ReactNode
}) {
  const id = useId()
  const ref = useRef<HTMLFormElement>(null)
  const latest = useRef({ busy, onClose })
  latest.current = { busy, onClose }
  useEffect(() => {
    const dialog = ref.current!
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const background: { element: HTMLElement; inert: boolean }[] = []
    let ancestor: HTMLElement | null = dialog.parentElement
    while (ancestor && ancestor !== document.body) {
      for (const sibling of ancestor.parentElement?.children || []) {
        if (sibling !== ancestor && sibling instanceof HTMLElement) {
          background.push({ element: sibling, inert: sibling.inert })
          sibling.inert = true
        }
      }
      ancestor = ancestor.parentElement
    }
    const topmost = () => [...document.querySelectorAll('[data-form-dialog]')].pop() === dialog
    const focusable = () => [...dialog.querySelectorAll<HTMLElement>('button, input, select, textarea, a[href], [tabindex]')]
      .filter(element => !element.matches(':disabled, [tabindex="-1"]') && element.getClientRects().length > 0)
    const focusFirst = () => (dialog.querySelector<HTMLElement>('[autofocus], input:not(:disabled)') || focusable()[0] || dialog).focus()
    if (!dialog.contains(document.activeElement)) focusFirst()
    const onKey = (event: KeyboardEvent) => {
      if (!topmost()) return
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        if (!latest.current.busy) latest.current.onClose()
      }
      if (event.key === 'Tab') {
        const elements = focusable()
        const first = elements[0]
        const last = elements[elements.length - 1]
        if (!first) { event.preventDefault(); dialog.focus(); return }
        if (!dialog.contains(document.activeElement) || (event.shiftKey && document.activeElement === first)) {
          event.preventDefault(); (event.shiftKey ? last : first).focus()
        } else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
      }
    }
    const onFocus = (event: FocusEvent) => { if (topmost() && !dialog.contains(event.target as Node)) focusFirst() }
    document.addEventListener('keydown', onKey, true)
    document.addEventListener('focusin', onFocus)
    return () => {
      document.removeEventListener('keydown', onKey, true)
      document.removeEventListener('focusin', onFocus)
      document.body.style.overflow = previousOverflow
      for (const item of background) item.element.inert = item.inert
      if (previousFocus?.isConnected) previousFocus.focus({ preventScroll: true })
    }
  }, [])
  return <div className="modal-backdrop" onClick={event => { if (event.target === event.currentTarget && !busy) onClose() }}>
    <form ref={ref} data-form-dialog className={'modal ' + className} role="dialog" aria-modal="true" aria-labelledby={id} aria-busy={busy} tabIndex={-1}
      noValidate onSubmit={event => { event.preventDefault(); if (!busy) onSubmit() }}>
      <div className="modal-head"><div>{eyebrow && <p className="eyebrow">{eyebrow}</p>}<h2 id={id}>{title}</h2></div>
        <button type="button" disabled={busy} aria-label="Cerrar formulario" onClick={onClose}>×</button></div>
      {children}
    </form>
  </div>
}

export function ColorPicker({ colors, value, onChange, disabled = false }: { colors: string[]; value: string; onChange: (value: string) => void; disabled?: boolean }) {
  const names = ['Violeta', 'Coral', 'Amarillo', 'Verde', 'Azul']
  return <fieldset className="color-field"><legend>Color</legend><div className="color-picker">{colors.map((color, index) =>
    <button key={color} type="button" disabled={disabled} aria-label={names[index] || color} aria-pressed={value === color}
      style={{ background: color }} className={value === color ? 'selected' : ''} onClick={() => onChange(color)} />
  )}</div></fieldset>
}

export function DeleteIcon() {
  return <svg aria-hidden="true" focusable="false" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <path d="M3 6h18M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2M5 6l1 14a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1l1-14M10 10v7m4-7v7" />
  </svg>
}
