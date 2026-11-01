import type { ReactNode } from 'react'

export function CardMetadata({ label, detail, tagged = false }: { label: string; detail: ReactNode; tagged?: boolean }) {
  return <span className="card-metadata">
    <span className="card-schedule" title={label}>{label}</span>
    <span className="card-detail" title={typeof detail === 'string' ? detail : undefined}>
      {tagged && <svg className="card-tag" aria-hidden="true" focusable="false" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 3h8l10 10-8 8L3 11Z" /><circle cx="7.5" cy="7.5" r="1" /></svg>}
      <span className="card-detail-text">{detail}</span>
    </span>
  </span>
}
